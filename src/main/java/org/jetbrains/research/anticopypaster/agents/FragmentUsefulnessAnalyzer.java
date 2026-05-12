package org.jetbrains.research.anticopypaster.agents;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDoWhileStatement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiKeyword;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPostfixExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiWhileStatement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fragment-level clone refactoring usefulness analyzer.
 *
 * This analyzer keeps fragment-specific similarity checks, but uses the same PSI-driven
 * structural signals as the whole-method usefulness checker wherever possible:
 * delegate detection, shared newly-added helpers, extraction without clone replacement,
 * non-target refactoring, and conservative fallback behavior.
 */
public final class FragmentUsefulnessAnalyzer {

    private FragmentUsefulnessAnalyzer() {}

    public static final class LineRange {
        public final int startLine;
        public final int endLine;

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
        public final double cloneSimilarityBefore;
        public final double cloneSimilarityAfterStill;
        public final double cloneSimilarityAfterReduced;
        public final int minTokenCount;
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
        EXCESSIVE_REFACTORING,
        INCOMPLETE_REFACTORING,
        POST_EXTRACTION_CLONE_DELETION,
        DIRECT_CLONE_REMOVAL,
        CALL_BASED_CLONE_SUBSTITUTION,
        CLONE_REMOVAL_BY_DELEGATION,
        FRAGMENTATION_OF_LOGIC,
        NON_TARGET_CLONE_REFACTORING,
        EXTRACTION_WITHOUT_CLONE_REPLACEMENT,
        POST_EXTRACTION_DELETION,
        DIRECT_REMOVAL,
        DELEGATION_TO_EXISTING,
        FRAGMENTATION,
        UNKNOWN
    }

    public enum Reason {
        FRAGMENT_TOO_SMALL,
        NOT_A_CLONE_IN_BEFORE,
        EXTRACT_METHOD_CONFIRMED,
        INCOMPLETE_REFACTORING_DETECTED,
        POST_EXTRACTION_CLONE_DELETION_DETECTED,
        DIRECT_CLONE_REMOVAL_DETECTED,
        CALL_BASED_CLONE_SUBSTITUTION_DETECTED,
        CLONE_REMOVAL_BY_DELEGATION_DETECTED,
        FRAGMENTATION_OF_LOGIC_DETECTED,
        NON_TARGET_CLONE_REFACTORING_DETECTED,
        EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED,
        EXCESSIVE_REFACTORING_DETECTED,
        EXTRACT_METHOD_NOT_FOUND,
        ANALYZER_FALLBACK
    }

    public static final class UsefulnessResult {
        public final boolean isUseful;
        public final int score;
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

    private static final class Sig {
        final Set<String> tokenSet;
        final int tokenCount;

        Sig(Set<String> tokenSet, int tokenCount) {
            this.tokenSet = tokenSet == null ? Set.of() : tokenSet;
            this.tokenCount = tokenCount;
        }
    }

    private static final class DelegateInfo {
        final boolean isDelegate;
        final Set<String> calleeKeys;

        DelegateInfo(boolean isDelegate, Set<String> calleeKeys) {
            this.isDelegate = isDelegate;
            this.calleeKeys = calleeKeys == null ? Set.of() : Set.copyOf(calleeKeys);
        }
    }

    private static final class RelocationResult {
        final LineRange range;
        final boolean matched;
        final String mode;

        RelocationResult(LineRange range, boolean matched, String mode) {
            this.range = range == null ? new LineRange(1, 1) : range;
            this.matched = matched;
            this.mode = mode == null ? "" : mode;
        }
    }

    private static final class PairOutcome {
        final Strategy strategy;
        final Reason reason;
        final String helperKey;
        final boolean requiresReductionCheck;
        final int score;
        final String debug;

        PairOutcome(Strategy strategy,
                    Reason reason,
                    String helperKey,
                    boolean requiresReductionCheck,
                    int score,
                    String debug) {
            this.strategy = strategy == null ? Strategy.UNKNOWN : strategy;
            this.reason = reason;
            this.helperKey = helperKey;
            this.requiresReductionCheck = requiresReductionCheck;
            this.score = score;
            this.debug = debug == null ? "" : debug;
        }
    }

    private static final class NormalizedSearchText {
        final String normalized;
        final List<Integer> offsets;

        NormalizedSearchText(String normalized, List<Integer> offsets) {
            this.normalized = normalized == null ? "" : normalized;
            this.offsets = offsets == null ? List.of() : offsets;
        }
    }

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
            if (project == null || project.isDisposed()) return fallback("project unavailable");
            if (cfg == null) cfg = new UsefulnessConfig();
            if (beforeSource == null) beforeSource = "";
            if (afterSource == null) afterSource = "";
            if (pastedRange == null || matchRange == null) return fallback("missing ranges");

            String beforeA = nonBlankOr(cloneCodeA, sliceByLineRange(beforeSource, pastedRange));
            String beforeB = nonBlankOr(cloneCodeB, sliceByLineRange(beforeSource, matchRange));

            Sig beforeSigA = buildFragmentSig(project, beforeA);
            Sig beforeSigB = buildFragmentSig(project, beforeB);

