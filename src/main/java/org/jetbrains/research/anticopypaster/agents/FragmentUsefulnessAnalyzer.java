
package org.jetbrains.research.anticopypaster.agents;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.util.TextRange;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Fragment-level clone refactoring usefulness analyzer.
 *
 * Purpose:
 * - The pasted snippet may be a fragment inside a method (not a whole method).
 * - We still want to detect the same refactoring categories discussed in the slides:
 *   EXTRACT_METHOD, POST_EXTRACTION_DELETION, DIRECT_REMOVAL, DELEGATION_TO_EXISTING,
 *   FRAGMENTATION, and INCOMPLETE_REFACTORING.
 *
 * Notes:
 * - This analyzer is best-effort and must never throw (returns null on fatal failures).
 * - It is snippet-centered: analyzes the clone pair consisting of (pasted snippet range) and (matching range).
 * - It does NOT require perfect range mapping across edits; it tries to re-locate fragments in AFTER using
 *   cloneCode text, and falls back to the provided line ranges.
 */
public final class FragmentUsefulnessAnalyzer {

    private FragmentUsefulnessAnalyzer() {}

    /* =============================
     * DTOs
     * ============================= */

    public static final class LineRange {
        public final int startLine; // 1-based inclusive
        public final int endLine;   // 1-based inclusive

        public LineRange(int startLine, int endLine) {
            this.startLine = Math.max(1, startLine);
            this.endLine = Math.max(this.startLine, endLine);
        }

        @Override
        public String toString() {
            return startLine + "-" + endLine;
        }
    }

    public static final class UsefulnessConfig {
        /** BEFORE clone similarity threshold (Jaccard on normalized token sets). */
        public final double cloneSimilarityBefore;
        /** AFTER similarity considered "still a clone" (incomplete refactoring signal). */
        public final double cloneSimilarityAfterStill;
        /** AFTER similarity considered "reduced". */
        public final double cloneSimilarityAfterReduced;
        /** Minimum normalized token count for a fragment to be meaningful. */
        public final int minTokenCount;
        /** Consider delegate if covered statements collapse into a single call with at most this many args. */
        public final int maxDelegateArgs;

        public UsefulnessConfig() {
            this(0.85, 0.80, 0.60, 20, 6);
        }

        public UsefulnessConfig(double cloneSimilarityBefore,
                                double cloneSimilarityAfterStill,
                                double cloneSimilarityAfterReduced,
                                int minTokenCount,
                                int maxDelegateArgs) {
            this.cloneSimilarityBefore = cloneSimilarityBefore;
            this.cloneSimilarityAfterStill = cloneSimilarityAfterStill;
            this.cloneSimilarityAfterReduced = cloneSimilarityAfterReduced;
            this.minTokenCount = minTokenCount;
            this.maxDelegateArgs = maxDelegateArgs;
        }
    }

    public enum Strategy {
        EXTRACT_METHOD,
        POST_EXTRACTION_DELETION,
        DIRECT_REMOVAL,
        DELEGATION_TO_EXISTING,
        FRAGMENTATION,
        INCOMPLETE_REFACTORING,
        UNKNOWN
    }

    public enum Reason {
        FRAGMENT_TOO_SMALL,
        NOT_A_CLONE_IN_BEFORE,
        CLONE_REDUCED_OR_REMOVED,
        INCOMPLETE_REFACTORING_DETECTED,
        ANALYZER_FALLBACK,
        NON_EXTRACT_METHOD_STRATEGY
    }

    public static final class UsefulnessResult {
        public final boolean isUseful;
        public final int score; // 0..100
        public final Strategy strategy;
        public final List<Reason> reasons;
        public final String notes;

        public UsefulnessResult(boolean isUseful,
                                int score,
                                Strategy strategy,
                                List<Reason> reasons,
                                String notes) {
            this.isUseful = isUseful;
            this.score = clamp(score, 0, 100);
            this.strategy = strategy == null ? Strategy.UNKNOWN : strategy;
            this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
            this.notes = notes == null ? "" : notes;
        }
    }

