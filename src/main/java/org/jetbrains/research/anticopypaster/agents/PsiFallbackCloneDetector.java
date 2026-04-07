
package org.jetbrains.research.anticopypaster.agents;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import com.intellij.lang.java.lexer.JavaLexer;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.TokenType;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.tree.IElementType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight PSI-based fallback clone detector.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Fast enough to run on paste-triggered workflows.</li>
 *   <li>Deterministic and safe (read-only PSI access).</li>
 *   <li>Returns candidates that are easy to feed into the refactoring agent.
 *       (The refactoring agent primarily relies on cloneCode; ranges are best-effort.)</li>
 * </ul>
 *
 * <p>Current v1 strategy:
 * <ul>
 *   <li>Search the current file text for occurrences of the pasted snippet (plus a few robust variants).</li>
 *   <li>If we find 2+ occurrences, we return all of them as clone candidates.</li>
 * </ul>
 *
 * <p>This intentionally does NOT attempt full project-wide clone detection.
 */
public final class PsiFallbackCloneDetector {

    private PsiFallbackCloneDetector() {
    }

    /**
     * A simple clone region descriptor.
     * Offsets are in file text coordinates (0-based, endOffset exclusive).
     * Line numbers are 1-based (best-effort) when a Document is available.
     */
    public static final class CloneCandidate {
        public final int startOffset;
        public final int endOffset;
        public final int startLine; // 1-based; -1 if unknown
        public final int endLine;   // 1-based; -1 if unknown
        public final String cloneCode;

        public CloneCandidate(int startOffset, int endOffset, int startLine, int endLine, String cloneCode) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.startLine = startLine;
            this.endLine = endLine;
            this.cloneCode = cloneCode;
        }

