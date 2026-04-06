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
 *  - NON_TARGET_CLONE_REFACTORING: some other clone pair was refactored while another original clone pair remained unchanged
 *  - EXTRACTION_WITHOUT_CLONE_REPLACEMENT: a helper was added, but the original clone bodies were not replaced with calls
 *
 * Important: This is a conservative, best-effort filter. It should never break the workflow.
 */
public final class usefulnessChecker {

    private usefulnessChecker() {}

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
        POST_EXTRACTION_CLONE_DELETION_DETECTED,
        DIRECT_CLONE_REMOVAL_DETECTED,
        CALL_BASED_CLONE_SUBSTITUTION_DETECTED,
        CLONE_REMOVAL_BY_DELEGATION_DETECTED,
        FRAGMENTATION_OF_LOGIC_DETECTED,
        NON_TARGET_CLONE_REFACTORING_DETECTED,
        EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED,

        EXTRACT_METHOD_NOT_FOUND,
        ANALYZER_FALLBACK
    }

    public enum Strategy {
        // "Good" outcome (the only one we accept as useful)
        EXTRACT_METHOD,

        // Failure modes / non-Extract-Method outcomes (JSS taxonomy)
        INCOMPLETE_REFACTORING,
        POST_EXTRACTION_CLONE_DELETION,
        DIRECT_CLONE_REMOVAL,
        CALL_BASED_CLONE_SUBSTITUTION,
        CLONE_REMOVAL_BY_DELEGATION,
        FRAGMENTATION_OF_LOGIC,
        NON_TARGET_CLONE_REFACTORING,
        EXTRACTION_WITHOUT_CLONE_REPLACEMENT,

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

    public static final class TargetMethodHint {
        public final String className;
        public final String methodName;
        public final int parameterCount;

        public TargetMethodHint(String className, String methodName, int parameterCount) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.parameterCount = parameterCount;
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
        return analyze(project, fileName, beforeSource, afterSource, cfg, null);
    }

    public static UsefulnessResult analyze(Project project,
                                          String fileName,
                                          String beforeSource,
                                          String afterSource,
                                          UsefulnessConfig cfg,
                                          List<TargetMethodHint> targetHints) {
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

            LinkedHashSet<String> targetKeys = resolveTargetMethodKeys(beforeMethods, targetHints);
            List<PairScore> targetPairs = computePairsRestricted(beforeSig, new ArrayList<>(targetKeys), 0.0, cfg.maxPairs);

            // Focus mode (preferred): identify the refactoring target methods by looking for
            // >=2 existing (BEFORE) methods that became thin delegates to the same newly added helper in AFTER.
            // This avoids noisy whole-file clone mining dominated by trivial getters/setters.
            Map<String, List<String>> helperToDelegates = new HashMap<>();
            for (Map.Entry<String, PsiMethod> e : afterMethods.entrySet()) {
                String methodKey = e.getKey();
                PsiMethod mAfter = e.getValue();
                if (!beforeMethods.containsKey(methodKey)) continue; // must exist in BEFORE
                DelegateInfo del = detectDelegate(mAfter, afterPsi, cfg);
                if (!del.isDelegate || del.calleeKeys.isEmpty()) continue;
                for (String callee : del.calleeKeys) {
                    if (!addedKeys.contains(callee)) continue; // only new helpers
                    helperToDelegates.computeIfAbsent(callee, k -> new ArrayList<>()).add(methodKey);
                }
            }

            // Pick the strongest helper target group (most delegates).
            List<String> focusKeys = List.of();
            String focusHelper = null;
            for (Map.Entry<String, List<String>> e : helperToDelegates.entrySet()) {
                List<String> ds = e.getValue();
                if (ds == null || ds.size() < 2) continue;
                if (focusKeys.isEmpty() || ds.size() > focusKeys.size()) {
                    focusKeys = ds;
                    focusHelper = e.getKey();
                }
            }

            // Debug lines to surface in the viewer via notes (avoid relying on stdout/idea.log).
            List<String> debugLines = new ArrayList<>();

            // Focused clonePairs: only consider pairs among the methods that delegate to the same newly added helper.
            // This approximates “analyze only the detection pair”.
            List<PairScore> clonePairs;
            if (!targetKeys.isEmpty()) {
                clonePairs = targetPairs;
                debugLines.add("targetMethods=" + targetKeys.size() + ", targetPairs=" + targetPairs.size() + ", analyzedClonePairs=" + clonePairs.size() + ", mode=target-only");
            } else if (focusKeys != null && focusKeys.size() >= 2) {
                List<PairScore> allClonePairs = findClonePairs(beforeSig, cfg);
                List<PairScore> focusedPairs = computePairsRestricted(beforeSig, focusKeys, 0.0, cfg.maxPairs);
                clonePairs = mergePairLists(focusedPairs, allClonePairs, cfg.maxPairs);
                debugLines.add("focusHelper=" + (focusHelper == null ? "" : focusHelper) + ", focusMethods=" + focusKeys.size());
                debugLines.add("focusedClonePairs=" + focusedPairs.size() + ", allClonePairs=" + allClonePairs.size() + ", analyzedClonePairs=" + clonePairs.size());
            } else {
                List<PairScore> allClonePairs = findClonePairs(beforeSig, cfg);
                clonePairs = allClonePairs;
                debugLines.add("clonePairsFound=" + clonePairs.size());
            }

            if (clonePairs.isEmpty()) {
                ConfirmResult confirm = confirmExtractMethodShape(beforeMethods, afterMethods, addedKeys, afterPsi, cfg);

                if (confirm.confirmed) {
                    return new UsefulnessResult(true, 100, List.of(Reason.EXTRACT_METHOD_CONFIRMED), confirm.notes);
                }

                if (confirm.notes != null && confirm.notes.contains("likely incomplete extract")) {
                    return new UsefulnessResult(
                            false,
                            40,
                            List.of(Reason.INCOMPLETE_REFACTORING_DETECTED),
                            confirm.notes
                    );
                }

                if (looksLikeNoExtractMethodFound(beforeSource, afterSource, addedKeys)) {
                    return new UsefulnessResult(
                            false,
                            45,
                            List.of(Reason.EXTRACT_METHOD_NOT_FOUND),
                            confirm.notes
                    );
                }

                return new UsefulnessResult(
                        false,
                        45,
                        List.of(Reason.EXTRACT_METHOD_NOT_FOUND),
                        confirm.notes
                );
            }

            // STRICT usefulness: ALL pairs must be EXTRACT_METHOD, else NOT useful.
            int analyzed = 0;
            int extractOk = 0;
            int untouchedClonePairs = 0;
            int targetExtractOk = 0;
            int nonTargetExtractOk = 0;
            int targetUntouchedClonePairs = 0;
            EnumMap<Strategy, Integer> strategyCounts = new EnumMap<>(Strategy.class);

            // Track which non-Extract-Method outcomes happened at least once
            EnumSet<Reason> failReasons = EnumSet.noneOf(Reason.class);

            for (PairScore ps : clonePairs) {
                if (analyzed >= cfg.maxPairs) break;
                analyzed++;
                boolean targetPair = isTargetPair(ps, targetKeys);

                PairOutcome out = evaluatePair(ps, beforeMethods, afterMethods, beforeSig, afterSig, addedKeys, afterPsi, cfg);

                double simAfterDbg = 0.0;
                try {
                    Sig aDbg = afterSig.get(ps.aKey);
                    Sig bDbg = afterSig.get(ps.bKey);
                    simAfterDbg = jaccard(aDbg, bDbg);
                } catch (Throwable ignored) {}

                // Add compact debug line to debugLines (cap to 10 pairs)
                if (debugLines.size() < 11) { // cap to keep notes readable (1 header + up to 10 pairs)
                    debugLines.add(ps.aKey + " <-> " + ps.bKey +
                            " | before=" + String.format(java.util.Locale.ROOT, "%.3f", ps.sim) +
                            ", after=" + String.format(java.util.Locale.ROOT, "%.3f", simAfterDbg) +
                            ", outcome=" + out.strategy);
                }

                strategyCounts.put(out.strategy, strategyCounts.getOrDefault(out.strategy, 0) + 1);

                if (out.strategy == Strategy.EXTRACT_METHOD) {
                    extractOk++;
                    if (targetPair) targetExtractOk++;
                    else nonTargetExtractOk++;
                    continue;
                }

                if (out.untouchedClone) {
                    untouchedClonePairs++;
                    if (targetPair) targetUntouchedClonePairs++;
                }

                // Any non-EXTRACT_METHOD outcome makes the proposal NOT useful.
                switch (out.strategy) {
                    case INCOMPLETE_REFACTORING -> failReasons.add(Reason.INCOMPLETE_REFACTORING_DETECTED);
                    case POST_EXTRACTION_CLONE_DELETION -> failReasons.add(Reason.POST_EXTRACTION_CLONE_DELETION_DETECTED);
                    case DIRECT_CLONE_REMOVAL -> failReasons.add(Reason.DIRECT_CLONE_REMOVAL_DETECTED);
                    case CALL_BASED_CLONE_SUBSTITUTION -> failReasons.add(Reason.CALL_BASED_CLONE_SUBSTITUTION_DETECTED);
                    case CLONE_REMOVAL_BY_DELEGATION -> failReasons.add(Reason.CLONE_REMOVAL_BY_DELEGATION_DETECTED);
                    case FRAGMENTATION_OF_LOGIC -> failReasons.add(Reason.FRAGMENTATION_OF_LOGIC_DETECTED);
                    case NON_TARGET_CLONE_REFACTORING -> failReasons.add(Reason.NON_TARGET_CLONE_REFACTORING_DETECTED);
                    case EXTRACTION_WITHOUT_CLONE_REPLACEMENT -> failReasons.add(Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED);
                    case UNKNOWN -> {
                        // Neutral/indeterminate for this pair; do not penalize the whole proposal.
                    }
                    default -> failReasons.add(Reason.ANALYZER_FALLBACK);
                }
            }

            if (!targetKeys.isEmpty()) {
                if (nonTargetExtractOk >= 1 && targetUntouchedClonePairs > 0
                        && !failReasons.contains(Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED)) {
                    failReasons.add(Reason.NON_TARGET_CLONE_REFACTORING_DETECTED);
                    strategyCounts.put(
                            Strategy.NON_TARGET_CLONE_REFACTORING,
                            strategyCounts.getOrDefault(Strategy.NON_TARGET_CLONE_REFACTORING, 0) + targetUntouchedClonePairs
                    );
                }
            } else if (extractOk >= 1 && untouchedClonePairs > 0
                    && !failReasons.contains(Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED)) {
                failReasons.add(Reason.NON_TARGET_CLONE_REFACTORING_DETECTED);
                strategyCounts.put(
                        Strategy.NON_TARGET_CLONE_REFACTORING,
                        strategyCounts.getOrDefault(Strategy.NON_TARGET_CLONE_REFACTORING, 0) + untouchedClonePairs
                );
            }

            List<Reason> reasons = new ArrayList<>();
            int score;

            // Relaxed usefulness rule:
            // Useful if there exists at least one confirmed EXTRACT_METHOD pair
            // and no severe failure signals were detected.
            boolean isUseful = (extractOk >= 1) && failReasons.isEmpty();

            if (isUseful) {
                reasons.add(Reason.EXTRACT_METHOD_CONFIRMED);
                score = 100;
            } else {
                if (failReasons.isEmpty()) {
                    reasons.add(Reason.EXTRACT_METHOD_NOT_FOUND);
                } else {
                    reasons.addAll(new ArrayList<>(failReasons));
                }
                score = 40; // simple fallback score (not used for decision logic)
            }

            String notes = "analyzedPairs=" + analyzed +
                    ", extractMethodPairs=" + extractOk +
                    ", targetExtractMethodPairs=" + targetExtractOk +
                    ", nonTargetExtractMethodPairs=" + nonTargetExtractOk +
                    ", untouchedClonePairs=" + untouchedClonePairs +
                    ", targetUntouchedClonePairs=" + targetUntouchedClonePairs +
                    ", targetMethods=" + targetKeys.size() +
                    ", addedMethods=" + addedKeys.size() +
                    ", strategies=" + summarizeStrategies(strategyCounts) +
                    ", debug=" + debugLines;

            return new UsefulnessResult(isUseful, score, reasons, notes);

        } catch (Throwable t) {
            return null; // best-effort: never break workflow
        }
    }

    /* =============================
     * Pair evaluation
     * ============================= */

    private static final class ConfirmResult {
        final boolean confirmed;
        final String notes;

        ConfirmResult(boolean confirmed, String notes) {
            this.confirmed = confirmed;
            this.notes = notes == null ? "" : notes;
        }
    }

    /**
     * Confirm Extract Method shape without relying on BEFORE clone re-detection.
     * We only look for a structural pattern in AFTER:
     * (1) a newly added helper method exists, and
     * (2) at least two methods that already existed in BEFORE become thin delegates, and
     * (3) they all delegate to the same newly added helper.
     */
    private static ConfirmResult confirmExtractMethodShape(Map<String, PsiMethod> beforeMethods,
                                                          Map<String, PsiMethod> afterMethods,
                                                          Set<String> addedKeys,
                                                          PsiJavaFile afterPsi,
                                                          UsefulnessConfig cfg) {
        try {
            if (afterPsi == null) return new ConfirmResult(false, "PSI parse failed");
            if (addedKeys == null || addedKeys.isEmpty()) {
                return new ConfirmResult(false, "No newly added helper method found in AFTER");
            }

            // Map: helperKey -> list of existing(before) methods that delegate to it in AFTER
            Map<String, List<String>> helperToDelegates = new HashMap<>();

            for (Map.Entry<String, PsiMethod> e : afterMethods.entrySet()) {
                String methodKey = e.getKey();
                PsiMethod mAfter = e.getValue();

                // Only consider methods that existed before (avoid counting freshly added wrappers)
                if (beforeMethods == null || !beforeMethods.containsKey(methodKey)) continue;

                DelegateInfo del = detectDelegate(mAfter, afterPsi, cfg);
                if (!del.isDelegate || del.calleeKeys.isEmpty()) continue;

                // Only count delegation to newly added helpers
                for (String callee : del.calleeKeys) {
                    if (!addedKeys.contains(callee)) continue;
                    helperToDelegates.computeIfAbsent(callee, k -> new ArrayList<>()).add(methodKey);
                }
            }

            // Confirm: any helper has >=2 existing methods delegating to it
            for (Map.Entry<String, List<String>> e : helperToDelegates.entrySet()) {
                String helperKey = e.getKey();
                List<String> delegates = e.getValue();
                if (delegates != null && delegates.size() >= 2) {
                    String notes = "Confirmed Extract Method shape via delegates-to-new-helper: helper=" + helperKey +
                            ", delegates=" + delegates.size() +
                            ", addedMethods=" + addedKeys.size();
                    return new ConfirmResult(true, notes);
                }
            }

            // Also accept the common case where existing methods keep their own method-specific
            // logic but both call the same newly added helper.
            Map<String, Integer> helperCallCounts = new HashMap<>();

            for (Map.Entry<String, PsiMethod> e : afterMethods.entrySet()) {
                String methodKey = e.getKey();
                PsiMethod mAfter = e.getValue();

                if (beforeMethods == null || !beforeMethods.containsKey(methodKey)) continue;

                Set<String> calls = collectResolvedCalleeKeys(mAfter, afterPsi, cfg);
                for (String callee : calls) {
                    if (!addedKeys.contains(callee)) continue;
                    helperCallCounts.put(callee, helperCallCounts.getOrDefault(callee, 0) + 1);
                }
            }

            for (Map.Entry<String, Integer> e : helperCallCounts.entrySet()) {
                if (e.getValue() >= 2) {
                    String notes = "Confirmed Extract Method shape via shared calls to new helper: helper=" + e.getKey() +
                            ", callers=" + e.getValue() +
                            ", addedMethods=" + addedKeys.size();
                    return new ConfirmResult(true, notes);
                }
            }

            return new ConfirmResult(false,
                    "Cannot confirm Extract Method shape: no >=2 existing methods delegate to the same newly added helper");

        } catch (Throwable t) {
            return new ConfirmResult(false, "Analyzer error while confirming Extract Method shape");
        }
    }

    private static final class PairOutcome {
        final Strategy strategy;
        final boolean reducedOrRemoved;
        final boolean untouchedClone;

        PairOutcome(Strategy strategy, boolean reducedOrRemoved) {
            this(strategy, reducedOrRemoved, false);
        }

        PairOutcome(Strategy strategy, boolean reducedOrRemoved, boolean untouchedClone) {
            this.strategy = strategy;
            this.reducedOrRemoved = reducedOrRemoved;
            this.untouchedClone = untouchedClone;
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

                    return new PairOutcome(Strategy.EXTRACT_METHOD, true);
                }

                // Delegation to an existing method (call-based clone substitution)
                return new PairOutcome(Strategy.CALL_BASED_CLONE_SUBSTITUTION, true);
            }
        }

        // (2) Asymmetric delegation cases
        // One side is a thin delegate, the other is not.
        // This can still be a valid Extract Method outcome for Type-3 clones:
        // the non-delegate side may keep its own non-cloned statements while both
        // methods call the same newly added helper that contains the shared logic.
        if (aDel.isDelegate ^ bDel.isDelegate) {
            DelegateInfo del = aDel.isDelegate ? aDel : bDel;
            String delegatedToOther = aDel.isDelegate ? bKey : aKey;
            PsiMethod nonDelegateMethod = aDel.isDelegate ? bAfter : aAfter;

            if (!del.calleeKeys.isEmpty() && del.calleeKeys.contains(delegatedToOther)) {
                return new PairOutcome(Strategy.CALL_BASED_CLONE_SUBSTITUTION, true);
            }

            Set<String> nonDelegateCalls = collectResolvedCalleeKeys(nonDelegateMethod, afterPsi, cfg);
            Set<String> sharedWithNonDelegate = new HashSet<>(del.calleeKeys);
            sharedWithNonDelegate.retainAll(nonDelegateCalls);

            if (!sharedWithNonDelegate.isEmpty() && intersects(sharedWithNonDelegate, addedKeys)) {
                return new PairOutcome(Strategy.EXTRACT_METHOD, true);
            }

            // Delegate exists but there is no evidence that both sides now share the same
            // newly extracted helper.
            return new PairOutcome(Strategy.CLONE_REMOVAL_BY_DELEGATION, true);
        }

        // Fragmentation: even if not pure delegates, both methods may now call multiple shared helpers and similarity drops.
        Set<String> aCalls = collectResolvedCalleeKeys(aAfter, afterPsi, cfg);
        Set<String> bCalls = collectResolvedCalleeKeys(bAfter, afterPsi, cfg);
        Set<String> sharedCalls = new HashSet<>(aCalls);
        sharedCalls.retainAll(bCalls);

        Set<String> sharedNewHelpers = new HashSet<>(sharedCalls);
        sharedNewHelpers.retainAll(addedKeys);
        if (!sharedNewHelpers.isEmpty()) {
            if (sharedNewHelpers.size() >= 2 && simAfter <= cfg.cloneSimilarityAfterReduced) {
                return new PairOutcome(Strategy.FRAGMENTATION_OF_LOGIC, true);
            }

            boolean asymmetricDelegateToSharedHelper = false;
            if (aDel.isDelegate ^ bDel.isDelegate) {
                DelegateInfo del = aDel.isDelegate ? aDel : bDel;
                asymmetricDelegateToSharedHelper = intersects(del.calleeKeys, sharedNewHelpers);
            }

            boolean bothDelegateToSharedHelper = aDel.isDelegate && bDel.isDelegate
                    && intersects(aDel.calleeKeys, sharedNewHelpers)
                    && intersects(bDel.calleeKeys, sharedNewHelpers);

            if (simAfter >= cfg.cloneSimilarityAfterStill) {
                if (bothDelegateToSharedHelper || asymmetricDelegateToSharedHelper) {
                    return new PairOutcome(Strategy.EXTRACT_METHOD, true);
                }
                return new PairOutcome(Strategy.INCOMPLETE_REFACTORING, false);
            }

            return new PairOutcome(Strategy.EXTRACT_METHOD, true);
        }

        if (sharedCalls.size() >= 2 && simAfter <= cfg.cloneSimilarityAfterReduced) {
            return new PairOutcome(Strategy.FRAGMENTATION_OF_LOGIC, true);
        }

        // If similarity remains high after, decide whether this is incomplete refactoring.
        if (simAfter >= cfg.cloneSimilarityAfterStill) {
            // Pair-local evidence of a refactoring attempt:
            // - either method became a thin delegate, OR
            // - either side calls a newly added helper method.
            boolean callsNewHelper = intersects(aCalls, addedKeys) || intersects(bCalls, addedKeys) ||
                    (aDel.isDelegate && intersects(aDel.calleeKeys, addedKeys)) ||
                    (bDel.isDelegate && intersects(bDel.calleeKeys, addedKeys));

            boolean untouchedClone = looksLikeUntouchedClonePair(
                    aKey, bKey, beforeSig, afterSig, addedKeys, simAfter, aDel, bDel, aCalls, bCalls, cfg
            );
            if (looksLikeExtractionWithoutCloneReplacement(
                    aKey, bKey, beforeSig, afterSig, addedKeys, simAfter, aDel, bDel, aCalls, bCalls, cfg
            )) {
                return new PairOutcome(Strategy.EXTRACTION_WITHOUT_CLONE_REPLACEMENT, false, true);
            }

            boolean refactorAttempt = aDel.isDelegate || bDel.isDelegate || callsNewHelper;

            if (refactorAttempt) {
                return new PairOutcome(Strategy.INCOMPLETE_REFACTORING, false);
            }
            // Otherwise: still clones, but no evidence of attempt in this proposal; treat as unknown (do not hard-fail globally).
            return new PairOutcome(Strategy.UNKNOWN, false, untouchedClone);
        }

        // Similarity dropped enough => reduced.
        if (simAfter <= cfg.cloneSimilarityAfterReduced) {
            if (!sharedNewHelpers.isEmpty()) {
                return new PairOutcome(Strategy.EXTRACT_METHOD, true);
            }
            return new PairOutcome(Strategy.UNKNOWN, false);
        }

        // Middle region: reduced a bit, but not clear
        return new PairOutcome(Strategy.UNKNOWN, false);
    }

    private static boolean looksLikeUntouchedClonePair(String aKey,
                                                       String bKey,
                                                       Map<String, Sig> beforeSig,
                                                       Map<String, Sig> afterSig,
                                                       Set<String> addedKeys,
                                                       double simAfter,
                                                       DelegateInfo aDel,
                                                       DelegateInfo bDel,
                                                       Set<String> aCalls,
                                                       Set<String> bCalls,
                                                       UsefulnessConfig cfg) {
        if (simAfter < cfg.cloneSimilarityAfterStill) return false;
        if (aDel != null && aDel.isDelegate) return false;
        if (bDel != null && bDel.isDelegate) return false;
        if (intersects(aCalls, addedKeys) || intersects(bCalls, addedKeys)) return false;

        double aSelf = jaccard(beforeSig.get(aKey), afterSig.get(aKey));
        double bSelf = jaccard(beforeSig.get(bKey), afterSig.get(bKey));
        return aSelf >= 0.95 && bSelf >= 0.95;
    }

    private static boolean looksLikeExtractionWithoutCloneReplacement(String aKey,
                                                                      String bKey,
                                                                      Map<String, Sig> beforeSig,
                                                                      Map<String, Sig> afterSig,
                                                                      Set<String> addedKeys,
                                                                      double simAfter,
                                                                      DelegateInfo aDel,
                                                                      DelegateInfo bDel,
                                                                      Set<String> aCalls,
                                                                      Set<String> bCalls,
                                                                      UsefulnessConfig cfg) {
        if (addedKeys == null || addedKeys.isEmpty()) return false;
        if (!looksLikeUntouchedClonePair(aKey, bKey, beforeSig, afterSig, addedKeys, simAfter, aDel, bDel, aCalls, bCalls, cfg)) {
            return false;
        }

        double helperThreshold = Math.max(cfg.cloneSimilarityAfterReduced, 0.55);
        for (String helperKey : addedKeys) {
            Sig helperSig = afterSig.get(helperKey);
            if (helperSig == null || helperSig.tokenCount < 3) continue;

            double simA = jaccard(beforeSig.get(aKey), helperSig);
            double simB = jaccard(beforeSig.get(bKey), helperSig);
            if ((simA >= helperThreshold && simB >= helperThreshold)
                    || Math.max(simA, simB) >= cfg.cloneSimilarityBefore) {
                return true;
            }
        }
        return false;
    }

    private static LinkedHashSet<String> resolveTargetMethodKeys(Map<String, PsiMethod> beforeMethods,
                                                                 List<TargetMethodHint> targetHints) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (beforeMethods == null || beforeMethods.isEmpty() || targetHints == null || targetHints.isEmpty()) {
            return out;
        }

        for (TargetMethodHint hint : targetHints) {
            if (hint == null || hint.methodName == null || hint.methodName.isBlank()) continue;
            for (Map.Entry<String, PsiMethod> e : beforeMethods.entrySet()) {
                PsiMethod method = e.getValue();
                if (method == null) continue;
                if (!hint.methodName.equals(method.getName())) continue;
                int paramCount = method.getParameterList() == null ? 0 : method.getParameterList().getParametersCount();
                if (paramCount != hint.parameterCount) continue;

                PsiClass owner = method.getContainingClass();
                String className = owner == null ? "" :
                        (owner.getQualifiedName() != null ? owner.getQualifiedName() : owner.getName());
                className = className == null ? "" : className;
                if (!hint.className.isBlank() && !hint.className.equals(className)) continue;

                out.add(e.getKey());
            }
        }
        return out;
    }

    private static boolean isTargetPair(PairScore ps, Set<String> targetKeys) {
        if (ps == null || targetKeys == null || targetKeys.isEmpty()) return false;
        return targetKeys.contains(ps.aKey) && targetKeys.contains(ps.bKey);
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
     * Fallback: in in-memory PSI, resolveMethod() may return null.
     * Try to resolve a callee key by syntactic lookup (name + arity) within the same PsiJavaFile.
     */
    private static String fallbackResolveMethodKeyByNameAndArity(PsiJavaFile file,
                                                                String methodName,
                                                                int argCount) {
        try {
            if (file == null || methodName == null || methodName.isBlank()) return null;

            PsiClass[] classes = file.getClasses();
            if (classes == null || classes.length == 0) return null;

            Deque<PsiClass> stack = new ArrayDeque<>();
            Collections.addAll(stack, classes);

            while (!stack.isEmpty()) {
                PsiClass c = stack.pop();
                if (c == null) continue;

                PsiMethod[] ms = c.getMethods();
                if (ms != null) {
                    for (PsiMethod m : ms) {
                        if (m == null) continue;
                        if (!methodName.equals(m.getName())) continue;
                        int pc = 0;
                        try {
                            pc = m.getParameterList() == null ? 0 : m.getParameterList().getParametersCount();
                        } catch (Throwable ignored) {}
                        if (pc == argCount) {
                            String key = methodKey(c, m);
                            if (key != null && !key.isBlank()) return key;
                        }
                    }
                }

                PsiClass[] inners = c.getInnerClasses();
                if (inners != null) Collections.addAll(stack, inners);
            }

            return null;
        } catch (Throwable t) {
            return null;
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
            PsiMethod resolved = null;
            try {
                resolved = call.resolveMethod();
            } catch (Throwable ignored) {}

            int argCount = call.getArgumentList() == null ? 0 : call.getArgumentList().getExpressionCount();
            if (argCount > cfg.maxDelegateParams) return new DelegateInfo(false, Set.of());

            String key;
            if (resolved != null) {
                key = resolveMethodKeyInFile(file, resolved);
            } else {
                String name = call.getMethodExpression() == null ? null : call.getMethodExpression().getReferenceName();
                key = fallbackResolveMethodKeyByNameAndArity(file, name, argCount);
            }
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

                int argCount = call.getArgumentList() == null ? 0 : call.getArgumentList().getExpressionCount();

                String key;
                if (resolved != null) {
                    key = resolveMethodKeyInFile(file, resolved);
                } else {
                    String name = call.getMethodExpression() == null ? null : call.getMethodExpression().getReferenceName();
                    key = fallbackResolveMethodKeyByNameAndArity(file, name, argCount);
                }

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
            PsiFile containing = resolved.getContainingFile();
            if (containing != null && !containing.isEquivalentTo(file)) {
                // Only treat as in-file if it belongs to this PsiJavaFile.
                return null;
            }
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
        // Phase 1: normal whole-method clone mining (skip very small methods to avoid noise).
        List<PairScore> pairs = computePairs(beforeSig, cfg.minTokenCount, cfg.cloneSimilarityBefore, cfg.maxPairs);
        if (!pairs.isEmpty()) return pairs;

        // Phase 2: fallback for short wrapper methods.
        // In practice, many good Extract Method outcomes start from tiny wrappers (e.g., two methods that both call the
        // same internal helper). Those methods may have < minTokenCount tokens, so Phase 1 finds nothing.
        // Here we allow smaller methods, but require a stricter similarity to reduce false positives.
        double strictSim = Math.max(cfg.cloneSimilarityBefore, 0.95);
        return computePairs(beforeSig, 3, strictSim, cfg.maxPairs);
    }

    private static List<PairScore> computePairs(Map<String, Sig> beforeSig, int minTokens, double simThreshold, int maxPairs) {
        List<String> keys = beforeSig.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().tokenCount >= minTokens)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        List<PairScore> pairs = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                Sig a = beforeSig.get(keys.get(i));
                Sig b = beforeSig.get(keys.get(j));
                double sim = jaccard(a, b);
                if (sim >= simThreshold) {
                    pairs.add(new PairScore(keys.get(i), keys.get(j), sim));
                }
            }
        }

        // Sort by similarity descending (analyze the strongest clones first)
        pairs.sort((p1, p2) -> Double.compare(p2.sim, p1.sim));

        // Cap to avoid huge runtime
        int cap = Math.max(1, maxPairs);
        if (pairs.size() > cap) {
            return pairs.subList(0, cap);
        }
        return pairs;
    }

    private static List<PairScore> computePairsRestricted(Map<String, Sig> beforeSig,
                                                         List<String> keys,
                                                         double simThreshold,
                                                         int maxPairs) {
        if (beforeSig == null || keys == null) return List.of();
        List<String> present = keys.stream().filter(k -> beforeSig.containsKey(k)).collect(Collectors.toList());
        List<PairScore> pairs = new ArrayList<>();
        for (int i = 0; i < present.size(); i++) {
            for (int j = i + 1; j < present.size(); j++) {
                Sig a = beforeSig.get(present.get(i));
                Sig b = beforeSig.get(present.get(j));
                double sim = jaccard(a, b);
                if (sim >= simThreshold) {
                    pairs.add(new PairScore(present.get(i), present.get(j), sim));
                }
            }
        }
        pairs.sort((p1, p2) -> Double.compare(p2.sim, p1.sim));
        int cap = Math.max(1, maxPairs);
        if (pairs.size() > cap) return pairs.subList(0, cap);
        return pairs;
    }

    private static List<PairScore> mergePairLists(List<PairScore> preferred,
                                                 List<PairScore> fallback,
                                                 int maxPairs) {
        LinkedHashMap<String, PairScore> merged = new LinkedHashMap<>();
        addUniquePairs(merged, preferred);
        addUniquePairs(merged, fallback);

        List<PairScore> out = new ArrayList<>(merged.values());
        int cap = Math.max(1, maxPairs);
        if (out.size() > cap) return out.subList(0, cap);
        return out;
    }

    private static void addUniquePairs(Map<String, PairScore> out, List<PairScore> pairs) {
        if (out == null || pairs == null) return;
        for (PairScore pair : pairs) {
            if (pair == null) continue;
            out.putIfAbsent(pairKey(pair.aKey, pair.bKey), pair);
        }
    }

    private static String pairKey(String aKey, String bKey) {
        String a = aKey == null ? "" : aKey;
        String b = bKey == null ? "" : bKey;
        return (a.compareTo(b) <= 0) ? (a + "::" + b) : (b + "::" + a);
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
        // Strict mode: only confirmed EXTRACT_METHOD is useful.
        return new UsefulnessResult(false, 40, List.of(Reason.ANALYZER_FALLBACK),
                "Analyzer fallback: " + (msg == null ? "" : msg));
    }

    private static boolean looksLikeNoExtractMethodFound(String beforeSource,
                                                         String afterSource,
                                                         Set<String> addedKeys) {
        if (addedKeys != null && !addedKeys.isEmpty()) return false;
        return normalizeForNoOpCheck(beforeSource).equals(normalizeForNoOpCheck(afterSource));
    }

    private static String normalizeForNoOpCheck(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder normalized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) normalized.append(ch);
        }
        return normalized.toString();
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
    /**
     * Returns true if the two sets intersect.
     */
    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        // Iterate the smaller set for efficiency
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        for (String s : small) {
            if (large.contains(s)) return true;
        }
        return false;
    }
    /* =============================
     * Feedback prompt generation
     * ============================= */

    public static String buildFeedbackPrompt(List<Reason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "Your previous refactoring was rejected, but no specific reason was identified. Please perform a correct Extract Method refactoring.";
        }

        // Priority order
        if (reasons.contains(Reason.INCOMPLETE_REFACTORING_DETECTED)) {
            return """
Your previous refactoring was rejected because it performed an incomplete Extract Method refactoring.

Problem:
The duplicated logic between the clone methods still remains in the original methods after refactoring.

How to fix it:
- Extract the full shared duplicated logic into exactly one helper method.
- Remove duplicated statements from the original methods.
- Keep only method-specific differences in each method.
- Make all original target clone methods call the helper.

Important constraints:
- Do not delete either clone method.
- Do not make one clone call the other.
- Do not split logic across multiple helpers.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED)) {
            return """
Your previous refactoring was rejected because you extracted a helper method but did not replace the original clone with calls to it.

Problem:
The helper exists, but the original duplicated clone logic still remains in place.

How to fix it:
- Keep the extracted helper method.
- Replace the duplicated clone logic in the original methods with calls to that helper.
- Remove the duplicated statements that were supposed to be extracted.

Important constraints:
- Do not leave the original clone body unchanged.
- Do not keep both the full clone and the extracted helper.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.NON_TARGET_CLONE_REFACTORING_DETECTED)) {
            return """
Your previous refactoring was rejected because it appears to refactor a different clone pair while leaving the target clone unchanged.

Problem:
Some clone-related changes were made in the file, but the original target clone pair still remains essentially unchanged.

How to fix it:
- Focus on the original target clone pair.
- Extract the shared logic from that target pair into one helper.
- Replace the target clone occurrences with calls to the helper.
- Avoid refactoring unrelated clone pairs unless they are part of the same target duplication.

Important constraints:
- Do not leave the target clone unchanged.
- Do not switch to a different duplicated region.
- Only perform Extract Method on the intended target clone.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.POST_EXTRACTION_CLONE_DELETION_DETECTED)) {
            return """
Your previous refactoring was rejected because one or more target clone methods were deleted after extraction.

Problem:
A valid Extract Method refactoring must preserve all original target clone methods.

How to fix it:
- Restore all original target clone methods.
- Keep the helper method.
- Make all original target clone methods call the helper.

Important constraints:
- Do not delete clone methods.
- Do not merge them.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.DIRECT_CLONE_REMOVAL_DETECTED)) {
            return """
Your previous refactoring was rejected because it removed a clone directly instead of extracting shared logic.

Problem:
Duplication was removed by deleting code, not by Extract Method.

How to fix it:
- Restore removed clone logic.
- Extract shared logic into one helper.
- Make all original target clone methods call the helper.

Important constraints:
- Do not delete clones.
- Do not substitute one clone with another.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.CALL_BASED_CLONE_SUBSTITUTION_DETECTED)) {
            return """
Your previous refactoring was rejected because one clone method calls the other instead of using a shared helper.

Problem:
This is call-based substitution, not Extract Method.

How to fix it:
- Introduce one new helper method with shared logic.
- Make all original target clone methods call the helper instead of each other.

Important constraints:
- Do not make one clone call the other.
- Do not delete clones.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.CLONE_REMOVAL_BY_DELEGATION_DETECTED)) {
            return """
Your previous refactoring was rejected because duplication was handled by delegation instead of proper extraction.

Problem:
One or more target clone methods became delegates, but no clear shared helper is used by all target clone methods.

How to fix it:
- Extract shared logic into one new helper.
- Make all target clone methods call the same helper.
- Keep method-specific differences.

Important constraints:
- Do not rely on delegation alone.
- Do not make one clone call the other.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.FRAGMENTATION_OF_LOGIC_DETECTED)) {
            return """
Your previous refactoring was rejected because the logic was fragmented across multiple helpers.

Problem:
Shared logic was split instead of extracted as a single unit.

How to fix it:
- Combine shared logic into one helper method.
- Make all target clone methods call that helper.

Important constraints:
- Do not create multiple helpers unnecessarily.
- Do not delete clones.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        if (reasons.contains(Reason.EXTRACT_METHOD_NOT_FOUND)) {
            return """
Your previous refactoring was rejected because no Extract Method refactoring was found.

Problem:
The target clone was left essentially unchanged, and no new extracted helper method was introduced to replace it.

How to fix it:
- Create one new helper method that contains the shared clone logic.
- Replace the duplicated statements in all original target clone methods with calls to that helper.
- Keep only method-specific differences outside the helper.

Important constraints:
- Do not leave the original clone body unchanged.
- Do not skip creating the extracted helper.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""";
        }

        return "Your previous refactoring was rejected. Please perform a valid Extract Method refactoring.";
    }
}
