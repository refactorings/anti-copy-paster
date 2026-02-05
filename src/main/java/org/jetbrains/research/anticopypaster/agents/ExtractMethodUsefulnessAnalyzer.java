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
        CLONES_REDUCED_OR_REMOVED,
        INCOMPLETE_REFACTORING_DETECTED,
        ANALYZER_FALLBACK
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
                return new UsefulnessResult(true, 60, List.of(Reason.NO_CLONES_DETECTED),
                        "No whole-method clone pairs detected in BEFORE; usefulness gate skipped.");
            }

            // Evaluate each pair after refactoring
            int analyzed = 0;
            int removedOrReduced = 0;
            int incomplete = 0;
            EnumMap<Strategy, Integer> strategyCounts = new EnumMap<>(Strategy.class);

            for (PairScore ps : clonePairs) {
                if (analyzed >= cfg.maxPairs) break;
                analyzed++;

                PairOutcome out = evaluatePair(ps, beforeMethods, afterMethods, beforeSig, afterSig, addedKeys, afterPsi, cfg);
                strategyCounts.put(out.strategy, strategyCounts.getOrDefault(out.strategy, 0) + 1);

                if (out.strategy == Strategy.INCOMPLETE_REFACTORING) {
                    incomplete++;
                } else if (out.reducedOrRemoved) {
                    removedOrReduced++;
                }
            }

            List<Reason> reasons = new ArrayList<>();
            boolean isUseful;
            int score = 100;

            if (incomplete > 0) {
                reasons.add(Reason.INCOMPLETE_REFACTORING_DETECTED);
                isUseful = false;
                score -= 50;
                score -= Math.min(30, incomplete * 10);
            } else if (removedOrReduced > 0) {
                reasons.add(Reason.CLONES_REDUCED_OR_REMOVED);
                isUseful = true;
                score -= Math.max(0, 30 - removedOrReduced * 5);
                score += Math.min(10, removedOrReduced); // small bonus
            } else {
                // No incomplete, but also no clear reduction: don't block hard; mark low confidence.
                reasons.add(Reason.ANALYZER_FALLBACK);
                isUseful = true;
                score = 55;
            }

            score = clamp(score, 0, 100);

            String notes = "analyzedPairs=" + analyzed +
                    ", reducedOrRemoved=" + removedOrReduced +
                    ", incomplete=" + incomplete +
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
                // Helper introduced somewhere + remaining method delegates
                return new PairOutcome(Strategy.POST_EXTRACTION_DELETION, true);
            }
            return new PairOutcome(Strategy.DIRECT_REMOVAL, true);
        }

        // If both deleted, clones removed.
        if (aDeleted && bDeleted) {
            return new PairOutcome(Strategy.DIRECT_REMOVAL, true);
        }

        // Both still exist: compare AFTER similarity
        Sig aSigAfter = afterSig.get(aKey);
        Sig bSigAfter = afterSig.get(bKey);
        double simAfter = jaccard(aSigAfter, bSigAfter);

        // Delegation / unification checks
        DelegateInfo aDel = detectDelegate(aAfter, afterPsi, cfg);
        DelegateInfo bDel = detectDelegate(bAfter, afterPsi, cfg);

        // Case: both are thin delegates to the same callee method(s)
        if (aDel.isDelegate && bDel.isDelegate) {
            Set<String> shared = new HashSet<>(aDel.calleeKeys);
            shared.retainAll(bDel.calleeKeys);

            if (!shared.isEmpty()) {
                // If shared callee is new => extract-method style. If existing => delegation-to-existing.
                boolean sharedHasNew = shared.stream().anyMatch(addedKeys::contains);
                if (shared.size() >= 2) {
                    return new PairOutcome(Strategy.FRAGMENTATION, true);
                }
                return new PairOutcome(sharedHasNew ? Strategy.EXTRACT_METHOD : Strategy.DELEGATION_TO_EXISTING, true);
            }
        }

        // Fragmentation: even if not pure delegates, both methods may now call multiple shared helpers and similarity drops.
        Set<String> aCalls = collectResolvedCalleeKeys(aAfter, afterPsi, cfg);
        Set<String> bCalls = collectResolvedCalleeKeys(bAfter, afterPsi, cfg);
        Set<String> sharedCalls = new HashSet<>(aCalls);
        sharedCalls.retainAll(bCalls);

        if (sharedCalls.size() >= 2 && simAfter <= cfg.cloneSimilarityAfterReduced) {
            return new PairOutcome(Strategy.FRAGMENTATION, true);
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
            return new PairOutcome(Strategy.UNKNOWN, true);
        }

        // Middle region: reduced a bit, but not clear
        return new PairOutcome(Strategy.UNKNOWN, false);
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