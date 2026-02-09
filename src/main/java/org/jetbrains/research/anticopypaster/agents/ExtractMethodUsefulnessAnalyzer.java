package org.jetbrains.research.anticopypaster.agents;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.tree.IElementType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Whole-method clone refactoring usefulness analyzer.
 *
 * This analyzer is designed for cases where clones are whole methods (not fragments).
 * It classifies several refactoring strategies observed in practice:
 *  - EXTRACT_METHOD: new helper introduced and both clones delegate/call it
 *  - POST_EXTRACTION_DELETION: helper introduced, one clone delegates, the other deleted
 *  - DIRECT_REMOVAL: one clone deleted without introducing a new helper
 *  - DELEGATION_TO_EXISTING: one clone becomes a thin delegate to an existing method (no new helper required)
 *  - FRAGMENTATION: duplication reduced by splitting logic into multiple shared helpers
 *  - INCOMPLETE_REFACTORING: duplication remains high after a refactoring attempt
 *
 * Important: This is a conservative, best-effort filter. It should never break the workflow.
 */
public final class ExtractMethodUsefulnessAnalyzer {

    private ExtractMethodUsefulnessAnalyzer() {}

    /* =============================
     * Public DTOs
     * ============================= */

    public static final class UsefulnessConfig {
        /** Min number of normalized tokens to consider a method for clone matching. */
        public final int minTokenCount;

        /** Threshold to declare two methods clones in BEFORE (Jaccard of token sets). */
        public final double cloneSimilarityBefore;

        /** Threshold to consider a clone still "remaining" in AFTER. */
        public final double cloneSimilarityAfterStill;

        /** If similarityAfter <= this threshold, we consider duplication "significantly reduced". */
        public final double cloneSimilarityAfterReduced;

        /** How many top clone pairs to analyze (avoid O(n^2) blowups on huge files). */
        public final int maxPairs;

        /** Max params for a delegate call site to be considered "thin" (heuristic). */
        public final int maxDelegateParams;

        public UsefulnessConfig() {
            this(25, 0.85, 0.80, 0.60, 30, 6);
        }

        public UsefulnessConfig(int minTokenCount,
                                double cloneSimilarityBefore,
                                double cloneSimilarityAfterStill,
                                double cloneSimilarityAfterReduced,
                                int maxPairs,
                                int maxDelegateParams) {
            this.minTokenCount = minTokenCount;
            this.cloneSimilarityBefore = cloneSimilarityBefore;
            this.cloneSimilarityAfterStill = cloneSimilarityAfterStill;
            this.cloneSimilarityAfterReduced = cloneSimilarityAfterReduced;
            this.maxPairs = maxPairs;
            this.maxDelegateParams = maxDelegateParams;
        }
    }

    public enum Reason {
        NO_CLONES_DETECTED,
        EXTRACT_METHOD_CONFIRMED,

        INCOMPLETE_REFACTORING_DETECTED,
        EXCESSIVE_REFACTORING_DETECTED,
        POST_EXTRACTION_CLONE_DELETION_DETECTED,
        DIRECT_CLONE_REMOVAL_DETECTED,
        CALL_BASED_CLONE_SUBSTITUTION_DETECTED,
        CLONE_REMOVAL_BY_DELEGATION_DETECTED,
        FRAGMENTATION_OF_LOGIC_DETECTED,

        ANALYZER_FALLBACK
    }

    public enum Strategy {
        // "Good" outcome (the only one we accept as useful)
        EXTRACT_METHOD,

        // Failure modes / non-Extract-Method outcomes (JSS taxonomy)
        INCOMPLETE_REFACTORING,
        EXCESSIVE_REFACTORING,
        POST_EXTRACTION_CLONE_DELETION,
        DIRECT_CLONE_REMOVAL,
        CALL_BASED_CLONE_SUBSTITUTION,
        CLONE_REMOVAL_BY_DELEGATION,
        FRAGMENTATION_OF_LOGIC,