            int minTok = Math.min(beforeSigA.tokenCount, beforeSigB.tokenCount);
            if (minTok < cfg.minTokenCount) {
                return new UsefulnessResult(
                        false,
                        30,
                        Strategy.UNKNOWN,
                        List.of(Reason.FRAGMENT_TOO_SMALL),
                        "minTokenCount=" + minTok + " (<" + cfg.minTokenCount + ")"
                );
            }

            double simBefore = jaccard(beforeSigA, beforeSigB);
            if (simBefore < cfg.cloneSimilarityBefore) {
                return new UsefulnessResult(
                        false,
                        30,
                        Strategy.UNKNOWN,
                        List.of(Reason.NOT_A_CLONE_IN_BEFORE),
                        "simBefore=" + fmt(simBefore) + " (<" + cfg.cloneSimilarityBefore + ")"
                );
            }

            PsiJavaFile beforePsi = parseInMemoryJavaFile(project, fileName, beforeSource);
            PsiJavaFile afterPsi = parseInMemoryJavaFile(project, fileName, afterSource);
            if (beforePsi == null || afterPsi == null) return fallback("PSI parse failed");

            Map<String, PsiMethod> beforeMethods = collectAllMethodsByKey(beforePsi);
            Map<String, PsiMethod> afterMethods = collectAllMethodsByKey(afterPsi);

            String hostKeyA = findHostMethodKeyByRange(beforePsi, beforeSource, pastedRange);
            String hostKeyB = findHostMethodKeyByRange(beforePsi, beforeSource, matchRange);
            if (hostKeyA == null || hostKeyB == null) return fallback("host method not found");

            Set<String> addedKeys = new LinkedHashSet<>(afterMethods.keySet());
            addedKeys.removeAll(beforeMethods.keySet());
            Map<String, Sig> addedHelperSigs = buildAddedHelperSigMap(afterMethods, addedKeys);

            PsiMethod afterHostA = afterMethods.get(hostKeyA);
            PsiMethod afterHostB = afterMethods.get(hostKeyB);

            RelocationResult afterARel = relocateAfter(afterSource, cloneCodeA, pastedRange);
            RelocationResult afterBRel = relocateAfter(afterSource, cloneCodeB, matchRange);

            String afterAText = (afterHostA == null) ? "" : sliceByLineRange(afterSource, afterARel.range);
            String afterBText = (afterHostB == null) ? "" : sliceByLineRange(afterSource, afterBRel.range);

            Sig afterSigA = buildFragmentSig(project, afterAText);
            Sig afterSigB = buildFragmentSig(project, afterBText);
            double simAfter = jaccard(afterSigA, afterSigB);

            DelegateInfo aDel = detectDelegate(afterHostA, afterPsi, cfg);
            DelegateInfo bDel = detectDelegate(afterHostB, afterPsi, cfg);
            Set<String> aCalls = collectResolvedCalleeKeys(afterHostA, afterPsi);
            Set<String> bCalls = collectResolvedCalleeKeys(afterHostB, afterPsi);

            PairOutcome outcome = evaluatePair(
                    hostKeyA,
                    hostKeyB,
                    afterHostA,
                    afterHostB,
                    aDel,
                    bDel,
                    aCalls,
                    bCalls,
                    addedKeys,
                    addedHelperSigs,
                    beforeSigA,
                    beforeSigB,
                    afterSigA,
                    afterSigB,
                    simAfter,
                    afterARel.matched && afterBRel.matched,
                    cfg
            );

            String notes = "strategy=" + outcome.strategy +
                    ", simBefore=" + fmt(simBefore) +
                    ", simAfter=" + fmt(simAfter) +
                    ", hostA=" + hostKeyA +
                    ", hostB=" + hostKeyB +
                    ", pasted(before=" + pastedRange + ", after=" + afterARel.range + ", mode=" + afterARel.mode + ")" +
                    ", match(before=" + matchRange + ", after=" + afterBRel.range + ", mode=" + afterBRel.mode + ")" +
                    ", addedMethods=" + addedKeys.size() +
                    ", helper=" + (outcome.helperKey == null ? "" : outcome.helperKey) +
                    (outcome.debug.isBlank() ? "" : (", debug=" + outcome.debug));

            if (outcome.strategy != Strategy.EXTRACT_METHOD) {
                Reason reason = outcome.reason == null ? Reason.EXTRACT_METHOD_NOT_FOUND : outcome.reason;
                return new UsefulnessResult(false, outcome.score, outcome.strategy, List.of(reason), notes);
            }

            if (outcome.helperKey != null && addedKeys.contains(outcome.helperKey)) {
                PsiMethod extracted = afterMethods.get(outcome.helperKey);
                int baseLines = Math.max(countNonEmptyCodeLines(beforeA), countNonEmptyCodeLines(beforeB));
                int extractedLines = countMethodBodyNonEmptyLines(extracted);
                int tolerance = 2;
                if (extractedLines > baseLines + tolerance) {
                    return new UsefulnessResult(
                            false,
                            35,
                            Strategy.EXCESSIVE_REFACTORING,
                            List.of(Reason.EXCESSIVE_REFACTORING_DETECTED),
                            notes + ", extractedLines=" + extractedLines + ", baseFragmentLines=" + baseLines + ", tol=" + tolerance
                    );
                }
            }

