package org.jetbrains.research.anticopypaster.workflow;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.containsReasonName;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.definitionForReason;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.extractUsefulnessDebugText;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.parseWrapperNamesFromUsefulnessDebug;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.previewOneLine;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.cancelAwareViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.closeQuietly;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.openLogWriter;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.openViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showDiffAndConfirmApply;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showNotification;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.teeViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.throwIfCancelled;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiMethod;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.agents.refactoring;
import org.jetbrains.research.anticopypaster.agents.compilation;
import org.jetbrains.research.anticopypaster.agents.testing;
import org.jetbrains.research.anticopypaster.agents.usefulnessChecker;
import org.jetbrains.research.anticopypaster.agents.FragmentUsefulnessAnalyzer;
import org.jetbrains.research.anticopypaster.agents.LlmUsefulnessEvaluator;
import org.jetbrains.research.anticopypaster.agents.PsiFallbackCloneDetector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.intellij.notification.NotificationType;
import org.jetbrains.research.anticopypaster.llm.LlmClient;
import org.jetbrains.research.anticopypaster.llm.LlmClientFactory;
import org.jetbrains.research.anticopypaster.llm.LlmConfigurationNotifier;
import org.jetbrains.research.anticopypaster.llm.NoopLlmClient;
import org.jetbrains.research.anticopypaster.rag.RagService;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.CloneUsageStatistics;

public final class CloneRefactorWorkflow {
    private static final String REFACTOR_RAG_DB_RESOURCE = "refactor_database.csv";
    private static final String WORKFLOW_STAGE_SEPARATOR = "————————————";

    // RAG retrieval tuning
    private static final int REFACTOR_RAG_TOP_K = 5;
    private static final int REFACTOR_RAG_MAX_CHARS = 8000;

    // Hard-cancel support
    private static final AtomicReference<Thread> _CURRENT_WORKFLOW_THREAD = new AtomicReference<>();
    private static final AtomicReference<Process> _CURRENT_PROCESS = new AtomicReference<>();
    private static final AtomicLong _WORKFLOW_RUN_ID = new AtomicLong(0L);

    // LLM client resolved from plugin settings (provider/model/key/etc.)
    private static volatile LlmClient LLM = new NoopLlmClient();

    /* ============================================================
     * Viewer (ToolWindow Console)
     * ============================================================ */

    // ===== User-cancel support (viewer close) =====
    private static final java.util.concurrent.atomic.AtomicBoolean _CANCELLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void resetCancelFlag() {
        _CANCELLED.set(false);
    }

    private static boolean isCancelled() {
        return _CANCELLED.get();
    }

    private static void cancelWorkflow(java.util.function.Consumer<String> viewer) {
        _CANCELLED.set(true);

        Process p = _CURRENT_PROCESS.getAndSet(null);
        if (p != null) {
            try {
                if (viewer != null) viewer.accept("[WORKFLOW] Cancel requested; killing process...");
            } catch (Throwable ignored) {}
            try {
                p.destroy();
            } catch (Throwable ignored) {}
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            } catch (Throwable ignored) {}
        }