        // Backward-compat / legacy labels (kept to avoid breaking other code paths)
        POST_EXTRACTION_DELETION,
        DIRECT_REMOVAL,
        DELEGATION_TO_EXISTING,
        FRAGMENTATION,

        UNKNOWN
    }

    public static final class UsefulnessResult {
        public final boolean isUseful;
        public final int score; // 0..100
        public final List<Reason> reasons;
        public final String notes;

        public UsefulnessResult(boolean isUseful, int score, List<Reason> reasons, String notes) {
            this.isUseful = isUseful;
            this.score = score;
            this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
            this.notes = notes == null ? "" : notes;
        }
    }

    /* =============================
     * Main API
     * ============================= */

    public static UsefulnessResult analyze(Project project,
                                          String fileName,
                                          String beforeSource,
                                          String afterSource,
                                          UsefulnessConfig cfg) {
        try {
            if (project == null || project.isDisposed()) return null;
            if (cfg == null) cfg = new UsefulnessConfig();
            if (beforeSource == null) beforeSource = "";
            if (afterSource == null) afterSource = "";

            PsiJavaFile beforePsi = parseInMemoryJavaFile(project, fileName, beforeSource);
            PsiJavaFile afterPsi = parseInMemoryJavaFile(project, fileName, afterSource);
            if (beforePsi == null || afterPsi == null) return fallback("PSI parse failed");

            Map<String, PsiMethod> beforeMethods = collectAllMethodsByKey(beforePsi);
            Map<String, PsiMethod> afterMethods = collectAllMethodsByKey(afterPsi);

            // Precompute normalized token signatures
            Map<String, Sig> beforeSig = new HashMap<>();
            for (Map.Entry<String, PsiMethod> e : beforeMethods.entrySet()) {
                beforeSig.put(e.getKey(), buildSig(e.getValue()));
            }
            Map<String, Sig> afterSig = new HashMap<>();
            for (Map.Entry<String, PsiMethod> e : afterMethods.entrySet()) {
                afterSig.put(e.getKey(), buildSig(e.getValue()));
            }

            // Identify new methods in AFTER (helpers / fragmentation candidates)
            Set<String> addedKeys = new HashSet<>(afterMethods.keySet());
            addedKeys.removeAll(beforeMethods.keySet());

            // Find clone candidates in BEFORE (whole-method)
            List<PairScore> clonePairs = findClonePairs(beforeSig, cfg);
            if (clonePairs.isEmpty()) {
                return new UsefulnessResult(false, 30, List.of(Reason.NO_CLONES_DETECTED),
                        "No whole-method clone pairs detected in BEFORE; cannot confirm Extract Method.");
            }

            // STRICT usefulness: ALL pairs must be EXTRACT_METHOD, else NOT useful.
            int analyzed = 0;
            int extractOk = 0;
            EnumMap<Strategy, Integer> strategyCounts = new EnumMap<>(Strategy.class);

            // Track which non-Extract-Method outcomes happened at least once
            EnumSet<Reason> failReasons = EnumSet.noneOf(Reason.class);

            for (PairScore ps : clonePairs) {
                if (analyzed >= cfg.maxPairs) break;
                analyzed++;

                PairOutcome out = evaluatePair(ps, beforeMethods, afterMethods, beforeSig, afterSig, addedKeys, afterPsi, cfg);
                strategyCounts.put(out.strategy, strategyCounts.getOrDefault(out.strategy, 0) + 1);

                if (out.strategy == Strategy.EXTRACT_METHOD) {
                    extractOk++;
                    continue;
                }

                // Any non-EXTRACT_METHOD outcome makes the proposal NOT useful.
                switch (out.strategy) {
                    case INCOMPLETE_REFACTORING -> failReasons.add(Reason.INCOMPLETE_REFACTORING_DETECTED);
                    case EXCESSIVE_REFACTORING -> failReasons.add(Reason.EXCESSIVE_REFACTORING_DETECTED);
                    case POST_EXTRACTION_CLONE_DELETION -> failReasons.add(Reason.POST_EXTRACTION_CLONE_DELETION_DETECTED);
                    case DIRECT_CLONE_REMOVAL -> failReasons.add(Reason.DIRECT_CLONE_REMOVAL_DETECTED);
                    case CALL_BASED_CLONE_SUBSTITUTION -> failReasons.add(Reason.CALL_BASED_CLONE_SUBSTITUTION_DETECTED);
                    case CLONE_REMOVAL_BY_DELEGATION -> failReasons.add(Reason.CLONE_REMOVAL_BY_DELEGATION_DETECTED);
                    case FRAGMENTATION_OF_LOGIC -> failReasons.add(Reason.FRAGMENTATION_OF_LOGIC_DETECTED);
                    default -> failReasons.add(Reason.ANALYZER_FALLBACK);
                }
            }

            List<Reason> reasons = new ArrayList<>();
            int score;

            boolean isUseful = (analyzed > 0) && (extractOk == analyzed) && failReasons.isEmpty();
            if (isUseful) {
                reasons.add(Reason.EXTRACT_METHOD_CONFIRMED);
                score = 100;
            } else {
                reasons.addAll(failReasons.isEmpty() ? List.of(Reason.ANALYZER_FALLBACK) : new ArrayList<>(failReasons));
                // Score: start from 60 and subtract per failure type + per non-extract pair
                int nonExtract = analyzed - extractOk;
                score = 60 - Math.min(40, nonExtract * 10);
                score = clamp(score, 0, 100);
            }

            String notes = "analyzedPairs=" + analyzed +
                    ", extractMethodPairs=" + extractOk +
                    ", addedMethods=" + addedKeys.size() +
                    ", strategies=" + summarizeStrategies(strategyCounts);

            return new UsefulnessResult(isUseful, score, reasons, notes);

        } catch (Throwable t) {
            return null; // best-effort: never break workflow
        }
    }