        @Override
        public String toString() {
            return "CloneCandidate{" +
                    "startOffset=" + startOffset +
                    ", endOffset=" + endOffset +
                    ", startLine=" + startLine +
                    ", endLine=" + endLine +
                    ", cloneCodeLen=" + (cloneCode == null ? 0 : cloneCode.length()) +
                    '}';
        }
    }

    /**
     * Detect clone candidates within the SAME file.
     *
     * @param project       IntelliJ project
     * @param file          file where paste happened
     * @param pastedSnippet pasted text
     * @return clone candidates (possibly empty)
     */
    public static List<CloneCandidate> detectInSameFile(Project project, VirtualFile file, String pastedSnippet) {
        if (project == null || file == null) return Collections.emptyList();
        if (pastedSnippet == null) return Collections.emptyList();

        String snippet = pastedSnippet;
        if (snippet.trim().isEmpty()) return Collections.emptyList();

        return ReadAction.compute(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) return Collections.emptyList();

            Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
            String fileText = psiFile.getText();
            if (fileText == null || fileText.isEmpty()) return Collections.emptyList();

            // Build a small set of robust snippet variants.
            // We keep this conservative so we don't create lots of false positives.
            Set<String> variants = buildSnippetVariants(snippet);

            // Collect occurrences across variants. Use LinkedHashSet to keep stable order.
            Set<Occurrence> occurrences = new LinkedHashSet<>();
            for (String v : variants) {
                occurrences.addAll(findAllOccurrences(fileText, v));
            }

            if (occurrences.size() < 2) {
                // Exact/near-exact text match did not find duplicates.
                // NEW: Type-1 (ignore whitespace/comments) fallback: tokenize both snippet and file,
                // skip whitespace/comments, and look for exact token-text sequences.
                occurrences = new LinkedHashSet<>();
                occurrences.addAll(findType1OccurrencesIgnoreComments(psiFile, snippet, fileText));

                if (occurrences.size() < 2) {
                    // Still nothing — try a lightweight Type-2 fallback: tokenize both snippet and file, normalize identifiers/literals,
                    // and look for matching token-kind sequences.
                    occurrences = new LinkedHashSet<>();
                    occurrences.addAll(findType2Occurrences(project, psiFile, snippet, fileText));

                    if (occurrences.size() < 2) {
                        // Still nothing — try a high-recall Type-3-ish fallback based on token shingles (k-grams).
                        // This can catch small edits/insertions/deletions while staying fast in same-file scope.
                        occurrences = new LinkedHashSet<>();
                        occurrences.addAll(findType3OccurrencesByShingles(psiFile, snippet, fileText));

                        if (occurrences.size() < 2) {
                            // Still nothing — return empty.
                            return Collections.emptyList();
                        }
                    }
                }
            }

            List<CloneCandidate> out = new ArrayList<>();
            // Use a representative clone code for the agent (prefer the original snippet).
            String representative = snippet.trim();

            for (Occurrence occ : occurrences) {
                int startLine = -1;
                int endLine = -1;
                if (document != null) {
                    // Document line numbers are 0-based; convert to 1-based inclusive.
                    int sLine0 = document.getLineNumber(occ.startOffset);
                    int eLine0 = document.getLineNumber(Math.max(occ.endOffset - 1, occ.startOffset));
                    startLine = sLine0 + 1;
                    endLine = eLine0 + 1;
                }

                // Clone code: best-effort exact slice from file. Fall back to representative snippet.
                String code;
                try {
                    code = fileText.substring(occ.startOffset, occ.endOffset);
                } catch (Throwable t) {
                    code = representative;
                }

                out.add(new CloneCandidate(occ.startOffset, occ.endOffset, startLine, endLine, code));
            }

            return out;
        });
    }

    // ---------------------------- helpers ----------------------------

    private static final int MIN_TOKENS_FOR_TYPE2 = 6;
    private static final int MIN_TOKENS_FOR_TYPE1_NO_COMMENTS = 6;

    // Type-3-ish (high recall) shingle matcher settings
    private static final int MIN_TOKENS_FOR_TYPE3 = 10;
    private static final int TYPE3_K = 5;
    // Lower threshold => fewer false negatives (more candidates). Tune if it gets too noisy.
    private static final double TYPE3_MIN_SIM = 0.40;
    private static final int TYPE3_MAX_CANDIDATES = 25;

    private static Set<Occurrence> findType2Occurrences(Project project, PsiFile psiFile, String pastedSnippet, String fileText) {
        if (psiFile == null) return Collections.emptySet();
        if (pastedSnippet == null) return Collections.emptySet();

        LanguageLevel level;
        try {
            level = PsiUtil.getLanguageLevel(psiFile);
        } catch (Throwable t) {
            level = LanguageLevel.HIGHEST;
        }

        List<NormTok> snippetToks = lexAndNormalize(pastedSnippet, level);
        if (snippetToks.size() < MIN_TOKENS_FOR_TYPE2) return Collections.emptySet();

        List<NormTok> fileToks = lexAndNormalize(fileText, level);
        if (fileToks.size() < snippetToks.size()) return Collections.emptySet();

        // Prepare the normalized kind sequence for snippet.
        int m = snippetToks.size();
        String[] needle = new String[m];
        for (int i = 0; i < m; i++) needle[i] = snippetToks.get(i).kind;

        Set<Occurrence> out = new LinkedHashSet<>();

        // Naive sliding-window match (single-file scope; acceptable in practice).
        for (int i = 0; i <= fileToks.size() - m; i++) {
            boolean ok = true;
            for (int j = 0; j < m; j++) {
                if (!needle[j].equals(fileToks.get(i + j).kind)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;

            int start = fileToks.get(i).startOffset;
            int end = fileToks.get(i + m - 1).endOffset;
            if (start >= 0 && end > start) {
                out.add(new Occurrence(start, end));
            }
        }

        return out;
    }

    private static final class NormTok {
        final String kind;
        final int startOffset;
        final int endOffset;

        private NormTok(String kind, int startOffset, int endOffset) {
            this.kind = kind;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private static List<NormTok> lexAndNormalize(String text, LanguageLevel level) {
        if (text == null || text.isEmpty()) return Collections.emptyList();

        JavaLexer lexer;
        try {
            lexer = new JavaLexer(level);
        } catch (Throwable t) {
            // If the lexer is unavailable for some reason, we cannot do Type-2 matching.
            return Collections.emptyList();
        }

        List<NormTok> out = new ArrayList<>();
        lexer.start(text);
        IElementType tt;
        while ((tt = lexer.getTokenType()) != null) {
            int s = lexer.getTokenStart();
            int e = lexer.getTokenEnd();

            // Skip whitespace
            if (tt == TokenType.WHITE_SPACE) {
                lexer.advance();
                continue;
            }

            // Skip comments (including Javadoc). Some token constants are not exposed in the public API,
            // so we use a conservative name-based check for doc comments.
            if (tt == JavaTokenType.END_OF_LINE_COMMENT ||
                    tt == JavaTokenType.C_STYLE_COMMENT ||
                    tt.toString().contains("DOC_COMMENT")) {
                lexer.advance();
                continue;
            }

            String kind = normalizeTokenKind(tt);
            out.add(new NormTok(kind, s, e));

            lexer.advance();
        }
        return out;
    }

    private static String normalizeTokenKind(IElementType tt) {
        if (tt == JavaTokenType.IDENTIFIER) {
            return "ID";
        }

        // Normalize common literal kinds
        if (tt == JavaTokenType.STRING_LITERAL ||
                tt == JavaTokenType.CHARACTER_LITERAL ||
                tt == JavaTokenType.INTEGER_LITERAL ||
                tt == JavaTokenType.LONG_LITERAL ||
                tt == JavaTokenType.FLOAT_LITERAL ||
                tt == JavaTokenType.DOUBLE_LITERAL ||
                tt == JavaTokenType.TRUE_KEYWORD ||
                tt == JavaTokenType.FALSE_KEYWORD ||
                tt == JavaTokenType.NULL_KEYWORD) {
            return "LIT";
        }

        // For keywords/operators/punctuators, the token type is stable enough for matching.
        return tt.toString();
    }

    private static final class Occurrence {
        final int startOffset;
        final int endOffset;

        Occurrence(int startOffset, int endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Occurrence)) return false;
            Occurrence that = (Occurrence) o;
            return startOffset == that.startOffset && endOffset == that.endOffset;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startOffset, endOffset);
        }
    }

    private static Set<String> buildSnippetVariants(String snippet) {
        Set<String> variants = new LinkedHashSet<>();

        String s0 = normalizeLineEndings(snippet);
        variants.add(s0);

        String s1 = s0.trim();
        if (!s1.isEmpty()) variants.add(s1);

        String s2 = rstripLines(s0);
        if (!s2.isEmpty()) variants.add(s2);

        String s3 = dedent(s0);
        if (!s3.isEmpty()) variants.add(s3);

        String s4 = dedent(rstripLines(s0)).trim();
        if (!s4.isEmpty()) variants.add(s4);

        // Remove only leading/trailing blank lines (common in copy/paste).
        String s5 = trimBlankLines(s0);
        if (!s5.isEmpty()) variants.add(s5);

        return variants;
    }

    private static List<Occurrence> findAllOccurrences(String haystack, String needle) {
        if (needle == null) return Collections.emptyList();
        if (needle.isEmpty()) return Collections.emptyList();

        List<Occurrence> out = new ArrayList<>();
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) break;
            out.add(new Occurrence(idx, idx + needle.length()));
            from = idx + Math.max(1, needle.length());
        }
        return out;
    }

    private static String normalizeLineEndings(String s) {
        if (s == null) return "";
        // Convert CRLF/CR to LF
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * Remove trailing spaces/tabs on each line (keeps indentation).
     */
    private static String rstripLines(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] lines = normalizeLineEndings(s).split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int end = line.length();
            while (end > 0) {
                char c = line.charAt(end - 1);
                if (c == ' ' || c == '\t') end--;
                else break;
            }
            sb.append(line, 0, end);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Remove common leading indentation across non-blank lines.
     */
    private static String dedent(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] lines = normalizeLineEndings(s).split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < line.length()) {
                char c = line.charAt(indent);
                if (c == ' ') indent++;
                else if (c == '\t') indent++; // treat tab as 1 for simplicity
                else break;
            }
            minIndent = Math.min(minIndent, indent);
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) return normalizeLineEndings(s);

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                sb.append(line);
            } else {
                int cut = Math.min(minIndent, line.length());
                sb.append(line.substring(cut));
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Trim only blank lines at the start/end.
     */
    private static String trimBlankLines(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = normalizeLineEndings(s);
        String[] lines = t.split("\n", -1);
        int start = 0;
        int end = lines.length - 1;
        while (start <= end && lines[start].trim().isEmpty()) start++;
        while (end >= start && lines[end].trim().isEmpty()) end--;
        if (start > end) return "";
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = start; i <= end; i++) {
            sb.append(lines[i]);
            if (i < end) sb.append('\n');
        }
        return sb.toString();
    }

    // Type-1 (ignore whitespace/comments) token matcher helpers

    private static final class RawTok {
        final String text;
        final int startOffset;
        final int endOffset;

        private RawTok(String text, int startOffset, int endOffset) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private static Set<Occurrence> findType1OccurrencesIgnoreComments(PsiFile psiFile, String pastedSnippet, String fileText) {
        if (psiFile == null) return Collections.emptySet();
        if (pastedSnippet == null || pastedSnippet.trim().isEmpty()) return Collections.emptySet();
        if (fileText == null || fileText.isEmpty()) return Collections.emptySet();

        LanguageLevel level;
        try {
            level = PsiUtil.getLanguageLevel(psiFile);
        } catch (Throwable t) {
            level = LanguageLevel.HIGHEST;
        }

        List<RawTok> needle = lexRawNoComments(pastedSnippet, level);
        if (needle.size() < MIN_TOKENS_FOR_TYPE1_NO_COMMENTS) return Collections.emptySet();

        List<RawTok> hay = lexRawNoComments(fileText, level);
        if (hay.size() < needle.size()) return Collections.emptySet();

        int m = needle.size();
        Set<Occurrence> out = new LinkedHashSet<>();

        // Naive sliding-window match (single-file scope; acceptable in practice).
        for (int i = 0; i <= hay.size() - m; i++) {
            boolean ok = true;
            for (int j = 0; j < m; j++) {
                if (!needle.get(j).text.equals(hay.get(i + j).text)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;

            int start = hay.get(i).startOffset;
            int end = hay.get(i + m - 1).endOffset;
            if (start >= 0 && end > start) {
                out.add(new Occurrence(start, end));
            }
        }

        return out;
    }

    private static List<RawTok> lexRawNoComments(String text, LanguageLevel level) {
        if (text == null || text.isEmpty()) return Collections.emptyList();

        JavaLexer lexer;
        try {
            lexer = new JavaLexer(level);
        } catch (Throwable t) {
            return Collections.emptyList();
        }

        List<RawTok> out = new ArrayList<>();
        lexer.start(text);
        IElementType tt;
        while ((tt = lexer.getTokenType()) != null) {
            int s = lexer.getTokenStart();
            int e = lexer.getTokenEnd();

            // Skip whitespace
            if (tt == TokenType.WHITE_SPACE) {
                lexer.advance();
                continue;
            }

            // Skip comments (including Javadoc). Some token constants are not exposed in the public API,
            // so we use a conservative name-based check for doc comments.
            if (tt == JavaTokenType.END_OF_LINE_COMMENT ||
                    tt == JavaTokenType.C_STYLE_COMMENT ||
                    tt.toString().contains("DOC_COMMENT")) {
                lexer.advance();
                continue;
            }

            String tokText;
            try {
                tokText = text.substring(s, e);
            } catch (Throwable t) {
                tokText = tt.toString();
            }

            out.add(new RawTok(tokText, s, e));
            lexer.advance();
        }

        return out;
    }


    private static Set<Occurrence> findType3OccurrencesByShingles(PsiFile psiFile, String pastedSnippet, String fileText) {
        if (psiFile == null) return Collections.emptySet();
        if (pastedSnippet == null || pastedSnippet.trim().isEmpty()) return Collections.emptySet();
        if (fileText == null || fileText.isEmpty()) return Collections.emptySet();

        LanguageLevel level;
        try {
            level = PsiUtil.getLanguageLevel(psiFile);
        } catch (Throwable t) {
            level = LanguageLevel.HIGHEST;
        }

        List<NormTok> snippetToks = lexAndNormalize(pastedSnippet, level);
        if (snippetToks.size() < MIN_TOKENS_FOR_TYPE3) return Collections.emptySet();

        List<NormTok> fileToks = lexAndNormalize(fileText, level);
        if (fileToks.size() < MIN_TOKENS_FOR_TYPE3) return Collections.emptySet();

        int k = Math.min(TYPE3_K, Math.max(3, snippetToks.size() / 3));
        int m = snippetToks.size();
        int snippetShinglesCount = Math.max(0, m - k + 1);
        if (snippetShinglesCount < 4) return Collections.emptySet();

        // Build snippet shingle hash set
        Set<Long> snippetShingles = new LinkedHashSet<>(snippetShinglesCount * 2);
        for (int i = 0; i <= m - k; i++) {
            snippetShingles.add(hashShingleKinds(snippetToks, i, k));
        }

        int n = fileToks.size();
        int nf = n - k + 1;
        if (nf <= 0) return Collections.emptySet();

        // hit[p] = 1 if file shingle at token index p is also in snippet shingles
        int[] hit = new int[nf];
        for (int p = 0; p < nf; p++) {
            long h = hashShingleKinds(fileToks, p, k);
            hit[p] = snippetShingles.contains(h) ? 1 : 0;
        }

        // Prefix sums for O(1) window scoring
        int[] pref = new int[nf + 1];
        for (int i = 0; i < nf; i++) pref[i + 1] = pref[i] + hit[i];

        // Allow small edits by letting window length vary a bit
        int delta = Math.max(2, m / 5); // +/- 20%
        int[] lens = new int[]{Math.max(MIN_TOKENS_FOR_TYPE3, m - delta), m, Math.min(n, m + delta)};

        Set<Occurrence> out = new LinkedHashSet<>();

        for (int L : lens) {
            if (L < k) continue;
            int windowShingles = Math.max(0, L - k + 1);
            if (windowShingles <= 0) continue;

            // Similarity: fraction of snippet shingles that appear in the window (recall-like => high recall)
            int minHits = Math.max(3, (int) Math.floor(snippetShinglesCount * TYPE3_MIN_SIM));

            // Shingle index range for a token window starting at t is [t .. t+L-k]
            int maxStartToken = n - L;
            for (int t = 0; t <= maxStartToken; t++) {
                int l = t;
                int r = t + L - k;
                if (r < l) continue;
                if (r >= nf) break;

                int hits = pref[r + 1] - pref[l];
                if (hits < minHits) continue;

                double sim = (double) hits / (double) snippetShinglesCount;
                if (sim < TYPE3_MIN_SIM) continue;

                int startOff = fileToks.get(t).startOffset;
                int endOff = fileToks.get(t + L - 1).endOffset;
                if (startOff >= 0 && endOff > startOff) {
                    out.add(new Occurrence(startOff, endOff));
                    if (out.size() >= TYPE3_MAX_CANDIDATES) return out;
                }
            }
        }

        return out;
    }

    private static long hashShingleKinds(List<NormTok> toks, int start, int k) {
        long h = 1469598103934665603L; // FNV-1a 64-bit offset basis
        for (int i = 0; i < k; i++) {
            String s = toks.get(start + i).kind;
            // FNV-1a over characters
            for (int c = 0; c < s.length(); c++) {
                h ^= (byte) s.charAt(c);
                h *= 1099511628211L;
            }
            // separator
            h ^= 0xFF;
            h *= 1099511628211L;
        }
        return h;
    }
}