        Thread t = _CURRENT_WORKFLOW_THREAD.get();
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {}
        }

        logStage(viewer, "WORKFLOW", "CANCELLED by user (viewer closed; process/thread interrupted)");
    }

    public static void run(Project project, List<VirtualFile> targets) {
        run(project, targets, null);
    }

    /** Snippet-centered entry: pastedSnippet is the user's pasted/selected code. */
    public static void run(Project project, List<VirtualFile> targets, String pastedSnippet) {
        if (targets == null || targets.isEmpty()) return;
        for (VirtualFile vf : targets) {
            runOnSingleFile(project, vf, pastedSnippet);
        }
    }

    /** Cross-file entry: detect clones across the selected files and refactor them as one working set. */
    public static void runCrossFiles(Project project, List<VirtualFile> targets, String pastedSnippet) {
        CrossFileCloneRefactorWorkflow.run(project, targets, pastedSnippet);
    }

    private static String readCurrentSource(VirtualFile vf, File ioFile) throws IOException {
        // Prefer in-memory content (includes unsaved edits) if available.
        try {
            String documentText = ReadAction.compute(() -> {
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                return doc == null ? null : doc.getText();
            });
            if (documentText != null) return documentText;
        } catch (Throwable ignored) {}

        // Fallback: read from disk.
        return Files.readString(ioFile.toPath(), StandardCharsets.UTF_8);
    }

    private static void logFeedbackToRefactorAgent(Consumer<String> viewer,
                                                   String feedback,
                                                   boolean feedbackOnlyPrompt) {
        if (feedback == null || feedback.isBlank()) return;
        String mode = feedbackOnlyPrompt ? "feedback-only" : "combined";
        logStage(viewer, "FEEDBACK", "sending feedback to refactor agent (mode=" + mode + "):\n" + feedback);
    }

    private static void logWorkflowStageStart(Consumer<String> viewer, String stageName) {
        if (stageName == null || stageName.isBlank()) return;
        logStage(viewer, "STAGE", stageName);
    }

    private static void logWorkflowStageEnd(Consumer<String> viewer) {
        logStage(viewer, "STAGE", WORKFLOW_STAGE_SEPARATOR);
    }

    private static void showRejectedRefactorNotification(Project project,
                                                         String fileName,
                                                         int attempt,
                                                         int maxAttempts,
                                                         String reason,
                                                         String details) {
        boolean willRetry = maxAttempts <= 0 || attempt < maxAttempts;
        StringBuilder message = new StringBuilder()
                .append("[Clone] Refactor attempt rejected (attempt ")
                .append(attempt)
                .append(")\n")
                .append("File: ")
                .append(fileName == null || fileName.isBlank() ? "unknown" : fileName)
                .append("\n\n")
                .append(willRetry
                        ? "The proposed change did not safely reduce the duplicated code, so AntiCopyPaster will revise it."
                        : "The proposed change did not safely reduce the duplicated code, so AntiCopyPaster will not apply it.");

        if (reason != null && !reason.isBlank()) {
            message.append("\n\nCheck: ").append(reason);
        }
        if (details != null && !details.isBlank()) {
            message.append("\n").append(details);
        }

        showNotification(project, message.toString(), NotificationType.WARNING);
    }

    private static String buildLlmSetupGuidance(Project project) {
        try {
            ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
            if (settings == null) {
                return "Open Settings > AntiCopyPaster and configure the LLM provider and API key.";
            }

            String provider = settings.getLlmprovider();
            String apiKey = settings.getAiderApiKey();

            if (provider == null || provider.isBlank()) {
                return "Open Settings > AntiCopyPaster, choose an LLM provider, and enter its API key.";
            }

            if ("Ollama".equalsIgnoreCase(provider)) {
                return "Open Settings > AntiCopyPaster and complete the Ollama configuration.";
            }

            if (apiKey == null || apiKey.isBlank()) {
                return "Open Settings > AntiCopyPaster and enter the API key for " + provider + " in Clone Settings.";
            }

            return "Open Settings > AntiCopyPaster and verify the provider, model, and API key settings.";
        } catch (Throwable ignored) {
            return "Open Settings > AntiCopyPaster and configure the LLM provider and API key.";
        }
    }

    /* ============================================================
     * Core Workflow
     * ============================================================ */

    private static void runOnSingleFile(Project project, VirtualFile vf, String pastedSnippet) {

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            BufferedWriter logWriter = null;
            Document trackedDocument = null;
            DocumentListener cloneMethodChangeListener = null;
            long runId = _WORKFLOW_RUN_ID.incrementAndGet();
            _CURRENT_WORKFLOW_THREAD.set(Thread.currentThread());
            try {
                resetCancelFlag();
                String fileName = vf.getName();
                File ioFile = new File(vf.getPath());
                String originalSource = readCurrentSource(vf, ioFile);

                // Resolve model name for log naming (best-effort)
                String modelNameForLog = "unknown_model";
                try {
                    ProjectSettingsState st0 = ProjectSettingsState.getInstance(project);
                    if (st0 != null) {
                        // Most common in this plugin: Aider model
                        String m = st0.getAiderModel();
                        if (m != null && !m.isBlank()) modelNameForLog = m;
                        // If Ollama is selected, prefer its model name
                        try {
                            String provider0 = st0.getLlmprovider();
                            if ("Ollama".equals(provider0)) {
                                String om = st0.getOllamaModelName();
                                if (om != null && !om.isBlank()) modelNameForLog = om;
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                logWriter = openLogWriter(project, fileName, modelNameForLog);
                Consumer<String> baseViewer = cancelAwareViewer(
                        openViewer(project, "Clone Workflow Output", CloneRefactorWorkflow::cancelWorkflow, CloneRefactorWorkflow::isCancelled),
                        CloneRefactorWorkflow::isCancelled
                );
                Consumer<String> viewer = teeViewer(baseViewer, logWriter);
                WorkflowJavaBuildSupport javaBuildSupport =
                        new WorkflowJavaBuildSupport(project, viewer, _CURRENT_PROCESS, CloneRefactorWorkflow::isCancelled);

                if (logWriter != null) {
                    viewer.accept("[LOG] writing to .anticopypaster/logs for file=" + fileName + ", model=" + modelNameForLog);
                }

                viewer.accept("[START] " + fileName);
                if (isCancelled()) return;
                if (pastedSnippet != null && !pastedSnippet.isBlank()) {
                    String pv = pastedSnippet.strip();
                    if (pv.length() > 200) pv = pv.substring(0, 200) + "...";
                    logStage(viewer, "PASTE", "snippet provided (chars=" + pastedSnippet.length() + "): " + pv.replace("\n", "\\n"));
                } else {
                    logStage(viewer, "PASTE", "no snippet provided (workflow will likely return no_clones with snippet-centered detection)");
                }

                // Classify pasted snippet: whole-method vs fragment (best-effort).
                PsiMethod wholeMethod = WorkflowCloneRangeSupport.findWholeMethodCoveredBySnippet(project, vf, originalSource, pastedSnippet);

                // Read settings used by the workflow
                int maxAttempts = 3;
                int minimumCloneCount = 2;
                try {
                    ProjectSettingsState st = ProjectSettingsState.getInstance(project);
                    if (st != null) {
                        maxAttempts = Math.max(1, st.getMaxAttempts());
                        minimumCloneCount = Math.max(2, st.getMinimumDuplicateMethods());

                        String selectedModel = null;
                        try {
                            selectedModel = st.getAiderModel();
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                    // keep defaults
                }
                logStage(viewer, "SETTINGS", "maxAttempts=" + maxAttempts +
                        ", minimumCloneCount=" + minimumCloneCount);

                // Resolve LLM from settings (provider/model/api key/base/version)
                LLM = LlmClientFactory.fromProjectSettings(project, viewer);

                logStage(viewer, "START", fileName);
                showNotification(project, "[Clone] Workflow started for: " + fileName, NotificationType.INFORMATION);

                if (LLM instanceof NoopLlmClient) {
                    String llmConfigurationProblem = LlmConfigurationNotifier.getConfigurationProblem(project, true);
                    if (llmConfigurationProblem != null) {
                        logStage(viewer, "LLM_SETTINGS", llmConfigurationProblem);
                        LlmConfigurationNotifier.notifyConfigurationProblem(
                                project,
                                "Clone Refactoring",
                                llmConfigurationProblem
                        );
                    } else {
                        showNotification(project,
                                "[Clone] LLM is not configured (missing/invalid provider settings or API key). Configure provider/model/API key in Settings.",
                                NotificationType.ERROR);
                    }
                    return;
                }

                detection detectionAgent = new detection();
                refactoring refactorAgent = new refactoring();
                compilation compileAgent = new compilation();
                testing testAgent = new testing();

                Function<String, String> llmCaller = prompt -> WorkflowLlmCallSupport.callDetection(LLM, prompt, viewer, project);
                Function<String, String> refactorLlmCaller = prompt -> WorkflowLlmCallSupport.callRefactor(LLM, prompt, viewer, project);
                LlmUsefulnessEvaluator.LabeledLlmCaller usefulnessLlmCaller =
                        (label, prompt) -> WorkflowLlmCallSupport.callUsefulness(LLM, label, prompt, viewer, project);

                /* ---------- Detection ---------- */
                detection.DetectedClone clone;
                logWorkflowStageStart(viewer, "DETECTION STAGE");
                try {
                    detection.DetectionResult det =
                            detectionAgent.detect(
                                    project,
                                    fileName,
                                    originalSource,
                                    pastedSnippet,
                                    llmCaller
                            );

                    if (det == null || det.clones == null || det.clones.isEmpty()) {
                        logStage(viewer, "DETECTION", "no clones from LLM; trying PSI fallback (same-file)");

                        // PSI fallback only makes sense when we have a snippet to anchor the search.
                        if (pastedSnippet != null && !pastedSnippet.isBlank()) {
                            try {
                                java.util.List<?> cands = PsiFallbackCloneDetector.detectInSameFile(project, vf, pastedSnippet);
                                logStage(viewer, "DETECTION", "PSI fallback candidates=" + (cands == null ? 0 : cands.size()));

                                if (cands != null && cands.size() >= 2) {
                                    // Convert PSI candidates into the same DTO shape as the LLM detector output.
                                    detection.DetectedClone psiClone = new detection.DetectedClone();
                                    psiClone.id = "psi_fallback_same_file";
                                    psiClone.ranges = new java.util.ArrayList<>();
                                    psiClone.cloneCodes = new java.util.ArrayList<>();

                                    for (Object cc : cands) {
                                        if (cc == null) continue;

                                        int sLine = WorkflowCloneRangeSupport.getIntField(cc, "startLine", "start", "fromLine", "start_line", "startline");
                                        int eLine = WorkflowCloneRangeSupport.getIntField(cc, "endLine", "end", "toLine", "end_line", "endline");
                                        if (sLine <= 0) sLine = 1;
                                        if (eLine <= 0) eLine = sLine;

                                        detection.CloneRange range = new detection.CloneRange();
                                        WorkflowCloneRangeSupport.setIntField(range, sLine, "startLine", "start", "fromLine", "start_line", "startline");
                                        WorkflowCloneRangeSupport.setIntField(range, eLine, "endLine", "end", "toLine", "end_line", "endline");
                                        psiClone.ranges.add(range);
                                        String cloneCode = WorkflowCloneRangeSupport.getStringField(cc, "cloneCode", "code", "snippet", "text");
                                        psiClone.cloneCodes.add(cloneCode == null ? "" : cloneCode);
                                    }
                                    if (psiClone.cloneCodes.size() > 0) psiClone.cloneCodeA = psiClone.cloneCodes.get(0);
                                    if (psiClone.cloneCodes.size() > 1) psiClone.cloneCodeB = psiClone.cloneCodes.get(1);

                                    det = new detection.DetectionResult();
                                    det.status = "found_clones";
                                    det.file = fileName;
                                    det.clones = new java.util.ArrayList<>();
                                    det.clones.add(psiClone);
                                    logStage(viewer, "DETECTION", "PSI fallback accepted: ranges=" + psiClone.ranges.size());
                                }
                            } catch (Throwable t) {
                                logStage(viewer, "DETECTION", "PSI fallback failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                        } else {
                            logStage(viewer, "DETECTION", "PSI fallback skipped: pastedSnippet is empty");
                        }

                        // If still no clones after fallback, stop.
                        if (det == null || det.clones == null || det.clones.isEmpty()) {
                            logStage(viewer, "DETECTION", "no clones (after PSI fallback)");
                            showNotification(project, "[Clone] No clones detected in: " + fileName, NotificationType.INFORMATION);
                            return;
                        }
                    }

                    det.clones = WorkflowCloneRangeSupport.resolveDetectedCloneRangesWithPsi(project, vf, originalSource, det.clones, viewer);
                    det.clones = WorkflowCloneRangeSupport.mergeOverlappingDetectedClones(originalSource, det.clones, viewer);
                    if (det.clones == null || det.clones.isEmpty()) {
                        logStage(viewer, "DETECTION", "stopped: no detected clone groups remained after range resolution/merge");
                        showNotification(project,
                                "[Clone] Clones were detected in: " + fileName +
                                        ", but none could be resolved to selectable ranges.",
                                NotificationType.INFORMATION);
                        return;
                    }
                    WorkflowCloneMetricsGate.Result metricsGate = WorkflowCloneMetricsGate.filter(
                            project,
                            det.clones,
                            viewer,
                            cloneForMetrics -> WorkflowCloneSelectionSupport.getDetectedCloneCodes(cloneForMetrics, originalSource),
                            (range, code) -> WorkflowCloneSelectionSupport.calculateCloneOccurrenceFeatures(project, vf, originalSource, range, code),
                            WorkflowCloneSelectionSupport::summarizeCloneRanges
                    );
                    boolean continueDespiteAllMetricsRejected = false;
                    if (metricsGate == null || !metricsGate.hasPassedClones()) {
                        logStage(viewer, "METRICS", "no detected clone groups passed the metrics gate; will ask user before refactoring");
                    } else if (metricsGate.passedClones.size() != det.clones.size()) {
                        logStage(viewer, "METRICS", "some clone groups did not pass the metrics gate; keeping all clone groups for selection");
                    }

                    try {
                        if (det != null) {
                            java.nio.file.Path nicadOut =
                                    java.nio.file.Path.of(project.getBasePath(),
                                            ".anticopypaster",
                                            "nicad",
                                            fileName + ".nicad.xml");

                            java.nio.file.Files.createDirectories(nicadOut.getParent());
                            detectionAgent.saveAsNiCadXml(det, vf.getPath(), nicadOut);
                        }
                    } catch (Exception e) {
                        logStage(viewer, "DETECTION", "Failed to save NiCad XML: " + e.getMessage());
                    }

                    int detectedCloneCount = 1;
                    if (det.clones != null) {
                        for (detection.DetectedClone c : det.clones) {
                            if (c != null && c.ranges != null) {
                                detectedCloneCount += c.ranges.size() - 1;
                            }
                        }
                    }
                    logStage(viewer, "DETECTION", "detected clone range count=" + detectedCloneCount);
                    logStage(viewer, "DETECTION", "detected clone class count=" + det.clones.size());

                    if (detectedCloneCount < minimumCloneCount) {
                        logStage(viewer, "DETECTION", "stopped: detected clone range count " + detectedCloneCount +
                                " is smaller than minimumCloneCount=" + minimumCloneCount + " for Clone_multiagent. This parameter is set by the user in the settings and can be adjusted based on your needs.");
                        showNotification(project,
                                "[Clone] Only " + detectedCloneCount + " clone range(s) detected in: " + fileName +
                                        ". Need at least " + minimumCloneCount + " to continue.",
                                NotificationType.INFORMATION);
                        return;
                    }

                    if (metricsGate == null || !metricsGate.hasPassedClones()) {
                        logStage(viewer, "METRICS", "asking user whether to continue after metrics gate rejected all clone groups");
                        continueDespiteAllMetricsRejected = WorkflowCloneMetricsGate.confirmContinue(
                                project,
                                fileName,
                                metricsGate,
                                "the detected clone groups"
                        );
                        if (!continueDespiteAllMetricsRejected) {
                            logStage(viewer, "METRICS", "stopped by user after metrics gate rejected all clone groups");
                            showNotification(project,
                                    "[Clone] Refactor skipped because the detected clones are below the current confidence requirement.",
                                    NotificationType.INFORMATION);
                            return;
                        }
                        logStage(viewer, "METRICS", "user chose to continue despite metrics gate rejection");
                    }

                    clone = WorkflowCloneSelectionSupport.chooseCloneToRefactor(project, vf, det.clones, viewer);
                    if (clone == null) {
                        showNotification(project, "[Clone] Clone selection cancelled for: " + fileName, NotificationType.WARNING);
                        return;
                    }
                    if (!continueDespiteAllMetricsRejected
                            && metricsGate != null
                            && metricsGate.hasPassedClones()
                            && !metricsGate.passedClones.contains(clone)) {
                        logStage(viewer, "METRICS", "selected clone did not pass metrics gate; asking user whether to continue");
                        if (!WorkflowCloneMetricsGate.confirmContinue(project, fileName, metricsGate, "the selected clone")) {
                            logStage(viewer, "METRICS", "stopped by user after selected clone failed metrics gate");
                            showNotification(project,
                                    "[Clone] Refactor skipped because the selected clone is below the current confidence requirement.",
                                    NotificationType.INFORMATION);
                            return;
                        }
                        logStage(viewer, "METRICS", "user chose to continue with selected clone despite metrics gate rejection");
                    }

                    clone = WorkflowCloneSelectionSupport.chooseCloneRangesToRefactor(project, vf, originalSource, clone, viewer);
                    if (clone == null) {
                        showNotification(project, "[Clone] Clone range selection cancelled for: " + fileName, NotificationType.WARNING);
                        return;
                    }
                    showNotification(project, "[Clone] Clones detected in: " + fileName, NotificationType.INFORMATION);
                } finally {
                    logWorkflowStageEnd(viewer);
                }

                final java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> watchedCloneMethods = WorkflowMethodSnapshotSupport.captureCloneMethodSnapshots(project, vf, clone, viewer);
                final java.util.List<usefulnessChecker.TargetMethodHint> targetMethodHints =
                        WorkflowMethodSnapshotSupport.buildUsefulnessTargetMethodHints(wholeMethod, watchedCloneMethods, viewer);
                trackedDocument = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
                if (trackedDocument != null && watchedCloneMethods != null && !watchedCloneMethods.isEmpty()) {
                    final java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> listenerSnapshots = watchedCloneMethods;
                    cloneMethodChangeListener = new DocumentListener() {
                        @Override
                        public void documentChanged(DocumentEvent event) {
                            if (isCancelled()) return;
                            String changedMethod = WorkflowMethodSnapshotSupport.findModifiedCloneMethod(project, vf, listenerSnapshots);
                            if (changedMethod != null) {
                                showNotification(project,
                                        "[Clone] Stopped because a cloned method was modified by the user: " + changedMethod,
                                        NotificationType.WARNING);
                                cancelWorkflow(viewer);
                            }
                        }
                    };
                    trackedDocument.addDocumentListener(cloneMethodChangeListener);
//                    logStage(viewer, "WATCH", "tracking " + watchedCloneMethods.size() + " cloned method(s) for user edits");
                } else {
                    logStage(viewer, "WATCH", "no cloned methods to track for user edits");
                }

                // Build the RAG query text for refactoring few-shot retrieval.
                // Prefer clone code if the detection agent already includes it; otherwise fall back to best-effort extraction.
                String refactorRagQuery = buildRefactorRagQueryText(originalSource, clone);

                // Precompute RAG guidance once per file (it will be prepended to refactor feedback each attempt).
                String refactorRagGuidance = "";

                try {
                    refactorRagGuidance = RagService.buildRefactorRagGuidance(
                            project,
                            REFACTOR_RAG_DB_RESOURCE,
                            refactorRagQuery,
                            REFACTOR_RAG_TOP_K,
                            REFACTOR_RAG_MAX_CHARS
                    );
                } catch (Throwable t) {
                    refactorRagGuidance = "";
                    logStage(viewer, "RAG", "refactor RAG guidance failed: " + t.getMessage());
                }


                String currentSource = originalSource;
                String feedback = null;
                boolean useFeedbackOnlyPrompt = false;
                boolean baselineCompileChecked = false;
                compilation.CompileResult baselineCompileResult = null;
                String baselineCompileLog = null;

                /* ---------- Retry Loop ---------- */
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        if (isCancelled()) {
                            showNotification(project, "[Clone] Cancelled by user.", NotificationType.WARNING);
                            return;
                        }

                    String changedMethodAtAttemptStart = WorkflowMethodSnapshotSupport.findModifiedCloneMethod(project, vf, watchedCloneMethods);
                        if (changedMethodAtAttemptStart != null) {
                            logStage(viewer, "WATCH", "stopped before attempt because cloned method changed: " + changedMethodAtAttemptStart);
                            showNotification(project,
                                    "[Clone] Stopped because a cloned method was modified by the user: " + changedMethodAtAttemptStart,
                                    NotificationType.WARNING);
                            cancelWorkflow(viewer);
                            return;
                        }

                        logStage(viewer, "ATTEMPT", attempt + "/" + maxAttempts);

                        /* ===== Refactor ===== */
                        String proposedSource;
                        logWorkflowStageStart(viewer, "REFACTORING STAGE (attempt " + attempt + "/" + maxAttempts + ")");
                        try {
                            // Prepend refactor few-shot examples (RAG) to the feedback for the refactor agent.
                            // This keeps the agent signature unchanged while still injecting few-shot context.
                            refactoring.RefactorResult rr;
                            refactoring.DetectedClone refactorClone = convertClone(clone);
                            if (useFeedbackOnlyPrompt && feedback != null && !feedback.isBlank()) {
                                logStage(viewer, "REFACTOR", "retry mode: feedback-only prompt");
                                logFeedbackToRefactorAgent(viewer, feedback, true);
                                rr = refactorAgent.refactorWithPrompt(
                                        fileName,
                                        currentSource,
                                        refactorClone,
                                        feedback,
                                        refactorLlmCaller
                                );
                            } else {
                                String combinedFeedback = "";
                                if (refactorRagGuidance != null && !refactorRagGuidance.isBlank()) {
                                    combinedFeedback += refactorRagGuidance.strip() + "\n\n";
                                }
                                if (feedback != null && !feedback.isBlank()) {
                                    combinedFeedback += "[PREVIOUS_FEEDBACK]\n" + feedback.strip() + "\n";
                                }
                                String feedbackForRefactor = combinedFeedback.isBlank() ? null : combinedFeedback;
                                logFeedbackToRefactorAgent(viewer, feedback, false);

                                rr = refactorAgent.refactorFile(
                                        fileName,
                                        currentSource,
                                        refactorClone,
                                        feedbackForRefactor,
                                        refactorLlmCaller
                                );
                            }


                            if (rr == null || rr.newSource == null || rr.newSource.isBlank()) {
                                String failReason;
                                if (rr == null) {
                                    failReason = "Refactor agent returned null result.";
                                } else if (rr.message != null && !rr.message.isBlank()) {
                                    failReason = rr.message;
                                } else if (rr.newSource == null) {
                                    failReason = "Refactor agent returned null newSource.";
                                } else {
                                    failReason = "Refactor agent returned empty newSource.";
                                }

                                feedback = "Refactor produced empty or invalid output. " + failReason;
                                useFeedbackOnlyPrompt = false;
                                logStage(viewer, "REFACTOR", "failed: " + failReason);

                                // Try to give a helpful hint for the most common cause: LLM returned empty due to misconfiguration.
                                String llmHint = "";
                                try {
                                    if (LLM instanceof NoopLlmClient) {
                                        llmHint = "\nGuide: " + buildLlmSetupGuidance(project);
                                    }
                                } catch (Throwable ignored) {}

                                showNotification(project,
                                        "[Clone] Refactor failed (attempt " + attempt + "/" + maxAttempts + ") for: " + fileName +
                                                "\nReason: " + failReason +
                                                llmHint +
                                                "\nCheck the workflow console/log for details.",
                                        NotificationType.WARNING);
                                continue;
                            }

                            // Do NOT apply immediately. We will compile/test the proposed source in an isolated temp output first.
                            proposedSource = rr.newSource;
                            logStage(viewer, "REFACTOR", "proposal generated (not applied yet)");
                            if (rr.selectedPanelistId != null && !rr.selectedPanelistId.isBlank()) {
                                String summary = rr.curatorSummary == null ? "" : rr.curatorSummary;
                                logStage(
                                        viewer,
                                        "REFACTOR",
                                        "curator selected " + rr.selectedPanelistId +
                                                (summary.isBlank() ? "" : (", summary=" + summary))
                                );
                            }

                            // ===== Show proposed refactored code (for debugging / transparency) =====
                            if (viewer != null) {
                                String src = proposedSource == null ? "" : proposedSource;
                                viewer.accept("[REFACTOR_CODE] proposedSource:" + src);
                            }

                            // Also persist the full proposed source to a file under the project, so the user can open it.
                            try {
                                String basePath = project == null ? null : project.getBasePath();
                                if (basePath != null && !basePath.isBlank()) {
                                    File outDir = new File(basePath, ".anticopypaster" + File.separator + "proposals");
                                    if (!outDir.exists()) outDir.mkdirs();
                                    File outFile = new File(outDir, fileName + ".attempt" + attempt + ".proposed.java");
                                    Files.writeString(outFile.toPath(), proposedSource, StandardCharsets.UTF_8);
                                    logStage(viewer, "REFACTOR_CODE", "full proposed source saved to: " + outFile.getAbsolutePath());
                                }
                            } catch (Throwable t) {
                                logStage(viewer, "REFACTOR_CODE", "failed to save proposed source: " + t.getMessage());
                            }
                        } finally {
                            logWorkflowStageEnd(viewer);
                        }

                        // ===== Usefulness Check (run BEFORE compilation/testing) =====
                        boolean isUseful = true;
                        logWorkflowStageStart(viewer, "USEFULNESS CHECKER STAGE (attempt " + attempt + "/" + maxAttempts + ")");
                        try {
                            boolean runPsiUsefulness = true;
                            boolean llmCuratorRejected = false;
                            LlmUsefulnessEvaluator.EvaluationResult llmUsefulnessResult = null;
                            String llmUsefulnessFeedbackSection = "";

                            try {
                                LlmUsefulnessEvaluator.UsefulnessInput llmUsefulnessInput =
                                        WorkflowUsefulnessFeedbackSupport.buildLlmUsefulnessInput(
                                                project,
                                                fileName,
                                                clone,
                                                currentSource,
                                                proposedSource,
                                                watchedCloneMethods,
                                                wholeMethod != null
                                        );
                                llmUsefulnessResult = LlmUsefulnessEvaluator.evaluate(
                                        llmUsefulnessInput,
                                        usefulnessLlmCaller
                                );
                                llmUsefulnessFeedbackSection = WorkflowUsefulnessFeedbackSupport.buildLlmUsefulnessFeedbackSection(llmUsefulnessResult);

                                if (llmUsefulnessResult != null
                                        && llmUsefulnessResult.available
                                        && llmUsefulnessResult.curatorResult != null) {
                                    if (llmUsefulnessResult.useful) {
                                        runPsiUsefulness = false;
                                        String summary = llmUsefulnessResult.curatorResult.summary;
                                        logStage(
                                                viewer,
                                                "USEFUL",
                                                "LLM curator accepted proposal" +
                                                        (summary == null || summary.isBlank() ? "" : (": " + summary))
                                        );
                                    } else {
                                        llmCuratorRejected = true;
                                        logStage(
                                                viewer,
                                                "USEFUL",
                                                "LLM curator rejected proposal: reasons=" +
                                                        llmUsefulnessResult.curatorResult.reasons +
                                                        (llmUsefulnessResult.curatorResult.summary == null
                                                                || llmUsefulnessResult.curatorResult.summary.isBlank()
                                                                ? ""
                                                                : (", summary=" + llmUsefulnessResult.curatorResult.summary))
                                        );
                                    }
                                } else {
                                    String notes = llmUsefulnessResult == null ? "" : llmUsefulnessResult.notes;
                                    logStage(
                                            viewer,
                                            "USEFUL",
                                            "LLM usefulness unavailable; falling back to PSI check" +
                                                    (notes == null || notes.isBlank() ? "" : (" (" + notes + ")"))
                                    );
                                }
                            } catch (Throwable t) {
                                logStage(viewer, "USEFUL", "LLM usefulness evaluation failed: " + t.getMessage() + " (falling back to PSI check)");
                            }

                            if (runPsiUsefulness && wholeMethod != null) {
                                usefulnessChecker.UsefulnessResult urBeforeCompile =
                                        usefulnessChecker.analyze(
                                                project,
                                                fileName,
                                                currentSource,
                                                proposedSource,
                                                new usefulnessChecker.UsefulnessConfig(),
                                                targetMethodHints
                                        );

                                if (urBeforeCompile == null) {
                                    isUseful = false;
                                    String reasonsText = "[PSI_USEFULNESS_UNAVAILABLE]";
                                    String reasonDefinition = "The PSI usefulness checker could not validate the proposed source. "
                                            + "This usually means the proposal is still syntactically invalid or structurally inconsistent, "
                                            + "so it cannot proceed to compilation.";
                                    String focusedProposedCode = WorkflowUsefulnessFeedbackSupport.buildFocusedFeedbackRefactoredCode(
                                            project,
                                            fileName,
                                            currentSource,
                                            proposedSource,
                                            watchedCloneMethods
                                    );
                                    String llmFeedbackText = llmUsefulnessFeedbackSection.isBlank()
                                            ? ""
                                            : ("\n\n" + llmUsefulnessFeedbackSection);
                                    String revisionInstruction = WorkflowUsefulnessFeedbackSupport.mergeRevisionInstructions(
                                            llmUsefulnessResult != null && llmUsefulnessResult.curatorResult != null
                                                    ? llmUsefulnessResult.curatorResult.feedback
                                                    : "",
                                            "Revise the refactoring so the PSI usefulness checker can validate it. "
                                                    + "Keep the target clone methods, preserve valid Java syntax, and make the duplicated code share one extracted implementation."
                                    );
                                    if (revisionInstruction == null || revisionInstruction.isBlank()) {
                                        revisionInstruction = "Revise the refactoring so the PSI usefulness checker can validate it.";
                                    }

                                    logStage(
                                            viewer,
                                            "USEFUL",
                                            llmCuratorRejected
                                                    ? "LLM curator rejected proposal and PSI usefulness was unavailable"
                                                    : "PSI usefulness check could not validate the proposal"
                                    );
                                    showRejectedRefactorNotification(
                                            project,
                                            fileName,
                                            attempt,
                                            maxAttempts,
                                            reasonsText,
                                            reasonDefinition);

                                    feedback = """
Your previous refactoring attempt was rejected by the usefulness checks.

[NOT_USEFUL_REFACTORED_CODE]
```java
%s
```%s

[REASONS]
%s

[REASON_DEFINITION]
%s

[REVISION_INSTRUCTION]
%s
""".formatted(
                                            focusedProposedCode,
                                            llmFeedbackText,
                                            reasonsText,
                                            reasonDefinition,
                                            revisionInstruction
                                    );
                                    useFeedbackOnlyPrompt = true;
                                } else if (!urBeforeCompile.isUseful) {
                                    boolean overridden = false;
                                    try {
                                        if (containsReasonName(urBeforeCompile.reasons, "EXTRACT_METHOD_NOT_FOUND")) {
                                            String[] wrappers = parseWrapperNamesFromUsefulnessDebug(extractUsefulnessDebugText(urBeforeCompile));
                                            if (wrappers != null && wrappers.length == 2
                                                    && WorkflowUsefulnessFeedbackSupport.looksLikeValidExtractMethodDelegation(currentSource, proposedSource, wrappers[0], wrappers[1])) {
                                                overridden = true;
                                                isUseful = true;
                                                logStage(viewer, "USEFUL", "override: both wrappers delegate to the same extracted helper");
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                        // fall through
                                    }

                                    if (!overridden) {
                                        isUseful = false;
                                        String msg = "Not useful refactoring proposal: reasons=" + urBeforeCompile.reasons;
                                        logStage(viewer, "USEFUL", "PSI rejected proposal: " + msg);
                                        showRejectedRefactorNotification(
                                                project,
                                                fileName,
                                                attempt,
                                                maxAttempts,
                                                String.valueOf(urBeforeCompile.reasons),
                                                definitionForReason(urBeforeCompile.reasons));

                                        String focusedProposedCode = WorkflowUsefulnessFeedbackSupport.buildFocusedFeedbackRefactoredCode(
                                                project,
                                                fileName,
                                                currentSource,
                                                proposedSource,
                                                watchedCloneMethods
                                        );
                                        String feedbackPrompt = WorkflowUsefulnessFeedbackSupport.buildUsefulnessFeedbackPrompt(
                                                project,
                                                fileName,
                                                proposedSource,
                                                urBeforeCompile.reasons,
                                                watchedCloneMethods
                                        );
                                        String reasonsText = String.valueOf(urBeforeCompile.reasons);
                                        String reasonDefinition = definitionForReason(urBeforeCompile.reasons);
                                        String notesText = (urBeforeCompile.notes == null || urBeforeCompile.notes.isBlank())
                                                ? ""
                                                : ("\n\n[USEFULNESS_NOTES]\n" + urBeforeCompile.notes);
                                        String llmFeedbackText = llmUsefulnessFeedbackSection.isBlank()
                                                ? ""
                                                : ("\n\n" + llmUsefulnessFeedbackSection);
                                        String revisionInstruction = WorkflowUsefulnessFeedbackSupport.mergeRevisionInstructions(
                                                llmUsefulnessResult != null && llmUsefulnessResult.curatorResult != null
                                                        ? llmUsefulnessResult.curatorResult.feedback
                                                        : "",
                                                feedbackPrompt == null ? "" : feedbackPrompt
                                        );
                                        if (revisionInstruction == null || revisionInstruction.isBlank()) {
                                            revisionInstruction = "Restore all target clone methods, keep the extracted helper, and make every original target clone call it.";
                                        }

                                        feedback = """
Your previous refactoring attempt was rejected by the usefulness checks.

[NOT_USEFUL_REFACTORED_CODE]
```java
%s
```%s

[REASONS]
%s

[REASON_DEFINITION]
%s%s

[REVISION_INSTRUCTION]
%s
""".formatted(
                                                    focusedProposedCode,
                                                    llmFeedbackText,
                                                    reasonsText,
                                                    reasonDefinition == null ? "" : reasonDefinition,
                                                    notesText,
                                                    revisionInstruction
                                            );
                                        useFeedbackOnlyPrompt = true;
                                    }
                                } else {
                                    if (llmCuratorRejected) {
                                        logStage(viewer, "USEFUL", "override: PSI usefulness accepted proposal after LLM curator rejection");
                                    } else {
                                        logStage(viewer, "USEFUL", "ok (before compile): score=" + urBeforeCompile.score +
                                                (urBeforeCompile.notes == null || urBeforeCompile.notes.isBlank() ? "" : (", notes=" + urBeforeCompile.notes)));
                                    }
                                }
                            } else if (runPsiUsefulness) {
                                detection.CloneRange rA = (clone.ranges != null && clone.ranges.size() > 0) ? clone.ranges.get(0) : null;
                                detection.CloneRange rB = (clone.ranges != null && clone.ranges.size() > 1) ? clone.ranges.get(1) : null;

                                FragmentUsefulnessAnalyzer.LineRange lrA = (rA == null)
                                        ? new FragmentUsefulnessAnalyzer.LineRange(1, 1)
                                        : new FragmentUsefulnessAnalyzer.LineRange(rA.startLine, rA.endLine);
                                FragmentUsefulnessAnalyzer.LineRange lrB = (rB == null)
                                        ? new FragmentUsefulnessAnalyzer.LineRange(1, 1)
                                        : new FragmentUsefulnessAnalyzer.LineRange(rB.startLine, rB.endLine);

                                String[] ab = WorkflowCloneSelectionSupport.extractCloneCodeABFromReason(clone.reason);
                                java.util.List<String> cloneCodes = WorkflowCloneSelectionSupport.getDetectedCloneCodes(clone, currentSource);
                                String codeA = cloneCodes.size() > 0 ? WorkflowCloneSelectionSupport.firstNonBlank(cloneCodes.get(0), ab[0]) : ab[0];
                                String codeB = cloneCodes.size() > 1 ? WorkflowCloneSelectionSupport.firstNonBlank(cloneCodes.get(1), ab[1]) : ab[1];

                                // Fallbacks: if detection didn't embed code blocks, use the pasted snippet as A.
                                if (codeA == null || codeA.isBlank()) codeA = pastedSnippet == null ? "" : pastedSnippet;
                                if ((codeB == null || codeB.isBlank()) && cloneCodes.size() > 1) codeB = cloneCodes.get(1);

                                try {
                                    FragmentUsefulnessAnalyzer.UsefulnessResult frBeforeCompile =
                                            FragmentUsefulnessAnalyzer.analyze(
                                                    project,
                                                    fileName,
                                                    currentSource,
                                                    proposedSource,
                                                    lrA,
                                                    lrB,
                                                    codeA,
                                                    codeB,
                                                    new FragmentUsefulnessAnalyzer.UsefulnessConfig()
                                            );

                                    if (frBeforeCompile == null || !frBeforeCompile.isUseful) {
                                        isUseful = false;
                                        String strategyText = frBeforeCompile == null
                                                ? "UNKNOWN"
                                                : String.valueOf(frBeforeCompile.strategy);
                                        String reasonsText = frBeforeCompile == null
                                                ? "[ANALYZER_FALLBACK]"
                                                : String.valueOf(frBeforeCompile.reasons);
                                        String notesText = (frBeforeCompile == null || frBeforeCompile.notes == null || frBeforeCompile.notes.isBlank())
                                                ? ""
                                                : ("\n\n[USEFULNESS_NOTES]\n" + frBeforeCompile.notes);
                                        String reasonDefinition = frBeforeCompile == null
                                                ? "The fragment usefulness analyzer could not confidently validate this refactoring, "
                                                + "so the proposal was rejected conservatively."
                                                : definitionForReason(frBeforeCompile.strategy);
                                        String msg = "Not useful refactoring proposal: strategy=" + strategyText +
                                                ", reasons=" + reasonsText;
                                        logStage(viewer, "USEFUL", "PSI rejected fragment proposal: " + msg);
                                        showRejectedRefactorNotification(
                                                project,
                                                fileName,
                                                attempt,
                                                maxAttempts,
                                                strategyText,
                                                reasonDefinition);

                                        String focusedProposedCode = WorkflowUsefulnessFeedbackSupport.buildFocusedFeedbackRefactoredCode(
                                                project,
                                                fileName,
                                                currentSource,
                                                proposedSource,
                                                watchedCloneMethods
                                        );
                                        String llmFeedbackText = llmUsefulnessFeedbackSection.isBlank()
                                                ? ""
                                                : ("\n\n" + llmUsefulnessFeedbackSection);
                                        String fragmentRevisionInstruction = WorkflowUsefulnessFeedbackSupport.mergeRevisionInstructions(
                                                llmUsefulnessResult != null && llmUsefulnessResult.curatorResult != null
                                                        ? llmUsefulnessResult.curatorResult.feedback
                                                        : "",
                                                "Your refactoring is not useful. You must actually remove or significantly reduce the duplicated fragment in BOTH places. "
                                                        + "Avoid incomplete refactoring, deleting one side, or delegating only one side."
                                        );
                                        if (fragmentRevisionInstruction == null || fragmentRevisionInstruction.isBlank()) {
                                            fragmentRevisionInstruction = "Your refactoring is not useful. You must actually remove or significantly reduce the duplicated fragment in BOTH places.";
                                        }
                                        feedback = """
Your previous refactoring attempt was rejected by the usefulness checks.

[NOT_USEFUL_REFACTORED_CODE]
```java
%s
```%s

[REASONS]
%s

[STRATEGY]
%s

[REASON_DEFINITION]
%s%s

[REVISION_INSTRUCTION]
%s
""".formatted(
                                                    focusedProposedCode,
                                                    llmFeedbackText,
                                                    reasonsText,
                                                    strategyText,
                                                    reasonDefinition == null ? "" : reasonDefinition,
                                                    notesText,
                                                    fragmentRevisionInstruction
                                            );
                                        useFeedbackOnlyPrompt = true;
                                    } else {
                                        if (llmCuratorRejected) {
                                            logStage(viewer, "USEFUL", "override: PSI fragment usefulness accepted proposal after LLM curator rejection");
                                        } else {
                                            logStage(viewer, "USEFUL", "ok(FRAGMENT, before compile): strategy=" + frBeforeCompile.strategy + ", score=" + frBeforeCompile.score +
                                                    (frBeforeCompile.notes == null || frBeforeCompile.notes.isBlank() ? "" : (", notes=" + frBeforeCompile.notes)));
                                        }
                                    }

                                    if (viewer != null) {
                                        logStage(viewer, "USEFUL", "fragment ranges(before compile): A=" + lrA + ", B=" + lrB +
                                                ", codeA.preview=" + previewOneLine(codeA, 120) +
                                                ", codeB.preview=" + previewOneLine(codeB, 120));
                                    }
                                } catch (Throwable t) {
                                    isUseful = false;
                                    String focusedProposedCode = WorkflowUsefulnessFeedbackSupport.buildFocusedFeedbackRefactoredCode(
                                            project,
                                            fileName,
                                            currentSource,
                                            proposedSource,
                                            watchedCloneMethods
                                    );
                                    String llmFeedbackText = llmUsefulnessFeedbackSection.isBlank()
                                            ? ""
                                            : ("\n\n" + llmUsefulnessFeedbackSection);
                                    String notesText = "\n\n[USEFULNESS_NOTES]\n" + t.getMessage();
                                    String revisionInstruction = WorkflowUsefulnessFeedbackSupport.mergeRevisionInstructions(
                                            llmUsefulnessResult != null && llmUsefulnessResult.curatorResult != null
                                                    ? llmUsefulnessResult.curatorResult.feedback
                                                    : "",
                                            "Revise the refactoring so the fragment usefulness analyzer can validate it. "
                                                    + "Keep both clone sites, preserve valid Java syntax, and extract the shared fragment cleanly."
                                    );
                                    if (revisionInstruction == null || revisionInstruction.isBlank()) {
                                        revisionInstruction = "Revise the refactoring so the fragment usefulness analyzer can validate it.";
                                    }

                                    logStage(viewer, "USEFUL", "fragment usefulness check failed (before compile): " + t.getMessage());
                                    showRejectedRefactorNotification(
                                            project,
                                            fileName,
                                            attempt,
                                            maxAttempts,
                                            "FRAGMENT_ANALYZER_ERROR",
                                            "The fragment usefulness analyzer failed before compilation.");

                                    feedback = """
Your previous refactoring attempt was rejected by the usefulness checks.

[NOT_USEFUL_REFACTORED_CODE]
```java
%s
```%s

[REASONS]
[FRAGMENT_USEFULNESS_ANALYZER_ERROR]

[REASON_DEFINITION]
The fragment usefulness analyzer failed before compilation.%s

[REVISION_INSTRUCTION]
%s
""".formatted(
                                            focusedProposedCode,
                                            llmFeedbackText,
                                            notesText,
                                            revisionInstruction
                                    );
                                    useFeedbackOnlyPrompt = true;
                                }
                            }
                        } finally {
                            logWorkflowStageEnd(viewer);
                        }

                        if (!isUseful) {
                            // Do not compile/test/apply; retry with feedback.
                            javaBuildSupport.clearPatchedClassesDir();
                            continue;
                        }
                        useFeedbackOnlyPrompt = false;
                        // ===== End usefulness check =====

                        String changedMethodBeforeCompile = WorkflowMethodSnapshotSupport.findModifiedCloneMethod(project, vf, watchedCloneMethods);
                        if (changedMethodBeforeCompile != null) {
                            logStage(viewer, "WATCH", "stopped before compile because cloned method changed: " + changedMethodBeforeCompile);
                            showNotification(project,
                                    "[Clone] Stopped because a cloned method was modified by the user: " + changedMethodBeforeCompile,
                                    NotificationType.WARNING);
                            cancelWorkflow(viewer);
                            return;
                        }
                        String ideCp;
                        logWorkflowStageStart(viewer, "COMPILATION STAGE (attempt " + attempt + "/" + maxAttempts + ")");
                        try {
                            // Compile the proposed source to a temp classes dir, without touching the original file.
                            ideCp = javaBuildSupport.buildProjectClasspathFromIde();
                            String ideSourcePath = javaBuildSupport.buildProjectSourcepathFromIde();
                            ideCp = javaBuildSupport.buildCompileClasspathWithSourceRoots(ideCp);
                            if (viewer != null) {
                                String cpPreview = ideCp == null ? "" : ideCp;
                                if (cpPreview.length() > 4000) {
                                    cpPreview = cpPreview.substring(0, 4000) + "\n...<truncated>...";
                                }
                                viewer.accept("[COMPILE] ide classpath:\n" + cpPreview);

                                String spPreview = ideSourcePath == null ? "" : ideSourcePath;
                                if (spPreview.length() > 4000) {
                                    spPreview = spPreview.substring(0, 4000) + "\n...<truncated>...";
                                }
                                viewer.accept("[COMPILE] ide sourcepath:\n" + spPreview);
                            }
                            WorkflowJavaBuildSupport.CompileAttempt proposalCompileAttempt = null;
                            String compileLog;
                            try {
                                throwIfCancelled(CloneRefactorWorkflow::isCancelled);
                                proposalCompileAttempt = javaBuildSupport.compileProposedSourceToTempAttempt(ioFile, fileName, proposedSource, ideCp);
                                compileLog = proposalCompileAttempt.toCompileLog();
                                if (proposalCompileAttempt.success) {
                                    javaBuildSupport.setPatchedClassesDir(proposalCompileAttempt.outputDir);
                                } else {
                                    javaBuildSupport.clearPatchedClassesDir();
                                }
                            } catch (Exception ce) {
                                javaBuildSupport.clearPatchedClassesDir();
                                compileLog = "BUILD FAILED\n" + (ce.getMessage() == null ? "" : ce.getMessage());
                            }

                            if (viewer != null) {
                                String cl = compileLog == null ? "null" : compileLog;
                                if (cl.length() > 4000) cl = cl.substring(0, 4000) + "\n...<truncated>...";
                                viewer.accept("[COMPILE] raw output:\n" + cl);
                            }

                            compilation.CompileResult cr = compileAgent.analyze(fileName, compileLog);

                            if (cr == null || !"compile_ok".equals(cr.status)) {
                                if (!baselineCompileChecked) {
                                    baselineCompileChecked = true;
                                    try {
                                        throwIfCancelled(CloneRefactorWorkflow::isCancelled);
                                        WorkflowJavaBuildSupport.CompileAttempt baselineAttempt =
                                                javaBuildSupport.compileProposedSourceToTempAttempt(ioFile, fileName, currentSource, ideCp);
                                        baselineCompileLog = baselineAttempt.toCompileLog();
                                    } catch (Exception bce) {
                                        baselineCompileLog = "BUILD FAILED\n" + (bce.getMessage() == null ? "" : bce.getMessage());
                                    }
                                    baselineCompileResult = compileAgent.analyze(fileName, baselineCompileLog);
                                    logStage(viewer, "COMPILE", "baseline check: " +
                                            (baselineCompileResult == null ? "unknown" : baselineCompileResult.summary));
                                }

                                compilation.CompileResult adjustedCompileResult =
                                        WorkflowUsefulnessFeedbackSupport.ignoreBaselineCompileErrors(fileName, cr, baselineCompileResult);
                                if (adjustedCompileResult != null
                                        && "compile_ok".equals(adjustedCompileResult.status)
                                        && proposalCompileAttempt != null
                                        && proposalCompileAttempt.outputDir != null) {
                                    javaBuildSupport.setPatchedClassesDir(proposalCompileAttempt.outputDir);
                                    cr = adjustedCompileResult;
                                    logStage(viewer, "COMPILE", adjustedCompileResult.summary);
                                } else {
                                    cr = adjustedCompileResult == null ? cr : adjustedCompileResult;
                                }
                            }

                            if (cr == null || !"compile_ok".equals(cr.status)) {
                                feedback = cr == null ? "Compilation failed." : cr.summary;
                                useFeedbackOnlyPrompt = false;
                                logStage(viewer, "COMPILE", "failed: " + feedback);
                                showNotification(project, "[Clone] Compilation failed (attempt " + attempt + ") for: " + fileName + "\n" + feedback, NotificationType.ERROR);
                                continue;
                            }

                            logStage(viewer, "COMPILE", "ok (isolated)");
                            showNotification(project, "Compilation successful: Ready to run (attempt " + attempt + ") for: " + fileName, NotificationType.INFORMATION);
                        } finally {
                            logWorkflowStageEnd(viewer);
                        }

                        /* ===== Test ===== */
                        boolean testsPassed = false;
                        logWorkflowStageStart(viewer, "TESTING STAGE (attempt " + attempt + "/" + maxAttempts + ")");
                        try {
                            // Resolve target FQN from the proposed source (PSI still reflects the original file until we apply).
                            String targetFqn = javaBuildSupport.resolvePrimaryClassFqn(proposedSource, fileName);
                            if (targetFqn == null) targetFqn = "";

                            if (targetFqn.isBlank()) {
                                // Fallback #1: regex-based parsing from the in-memory source we just produced
                                String f1 = javaBuildSupport.resolvePrimaryClassFqn(currentSource, fileName);
                                if (f1 != null && !f1.isBlank()) {
                                    targetFqn = f1;
                                    logStage(viewer, "TEST", "PSI FQN empty; fallback to regex(in-memory): " + targetFqn);
                                } else {
                                    logStage(viewer, "TEST", "PSI FQN empty; regex(in-memory) also empty");
                                }
                            }

                            if (targetFqn.isBlank()) {
                                // Fallback #2: re-read the on-disk file after write (sometimes source roots/PSI lag)
                                try {
                                    String onDisk = Files.readString(ioFile.toPath(), StandardCharsets.UTF_8);
                                    String f2 = javaBuildSupport.resolvePrimaryClassFqn(onDisk, fileName);
                                    if (f2 != null && !f2.isBlank()) {
                                        targetFqn = f2;
                                        logStage(viewer, "TEST", "Still empty; fallback to regex(on-disk): " + targetFqn);
                                    } else {
                                        logStage(viewer, "TEST", "regex(on-disk) also empty");
                                    }
                                } catch (Throwable t) {
                                    logStage(viewer, "TEST", "Failed to read on-disk source for FQN fallback: " + t.getMessage());
                                }
                            }

                            if (targetFqn.isBlank()) {
                                // Last resort: use file basename (may still fail if package exists, but avoids empty targetClass)
                                String base = fileName != null && fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
                                targetFqn = (base == null ? "" : base.trim());
                                logStage(viewer, "TEST", "All FQN resolution failed; last resort basename: " + targetFqn);
                            }

                            logStage(viewer, "TEST", "targetFqn=" + (targetFqn == null ? "null" : targetFqn));
                            javaBuildSupport.setTargetFqn(targetFqn);

                            if (targetFqn == null || targetFqn.isBlank()) {
                                feedback = "Test skipped: target class FQN could not be resolved.";
                                useFeedbackOnlyPrompt = false;
                                showNotification(project, "[Clone] Test skipped (attempt " + attempt + ") for: " + fileName + " (cannot resolve class FQN)", NotificationType.WARNING);
                                continue;
                            }

                            testing.TestRunRequest treq =
                                    new testing.TestRunRequest(
                                            project.getBasePath(),
                                            targetFqn,
                                            null,
                                            false
                                    );

                            String changedMethodBeforeTest = WorkflowMethodSnapshotSupport.findModifiedCloneMethod(project, vf, watchedCloneMethods);
                            if (changedMethodBeforeTest != null) {
                                logStage(viewer, "WATCH", "stopped before test because cloned method changed: " + changedMethodBeforeTest);
                                showNotification(project,
                                        "[Clone] Stopped because a cloned method was modified by the user: " + changedMethodBeforeTest,
                                        NotificationType.WARNING);
                                cancelWorkflow(viewer);
                                return;
                            }

                            throwIfCancelled(CloneRefactorWorkflow::isCancelled);
                            testing.TestResult tr =
                                    testAgent.runAndSummarize(
                                            treq,
                                            javaBuildSupport::runTests,
                                            llmCaller,
                                            originalSource,
                                            currentSource
                                    );
                            if (viewer != null && tr != null) {
                                String raw = tr.raw == null ? "" : tr.raw;
                                if (raw.length() > 4000) raw = raw.substring(0, 4000) + "\n...<truncated>...";
                                viewer.accept("[TEST] raw output:\n" + raw);
                            }

                            if (tr != null && "tests_passed".equals(tr.status)) {
                                logStage(viewer, "TEST", "passed");
                                testsPassed = true;
                            } else {
                                feedback = tr == null ? "Tests failed." :
                                        (tr.summary != null ? tr.summary : tr.raw);
                                useFeedbackOnlyPrompt = false;

                                logStage(viewer, "TEST", "failed");
                                showNotification(project, "[Clone] Tests failed (attempt " + attempt + ") for: " + fileName, NotificationType.WARNING);
                            }
                        } finally {
                            logWorkflowStageEnd(viewer);
                        }

                        if (testsPassed) {
                            String changedMethodBeforeApply = WorkflowMethodSnapshotSupport.findModifiedCloneMethod(project, vf, watchedCloneMethods);
                            if (changedMethodBeforeApply != null) {
                                logStage(viewer, "WATCH", "stopped before apply because cloned method changed: " + changedMethodBeforeApply);
                                showNotification(project,
                                        "[Clone] Stopped because a cloned method was modified by the user: " + changedMethodBeforeApply,
                                        NotificationType.WARNING);
                                cancelWorkflow(viewer);
                                return;
                            }
                            // Now that compile+test passed, ask user whether to apply the refactor to the real file.
                            boolean applyNow = showDiffAndConfirmApply(project, fileName, currentSource, proposedSource);
                            if (applyNow) {
                                CloneUsageStatistics.getInstance(project).refactoringAccepted();
                                currentSource = proposedSource;
                                Files.writeString(ioFile.toPath(), currentSource, StandardCharsets.UTF_8);
                                logStage(viewer, "REFACTOR", "applied after verification");
                                showNotification(project, "[Clone] Tests passed. Refactor applied for: " + fileName, NotificationType.INFORMATION);
                            } else {
                                CloneUsageStatistics.getInstance(project).refactoringCancelled();
                                logStage(viewer, "REFACTOR", "verified but not applied (user cancelled)");
                                showNotification(project, "[Clone] Tests passed but changes were not applied (user cancelled): " + fileName, NotificationType.WARNING);
                            }

                            logStage(viewer, "WORKFLOW", "SUCCESS");
                            // Clear patched classes dir for subsequent runs
                            javaBuildSupport.clearPatchedClassesDir();
                            return;
                        }
                }

                logStage(viewer, "WORKFLOW", "FAILED after " + maxAttempts + " retries");
                showNotification(project, "[Clone] Workflow failed after " + maxAttempts + " retries for: " + vf.getName(), NotificationType.ERROR);

            } catch (Exception e) {
                if (isCancelled() || Thread.currentThread().isInterrupted()
                        || (e.getMessage() != null && e.getMessage().contains("CANCELLED"))) {
                    showNotification(project, "Operation cancelled by user.", NotificationType.WARNING);
                    return;
                }
                e.printStackTrace();
                showNotification(project, "[Clone] Workflow crashed: " + e.getMessage(), NotificationType.ERROR);
            } finally {
                if (trackedDocument != null && cloneMethodChangeListener != null) {
                    try {
                        trackedDocument.removeDocumentListener(cloneMethodChangeListener);
                    } catch (Throwable ignored) {}
                }
                _CURRENT_PROCESS.set(null);
                if (_WORKFLOW_RUN_ID.get() == runId) {
                    _CURRENT_WORKFLOW_THREAD.compareAndSet(Thread.currentThread(), null);
                }
                closeQuietly(logWriter);
            }
        });
    }


    // NOTE: RAG retrieval uses clone code (via buildRefactorRagQueryText) when available; ranges here are only for agent context.
    private static refactoring.DetectedClone convertClone(detection.DetectedClone c) {
        String representative = "";
        if (c != null) {
            java.util.List<String> cloneCodes = WorkflowCloneSelectionSupport.getDetectedCloneCodes(c, null);
            if (!cloneCodes.isEmpty() && cloneCodes.get(0) != null && !cloneCodes.get(0).isBlank()) {
                representative = cloneCodes.get(0);
            } else if (c.cloneCodeA != null && !c.cloneCodeA.isBlank()) {
                representative = c.cloneCodeA;
            } else if (c.cloneCodeB != null && !c.cloneCodeB.isBlank()) {
                representative = c.cloneCodeB;
            }
        }
        return new refactoring.DetectedClone(
                c.id,
                c.ranges.stream()
                        .map(r -> new refactoring.CloneRange(r.startLine, r.endLine))
                        .toList(),
                c.refactorType,
                c.reason,
                representative,
                WorkflowCloneSelectionSupport.getDetectedCloneCodes(c, null),
                c.cloneCodeA,
                c.cloneCodeB
        );
    }


    // ===== Ablation toggle: set to false to disable RAG retrieval entirely =====
    private static final boolean ENABLE_REFACTOR_RAG = true;

    /**
     * Build a query string for refactor RAG retrieval.
     *
     * Priority:
     *  1) If detection embeds clone code into `clone.reason` using tags, extract it.
     *  2) Otherwise, best-effort extract by the first reported range (fallback only).
     *
     * NOTE: You said ranges may be inaccurate; this method only uses ranges as a fallback.
     */
    private static String buildRefactorRagQueryText(String fullSource, detection.DetectedClone clone) {
        if (!ENABLE_REFACTOR_RAG) return ""; // Ablation: disable RAG
        if (clone == null) return "";

        // 1) Try to extract clone code embedded in the reason field (recommended).
        // Expected formats (either is fine):
        //   [CLONE_CODE] ... [/CLONE_CODE]
        //   [CLONE_CODE_A] ... [/CLONE_CODE_A] and [CLONE_CODE_B] ... [/CLONE_CODE_B]
        try {
            String reason = clone.reason;
            if (reason != null && !reason.isBlank()) {
                java.util.regex.Matcher m1 = java.util.regex.Pattern
                        .compile("(?s)\\[CLONE_CODE\\](.*?)\\[/CLONE_CODE\\]")
                        .matcher(reason);
                if (m1.find()) {
                    String code = m1.group(1);
                    if (code != null && !code.isBlank()) return code.strip();
                }

                java.util.regex.Matcher ma = java.util.regex.Pattern
                        .compile("(?s)\\[CLONE_CODE_A\\](.*?)\\[/CLONE_CODE_A\\]")
                        .matcher(reason);
                java.util.regex.Matcher mb = java.util.regex.Pattern
                        .compile("(?s)\\[CLONE_CODE_B\\](.*?)\\[/CLONE_CODE_B\\]")
                        .matcher(reason);

                String a = ma.find() ? ma.group(1) : null;
                String b = mb.find() ? mb.group(1) : null;

                if (a != null && !a.isBlank() && b != null && !b.isBlank()) {
                    return (a.strip() + "\n\n" + b.strip()).strip();
                }
                if (a != null && !a.isBlank()) return a.strip();
                if (b != null && !b.isBlank()) return b.strip();
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 2) Fallback: use the first range to slice the in-memory source.
        // This is ONLY a fallback because you said ranges may be inaccurate.
        try {
            if (fullSource == null || fullSource.isBlank()) return "";
            if (clone.ranges == null || clone.ranges.isEmpty()) return "";

            detection.CloneRange r = clone.ranges.get(0);
            int start = Math.max(1, r.startLine);
            int end = Math.max(start, r.endLine);

            String[] lines = fullSource.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
            int sIdx = Math.min(lines.length, Math.max(1, start)) - 1;
            int eIdx = Math.min(lines.length, Math.max(1, end)) - 1;
            if (sIdx > eIdx) return "";

            StringBuilder sb = new StringBuilder();
            for (int i = sIdx; i <= eIdx; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().strip();
        } catch (Throwable ignored) {
            return "";
        }
    }

}