            boolean reliableReductionSignal = afterARel.matched && afterBRel.matched;
            if (outcome.requiresReductionCheck && reliableReductionSignal && simAfter > cfg.cloneSimilarityAfterReduced) {
                return new UsefulnessResult(
                        false,
                        40,
                        Strategy.INCOMPLETE_REFACTORING,
                        List.of(Reason.INCOMPLETE_REFACTORING_DETECTED),
                        notes
                );
            }

            int score = reliableReductionSignal
                    ? clamp(100 - (int) Math.round(40.0 * Math.max(0.0, Math.min(1.0, simAfter))), 0, 100)
                    : 95;

            return new UsefulnessResult(
                    true,
                    score,
                    Strategy.EXTRACT_METHOD,
                    List.of(Reason.EXTRACT_METHOD_CONFIRMED),
                    notes
            );
        } catch (Throwable t) {
            String detail = t.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = t.getClass().getSimpleName();
            } else {
                detail = t.getClass().getSimpleName() + ": " + detail;
            }
            return fallback(detail);
        }
    }

    private static PairOutcome evaluatePair(String hostKeyA,
                                            String hostKeyB,
                                            PsiMethod afterHostA,
                                            PsiMethod afterHostB,
                                            DelegateInfo aDel,
                                            DelegateInfo bDel,
                                            Set<String> aCalls,
                                            Set<String> bCalls,
                                            Set<String> addedKeys,
                                            Map<String, Sig> addedHelperSigs,
                                            Sig beforeSigA,
                                            Sig beforeSigB,
                                            Sig afterSigA,
                                            Sig afterSigB,
                                            double simAfter,
                                            boolean relocationReliable,
                                            UsefulnessConfig cfg) {
        boolean aDeleted = afterHostA == null;
        boolean bDeleted = afterHostB == null;

        if (aDeleted ^ bDeleted) {
            DelegateInfo remainingDel = aDeleted ? bDel : aDel;
            String helper = firstIntersection(remainingDel.calleeKeys, addedKeys);
            if (helper != null) {
                return new PairOutcome(
                        Strategy.POST_EXTRACTION_CLONE_DELETION,
                        Reason.POST_EXTRACTION_CLONE_DELETION_DETECTED,
                        helper,
                        false,
                        35,
                        "oneHostDeleted=true"
                );
            }
            return new PairOutcome(
                    Strategy.DIRECT_CLONE_REMOVAL,
                    Reason.DIRECT_CLONE_REMOVAL_DETECTED,
                    null,
                    false,
                    35,
                    "oneHostDeleted=true"
            );
        }

        if (aDeleted && bDeleted) {
            return new PairOutcome(
                    Strategy.DIRECT_CLONE_REMOVAL,
                    Reason.DIRECT_CLONE_REMOVAL_DETECTED,
                    null,
                    false,
                    35,
                    "bothHostsDeleted=true"
            );
        }

        Set<String> sharedCalls = intersection(aCalls, bCalls);
        Set<String> sharedNewHelpers = intersection(sharedCalls, addedKeys);
        Set<String> sharedDelegateCallees = intersection(aDel.calleeKeys, bDel.calleeKeys);

        if (aDel.isDelegate && bDel.isDelegate && !sharedDelegateCallees.isEmpty()) {
            if (sharedDelegateCallees.size() >= 2) {
                return new PairOutcome(
                        Strategy.FRAGMENTATION_OF_LOGIC,
                        Reason.FRAGMENTATION_OF_LOGIC_DETECTED,
                        null,
                        false,
                        40,
                        "sharedDelegateCallees=" + sharedDelegateCallees.size()
                );
            }

            String only = first(sharedDelegateCallees);
            if (addedKeys.contains(only)) {
                return new PairOutcome(
                        Strategy.EXTRACT_METHOD,
                        Reason.EXTRACT_METHOD_CONFIRMED,
                        only,
                        false,
                        100,
                        "bothDelegateToNewHelper=true"
                );
            }

            return new PairOutcome(
                    Strategy.CALL_BASED_CLONE_SUBSTITUTION,
                    Reason.CALL_BASED_CLONE_SUBSTITUTION_DETECTED,
                    only,
                    false,
                    45,
                    "bothDelegateToExisting=true"
            );
        }

        if (aDel.isDelegate ^ bDel.isDelegate) {
            DelegateInfo del = aDel.isDelegate ? aDel : bDel;
            Set<String> nonDelegateCalls = aDel.isDelegate ? bCalls : aCalls;
            String otherHostKey = aDel.isDelegate ? hostKeyB : hostKeyA;
            if (del.calleeKeys.contains(otherHostKey)) {
                return new PairOutcome(
                        Strategy.CALL_BASED_CLONE_SUBSTITUTION,
                        Reason.CALL_BASED_CLONE_SUBSTITUTION_DETECTED,
                        otherHostKey,
                        false,
                        45,
                        "delegateCallsOtherHost=true"
                );
            }

            Set<String> sharedWithNonDelegate = intersection(del.calleeKeys, nonDelegateCalls);
            String helper = firstIntersection(sharedWithNonDelegate, addedKeys);
            if (helper != null) {
                if (relocationReliable && simAfter >= cfg.cloneSimilarityAfterStill) {
                    if (looksLikeExtractionWithoutCloneReplacement(beforeSigA, beforeSigB, afterSigA, afterSigB, addedHelperSigs, Set.of(helper), cfg)) {
                        return new PairOutcome(
                                Strategy.EXTRACTION_WITHOUT_CLONE_REPLACEMENT,
                                Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED,
                                helper,
                                false,
                                40,
                                "delegatePlusSharedNewHelperButFragmentsUntouched=true"
                        );
                    }
                    return new PairOutcome(
                            Strategy.INCOMPLETE_REFACTORING,
                            Reason.INCOMPLETE_REFACTORING_DETECTED,
                            helper,
                            false,
                            40,
                            "delegatePlusSharedNewHelperButSimilarityHigh=true"
                    );
                }

                return new PairOutcome(
                        Strategy.EXTRACT_METHOD,
                        Reason.EXTRACT_METHOD_CONFIRMED,
                        helper,
                        true,
                        100,
                        "asymmetricDelegateToSharedNewHelper=true"
                );
            }

            if (!sharedWithNonDelegate.isEmpty()) {
                return new PairOutcome(
                        Strategy.CALL_BASED_CLONE_SUBSTITUTION,
                        Reason.CALL_BASED_CLONE_SUBSTITUTION_DETECTED,
                        first(sharedWithNonDelegate),
                        false,
                        45,
                        "delegatePlusSharedExistingCall=true"
                );
            }

            return new PairOutcome(
                    Strategy.CLONE_REMOVAL_BY_DELEGATION,
                    Reason.CLONE_REMOVAL_BY_DELEGATION_DETECTED,
                    null,
                    false,
                    45,
                    "asymmetricDelegateWithoutSharedHelper=true"
            );
        }

        if (!sharedNewHelpers.isEmpty()) {
            if (sharedNewHelpers.size() >= 2 && relocationReliable && simAfter <= cfg.cloneSimilarityAfterReduced) {
                return new PairOutcome(
                        Strategy.FRAGMENTATION_OF_LOGIC,
                        Reason.FRAGMENTATION_OF_LOGIC_DETECTED,
                        null,
                        false,
                        40,
                        "sharedNewHelpers=" + sharedNewHelpers.size()
                );
            }

            String helper = first(sharedNewHelpers);
            boolean strongLocalEvidence = !aDel.calleeKeys.isEmpty() || !bDel.calleeKeys.isEmpty();
            if (!relocationReliable) {
                return new PairOutcome(
                        Strategy.EXTRACT_METHOD,
                        Reason.EXTRACT_METHOD_CONFIRMED,
                        helper,
                        false,
                        100,
                        "sharedNewHelperWithUncertainRelocation=true"
                );
            }

            if (simAfter >= cfg.cloneSimilarityAfterStill) {
                if (looksLikeExtractionWithoutCloneReplacement(beforeSigA, beforeSigB, afterSigA, afterSigB, addedHelperSigs, Set.of(helper), cfg)) {
                    return new PairOutcome(
                            Strategy.EXTRACTION_WITHOUT_CLONE_REPLACEMENT,
                            Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED,
                            helper,
                            false,
                            40,
                            "sharedNewHelperButFragmentsUntouched=true"
                    );
                }

                if (strongLocalEvidence && (aDel.isDelegate || bDel.isDelegate)) {
                    return new PairOutcome(
                            Strategy.EXTRACT_METHOD,
                            Reason.EXTRACT_METHOD_CONFIRMED,
                            helper,
                            false,
                            100,
                            "sharedNewHelperViaDelegateShape=true"
                    );
                }

                return new PairOutcome(
                        Strategy.INCOMPLETE_REFACTORING,
                        Reason.INCOMPLETE_REFACTORING_DETECTED,
                        helper,
                        false,
                        40,
                        "sharedNewHelperButSimilarityStillHigh=true"
                );
            }

            return new PairOutcome(
                    Strategy.EXTRACT_METHOD,
                    Reason.EXTRACT_METHOD_CONFIRMED,
                    helper,
                    true,
                    100,
                    "sharedNewHelperAndSimilarityReduced=true"
            );
        }

        if (sharedCalls.size() >= 2 && relocationReliable && simAfter <= cfg.cloneSimilarityAfterReduced) {
            return new PairOutcome(
                    Strategy.FRAGMENTATION_OF_LOGIC,
                    Reason.FRAGMENTATION_OF_LOGIC_DETECTED,
                    null,
                    false,
                    40,
                    "sharedCalls=" + sharedCalls.size()
            );
        }

        boolean localRefactorAttempt = aDel.isDelegate
                || bDel.isDelegate
                || intersects(aCalls, addedKeys)
                || intersects(bCalls, addedKeys)
                || !sharedCalls.isEmpty();
        boolean untouchedFragment = looksLikeUntouchedFragmentPair(beforeSigA, beforeSigB, afterSigA, afterSigB, cfg);

        if (relocationReliable && simAfter >= cfg.cloneSimilarityAfterStill) {
            if (looksLikeExtractionWithoutCloneReplacement(beforeSigA, beforeSigB, afterSigA, afterSigB, addedHelperSigs, addedKeys, cfg)) {
                return new PairOutcome(
                        Strategy.EXTRACTION_WITHOUT_CLONE_REPLACEMENT,
                        Reason.EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED,
                        null,
                        false,
                        40,
                        "helperAddedButTargetFragmentUntouched=true"
                );
            }

            if (!localRefactorAttempt && untouchedFragment && addedKeys.isEmpty()) {
                return new PairOutcome(
                        Strategy.UNKNOWN,
                        Reason.EXTRACT_METHOD_NOT_FOUND,
                        null,
                        false,
                        45,
                        "targetFragmentUntouchedAndNoExtractedHelper=true"
                );
            }

            if (!localRefactorAttempt && !addedKeys.isEmpty()) {
                return new PairOutcome(
                        Strategy.NON_TARGET_CLONE_REFACTORING,
                        Reason.NON_TARGET_CLONE_REFACTORING_DETECTED,
                        null,
                        false,
                        40,
                        "targetFragmentUntouchedButOtherHelpersAdded=true"
                );
            }

            if (localRefactorAttempt) {
                return new PairOutcome(
                        Strategy.INCOMPLETE_REFACTORING,
                        Reason.INCOMPLETE_REFACTORING_DETECTED,
                        null,
                        false,
                        40,
                        "localAttemptButSimilarityStillHigh=true"
                );
            }
        }

        if (relocationReliable && simAfter <= cfg.cloneSimilarityAfterReduced) {
            return new PairOutcome(
                    Strategy.UNKNOWN,
                    Reason.EXTRACT_METHOD_NOT_FOUND,
                    null,
                    false,
                    45,
                    "similarityReducedWithoutSharedHelper=true"
            );
        }

        return new PairOutcome(
                Strategy.UNKNOWN,
                Reason.EXTRACT_METHOD_NOT_FOUND,
                null,
                false,
                40,
                "noConfirmingExtractMethodEvidence=true"
        );
    }

    private static boolean looksLikeExtractionWithoutCloneReplacement(Sig beforeSigA,
                                                                      Sig beforeSigB,
                                                                      Sig afterSigA,
                                                                      Sig afterSigB,
                                                                      Map<String, Sig> addedHelperSigs,
                                                                      Set<String> candidateHelpers,
                                                                      UsefulnessConfig cfg) {
        if (!looksLikeUntouchedFragmentPair(beforeSigA, beforeSigB, afterSigA, afterSigB, cfg)) return false;

        if (candidateHelpers != null && !candidateHelpers.isEmpty()) return true;
        if (addedHelperSigs == null || addedHelperSigs.isEmpty()) return false;

        double helperThreshold = Math.max(cfg.cloneSimilarityAfterReduced, 0.55);
        for (Map.Entry<String, Sig> entry : addedHelperSigs.entrySet()) {
            Sig helperSig = entry.getValue();
            if (helperSig == null || helperSig.tokenCount < 3) continue;

            double simA = jaccard(beforeSigA, helperSig);
            double simB = jaccard(beforeSigB, helperSig);
            if ((simA >= helperThreshold && simB >= helperThreshold)
                    || Math.max(simA, simB) >= cfg.cloneSimilarityBefore) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeUntouchedFragmentPair(Sig beforeSigA,
                                                          Sig beforeSigB,
                                                          Sig afterSigA,
                                                          Sig afterSigB,
                                                          UsefulnessConfig cfg) {
        if (beforeSigA == null || beforeSigB == null || afterSigA == null || afterSigB == null) return false;
        if (afterSigA.tokenCount == 0 || afterSigB.tokenCount == 0) return false;

        double selfA = jaccard(beforeSigA, afterSigA);
        double selfB = jaccard(beforeSigB, afterSigB);
        double pairAfter = jaccard(afterSigA, afterSigB);
        return pairAfter >= cfg.cloneSimilarityAfterStill && selfA >= 0.95 && selfB >= 0.95;
    }

    private static DelegateInfo detectDelegate(PsiMethod method, PsiJavaFile file, UsefulnessConfig cfg) {
        try {
            if (method == null) return new DelegateInfo(false, Set.of());
            PsiCodeBlock body = method.getBody();
            if (body == null) return new DelegateInfo(false, Set.of());

            PsiStatement[] statements = body.getStatements();
            if (statements == null || statements.length != 1) return new DelegateInfo(false, Set.of());

            PsiMethodCallExpression call = null;
            if (statements[0] instanceof PsiReturnStatement ret) {
                PsiExpression value = ret.getReturnValue();
                if (value instanceof PsiMethodCallExpression methodCall) call = methodCall;
            } else if (statements[0] instanceof PsiExpressionStatement exprStmt) {
                PsiExpression expr = exprStmt.getExpression();
                if (expr instanceof PsiMethodCallExpression methodCall) call = methodCall;
            }

            if (call == null) return new DelegateInfo(false, Set.of());

            int argCount = call.getArgumentList() == null ? 0 : call.getArgumentList().getExpressionCount();
            if (argCount > cfg.maxDelegateArgs) return new DelegateInfo(false, Set.of());

            PsiMethod resolved = null;
            try {
                resolved = call.resolveMethod();
            } catch (Throwable ignored) {
            }

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

    private static Set<String> collectResolvedCalleeKeys(PsiMethod method, PsiJavaFile file) {
        try {
            if (method == null) return Set.of();
            PsiCodeBlock body = method.getBody();
            if (body == null) return Set.of();

            Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
            if (calls == null || calls.isEmpty()) return Set.of();

            Set<String> out = new LinkedHashSet<>();
            for (PsiMethodCallExpression call : calls) {
                PsiMethod resolved = null;
                try {
                    resolved = call.resolveMethod();
                } catch (Throwable ignored) {
                }

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
                PsiClass psiClass = stack.pop();
                if (psiClass == null) continue;

                PsiMethod[] methods = psiClass.getMethods();
                if (methods != null) {
                    for (PsiMethod method : methods) {
                        if (method == null) continue;
                        if (!methodName.equals(method.getName())) continue;
                        int paramCount = 0;
                        try {
                            paramCount = method.getParameterList() == null ? 0 : method.getParameterList().getParametersCount();
                        } catch (Throwable ignored) {
                        }
                        if (paramCount == argCount) {
                            String key = methodKey(psiClass, method);
                            if (key != null && !key.isBlank()) return key;
                        }
                    }
                }

                PsiClass[] innerClasses = psiClass.getInnerClasses();
                if (innerClasses != null) Collections.addAll(stack, innerClasses);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String resolveMethodKeyInFile(PsiJavaFile file, PsiMethod resolved) {
        try {
            if (file == null || resolved == null) return null;
            PsiFile containing = resolved.getContainingFile();
            if (containing != null && !containing.isEquivalentTo(file)) return null;
            PsiClass owner = resolved.getContainingClass();
            if (owner == null) return null;
            return methodKey(owner, resolved);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Sig buildFragmentSig(Project project, String fragmentText) {
        try {
            PsiCodeBlock body = parseWrappedFragmentBody(project, fragmentText);
            if (body != null) return buildSigFromCodeBlock(body);
        } catch (Throwable ignored) {
        }
        return buildFallbackTextSig(fragmentText);
    }

    private static Map<String, Sig> buildAddedHelperSigMap(Map<String, PsiMethod> afterMethods, Set<String> addedKeys) {
        Map<String, Sig> out = new HashMap<>();
        if (afterMethods == null || afterMethods.isEmpty() || addedKeys == null || addedKeys.isEmpty()) return out;
        for (String key : addedKeys) {
            PsiMethod method = afterMethods.get(key);
            if (method != null) out.put(key, buildMethodSig(method));
        }
        return out;
    }

    private static PsiCodeBlock parseWrappedFragmentBody(Project project, String fragmentText) {
        try {
            if (project == null || project.isDisposed()) return null;
            String body = fragmentText == null ? "" : fragmentText;
            String wrapped = "class __ACPFragmentWrapper {\n" +
                    "    void __fragment() {\n" +
                    body + "\n" +
                    "    }\n" +
                    "}\n";

            PsiJavaFile psi = parseInMemoryJavaFile(project, "__ACPFragmentWrapper.java", wrapped);
            if (psi == null) return null;

            PsiClass[] classes = psi.getClasses();
            if (classes == null || classes.length == 0) return null;

            PsiMethod[] methods = classes[0].findMethodsByName("__fragment", false);
            if (methods == null || methods.length == 0) return null;
            return methods[0].getBody();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Sig buildMethodSig(PsiMethod method) {
        try {
            if (method == null) return new Sig(Set.of(), 0);
            PsiCodeBlock body = method.getBody();
            if (body == null) return new Sig(Set.of(), 0);
            return buildSigFromCodeBlock(body);
        } catch (Throwable t) {
            return new Sig(Set.of(), 0);
        }
    }

    private static Sig buildSigFromCodeBlock(PsiCodeBlock body) {
        try {
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
                    if (op != null) tokens.add("OP:" + op);
                    super.visitBinaryExpression(expression);
                }

                @Override
                public void visitPrefixExpression(PsiPrefixExpression expression) {
                    IElementType op = expression.getOperationTokenType();
                    if (op != null) tokens.add("OP:" + op);
                    super.visitPrefixExpression(expression);
                }

                @Override
                public void visitPostfixExpression(PsiPostfixExpression expression) {
                    IElementType op = expression.getOperationTokenType();
                    if (op != null) tokens.add("OP:" + op);
                    super.visitPostfixExpression(expression);
                }

                @Override
                public void visitAssignmentExpression(PsiAssignmentExpression expression) {
                    PsiJavaToken tok = expression.getOperationSign();
                    IElementType op = tok == null ? null : tok.getTokenType();
                    if (op != null) tokens.add("OP:" + op);
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

            return new Sig(new HashSet<>(tokens), tokens.size());
        } catch (Throwable t) {
            return new Sig(Set.of(), 0);
        }
    }

    private static Sig buildFallbackTextSig(String text) {
        if (text == null || text.isBlank()) return new Sig(Set.of(), 0);

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                current.append(ch);
                continue;
            }

            flushFallbackToken(current, tokens, text, i);
            if (!Character.isWhitespace(ch)) {
                tokens.add("P:" + ch);
            }
        }
        flushFallbackToken(current, tokens, text, text.length());
        return new Sig(new HashSet<>(tokens), tokens.size());
    }

    private static void flushFallbackToken(StringBuilder current, List<String> tokens, String source, int nextIndex) {
        if (current.length() == 0) return;
        String token = current.toString();
        current.setLength(0);

        if (JAVA_KEYWORDS.contains(token)) {
            tokens.add("KW:" + token);
            return;
        }

        boolean numeric = true;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (!(Character.isDigit(ch) || ch == '.' || ch == '_' || ch == 'x' || ch == 'X')) {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            tokens.add("LIT");
            return;
        }

        int lookahead = nextIndex;
        while (lookahead < source.length() && Character.isWhitespace(source.charAt(lookahead))) lookahead++;
        if (lookahead < source.length() && source.charAt(lookahead) == '(') {
            tokens.add("CALL:" + token);
            return;
        }

        tokens.add("ID");
    }

    private static PsiJavaFile parseInMemoryJavaFile(Project project, String fileName, String text) {
        try {
            String effectiveName = (fileName == null || fileName.isBlank()) ? "Temp.java" : fileName;
            if (!effectiveName.endsWith(".java")) effectiveName = effectiveName + ".java";
            String effectiveText = text == null ? "" : text;

            PsiFile psi = PsiFileFactory.getInstance(project)
                    .createFileFromText(effectiveName, JavaLanguage.INSTANCE, effectiveText, false, true);
            return (psi instanceof PsiJavaFile javaFile) ? javaFile : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, PsiMethod> collectAllMethodsByKey(PsiJavaFile javaFile) {
        Map<String, PsiMethod> map = new LinkedHashMap<>();
        if (javaFile == null) return map;

        PsiClass[] classes = javaFile.getClasses();
        if (classes == null) return map;
        for (PsiClass psiClass : classes) collectFromClassRecursive(psiClass, map);
        return map;
    }

    private static void collectFromClassRecursive(PsiClass psiClass, Map<String, PsiMethod> map) {
        if (psiClass == null) return;

        PsiMethod[] methods = psiClass.getMethods();
        if (methods != null) {
            for (PsiMethod method : methods) {
                String key = methodKey(psiClass, method);
                if (key != null && !key.isBlank()) map.putIfAbsent(key, method);
            }
        }

        PsiClass[] innerClasses = psiClass.getInnerClasses();
        if (innerClasses != null) {
            for (PsiClass inner : innerClasses) collectFromClassRecursive(inner, map);
        }
    }

    private static String methodKey(PsiClass owner, PsiMethod method) {
        if (method == null) return "";
        String cls = owner == null ? "" : owner.getQualifiedName();
        if (cls == null || cls.isBlank()) cls = owner == null ? "" : owner.getName();

        StringBuilder sb = new StringBuilder();
        sb.append(cls == null ? "" : cls).append("#").append(method.getName()).append("(");
        try {
            PsiParameter[] params = method.getParameterList() == null ? new PsiParameter[0] : method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(params[i].getType().getCanonicalText());
            }
        } catch (Throwable ignored) {
        }
        sb.append(")");
        return sb.toString();
    }

    private static String findHostMethodKeyByRange(PsiJavaFile psi, String source, LineRange range) {
        try {
            if (psi == null || source == null || range == null) return null;

            int startOffset = offsetAtLine(source, range.startLine);
            int endOffsetExclusive = offsetAtLine(source, range.endLine + 1);
            int safeStart = Math.min(startOffset, Math.max(0, psi.getTextLength() - 1));
            int safeEnd = Math.min(Math.max(startOffset, endOffsetExclusive - 1), Math.max(0, psi.getTextLength() - 1));

            PsiElementLike element = findHostMethodElement(psi, safeStart, safeEnd);
            if (element == null || element.method == null || element.owner == null) return null;

            String key = methodKey(element.owner, element.method);
            return key == null || key.isBlank() ? null : key;
        } catch (Throwable t) {
            return null;
        }
    }

    private static PsiElementLike findHostMethodElement(PsiJavaFile psi, int... offsets) {
        try {
            for (int offset : offsets) {
                if (offset < 0 || offset >= psi.getTextLength()) continue;
                var el = psi.findElementAt(offset);
                PsiMethod host = PsiTreeUtil.getParentOfType(el, PsiMethod.class, false);
                if (host == null) continue;
                PsiClass owner = host.getContainingClass();
                if (owner == null) continue;
                return new PsiElementLike(host, owner);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static RelocationResult relocateAfter(String afterSource, String cloneCode, LineRange fallback) {
        try {
            if (afterSource == null || afterSource.isBlank()) {
                return new RelocationResult(fallback, false, "range-fallback");
            }

            if (cloneCode != null && !cloneCode.isBlank()) {
                int exact = afterSource.indexOf(cloneCode);
                if (exact >= 0) {
                    return new RelocationResult(toLineRange(afterSource, exact, exact + cloneCode.length()), true, "exact");
                }

                String normalizedSnippet = normalizeSearchSnippet(cloneCode);
                if (!normalizedSnippet.isBlank()) {
                    NormalizedSearchText normalizedSource = normalizeForSearch(afterSource);
                    int normalizedIndex = normalizedSource.normalized.indexOf(normalizedSnippet);
                    if (normalizedIndex >= 0) {
                        int startOffset = normalizedSource.offsets.get(normalizedIndex);
                        int endOffset = normalizedSource.offsets.get(normalizedIndex + normalizedSnippet.length() - 1) + 1;
                        return new RelocationResult(toLineRange(afterSource, startOffset, endOffset), true, "normalized");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new RelocationResult(fallback, false, "range-fallback");
    }

    private static NormalizedSearchText normalizeForSearch(String text) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        if (text == null) return new NormalizedSearchText("", offsets);

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            normalized.append(ch);
            offsets.add(i);
        }
        return new NormalizedSearchText(normalized.toString(), offsets);
    }

    private static String normalizeSearchSnippet(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) normalized.append(ch);
        }
        return normalized.toString();
    }

    private static LineRange toLineRange(String text, int startOffset, int endOffsetExclusive) {
        int safeStart = Math.max(0, Math.min(startOffset, text == null ? 0 : text.length()));
        int safeEnd = Math.max(safeStart, Math.min(endOffsetExclusive, text == null ? 0 : text.length()));
        int startLine = lineOfOffset(text, safeStart);
        int endLine = lineOfOffset(text, Math.max(safeStart, safeEnd - 1));
        return new LineRange(startLine, endLine);
    }

    private static int lineOfOffset(String text, int offset) {
        if (text == null || text.isEmpty()) return 1;
        int safe = Math.max(0, Math.min(offset, text.length()));
        int line = 1;
        for (int i = 0; i < safe; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String sliceByLineRange(String text, LineRange range) {
        try {
            if (text == null || range == null) return "";
            int start = offsetAtLine(text, range.startLine);
            int end = offsetAtLine(text, range.endLine + 1);
            if (start < 0) return "";
            if (end < 0) end = text.length();
            start = Math.min(start, text.length());
            end = Math.min(Math.max(end, start), text.length());
            return text.substring(start, end);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int offsetAtLine(String text, int line1Based) {
        if (text == null) return -1;
        if (line1Based <= 1) return 0;
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (line == line1Based) return i;
            if (text.charAt(i) == '\n') line++;
        }
        return text.length();
    }

    private static String nonBlankOr(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback == null ? "" : fallback;
    }

    private static int countNonEmptyCodeLines(String text) {
        try {
            if (text == null) return 0;
            String[] lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
            int count = 0;
            for (String raw : lines) {
                if (raw == null) continue;
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("//")) continue;
                if (trimmed.equals("{") || trimmed.equals("}")) continue;
                count++;
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int countMethodBodyNonEmptyLines(PsiMethod method) {
        try {
            if (method == null) return 0;
            PsiCodeBlock body = method.getBody();
            if (body == null) return 0;
            return countNonEmptyCodeLines(body.getText());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return Set.of();
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        Set<String> out = new LinkedHashSet<>();
        for (String item : small) {
            if (large.contains(item)) out.add(item);
        }
        return out;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        return !intersection(a, b).isEmpty();
    }

    private static String first(Set<String> values) {
        if (values == null || values.isEmpty()) return null;
        for (String value : values) return value;
        return null;
    }

    private static String firstIntersection(Set<String> a, Set<String> b) {
        return first(intersection(a, b));
    }

    private static double jaccard(Sig a, Sig b) {
        if (a == null || b == null) return 0.0;
        if (a.tokenSet.isEmpty() || b.tokenSet.isEmpty()) return 0.0;

        Set<String> small = a.tokenSet.size() <= b.tokenSet.size() ? a.tokenSet : b.tokenSet;
        Set<String> large = small == a.tokenSet ? b.tokenSet : a.tokenSet;

        int intersection = 0;
        for (String token : small) {
            if (large.contains(token)) intersection++;
        }

        int union = a.tokenSet.size() + b.tokenSet.size() - intersection;
        if (union <= 0) return 0.0;
        return intersection * 1.0 / union;
    }

    private static UsefulnessResult fallback(String msg) {
        return new UsefulnessResult(
                false,
                40,
                Strategy.UNKNOWN,
                List.of(Reason.ANALYZER_FALLBACK),
                "Analyzer fallback: " + (msg == null ? "" : msg)
        );
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "var", "record", "sealed", "permits", "non", "yield"
    );

    private static final class PsiElementLike {
        final PsiMethod method;
        final PsiClass owner;

        PsiElementLike(PsiMethod method, PsiClass owner) {
            this.method = method;
            this.owner = owner;
        }
    }
}
