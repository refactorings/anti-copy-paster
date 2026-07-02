package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.safeTruncate;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileProposalSupport.buildCrossFileBeforeDiffBundle;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileProposalSupport.buildCrossFileDiffBundle;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileProposalSupport.compileCrossFileProposal;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileProposalSupport.saveCrossFileProposals;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileProposalSupport.writeCrossFileChanges;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.countLines;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.readCrossFileSources;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.sliceLines;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.cancelAwareViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.closeQuietly;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.openLogWriter;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.openViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showDiffAndConfirmApply;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showNotification;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.teeViewer;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.research.anticopypaster.agents.LlmUsefulnessEvaluator;
import org.jetbrains.research.anticopypaster.agents.compilation;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.llm.LlmClient;
import org.jetbrains.research.anticopypaster.llm.LlmClientFactory;
import org.jetbrains.research.anticopypaster.llm.LlmConfigurationNotifier;
import org.jetbrains.research.anticopypaster.llm.NoopLlmClient;
import org.jetbrains.research.anticopypaster.statistics.CloneUsageStatistics;

import java.io.BufferedWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CrossFileCloneRefactorWorkflow {
    private static final int MAX_USEFULNESS_WINDOW_LINES = 80;
    private static final ConcurrentMap<String, AtomicBoolean> CANCELLED_BY_PROJECT = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, AtomicReference<Thread>> CURRENT_THREAD_BY_PROJECT = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, AtomicReference<Process>> CURRENT_PROCESS_BY_PROJECT = new ConcurrentHashMap<>();

    private CrossFileCloneRefactorWorkflow() {}

    static void run(Project project, List<VirtualFile> targets, String pastedSnippet) {
        if (targets == null || targets.size() < 2) {
            showNotification(project, "[Clone] Cross Files needs at least two selected files.", NotificationType.WARNING);
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> runInBackground(project, targets, pastedSnippet));
    }

    private static void runInBackground(Project project, List<VirtualFile> targets, String pastedSnippet) {
        BufferedWriter logWriter = null;
        String projectKey = projectKey(project);
        AtomicBoolean cancelled = cancelFlag(projectKey);
        AtomicReference<Thread> currentThread = threadRef(projectKey);
        AtomicReference<Process> currentProcess = processRef(projectKey);
        BooleanSupplier isCancelled = () -> isCancelled(projectKey);
        currentThread.set(Thread.currentThread());
        try {
            resetCancelFlag(cancelled);

            String modelNameForLog = resolveModelNameForLog(project);
            logWriter = openLogWriter(project, "cross-files", modelNameForLog);
            Consumer<String> baseViewer = cancelAwareViewer(
                    openViewer(
                            project,
                            "Cross Files Clone Workflow Output",
                            viewer -> cancelWorkflow(projectKey, viewer),
                            isCancelled
                    ),
                    isCancelled
            );
            Consumer<String> viewer = teeViewer(baseViewer, logWriter);

            viewer.accept("[START] Cross Files");
            showNotification(project,
                    "[Clone] Cross Files workflow started for " + targets.size() + " selected file(s).",
                    NotificationType.INFORMATION);
            logStage(viewer, "SETTINGS", "selectedTargets=" + targets.size());
            if (pastedSnippet != null && !pastedSnippet.isBlank()) {
                logStage(viewer, "PASTE", "snippet provided (chars=" + pastedSnippet.length() + ")");
            } else {
                logStage(viewer, "PASTE", "no snippet provided; cross-file detection will inspect the selected files");
            }

            LlmClient llm = LlmClientFactory.fromProjectSettings(project, viewer);
            if (llm instanceof NoopLlmClient) {
                notifyLlmConfigurationProblem(project, viewer);
                return;
            }

            List<CrossFileSource> sources = readCrossFileSources(project, targets, viewer);
            if (sources.size() < 2) {
                showNotification(project, "[Clone] Cross Files needs at least two readable Java files.", NotificationType.WARNING);
                logStage(viewer, "WORKFLOW", "stopped: readable Java source files=" + sources.size());
                return;
            }

            WorkflowJavaBuildSupport javaBuildSupport =
                    new WorkflowJavaBuildSupport(project, viewer, currentProcess, isCancelled);
            int maxAttempts = resolveMaxAttempts(project);
            logStage(viewer, "SETTINGS", "maxAttempts=" + maxAttempts);

            logStage(viewer, "DETECTION", "running Detection Agent across " + sources.size() + " files");
            CrossFileDetectionResult detectionResult = CrossFileDetectionSupport.runCrossFileDetectionAgent(llm, project, viewer, sources, pastedSnippet);
            if (detectionResult == null || !detectionResult.parsed) {
                String detail = detectionResult == null ? "No detection result returned." : detectionResult.message;
                logStage(viewer, "DETECTION", "failed to parse cross-file detection result: " + detail);
                showNotification(project, "[Clone] Cross Files detection failed: " + detail, NotificationType.ERROR);
                return;
            }
            for (String warning : detectionResult.warnings) {
                logStage(viewer, "DETECTION", "warning: " + warning);
            }
            if ("no_clones".equalsIgnoreCase(detectionResult.status == null ? "" : detectionResult.status.trim())
                    || detectionResult.clones.isEmpty()) {
                logStage(viewer, "DETECTION", "no cross-file clones reported");
                showNotification(project, "[Clone] No cross-file clones detected in selected files.", NotificationType.INFORMATION);
                return;
            }

            List<CrossFileClone> cloneCandidates = detectionResult.clones;
            CrossFileMetricsGateSupport.Result metricsGate =
                    CrossFileMetricsGateSupport.filter(project, detectionResult.clones, viewer);
            if (metricsGate == null || !metricsGate.hasPassedClones()) {
                logStage(viewer, "METRICS", "no cross-file clone groups passed the metrics gate; will ask user before refactoring");
                boolean continueDespiteMetrics = WorkflowCloneMetricsGate.confirmContinue(
                        project,
                        "Cross Files",
                        metricsGate == null ? null : metricsGate.metricsGate,
                        "the detected cross-file clone groups"
                );
                if (!continueDespiteMetrics) {
                    logStage(viewer, "METRICS", "stopped by user after metrics gate rejected all cross-file clone groups");
                    showNotification(project,
                            WorkflowCloneMetricsGate.buildExtractMethodRecommendationNotification(
                                    "Cross Files",
                                    metricsGate == null ? null : metricsGate.metricsGate,
                                    false
                            ),
                            NotificationType.INFORMATION);
                    return;
                }
                logStage(viewer, "METRICS", "user chose to continue despite cross-file metrics gate rejection");
            } else if (metricsGate.passedClones.size() != detectionResult.clones.size()) {
                logStage(viewer, "METRICS", "some cross-file clone groups did not pass the metrics gate; selecting from passed clone groups");
                cloneCandidates = metricsGate.passedClones;
            }

            CrossFileClone selectedClone = CrossFileDetectionSupport.selectBestCrossFileClone(cloneCandidates);
            boolean selectedClonePassedMetrics = metricsGate != null
                    && metricsGate.hasPassedClones()
                    && metricsGate.passedClones.contains(selectedClone);
            if (selectedClone == null) {
                String detail = detectionResult.message == null || detectionResult.message.isBlank()
                        ? "Detection result did not include a clone spanning at least two files."
                        : detectionResult.message;
                logStage(viewer, "DETECTION", "stopped: " + detail);
                showNotification(project, "[Clone] No valid cross-file clone found. " + detail, NotificationType.INFORMATION);
                return;
            }
            logStage(viewer, "DETECTION", "selected clone " + selectedClone.displayId()
                    + " touching " + selectedClone.affectedSources().size()
                    + " file(s): " + selectedClone.affectedPathSummary());
            if (selectedClonePassedMetrics) {
                showNotification(project,
                        WorkflowCloneMetricsGate.buildExtractMethodRecommendationNotification(
                                "Cross Files",
                                metricsGate == null ? null : metricsGate.metricsGate,
                                true
                        ),
                        NotificationType.INFORMATION);
            } else {
                showNotification(project,
                        WorkflowCloneMetricsGate.buildExtractMethodRecommendationNotification(
                                "Cross Files",
                                metricsGate == null ? null : metricsGate.metricsGate,
                                false
                        ),
                        NotificationType.INFORMATION);
            }

            CrossFileRefactorResult result = null;
            String beforeBundle = buildCrossFileDiffBundle(sources, Map.of());
            String afterBundle = "";
            String retryFeedback = "";
            CrossFileRefactorResult previousResultForRepair = null;
            boolean readyToApply = false;
            String finalFailure = "";

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                logStage(viewer, "ATTEMPT", attempt + "/" + maxAttempts);
                logStage(viewer, "REFACTOR", "running Refactoring Agent for selected cross-file clone");
                result = CrossFileRefactoringSupport.runCrossFileRefactoringAgent(
                        llm,
                        project,
                        viewer,
                        sources,
                        selectedClone,
                        retryFeedback,
                        previousResultForRepair
                );

                if (result == null || !result.parsed) {
                    String detail = result == null ? "No result returned." : result.message;
                    if (result != null) {
                        for (String warning : result.warnings) {
                            logStage(viewer, "REFACTOR", "warning: " + warning);
                        }
                    }
                    finalFailure = "Cross Files refactor failed: " + detail;
                    logStage(viewer, "REFACTOR", "failed to parse cross-file result: " + detail);
                    retryFeedback = CrossFileRefactoringSupport.buildRetryFeedback("The previous Refactoring Agent output could not be parsed or was incomplete.", detail, "");
                    previousResultForRepair = null;
                    if (attempt < maxAttempts) continue;
                    break;
                }
                if ("failed".equalsIgnoreCase(result.status == null ? "" : result.status.trim())) {
                    String detail = result.summary == null || result.summary.isBlank()
                            ? "Refactoring Agent reported failure."
                            : result.summary;
                    finalFailure = "Cross Files refactor failed: " + detail;
                    logStage(viewer, "REFACTOR", "failed: " + detail);
                    retryFeedback = CrossFileRefactoringSupport.buildRetryFeedback("The previous Refactoring Agent reported failure.", detail, "");
                    previousResultForRepair = null;
                    if (attempt < maxAttempts) continue;
                    break;
                }
                if ("no_clones".equalsIgnoreCase(result.status == null ? "" : result.status.trim())) {
                    finalFailure = "No cross-file clones detected in selected files.";
                    logStage(viewer, "DETECTION", "no cross-file clones reported");
                    break;
                }
                if (!result.hasChanges()) {
                    String summary = result.summary == null || result.summary.isBlank()
                            ? "No modified files were returned."
                            : result.summary;
                    finalFailure = "Cross Files produced no file changes. " + summary;
                    logStage(viewer, "REFACTOR", "no modified files: " + summary);
                    retryFeedback = CrossFileRefactoringSupport.buildRetryFeedback("The previous Refactoring Agent output produced no file changes.", summary, "");
                    previousResultForRepair = result;
                    if (attempt < maxAttempts) continue;
                    break;
                }

                for (String warning : result.warnings) {
                    logStage(viewer, "REFACTOR", "warning: " + warning);
                }

                saveCrossFileProposals(project, result, viewer);
                beforeBundle = buildCrossFileBeforeDiffBundle(sources, result);
                afterBundle = buildCrossFileDiffBundle(sources, result);

                logStage(viewer, "USEFULNESS", "running Usefulness Checker before compile");
                CrossFileUsefulnessResult usefulness = runCrossFileUsefulnessChecker(
                        llm,
                        project,
                        viewer,
                        sources,
                        selectedClone,
                        result,
                        beforeBundle,
                        afterBundle
                );
                if (usefulness.parsed && !usefulness.useful) {
                    String detail = usefulness.summary == null || usefulness.summary.isBlank()
                            ? "The Usefulness Checker judged the cross-file refactor not useful."
                            : usefulness.summary;
                    finalFailure = "Cross Files refactor was not applied: " + detail;
                    logStage(viewer, "USEFULNESS", "not useful: " + detail);
                    showRejectedCrossFileRefactorNotification(
                            project,
                            attempt,
                            maxAttempts,
                            "Usefulness Checker",
                            detail
                    );
                    retryFeedback = CrossFileRefactoringSupport.buildRetryFeedback(
                            "The previous refactoring attempt was rejected by the Usefulness Checker.",
                            detail,
                            usefulness.feedback
                    );
                    previousResultForRepair = result;
                    if (attempt < maxAttempts) continue;
                    break;
                }

                logStage(viewer, "COMPILE", "running Compilation Testing for " + result.changedFileCount() + " proposed file(s)");
                compilation.CompileResult compileResult = compileCrossFileProposal(javaBuildSupport, result, viewer);
                if (compileResult == null || !"compile_ok".equals(compileResult.status)) {
                    String detail = compileResult == null ? "Compilation did not return a result." : compileResult.summary;
                    finalFailure = "Cross Files refactor was not applied because compilation failed. " + detail;
                    logStage(viewer, "COMPILE", "failed: " + detail);
                    showNotification(
                            project,
                            "[Clone] Cross Files compilation failed (attempt " + attempt + ")\n" + detail,
                            NotificationType.ERROR
                    );
                    retryFeedback = CrossFileRefactoringSupport.buildRetryFeedback(
                            "The previous refactoring attempt failed compilation.",
                            CrossFileRefactoringSupport.buildCompileRetryDetail(compileResult),
                            ""
                    );
                    previousResultForRepair = result;
                    if (attempt < maxAttempts) continue;
                    break;
                }
                logStage(viewer, "COMPILE", compileResult.summary);
                showNotification(project,
                        "Compilation successful: Ready to run (attempt " + attempt + ") for: Cross Files",
                        NotificationType.INFORMATION);

                logStage(viewer, "TEST", "running Testing Agent for affected cross-file target classes");
                CrossFileTestingSupport.CrossFileTestResult testResult =
                        CrossFileTestingSupport.runCrossFileTests(
                                javaBuildSupport,
                                llm,
                                project,
                                viewer,
                                selectedClone,
                                result
                        );
                if (testResult == null || !testResult.passed) {
                    String detail = testResult == null
                            ? "Testing Agent did not return a result."
                            : testResult.summary;
                    finalFailure = "Cross Files refactor was not applied because tests failed. " + detail;
                    logStage(viewer, "TEST", "failed: " + detail);
                    showNotification(project,
                            "[Clone] Cross Files tests failed (attempt " + attempt + ")",
                            NotificationType.WARNING);
                    retryFeedback = CrossFileRefactoringSupport.buildRetryFeedback(
                            "The previous refactoring attempt failed generated tests.",
                            testResult == null ? detail : testResult.feedback,
                            ""
                    );
                    previousResultForRepair = result;
                    if (attempt < maxAttempts) continue;
                    break;
                }
                logStage(viewer, "TEST", testResult.summary);
                readyToApply = true;
                break;
            }

            if (!readyToApply) {
                if (finalFailure == null || finalFailure.isBlank()) {
                    finalFailure = "Cross Files workflow failed after " + maxAttempts + " attempt(s).";
                }
                logStage(viewer, "WORKFLOW", "FAILED after " + maxAttempts + " attempt(s): " + finalFailure);
                showNotification(project, "[Clone] " + finalFailure, NotificationType.WARNING);
                return;
            }

            boolean applyNow = showDiffAndConfirmApply(project, "Cross Files", beforeBundle, afterBundle);
            if (!applyNow) {
                CloneUsageStatistics.getInstance(project).refactoringCancelled();
                logStage(viewer, "REFACTOR", "cross-file proposal not applied (user cancelled)");
                showNotification(project,
                        "[Clone] Tests passed but Cross Files changes were not applied (user cancelled).",
                        NotificationType.WARNING);
                return;
            }

            writeCrossFileChanges(project, result);
            CloneUsageStatistics.getInstance(project).refactoringAccepted();
            logStage(viewer, "WORKFLOW", "SUCCESS applied files=" + result.changedFileCount());
            showNotification(project,
                    "[Clone] Tests passed. Cross Files refactor applied to " + result.changedFileCount() + " file(s).",
                    NotificationType.INFORMATION);
        } catch (Exception e) {
            if (isCancelled(projectKey)
                    || Thread.currentThread().isInterrupted()
                    || (e.getMessage() != null && e.getMessage().contains("CANCELLED"))) {
                showNotification(project, "Operation cancelled by user.", NotificationType.WARNING);
                return;
            }
            e.printStackTrace();
            showNotification(project, "[Clone] Cross Files workflow crashed: " + e.getMessage(), NotificationType.ERROR);
        } finally {
            closeQuietly(logWriter);
            currentProcess.set(null);
            currentThread.compareAndSet(Thread.currentThread(), null);
            cleanupProjectWorkflowState(projectKey, cancelled, currentThread, currentProcess);
        }
    }

    private static void resetCancelFlag(AtomicBoolean cancelled) {
        cancelled.set(false);
    }

    private static boolean isCancelled(String projectKey) {
        return cancelFlag(projectKey).get();
    }

    private static void cancelWorkflow(String projectKey, Consumer<String> viewer) {
        cancelFlag(projectKey).set(true);
        Thread thread = threadRef(projectKey).get();
        if (thread != null) {
            thread.interrupt();
        }
        Process process = processRef(projectKey).get();
        if (process != null) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {}
        }
        logStage(viewer, "WORKFLOW", "CANCELLED by user (viewer closed; thread interrupted)");
    }

    private static AtomicBoolean cancelFlag(String projectKey) {
        return CANCELLED_BY_PROJECT.computeIfAbsent(projectKey, ignored -> new AtomicBoolean(false));
    }

    private static AtomicReference<Thread> threadRef(String projectKey) {
        return CURRENT_THREAD_BY_PROJECT.computeIfAbsent(projectKey, ignored -> new AtomicReference<>());
    }

    private static AtomicReference<Process> processRef(String projectKey) {
        return CURRENT_PROCESS_BY_PROJECT.computeIfAbsent(projectKey, ignored -> new AtomicReference<>());
    }

    private static void cleanupProjectWorkflowState(String projectKey,
                                                    AtomicBoolean cancelled,
                                                    AtomicReference<Thread> currentThread,
                                                    AtomicReference<Process> currentProcess) {
        CURRENT_THREAD_BY_PROJECT.remove(projectKey, currentThread);
        CURRENT_PROCESS_BY_PROJECT.remove(projectKey, currentProcess);
        CANCELLED_BY_PROJECT.remove(projectKey, cancelled);
    }

    private static String projectKey(Project project) {
        if (project == null) return "<no-project>";
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) return basePath;
        return Integer.toHexString(System.identityHashCode(project));
    }

    private static String resolveModelNameForLog(Project project) {
        try {
            ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
            if (settings == null) return "unknown_model";

            if ("Ollama".equals(settings.getLlmprovider())) {
                String ollamaModel = settings.getOllamaModelName();
                if (ollamaModel != null && !ollamaModel.isBlank()) return ollamaModel;
            }

            String aiderModel = settings.getAiderModel();
            return aiderModel == null || aiderModel.isBlank() ? "unknown_model" : aiderModel;
        } catch (Throwable ignored) {
            return "unknown_model";
        }
    }

    private static void notifyLlmConfigurationProblem(Project project, Consumer<String> viewer) {
        String problem = LlmConfigurationNotifier.getConfigurationProblem(project, true);
        if (problem != null) {
            logStage(viewer, "LLM_SETTINGS", problem);
            LlmConfigurationNotifier.notifyConfigurationProblem(project, "Cross Files Clone Refactoring", problem);
        } else {
            showNotification(project, "[Clone] LLM is not configured. Configure provider/model/API key in Settings.", NotificationType.ERROR);
        }
    }

    private static int resolveMaxAttempts(Project project) {
        try {
            ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
            if (settings != null) {
                return Math.max(1, settings.getMaxAttempts());
            }
        } catch (Throwable ignored) {}
        return 3;
    }

    private static void showRejectedCrossFileRefactorNotification(Project project,
                                                                  int attempt,
                                                                  int maxAttempts,
                                                                  String reason,
                                                                  String details) {
        boolean willRetry = maxAttempts <= 0 || attempt < maxAttempts;
        StringBuilder message = new StringBuilder()
                .append("[Clone] Cross Files refactor attempt rejected (attempt ")
                .append(attempt)
                .append(")\n\n")
                .append(willRetry
                        ? "The proposed cross-file change did not safely reduce the duplicated code, so AntiCopyPaster will revise it."
                        : "The proposed cross-file change did not safely reduce the duplicated code, so AntiCopyPaster will not apply it.");

        if (reason != null && !reason.isBlank()) {
            message.append("\n\nCheck: ").append(reason);
        }
        if (details != null && !details.isBlank()) {
            message.append("\n").append(details);
        }

        showNotification(project, message.toString(), NotificationType.WARNING);
    }

    static CrossFileUsefulnessResult runCrossFileUsefulnessChecker(LlmClient llm,
                                                                           Project project,
                                                                           Consumer<String> viewer,
                                                                           List<CrossFileSource> sources,
                                                                           CrossFileClone selectedClone,
                                                                           CrossFileRefactorResult refactorResult,
                                                                           String beforeBundle,
                                                                           String afterBundle) {
        LlmUsefulnessEvaluator.UsefulnessInput input = buildCrossFileUsefulnessInput(
                sources,
                selectedClone,
                refactorResult,
                beforeBundle,
                afterBundle
        );
        LlmUsefulnessEvaluator.EvaluationResult evaluation = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> WorkflowLlmCallSupport.callUsefulness(llm, label, prompt, viewer, project)
        );
        CrossFileUsefulnessResult result = toCrossFileUsefulnessResult(evaluation);
        if (!result.parsed) {
            logStage(viewer, "USEFULNESS", "Usefulness Checker output could not be parsed; continuing as best-effort: " + result.message);
        }
        return result;
    }

    static LlmUsefulnessEvaluator.UsefulnessInput buildCrossFileUsefulnessInput(
            List<CrossFileSource> sources,
            CrossFileClone selectedClone,
            CrossFileRefactorResult refactorResult,
            String beforeBundle,
            String afterBundle) {
        return new LlmUsefulnessEvaluator.UsefulnessInput(
                buildCrossFileUsefulnessFileName(selectedClone),
                LlmUsefulnessEvaluator.CloneKind.FRAGMENT,
                buildCrossFileUsefulnessCloneContext(sources, selectedClone, refactorResult),
                nonBlankOr(buildCrossFileFocusedUsefulnessCode(selectedClone, refactorResult, false), beforeBundle),
                nonBlankOr(buildCrossFileFocusedUsefulnessCode(selectedClone, refactorResult, true), afterBundle)
        );
    }

    static String buildCrossFileUsefulnessFileName(CrossFileClone selectedClone) {
        String paths = selectedClone == null ? "" : selectedClone.affectedPathSummary();
        return paths == null || paths.isBlank() ? "Cross Files" : "Cross Files: " + paths;
    }

    static String buildCrossFileUsefulnessCloneContext(List<CrossFileSource> sources,
                                                              CrossFileClone selectedClone,
                                                              CrossFileRefactorResult refactorResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cross-file Extract Method target.\n");
        sb.append("Use the same usefulness categories as the current-file workflow; do not invent additional categories.\n");
        sb.append("Judge only whether the selected target occurrences were handled by Extract Method.\n\n");
        CrossFileRefactoringSupport.appendSelectedFiles(sb, sources);
        CrossFileRefactoringSupport.appendTargetClone(sb, selectedClone);
        if (refactorResult != null) {
            sb.append("Refactoring summary: ").append(refactorResult.summary == null ? "" : refactorResult.summary).append("\n");
            if (!refactorResult.selectedPanelistId.isBlank()) {
                sb.append("Selected refactoring panelist: ").append(refactorResult.selectedPanelistId).append("\n");
            }
            if (!refactorResult.curatorMatchedCategories.isEmpty()) {
                sb.append("Refactoring curator categories: ").append(refactorResult.curatorMatchedCategories).append("\n");
            }
        }
        return sb.toString();
    }

    static String buildCrossFileFocusedUsefulnessCode(CrossFileClone selectedClone,
                                                             CrossFileRefactorResult refactorResult,
                                                             boolean after) {
        StringBuilder sb = new StringBuilder();
        sb.append(after ? "=== AFTER FOCUSED CONTEXT ===\n" : "=== BEFORE FOCUSED CONTEXT ===\n");

        java.util.LinkedHashSet<CrossFileSource> affectedSources = selectedClone == null
                ? new java.util.LinkedHashSet<>()
                : selectedClone.affectedSources();
        for (CrossFileOccurrenceSpec spec : CrossFileTextEditSupport.buildCrossFileOccurrenceSpecs(selectedClone)) {
            if (spec == null || spec.occurrence == null || spec.occurrence.source == null) continue;
            CrossFileOccurrence occurrence = spec.occurrence;
            String sourceText = after
                    ? refactoredSourceForUsefulness(occurrence.source, refactorResult)
                    : occurrence.source.source;
            sb.append(spec.occurrenceId)
                    .append(" ")
                    .append(occurrence.source.relativePath)
                    .append(" lines ")
                    .append(occurrence.startLine)
                    .append("-")
                    .append(occurrence.endLine)
                    .append(":\n");
            sb.append("```java\n")
                    .append(sliceUsefulnessWindow(sourceText, occurrence.startLine, occurrence.endLine))
                    .append("\n```\n\n");
        }

        if (refactorResult != null) {
            for (Map.Entry<CrossFileSource, String> entry : refactorResult.newSourcesByFile.entrySet()) {
                CrossFileSource source = entry == null ? null : entry.getKey();
                if (source == null || affectedSources.contains(source)) continue;
                sb.append(after ? "Changed shared file after: " : "Changed shared file before: ")
                        .append(source.relativePath)
                        .append("\n");
                String text = after ? entry.getValue() : source.source;
                sb.append("```java\n").append(safeTruncate(text, 8000)).append("\n```\n\n");
            }
            for (CrossFileNewSource source : refactorResult.newFilesByPath.values()) {
                if (source == null) continue;
                sb.append(after ? "New helper file: " : "New helper file before: ")
                        .append(source.relativePath)
                        .append("\n");
                sb.append("```java\n")
                        .append(after ? safeTruncate(source.source, 8000) : "(new file)")
                        .append("\n```\n\n");
            }
        }

        return sb.toString().trim();
    }

    static String refactoredSourceForUsefulness(CrossFileSource source, CrossFileRefactorResult refactorResult) {
        if (source == null) return "";
        if (refactorResult == null) return source.source;
        String updated = refactorResult.newSourcesByFile.get(source);
        return updated == null ? source.source : updated;
    }

    static String sliceUsefulnessWindow(String source, int startLine, int endLine) {
        if (source == null || source.isBlank()) return "";
        int paddingBefore = 8;
        int paddingAfter = 16;
        int from = Math.max(1, startLine - paddingBefore);
        int to = Math.max(from, Math.min(countLines(source), endLine + paddingAfter));
        if (to - from + 1 > MAX_USEFULNESS_WINDOW_LINES) {
            to = Math.min(countLines(source), from + MAX_USEFULNESS_WINDOW_LINES - 1);
            if (endLine > to) {
                from = Math.max(1, endLine - MAX_USEFULNESS_WINDOW_LINES + 1);
                to = Math.min(countLines(source), from + MAX_USEFULNESS_WINDOW_LINES - 1);
            }
        }
        String window = sliceLines(source, from, to);
        return safeTruncate(window, 8000);
    }

    static String nonBlankOr(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }

    static CrossFileUsefulnessResult toCrossFileUsefulnessResult(LlmUsefulnessEvaluator.EvaluationResult evaluation) {
        CrossFileUsefulnessResult result = new CrossFileUsefulnessResult();
        if (evaluation == null) {
            result.message = "LLM usefulness evaluation returned no result.";
            return result;
        }
        result.parsed = evaluation.available;
        result.useful = evaluation.useful;
        result.message = evaluation.notes;
        if (evaluation.curatorResult != null) {
            result.summary = evaluation.curatorResult.summary;
            result.feedback = evaluation.curatorResult.feedback;
            if (!evaluation.curatorResult.reasons.isEmpty()) {
                result.message = nonBlankOr(result.message, "reasons=" + evaluation.curatorResult.reasons);
            }
        }
        if (!result.parsed && (result.message == null || result.message.isBlank())) {
            result.message = "LLM usefulness curator did not return parseable output.";
            return result;
        }
        return result;
    }

}