    /* =============================
     * Main API
     * ============================= */

    /**
     * Analyze fragment usefulness for one clone pair: (pasted snippet range) vs (matching range).
     */
    public static UsefulnessResult analyze(Project project,
                                          String fileName,
                                          String beforeSource,
                                          String afterSource,
                                          LineRange pastedRange,
                                          LineRange matchRange,
                                          String cloneCodeA,
                                          String cloneCodeB,
                                          UsefulnessConfig cfg) {
        try {
            if (project == null || project.isDisposed()) return null;
            if (cfg == null) cfg = new UsefulnessConfig();
            if (beforeSource == null) beforeSource = "";
            if (afterSource == null) afterSource = "";
            if (pastedRange == null || matchRange == null) return fallback("missing ranges");

            // 1) Get BEFORE fragment texts (prefer cloneCodeA/B if provided; otherwise slice by range)
            String beforeA = nonBlankOr(sliceByLineRange(beforeSource, pastedRange), cloneCodeA);
            String beforeB = nonBlankOr(sliceByLineRange(beforeSource, matchRange), cloneCodeB);

            Sig beforeSigA = sigFromText(beforeA);
            Sig beforeSigB = sigFromText(beforeB);

            List<Reason> reasons = new ArrayList<>();

            int minTok = Math.min(beforeSigA.tokenCount, beforeSigB.tokenCount);
            if (minTok < cfg.minTokenCount) {
                reasons.add(Reason.FRAGMENT_TOO_SMALL);
                return new UsefulnessResult(true, 55, Strategy.UNKNOWN, reasons,
                        "minTokenCount=" + minTok + " (<" + cfg.minTokenCount + ")");
            }

            double simBefore = jaccard(beforeSigA, beforeSigB);
            if (simBefore < cfg.cloneSimilarityBefore) {
                reasons.add(Reason.NOT_A_CLONE_IN_BEFORE);
                return new UsefulnessResult(true, 55, Strategy.UNKNOWN, reasons,
                        "simBefore=" + fmt(simBefore) + " (<" + cfg.cloneSimilarityBefore + ")");
            }

            // 2) Parse PSI for BEFORE/AFTER to compute added methods and to analyze calls
            PsiJavaFile beforePsi = parseInMemoryJavaFile(project, fileName, beforeSource);
            PsiJavaFile afterPsi = parseInMemoryJavaFile(project, fileName, afterSource);
            if (beforePsi == null || afterPsi == null) return fallback("PSI parse failed");

            Map<String, PsiMethod> beforeMethods = collectAllMethodsByKey(beforePsi);
            Map<String, PsiMethod> afterMethods = collectAllMethodsByKey(afterPsi);
            Set<String> addedMethodKeys = new HashSet<>(afterMethods.keySet());
            addedMethodKeys.removeAll(beforeMethods.keySet());

            // 3) Re-locate A/B in AFTER (best-effort) for similarity/notes only.
            LineRange afterA = relocateAfter(afterSource, cloneCodeA, pastedRange);
            LineRange afterB = relocateAfter(afterSource, cloneCodeB, matchRange);

            String afterAText = sliceByLineRange(afterSource, afterA);
            String afterBText = sliceByLineRange(afterSource, afterB);

            Sig afterSigA = sigFromText(afterAText);
            Sig afterSigB = sigFromText(afterBText);
            double simAfter = jaccard(afterSigA, afterSigB);

            // 4) Strategy detection based on AFTER evidence at the HOST METHOD level.
            // Range-based slicing is unstable after edits; instead we map the BEFORE fragment to its host method,
            // then analyze the corresponding host methods in AFTER.
            String hostKeyA = findHostMethodKeyByRange(beforePsi, beforeSource, pastedRange);
            String hostKeyB = findHostMethodKeyByRange(beforePsi, beforeSource, matchRange);

            PsiMethod afterHostA = hostKeyA == null ? null : afterMethods.get(hostKeyA);
            PsiMethod afterHostB = hostKeyB == null ? null : afterMethods.get(hostKeyB);

            OccEvidence evA = evidenceForMethod(afterHostA, afterPsi, cfg);
            OccEvidence evB = evidenceForMethod(afterHostB, afterPsi, cfg);

            boolean aRemoved = evA.removed;
            boolean bRemoved = evB.removed;

            Strategy strategy = Strategy.UNKNOWN;
            boolean reducedOrRemoved = false;

            // Removal-like
            if (aRemoved ^ bRemoved) {
                // One side gone
                if (!addedMethodKeys.isEmpty() && (evA.isDelegate || evB.isDelegate)) {
                    strategy = Strategy.POST_EXTRACTION_DELETION;
                } else {
                    strategy = Strategy.DIRECT_REMOVAL;
                }
                reducedOrRemoved = true;
            } else if (aRemoved && bRemoved) {
                strategy = Strategy.DIRECT_REMOVAL;
                reducedOrRemoved = true;
            } else {
                // Both present
                // Delegate/unification
                Set<String> sharedCallees = new HashSet<>(evA.calleeKeys);
                sharedCallees.retainAll(evB.calleeKeys);

                if (evA.isDelegate && evB.isDelegate && !sharedCallees.isEmpty()) {
                    boolean sharedHasNew = sharedCallees.stream().anyMatch(addedMethodKeys::contains);
                    if (sharedCallees.size() >= 2) {
                        strategy = Strategy.FRAGMENTATION;
                    } else {
                        strategy = sharedHasNew ? Strategy.EXTRACT_METHOD : Strategy.DELEGATION_TO_EXISTING;
                    }
                    reducedOrRemoved = true;
                } else {
                    // Fragmentation: shared multiple helpers and similarity reduced
                    if (sharedCallees.size() >= 2 && simAfter <= cfg.cloneSimilarityAfterReduced) {
                        strategy = Strategy.FRAGMENTATION;
                        reducedOrRemoved = true;
                    } else if (simAfter <= cfg.cloneSimilarityAfterReduced) {
                        strategy = Strategy.UNKNOWN;
                        reducedOrRemoved = true;
                    }
                }
            }

            // 5) Incomplete refactoring detection (same concept as whole-method)
            boolean attempt = (!addedMethodKeys.isEmpty()) || evA.hasAnyCall || evB.hasAnyCall || evA.isDelegate || evB.isDelegate;
            if (!aRemoved && !bRemoved && simAfter >= cfg.cloneSimilarityAfterStill && attempt) {
                strategy = Strategy.INCOMPLETE_REFACTORING;
                reasons.add(Reason.INCOMPLETE_REFACTORING_DETECTED);
                int score = 40;
                String notes = "simBefore=" + fmt(simBefore) + ", simAfter=" + fmt(simAfter) +
                        ", afterA=" + afterA + ", afterB=" + afterB +
                        ", addedMethods=" + addedMethodKeys.size() +
                        ", A(delegate=" + evA.isDelegate + ", calls=" + evA.calleeKeys.size() + ")" +
                        ", B(delegate=" + evB.isDelegate + ", calls=" + evB.calleeKeys.size() + ")";
                return new UsefulnessResult(false, score, strategy, reasons, notes);
            }

            // 6) Final decision and score (STRICT MODE)
            // Per tool definition: ONLY a true Extract-Method refactoring is considered useful.
            // All other outcomes are treated as NOT useful, even if duplication was reduced.

            String notes = "strategy=" + strategy +
                    ", simBefore=" + fmt(simBefore) +
                    ", simAfter=" + fmt(simAfter) +
                    ", pasted(before=" + pastedRange + ", after=" + afterA + ")" +
                    ", match(before=" + matchRange + ", after=" + afterB + ")" +
                    ", addedMethods=" + addedMethodKeys.size() +
                    ", A(delegate=" + evA.isDelegate + ", calls=" + evA.calleeKeys.size() + ")" +
                    ", B(delegate=" + evB.isDelegate + ", calls=" + evB.calleeKeys.size() + ")";

            // If it's not Extract-Method, it's NOT useful under this strict definition.
            if (strategy != Strategy.EXTRACT_METHOD) {
                reasons.add(Reason.NON_EXTRACT_METHOD_STRATEGY);
                int score = 30;
                // Give a little credit if duplication was reduced/removed, but still fail usefulness.
                if (simAfter <= cfg.cloneSimilarityAfterReduced) score = 45;
                if (aRemoved || bRemoved) score = Math.max(score, 40);
                return new UsefulnessResult(false, score, strategy, reasons, notes);
            }

            // Extract-Method: consider useful only when the clone is reduced/removed.
            if (simAfter > cfg.cloneSimilarityAfterReduced) {
                // The extracted method exists but the two fragments still look similar: treat as incomplete.
                reasons.add(Reason.INCOMPLETE_REFACTORING_DETECTED);
                return new UsefulnessResult(false, 40, Strategy.INCOMPLETE_REFACTORING, reasons, notes);
            }

            reasons.add(Reason.CLONE_REDUCED_OR_REMOVED);
            int score = 100;
            // Reward stronger reduction (lower simAfter)
            score -= (int) Math.round(40.0 * Math.max(0.0, Math.min(1.0, simAfter)));
            score = clamp(score, 0, 100);

            return new UsefulnessResult(true, score, strategy, reasons, notes);

        } catch (Throwable t) {
            return null;
        }
    }