    /* =============================
     * Pair evaluation
     * ============================= */

    private static final class PairOutcome {
        final Strategy strategy;
        final boolean reducedOrRemoved;

        PairOutcome(Strategy strategy, boolean reducedOrRemoved) {
            this.strategy = strategy;
            this.reducedOrRemoved = reducedOrRemoved;
        }
    }

    private static PairOutcome evaluatePair(PairScore pair,
                                           Map<String, PsiMethod> beforeMethods,
                                           Map<String, PsiMethod> afterMethods,
                                           Map<String, Sig> beforeSig,
                                           Map<String, Sig> afterSig,
                                           Set<String> addedKeys,
                                           PsiJavaFile afterPsi,
                                           UsefulnessConfig cfg) {

        String aKey = pair.aKey;
        String bKey = pair.bKey;

        PsiMethod aAfter = afterMethods.get(aKey);
        PsiMethod bAfter = afterMethods.get(bKey);

        boolean aDeleted = (aAfter == null);
        boolean bDeleted = (bAfter == null);

        // If one side deleted: could be direct removal or post-extraction deletion.
        if (aDeleted ^ bDeleted) {
            String remainingKey = aDeleted ? bKey : aKey;
            PsiMethod remaining = afterMethods.get(remainingKey);

            DelegateInfo del = (remaining == null) ? null : detectDelegate(remaining, afterPsi, cfg);

            if (!addedKeys.isEmpty() && del != null && del.isDelegate) {
                // Helper introduced somewhere + remaining method delegates, while the other clone is deleted.
                return new PairOutcome(Strategy.POST_EXTRACTION_CLONE_DELETION, true);
            }
            // One side deleted without a symmetric Extract Method outcome.
            return new PairOutcome(Strategy.DIRECT_CLONE_REMOVAL, true);
        }

        if (aDeleted && bDeleted) {
            return new PairOutcome(Strategy.DIRECT_CLONE_REMOVAL, true);
        }

        // Both still exist: compare AFTER similarity
        Sig aSigAfter = afterSig.get(aKey);
        Sig bSigAfter = afterSig.get(bKey);
        double simAfter = jaccard(aSigAfter, bSigAfter);

        // Delegation / unification checks
        DelegateInfo aDel = detectDelegate(aAfter, afterPsi, cfg);
        DelegateInfo bDel = detectDelegate(bAfter, afterPsi, cfg);

        /* =============================
         * Excessive refactoring (whole-method)
         * ============================= */
        // See helper methods below

        // -----------------------------
        // Step 1: delegate-based checks
        // -----------------------------

        // (1) Both are thin delegates to the same callee(s)
        if (aDel.isDelegate && bDel.isDelegate) {
            Set<String> shared = new HashSet<>(aDel.calleeKeys);
            shared.retainAll(bDel.calleeKeys);

            if (!shared.isEmpty()) {
                // If they delegate to >=2 shared callees, this is fragmentation of logic.
                if (shared.size() >= 2) {
                    return new PairOutcome(Strategy.FRAGMENTATION_OF_LOGIC, true);
                }

                // Exactly one shared callee.
                String only = shared.iterator().next();
                boolean isNew = addedKeys.contains(only);

                if (isNew) {
                    // Intended Extract Method: both delegates to the newly added helper
                    PsiMethod helper = afterMethods.get(only);
                    PsiMethod beforeA = beforeMethods.get(aKey);
                    PsiMethod beforeB = beforeMethods.get(bKey);

                    // Excessive refactoring: helper includes much more code than original clones
                    // (heuristic ratio 1.20)
                    if (helper != null && isExcessiveExtraction(beforeA, beforeB, helper, 1.20)) {
                        return new PairOutcome(Strategy.EXCESSIVE_REFACTORING, false);
                    }

                    return new PairOutcome(Strategy.EXTRACT_METHOD, true);
                }

                // Delegation to an existing method (call-based clone substitution)
                return new PairOutcome(Strategy.CALL_BASED_CLONE_SUBSTITUTION, true);
            }
        }

        // (2) Asymmetric delegation cases
        // If exactly one side is a delegate, and it delegates to the other clone method => call-based clone substitution.
        if (aDel.isDelegate ^ bDel.isDelegate) {
            DelegateInfo del = aDel.isDelegate ? aDel : bDel;
            String delegatedFrom = aDel.isDelegate ? aKey : bKey;
            String delegatedToOther = aDel.isDelegate ? bKey : aKey;

            if (!del.calleeKeys.isEmpty() && del.calleeKeys.contains(delegatedToOther)) {
                return new PairOutcome(Strategy.CALL_BASED_CLONE_SUBSTITUTION, true);
            }

            // Delegate exists but not to the same shared callee => clone removal by delegation
            return new PairOutcome(Strategy.CLONE_REMOVAL_BY_DELEGATION, true);
        }

        // Fragmentation: even if not pure delegates, both methods may now call multiple shared helpers and similarity drops.
        Set<String> aCalls = collectResolvedCalleeKeys(aAfter, afterPsi, cfg);
        Set<String> bCalls = collectResolvedCalleeKeys(bAfter, afterPsi, cfg);
        Set<String> sharedCalls = new HashSet<>(aCalls);
        sharedCalls.retainAll(bCalls);

        if (sharedCalls.size() >= 2 && simAfter <= cfg.cloneSimilarityAfterReduced) {
            return new PairOutcome(Strategy.FRAGMENTATION_OF_LOGIC, true);
        }

        // If similarity remains high after, decide whether this is incomplete refactoring.
        if (simAfter >= cfg.cloneSimilarityAfterStill) {
            boolean refactorAttempt = (!addedKeys.isEmpty()) ||
                    aDel.isDelegate || bDel.isDelegate ||
                    (!sharedCalls.isEmpty());

            if (refactorAttempt) {
                return new PairOutcome(Strategy.INCOMPLETE_REFACTORING, false);
            }
            // Otherwise: still clones, but no evidence of attempt in this proposal; treat as unknown (do not hard-fail globally).
            return new PairOutcome(Strategy.UNKNOWN, false);
        }

        // Similarity dropped enough => reduced (even if strategy unknown)
        if (simAfter <= cfg.cloneSimilarityAfterReduced) {
            // Similarity reduced, but we cannot prove it is Extract Method.
            return new PairOutcome(Strategy.UNKNOWN, false);
        }

        // Middle region: reduced a bit, but not clear
        return new PairOutcome(Strategy.UNKNOWN, false);
    }

