
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
 * <p>Current strategy:
 * <ul>
 *   <li>Search the current file text for conservative exact matches of the pasted snippet.</li>
 *   <li>If exact matching fails, fall back to a Type-1 token-exact match that ignores whitespace/comments.</li>
 *   <li>If Type-1 still fails, try Type-2 matching by normalizing identifiers and literals.</li>
 *   <li>As a final fallback, try conservative Type-3 matching with a bounded token window and LCS threshold.</li>
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

            // Build a small set of exact-match variants.
            // Keep this conservative: overlapping matches from aggressively normalized variants can
            // inflate one real occurrence into multiple fake ones.
            Set<String> variants = buildSnippetVariants(snippet);

            // Collect occurrences across variants. Use LinkedHashSet to keep stable order.
            Set<Occurrence> occurrences = new LinkedHashSet<>();
            for (String v : variants) {
                occurrences.addAll(findAllOccurrences(fileText, v));
            }
            occurrences = collapseOverlappingOccurrences(occurrences);

            if (occurrences.size() < 2) {
                // Type-1: same token text after ignoring whitespace/comments.
                occurrences = collapseOverlappingOccurrences(
                        findType1OccurrencesIgnoreComments(psiFile, snippet, fileText)
                );
            }

            if (occurrences.size() < 2) {
                // Type-2: same normalized token sequence, allowing renamed identifiers/literals.
                occurrences = collapseOverlappingOccurrences(
                        findType2OccurrencesNormalized(psiFile, snippet, fileText)
                );
            }

            if (occurrences.size() < 2) {
                // Type-3: similar normalized token sequence with bounded insertions/deletions.
                occurrences = collapseOverlappingOccurrences(
                        findType3OccurrencesApproximate(psiFile, snippet, fileText)
                );
            }

            if (occurrences.size() < 2) {
                return Collections.emptyList();
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

    private static final int MIN_TOKENS_FOR_TYPE1_NO_COMMENTS = 6;
    private static final int MIN_TOKENS_FOR_TYPE2_NORMALIZED = 8;
    private static final int MIN_TOKENS_FOR_TYPE3_APPROXIMATE = 12;
    private static final double TYPE3_MIN_NEEDLE_COVERAGE = 0.75;
    private static final double TYPE3_MIN_LCS_SIMILARITY = 0.78;

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
            // If the lexer is unavailable for some reason, normalized matching is disabled.
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
        if (!s0.isEmpty()) variants.add(s0);

        String s1 = rstripLines(s0);
        if (!s1.isEmpty()) variants.add(s1);

        // Remove only leading/trailing blank lines (common in copy/paste).
        String s2 = trimBlankLines(s0);
        if (!s2.isEmpty()) variants.add(s2);

        return variants;
    }

    private static Set<Occurrence> collapseOverlappingOccurrences(Set<Occurrence> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) return Collections.emptySet();

        List<Occurrence> sorted = new ArrayList<>(occurrences);
        sorted.sort((left, right) -> {
            if (left.startOffset != right.startOffset) {
                return Integer.compare(left.startOffset, right.startOffset);
            }
            int leftLen = left.endOffset - left.startOffset;
            int rightLen = right.endOffset - right.startOffset;
            return Integer.compare(rightLen, leftLen);
        });

        LinkedHashSet<Occurrence> collapsed = new LinkedHashSet<>();
        Occurrence current = null;
        for (Occurrence occurrence : sorted) {
            if (current == null) {
                current = occurrence;
                continue;
            }

            if (occurrence.startOffset < current.endOffset && current.startOffset < occurrence.endOffset) {
                int currentLen = current.endOffset - current.startOffset;
                int nextLen = occurrence.endOffset - occurrence.startOffset;
                if (nextLen > currentLen) {
                    current = occurrence;
                }
                continue;
            }

            collapsed.add(current);
            current = occurrence;
        }

        if (current != null) {
            collapsed.add(current);
        }
        return collapsed;
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

    private static LanguageLevel getLanguageLevel(PsiFile psiFile) {
        try {
            return PsiUtil.getLanguageLevel(psiFile);
        } catch (Throwable t) {
            return LanguageLevel.HIGHEST;
        }
    }

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

        LanguageLevel level = getLanguageLevel(psiFile);

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

    // Type-2 (rename/literal-normalized exact token sequence) matcher helpers

    private static Set<Occurrence> findType2OccurrencesNormalized(PsiFile psiFile, String pastedSnippet, String fileText) {
        if (psiFile == null) return Collections.emptySet();
        if (pastedSnippet == null || pastedSnippet.trim().isEmpty()) return Collections.emptySet();
        if (fileText == null || fileText.isEmpty()) return Collections.emptySet();

        LanguageLevel level = getLanguageLevel(psiFile);
        List<NormTok> needle = lexAndNormalize(pastedSnippet, level);
        if (needle.size() < MIN_TOKENS_FOR_TYPE2_NORMALIZED) return Collections.emptySet();

        List<NormTok> hay = lexAndNormalize(fileText, level);
        if (hay.size() < needle.size()) return Collections.emptySet();

        int m = needle.size();
        Set<Occurrence> out = new LinkedHashSet<>();

        for (int i = 0; i <= hay.size() - m; i++) {
            if (!matchesNormalizedWindow(needle, hay, i, m)) continue;

            int start = hay.get(i).startOffset;
            int end = hay.get(i + m - 1).endOffset;
            if (start >= 0 && end > start) {
                out.add(new Occurrence(start, end));
            }
        }

        return out;
    }

    private static boolean matchesNormalizedWindow(List<NormTok> needle, List<NormTok> hay, int hayStart, int length) {
        if (needle == null || hay == null) return false;
        if (length != needle.size()) return false;
        if (hayStart < 0 || hayStart + length > hay.size()) return false;

        for (int i = 0; i < length; i++) {
            if (!needle.get(i).kind.equals(hay.get(hayStart + i).kind)) {
                return false;
            }
        }
        return true;
    }

    // Type-3 (bounded approximate normalized token sequence) matcher helpers

    private static Set<Occurrence> findType3OccurrencesApproximate(PsiFile psiFile, String pastedSnippet, String fileText) {
        if (psiFile == null) return Collections.emptySet();
        if (pastedSnippet == null || pastedSnippet.trim().isEmpty()) return Collections.emptySet();
        if (fileText == null || fileText.isEmpty()) return Collections.emptySet();

        LanguageLevel level = getLanguageLevel(psiFile);
        List<NormTok> needle = lexAndNormalize(pastedSnippet, level);
        if (needle.size() < MIN_TOKENS_FOR_TYPE3_APPROXIMATE) return Collections.emptySet();

        List<NormTok> hay = lexAndNormalize(fileText, level);
        if (hay.size() < MIN_TOKENS_FOR_TYPE3_APPROXIMATE) return Collections.emptySet();

        int m = needle.size();
        int delta = Math.max(4, m / 3);
        int minWindow = Math.max(MIN_TOKENS_FOR_TYPE3_APPROXIMATE, m - delta);
        int maxWindow = Math.min(hay.size(), m + delta);

        Set<Occurrence> out = new LinkedHashSet<>();
        String firstKind = needle.get(0).kind;
        String lastKind = needle.get(m - 1).kind;

        for (int start = 0; start < hay.size(); start++) {
            if (!firstKind.equals(hay.get(start).kind)) continue;

            int largestPossibleWindow = Math.min(maxWindow, hay.size() - start);
            if (largestPossibleWindow < minWindow) continue;

            Occurrence best = null;
            double bestScore = -1.0;
            int bestLengthDistance = Integer.MAX_VALUE;

            for (int length = minWindow; length <= largestPossibleWindow; length++) {
                if (!lastKind.equals(hay.get(start + length - 1).kind)) continue;
                if (!passesType3StructuralGuard(needle, hay, start, length)) continue;

                int lcs = lcsLength(needle, hay, start, length);
                double needleCoverage = lcs / (double) m;
                double similarity = lcs / (double) Math.max(m, length);
                if (needleCoverage < TYPE3_MIN_NEEDLE_COVERAGE) continue;
                if (similarity < TYPE3_MIN_LCS_SIMILARITY) continue;

                double score = needleCoverage + similarity;
                int lengthDistance = Math.abs(length - m);
                if (score > bestScore ||
                        (Double.compare(score, bestScore) == 0 && lengthDistance < bestLengthDistance)) {
                    int end = hay.get(start + length - 1).endOffset;
                    int startOffset = hay.get(start).startOffset;
                    if (startOffset >= 0 && end > startOffset) {
                        best = new Occurrence(startOffset, end);
                        bestScore = score;
                        bestLengthDistance = lengthDistance;
                    }
                }
            }

            if (best != null) {
                out.add(best);
            }
        }

        return out;
    }

    private static int lcsLength(List<NormTok> needle, List<NormTok> hay, int hayStart, int hayLength) {
        int[] previous = new int[hayLength + 1];
        int[] current = new int[hayLength + 1];

        for (NormTok needleTok : needle) {
            for (int j = 1; j <= hayLength; j++) {
                String hayKind = hay.get(hayStart + j - 1).kind;
                if (needleTok.kind.equals(hayKind)) {
                    current[j] = previous[j - 1] + 1;
                } else {
                    current[j] = Math.max(previous[j], current[j - 1]);
                }
            }

            int[] tmp = previous;
            previous = current;
            current = tmp;
            for (int j = 0; j <= hayLength; j++) {
                current[j] = 0;
            }
        }

        return previous[hayLength];
    }

    private static boolean passesType3StructuralGuard(List<NormTok> needle, List<NormTok> hay, int hayStart, int hayLength) {
        int anchorCount = 0;
        int matchedAnchors = 0;
        boolean[] used = new boolean[hayLength];

        for (NormTok needleTok : needle) {
            if (!isType3AnchorKind(needleTok.kind)) continue;
            anchorCount++;

            for (int i = 0; i < hayLength; i++) {
                if (used[i]) continue;
                if (!needleTok.kind.equals(hay.get(hayStart + i).kind)) continue;
                used[i] = true;
                matchedAnchors++;
                break;
            }
        }

        if (anchorCount < 4) return true;
        return matchedAnchors * 10 >= anchorCount * 7;
    }

    private static boolean isType3AnchorKind(String kind) {
        if (kind == null) return false;
        if ("ID".equals(kind) || "LIT".equals(kind)) return false;
        return !("LPARENTH".equals(kind) ||
                "RPARENTH".equals(kind) ||
                "LBRACE".equals(kind) ||
                "RBRACE".equals(kind) ||
                "SEMICOLON".equals(kind) ||
                "COMMA".equals(kind) ||
                "DOT".equals(kind));
    }
}