    /* =============================
     * Evidence extraction
     * ============================= */

    private static final class OccEvidence {
        final boolean removed;
        final boolean isDelegate;
        final boolean hasAnyCall;
        final Set<String> calleeKeys;

        OccEvidence(boolean removed, boolean isDelegate, boolean hasAnyCall, Set<String> calleeKeys) {
            this.removed = removed;
            this.isDelegate = isDelegate;
            this.hasAnyCall = hasAnyCall;
            this.calleeKeys = calleeKeys == null ? Set.of() : Set.copyOf(calleeKeys);
        }
    }

    private static String findHostMethodKeyByRange(PsiJavaFile psi, String source, LineRange r) {
        try {
            if (psi == null || source == null || r == null) return null;
            int startOffset = offsetAtLine(source, r.startLine);
            if (startOffset < 0) return null;
            int safeOffset = Math.min(startOffset, Math.max(0, psi.getTextLength() - 1));
            PsiElement el = psi.findElementAt(safeOffset);
            PsiMethod host = PsiTreeUtil.getParentOfType(el, PsiMethod.class, false);
            if (host == null) return null;
            PsiClass owner = host.getContainingClass();
            if (owner == null) return null;
            String key = methodKey(owner, host);
            return (key == null || key.isBlank()) ? null : key;
        } catch (Throwable t) {
            return null;
        }
    }