    /* =============================
     * Excessive refactoring (whole-method)
     * ============================= */

    private static int bodyLineCount(PsiMethod m) {
        try {
            if (m == null || m.getBody() == null) return 0;
            PsiCodeBlock b = m.getBody();
            if (b == null) return 0;
            PsiStatement[] st = b.getStatements();
            if (st == null) return 0;
            if (st.length == 0) return 0;

            int start = st[0].getTextRange() == null ? -1 : st[0].getTextRange().getStartOffset();
            int end = st[st.length - 1].getTextRange() == null ? -1 : st[st.length - 1].getTextRange().getEndOffset();
            if (start < 0 || end < 0 || end <= start) return 0;

            String txt = b.getText();
            if (txt == null || txt.isBlank()) return 0;
            // Approx line count by counting '\n' inside the body text
            int lines = 1;
            for (int i = 0; i < txt.length(); i++) if (txt.charAt(i) == '\n') lines++;
            return lines;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isExcessiveExtraction(PsiMethod beforeA,
                                                 PsiMethod beforeB,
                                                 PsiMethod extractedHelper,
                                                 double maxRatio) {
        // Heuristic: extracted helper is "excessive" if its body is much larger than each original clone body.
        // Default ratio: 1.20 means helper is 20% larger than the larger clone body.
        int aLines = bodyLineCount(beforeA);
        int bLines = bodyLineCount(beforeB);
        int hLines = bodyLineCount(extractedHelper);
        int base = Math.max(aLines, bLines);
        if (base <= 0 || hLines <= 0) return false;
        return hLines > (int) Math.ceil(base * maxRatio);
    }

    /* =============================
     * Delegate / call analysis
     * ============================= */

    private static final class DelegateInfo {
        final boolean isDelegate;
        final Set<String> calleeKeys;

        DelegateInfo(boolean isDelegate, Set<String> calleeKeys) {
            this.isDelegate = isDelegate;
            this.calleeKeys = calleeKeys == null ? Set.of() : Set.copyOf(calleeKeys);
        }
    }

    /**
     * Detect if a method is a thin delegate:
     * - body has 1 statement, which is either a return call or expression call
     * - call resolves to a method in the same PSI file
     * - param count of the call is not too large (heuristic)
     */
    private static DelegateInfo detectDelegate(PsiMethod m, PsiJavaFile file, UsefulnessConfig cfg) {
        try {
            if (m == null) return new DelegateInfo(false, Set.of());
            PsiCodeBlock body = m.getBody();
            if (body == null) return new DelegateInfo(false, Set.of());

            PsiStatement[] st = body.getStatements();
            if (st == null || st.length != 1) return new DelegateInfo(false, Set.of());

            PsiMethodCallExpression call = null;

            if (st[0] instanceof PsiReturnStatement) {
                PsiExpression ret = ((PsiReturnStatement) st[0]).getReturnValue();
                if (ret instanceof PsiMethodCallExpression) call = (PsiMethodCallExpression) ret;
            } else if (st[0] instanceof PsiExpressionStatement) {
                PsiExpression expr = ((PsiExpressionStatement) st[0]).getExpression();
                if (expr instanceof PsiMethodCallExpression) call = (PsiMethodCallExpression) expr;
            }

            if (call == null) return new DelegateInfo(false, Set.of());
            PsiMethod resolved = call.resolveMethod();
            if (resolved == null) return new DelegateInfo(false, Set.of());

            int argCount = call.getArgumentList() == null ? 0 : call.getArgumentList().getExpressionCount();
            if (argCount > cfg.maxDelegateParams) return new DelegateInfo(false, Set.of());

            String key = resolveMethodKeyInFile(file, resolved);
            if (key == null || key.isBlank()) return new DelegateInfo(false, Set.of());

            return new DelegateInfo(true, Set.of(key));
        } catch (Throwable t) {
            return new DelegateInfo(false, Set.of());
        }
    }

    /** Collect resolved callee keys (in-file) used in this method. */
    private static Set<String> collectResolvedCalleeKeys(PsiMethod m, PsiJavaFile file, UsefulnessConfig cfg) {
        try {
            if (m == null) return Set.of();
            PsiCodeBlock body = m.getBody();
            if (body == null) return Set.of();

            Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
            if (calls == null || calls.isEmpty()) return Set.of();

            Set<String> out = new HashSet<>();
            for (PsiMethodCallExpression call : calls) {
                PsiMethod resolved = null;
                try {
                    resolved = call.resolveMethod();
                } catch (Throwable ignored) {}

                if (resolved == null) continue;
                String key = resolveMethodKeyInFile(file, resolved);
                if (key != null && !key.isBlank()) out.add(key);
            }
            return out;
        } catch (Throwable t) {
            return Set.of();
        }
    }

    private static String resolveMethodKeyInFile(PsiJavaFile file, PsiMethod resolved) {
        try {
            if (file == null || resolved == null) return null;
            PsiClass owner = resolved.getContainingClass();
            if (owner == null) return null;
            return methodKey(owner, resolved);
        } catch (Throwable t) {
            return null;
        }
    }

    /* =============================
     * Clone pair finding
     * ============================= */

    private static final class Sig {
        final Set<String> tokenSet;
        final int tokenCount;

        Sig(Set<String> tokenSet, int tokenCount) {
            this.tokenSet = tokenSet == null ? Set.of() : tokenSet;
            this.tokenCount = tokenCount;
        }
    }

    private static final class PairScore {
        final String aKey;
        final String bKey;
        final double sim;

        PairScore(String aKey, String bKey, double sim) {
            this.aKey = aKey;
            this.bKey = bKey;
            this.sim = sim;
        }
    }

    private static List<PairScore> findClonePairs(Map<String, Sig> beforeSig, UsefulnessConfig cfg) {
        List<String> keys = beforeSig.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().tokenCount >= cfg.minTokenCount)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        List<PairScore> pairs = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                Sig a = beforeSig.get(keys.get(i));
                Sig b = beforeSig.get(keys.get(j));
                double sim = jaccard(a, b);
                if (sim >= cfg.cloneSimilarityBefore) {
                    pairs.add(new PairScore(keys.get(i), keys.get(j), sim));
                }
            }
        }