    private static OccEvidence evidenceForMethod(PsiMethod host, PsiJavaFile afterPsi, UsefulnessConfig cfg) {
        try {
            if (host == null) return new OccEvidence(true, false, false, Set.of());
            PsiCodeBlock body = host.getBody();
            if (body == null) return new OccEvidence(false, false, false, Set.of());

            Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
            Set<String> calleeKeys = new HashSet<>();
            boolean hasAnyCall = false;

            for (PsiMethodCallExpression call : calls) {
                hasAnyCall = true;
                PsiMethod resolved = null;
                try { resolved = call.resolveMethod(); } catch (Throwable ignored) {}
                if (resolved == null) continue;
                PsiClass owner = resolved.getContainingClass();
                if (owner == null) continue;
                String key = methodKey(owner, resolved);
                if (key != null && !key.isBlank()) calleeKeys.add(key);
            }

            // Delegate method heuristic: the method body is a single call or a single return call.
            String bodyText = body.getText();
            boolean isDelegate = looksLikeSingleCall(bodyText == null ? "" : bodyText.trim(), cfg == null ? 6 : cfg.maxDelegateArgs);

            return new OccEvidence(false, isDelegate, hasAnyCall, calleeKeys);
        } catch (Throwable t) {
            return new OccEvidence(false, false, false, Set.of());
        }
    }

    private static OccEvidence evidenceForRange(PsiJavaFile afterPsi, String afterSource, LineRange r, UsefulnessConfig cfg) {
        try {
            if (afterPsi == null || afterSource == null || r == null) return new OccEvidence(true, false, false, Set.of());
            int startOffset = offsetAtLine(afterSource, r.startLine);
            int endOffset = offsetAtLine(afterSource, r.endLine + 1);
            if (startOffset < 0 || endOffset < 0 || startOffset >= afterSource.length()) {
                return new OccEvidence(true, false, false, Set.of());
            }
            endOffset = Math.min(afterSource.length(), Math.max(startOffset, endOffset));

            PsiElement startEl = afterPsi.findElementAt(Math.min(startOffset, Math.max(0, afterPsi.getTextLength() - 1)));
            PsiMethod host = PsiTreeUtil.getParentOfType(startEl, PsiMethod.class, false);
            if (host == null) {
                // Can't resolve; treat as present but unknown
                return new OccEvidence(false, false, false, Set.of());
            }

            // Collect calls inside the covered text range within the host body.
            PsiCodeBlock body = host.getBody();
            if (body == null) return new OccEvidence(false, false, false, Set.of());

            TextRange frag = new TextRange(startOffset, Math.min(endOffset, afterSource.length()));

            Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
            Set<String> calleeKeys = new HashSet<>();
            boolean hasAnyCall = false;

            for (PsiMethodCallExpression call : calls) {
                PsiElement el = call;
                TextRange tr = el.getTextRange();
                if (tr == null) continue;
                if (!tr.intersects(frag)) continue;

                hasAnyCall = true;

                PsiMethod resolved = null;
                try { resolved = call.resolveMethod(); } catch (Throwable ignored) {}
                if (resolved == null) continue;

                PsiClass owner = resolved.getContainingClass();
                if (owner == null) continue;

                String key = methodKey(owner, resolved);
                if (key != null && !key.isBlank()) calleeKeys.add(key);
            }

            // Detect delegate-like replacement of the fragment: if the fragment itself now looks like a single call statement.
            // Best-effort: if the fragment text trimmed is exactly a call or return call, treat as delegate.
            String fragText = safeSubstring(afterSource, frag.getStartOffset(), frag.getEndOffset()).trim();
            boolean isDelegate = looksLikeSingleCall(fragText, cfg.maxDelegateArgs);

            return new OccEvidence(false, isDelegate, hasAnyCall, calleeKeys);
        } catch (Throwable t) {
            return new OccEvidence(false, false, false, Set.of());
        }
    }

    private static boolean looksLikeSingleCall(String text, int maxArgs) {
        try {
            if (text == null) return false;
            String s = text.trim();
            if (s.isEmpty()) return false;
            // Remove trailing semicolon if present
            if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
            if (s.startsWith("return ")) s = s.substring("return ".length()).trim();

            // Very small heuristic: somethingLike foo(...)
            int lp = s.indexOf('(');
            int rp = s.lastIndexOf(')');
            if (lp < 1 || rp < lp) return false;

            String namePart = s.substring(0, lp).trim();
            if (namePart.isEmpty()) return false;

            String args = s.substring(lp + 1, rp).trim();
            if (args.isEmpty()) return true;

            // Count commas at top-level (best-effort; ignores generics/strings)
            int commas = 0;
            for (int i = 0; i < args.length(); i++) {
                if (args.charAt(i) == ',') commas++;
            }
            int argCount = commas + 1;
            return argCount <= maxArgs;
        } catch (Throwable t) {
            return false;
        }
    }

    /* =============================
     * Relocation / slicing
     * ============================= */