        // Sort by similarity descending (analyze the strongest clones first)
        pairs.sort((p1, p2) -> Double.compare(p2.sim, p1.sim));

        // Cap to avoid huge runtime
        if (pairs.size() > cfg.maxPairs) {
            return pairs.subList(0, cfg.maxPairs);
        }
        return pairs;
    }

    private static double jaccard(Sig a, Sig b) {
        if (a == null || b == null) return 0.0;
        if (a.tokenSet.isEmpty() || b.tokenSet.isEmpty()) return 0.0;

        Set<String> small = a.tokenSet.size() <= b.tokenSet.size() ? a.tokenSet : b.tokenSet;
        Set<String> large = small == a.tokenSet ? b.tokenSet : a.tokenSet;

        int inter = 0;
        for (String s : small) {
            if (large.contains(s)) inter++;
        }
        int union = a.tokenSet.size() + b.tokenSet.size() - inter;
        if (union <= 0) return 0.0;
        return inter * 1.0 / union;
    }

    /* =============================
     * PSI parsing and method collection
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

        for (PsiClass c : classes) {
            collectFromClassRecursive(c, map);
        }
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
     * Normalized signature builder
     * ============================= */

    private static Sig buildSig(PsiMethod m) {
        try {
            if (m == null) return new Sig(Set.of(), 0);
            PsiCodeBlock body = m.getBody();
            if (body == null) return new Sig(Set.of(), 0);

            List<String> tokens = new ArrayList<>(256);
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitKeyword(PsiKeyword keyword) {
                    tokens.add("KW:" + keyword.getText());
                    super.visitKeyword(keyword);
                }

                @Override
                public void visitLiteralExpression(PsiLiteralExpression expression) {
                    tokens.add("LIT");
                    super.visitLiteralExpression(expression);
                }

                @Override
                public void visitReferenceExpression(PsiReferenceExpression expression) {
                    // Replace identifiers with ID, but keep method call names when we can infer them later.
                    tokens.add("ID");
                    super.visitReferenceExpression(expression);
                }

                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    String name = expression.getMethodExpression() == null ? null : expression.getMethodExpression().getReferenceName();
                    if (name != null && !name.isBlank()) tokens.add("CALL:" + name);
                    super.visitMethodCallExpression(expression);
                }

                @Override
                public void visitBinaryExpression(PsiBinaryExpression expression) {
                    IElementType op = expression.getOperationTokenType();
                    if (op != null) tokens.add("OP:" + op.toString());
                    super.visitBinaryExpression(expression);
                }

                @Override
                public void visitPrefixExpression(PsiPrefixExpression expression) {
                    IElementType op = expression.getOperationTokenType();
                    if (op != null) tokens.add("OP:" + op.toString());
                    super.visitPrefixExpression(expression);
                }

                @Override
                public void visitPostfixExpression(PsiPostfixExpression expression) {
                    IElementType op = expression.getOperationTokenType();
                    if (op != null) tokens.add("OP:" + op.toString());
                    super.visitPostfixExpression(expression);
                }

                @Override
                public void visitAssignmentExpression(PsiAssignmentExpression expression) {
                    PsiJavaToken tok = expression.getOperationSign();
                    IElementType op = (tok == null) ? null : tok.getTokenType();
                    if (op != null) tokens.add("OP:" + op.toString());
                    super.visitAssignmentExpression(expression);
                }

                @Override
                public void visitIfStatement(PsiIfStatement statement) {
                    tokens.add("ST:if");
                    super.visitIfStatement(statement);
                }

                @Override
                public void visitForStatement(PsiForStatement statement) {
                    tokens.add("ST:for");
                    super.visitForStatement(statement);
                }

                @Override
                public void visitForeachStatement(PsiForeachStatement statement) {
                    tokens.add("ST:foreach");
                    super.visitForeachStatement(statement);
                }

                @Override
                public void visitWhileStatement(PsiWhileStatement statement) {
                    tokens.add("ST:while");
                    super.visitWhileStatement(statement);
                }

                @Override
                public void visitDoWhileStatement(PsiDoWhileStatement statement) {
                    tokens.add("ST:dowhile");
                    super.visitDoWhileStatement(statement);
                }

                @Override
                public void visitSwitchStatement(PsiSwitchStatement statement) {
                    tokens.add("ST:switch");
                    super.visitSwitchStatement(statement);
                }

                @Override
                public void visitTryStatement(PsiTryStatement statement) {
                    tokens.add("ST:try");
                    super.visitTryStatement(statement);
                }

                @Override
                public void visitCatchSection(PsiCatchSection section) {
                    tokens.add("ST:catch");
                    super.visitCatchSection(section);
                }

                @Override
                public void visitReturnStatement(PsiReturnStatement statement) {
                    tokens.add("ST:return");
                    super.visitReturnStatement(statement);
                }

                @Override
                public void visitThrowStatement(PsiThrowStatement statement) {
                    tokens.add("ST:throw");
                    super.visitThrowStatement(statement);
                }
            });

            // Build set signature (Jaccard-friendly)
            Set<String> tokenSet = new HashSet<>(tokens);
            return new Sig(tokenSet, tokens.size());
        } catch (Throwable t) {
            return new Sig(Set.of(), 0);
        }
    }

    /* =============================
     * Utilities
     * ============================= */

    private static UsefulnessResult fallback(String msg) {
        return new UsefulnessResult(true, 55, List.of(Reason.ANALYZER_FALLBACK),
                "Analyzer fallback: " + (msg == null ? "" : msg));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String summarizeStrategies(EnumMap<Strategy, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Strategy, Integer> e : counts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }
}