    private static LineRange relocateAfter(String afterSource, String cloneCode, LineRange fallback) {
        try {
            if (afterSource == null) return fallback;
            if (cloneCode != null && !cloneCode.isBlank()) {
                int idx = afterSource.indexOf(cloneCode);
                if (idx >= 0) {
                    int startLine = 1;
                    for (int i = 0; i < idx; i++) if (afterSource.charAt(i) == '\n') startLine++;

                    int endIdx = Math.min(afterSource.length(), idx + cloneCode.length());
                    int endLine = startLine;
                    for (int i = idx; i < endIdx; i++) if (afterSource.charAt(i) == '\n') endLine++;

                    return new LineRange(startLine, endLine);
                }
            }
            return fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String sliceByLineRange(String text, LineRange r) {
        try {
            if (text == null || r == null) return "";
            int start = offsetAtLine(text, r.startLine);
            int end = offsetAtLine(text, r.endLine + 1);
            if (start < 0) return "";
            if (end < 0) end = text.length();
            start = Math.min(start, text.length());
            end = Math.min(Math.max(end, start), text.length());
            return text.substring(start, end);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 1-based line to char offset (start of that line). For line==last+1 returns text.length(). */
    private static int offsetAtLine(String text, int line1Based) {
        if (text == null) return -1;
        if (line1Based <= 1) return 0;
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (line == line1Based) return i;
            if (text.charAt(i) == '\n') line++;
        }
        // If asking for line past the last line, return EOF
        return text.length();
    }

    private static String safeSubstring(String s, int start, int end) {
        try {
            if (s == null) return "";
            int a = Math.max(0, Math.min(start, s.length()));
            int b = Math.max(a, Math.min(end, s.length()));
            return s.substring(a, b);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String nonBlankOr(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null ? "" : b;
    }

    /* =============================
     * PSI parsing and method keys
     * ============================= */

    private static PsiJavaFile parseInMemoryJavaFile(Project project, String fileName, String text) {
        try {
            if (fileName == null || fileName.isBlank()) fileName = "Temp.java";
            if (!fileName.endsWith(".java")) fileName = fileName + ".java";
            if (text == null) text = "";

            PsiFile psi = PsiFileFactory.getInstance(project)
                    .createFileFromText(fileName, JavaLanguage.INSTANCE, text, false, true);
            return (psi instanceof PsiJavaFile) ? (PsiJavaFile) psi : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, PsiMethod> collectAllMethodsByKey(PsiJavaFile jf) {
        Map<String, PsiMethod> map = new LinkedHashMap<>();
        if (jf == null) return map;

        PsiClass[] classes = jf.getClasses();
        if (classes == null) return map;

        for (PsiClass c : classes) collectFromClassRecursive(c, map);
        return map;
    }

    private static void collectFromClassRecursive(PsiClass c, Map<String, PsiMethod> map) {
        if (c == null) return;

        PsiMethod[] ms = c.getMethods();
        if (ms != null) {
            for (PsiMethod m : ms) {
                String key = methodKey(c, m);
                if (key != null && !key.isBlank()) map.putIfAbsent(key, m);
            }
        }

        PsiClass[] inners = c.getInnerClasses();
        if (inners != null) {
            for (PsiClass ic : inners) collectFromClassRecursive(ic, map);
        }
    }

    private static String methodKey(PsiClass owner, PsiMethod m) {
        if (m == null) return "";
        String cls = owner == null ? "" : owner.getQualifiedName();
        if (cls == null || cls.isBlank()) cls = owner == null ? "" : owner.getName();

        StringBuilder sb = new StringBuilder();
        sb.append(cls == null ? "" : cls).append("#").append(m.getName()).append("(");
        try {
            PsiParameter[] ps = m.getParameterList() == null ? new PsiParameter[0] : m.getParameterList().getParameters();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(ps[i].getType().getCanonicalText());
            }
        } catch (Throwable ignored) {}
        sb.append(")");
        return sb.toString();
    }

    /* =============================
     * Normalized signature / similarity
     * ============================= */

    private static final class Sig {
        final Set<String> tokenSet;
        final int tokenCount;

        Sig(Set<String> tokenSet, int tokenCount) {
            this.tokenSet = tokenSet == null ? Set.of() : tokenSet;
            this.tokenCount = tokenCount;
        }
    }

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> JAVA_KW = Set.of(
            "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
            "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
            "implements","import","instanceof","int","interface","long","native","new","package","private",
            "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
            "throw","throws","transient","try","void","volatile","while","var","record","sealed","permits"
    );

    private static Sig sigFromText(String text) {
        if (text == null) return new Sig(Set.of(), 0);
        List<String> toks = tokenizeAndNormalize(text);
        return new Sig(new HashSet<>(toks), toks.size());
    }

    private static List<String> tokenizeAndNormalize(String text) {
        // Very lightweight tokenizer: split on whitespace and punctuation, keep operators as tokens when possible.
        // Normalize identifiers -> ID (except keywords), numbers/strings -> LIT.
        List<String> out = new ArrayList<>();
        if (text == null) return out;

        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            // crude string literal handling
            if (ch == '"' || ch == '\'') {
                // flush current
                flushToken(cur, out);
                // skip until closing quote
                char quote = ch;
                i++;
                while (i < text.length()) {
                    char c = text.charAt(i);
                    if (c == '\\') { // escape
                        i += 2;
                        continue;
                    }
                    if (c == quote) break;
                    i++;
                }
                out.add("LIT");
                continue;
            }

            if (Character.isLetterOrDigit(ch) || ch == '_' ) {
                cur.append(ch);
            } else {
                flushToken(cur, out);
                if (!Character.isWhitespace(ch)) {
                    // keep some operators/punctuations as tokens
                    if ("+-*/%=&|!<>^".indexOf(ch) >= 0) {
                        out.add("OP:" + ch);
                    } else if (ch == '(' || ch == ')' || ch == '{' || ch == '}' || ch == '[' || ch == ']') {
                        out.add("BR:" + ch);
                    } else if (ch == '.' ) {
                        out.add("DOT");
                    } else if (ch == ',' ) {
                        out.add("COMMA");
                    } else if (ch == ';') {
                        out.add("SEMI");
                    }
                }
            }
        }
        flushToken(cur, out);
        return out;
    }

    private static void flushToken(StringBuilder cur, List<String> out) {
        if (cur.length() == 0) return;
        String tok = cur.toString();
        cur.setLength(0);

        // number?
        boolean isNum = true;
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == 'x' || c == 'X' || (i == 0 && (c == '-' || c == '+')))) {
                isNum = false;
                break;
            }
        }
        if (isNum) {
            out.add("LIT");
            return;
        }

        if (IDENT.matcher(tok).matches()) {
            String lower = tok;
            if (JAVA_KW.contains(lower)) {
                out.add("KW:" + lower);
            } else {
                out.add("ID");
            }
        } else {
            out.add(tok);
        }
    }

    private static double jaccard(Sig a, Sig b) {
        if (a == null || b == null) return 0.0;
        if (a.tokenSet.isEmpty() || b.tokenSet.isEmpty()) return 0.0;

        Set<String> small = a.tokenSet.size() <= b.tokenSet.size() ? a.tokenSet : b.tokenSet;
        Set<String> large = small == a.tokenSet ? b.tokenSet : a.tokenSet;

        int inter = 0;
        for (String s : small) if (large.contains(s)) inter++;
        int union = a.tokenSet.size() + b.tokenSet.size() - inter;
        if (union <= 0) return 0.0;
        return inter * 1.0 / union;
    }

    /* =============================
     * Utilities
     * ============================= */

    private static UsefulnessResult fallback(String msg) {
        return new UsefulnessResult(true, 60, Strategy.UNKNOWN,
                List.of(Reason.ANALYZER_FALLBACK),
                "fallback=" + (msg == null ? "" : msg));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%.2f", d);
    }
}
