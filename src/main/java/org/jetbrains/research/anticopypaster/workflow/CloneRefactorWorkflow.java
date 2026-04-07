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
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showNotification;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.teeViewer;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.throwIfCancelled;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.agents.refactoring;
import org.jetbrains.research.anticopypaster.agents.compilation;
import org.jetbrains.research.anticopypaster.agents.testing;
import org.jetbrains.research.anticopypaster.agents.usefulnessChecker;
import org.jetbrains.research.anticopypaster.agents.FragmentUsefulnessAnalyzer;
import org.jetbrains.research.anticopypaster.agents.PsiFallbackCloneDetector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ModalityState;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import javax.swing.*;
import org.jetbrains.research.anticopypaster.llm.LlmClient;
import org.jetbrains.research.anticopypaster.llm.LlmClientFactory;
import org.jetbrains.research.anticopypaster.llm.NoopLlmClient;
import org.jetbrains.research.anticopypaster.rag.RagService;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

public final class CloneRefactorWorkflow {
    private static final String REFACTOR_RAG_DB_RESOURCE = "rag/refactor_database.csv";

    // RAG retrieval tuning
    private static final int REFACTOR_RAG_TOP_K = 5;
    private static final int REFACTOR_RAG_MAX_CHARS = 8000;

    // Refactor proposal preview (console)
    private static final int REFACTOR_PROPOSAL_PREVIEW_CHARS = 12000;

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

    private static String readCurrentSource(VirtualFile vf, File ioFile) throws IOException {
        // Prefer in-memory content (includes unsaved edits) if available.
        try {
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) {
                return doc.getText();
            }
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
                PsiMethod wholeMethod = findWholeMethodCoveredBySnippet(project, vf, originalSource, pastedSnippet);

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
                    showNotification(project,
                            "[Clone] LLM is not configured (missing/invalid provider settings or API key). LLM calls will return empty and detection will always be 'no clones'. Configure provider/model/API key in Settings.",
                            NotificationType.ERROR);
                }

                detection detectionAgent = new detection();
                refactoring refactorAgent = new refactoring();
                compilation compileAgent = new compilation();
                testing testAgent = new testing();

                Function<String, String> llmCaller = prompt -> {
                    try {
                        String resp = LLM.complete(prompt);
                        String full = resp == null ? "" : resp;
                        String preview = resp == null ? "null" : resp.strip();
                        if (preview.length() > 300) preview = preview.substring(0, 300) + "...";
                        logStage(viewer, "LLM", "response preview: " + preview.replace("\n", "\\n"));
                        logStage(viewer, "LLM", "full response begin");
                        viewer.accept(full);
                        logStage(viewer, "LLM", "full response end");
                        return full;
                    } catch (Exception e) {
                        logStage(viewer, "LLM", "exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        showNotification(project, "[Clone] LLM call failed: " + e.getMessage(), NotificationType.ERROR);
                        return "";
                    }
                };

                /* ---------- Detection ---------- */
                detection.DetectionResult det =
                        detectionAgent.detect(
                                project,
                                fileName,
                                originalSource,
                                pastedSnippet,
                                llmCaller
                        );

                try {
                    if (det != null) {
                        java.nio.file.Path nicadOut =
                                java.nio.file.Path.of(project.getBasePath(),
                                        ".anticopypaster",
                                        "nicad",
                                        fileName + ".nicad.xml");

                        java.nio.file.Files.createDirectories(nicadOut.getParent());

                        detectionAgent.saveAsNiCadXml(det, vf.getPath(), nicadOut);

//                        logStage(viewer, "DETECTION", "NiCad file saved: " + nicadOut);
                    }
                } catch (Exception e) {
                    logStage(viewer, "DETECTION", "Failed to save NiCad XML: " + e.getMessage());
                }


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

                                    int sLine = getIntField(cc, "startLine", "start", "fromLine", "start_line", "startline");
                                    int eLine = getIntField(cc, "endLine", "end", "toLine", "end_line", "endline");
                                    if (sLine <= 0) sLine = 1;
                                    if (eLine <= 0) eLine = sLine;

                                    detection.CloneRange range = new detection.CloneRange();
                                    setIntField(range, sLine, "startLine", "start", "fromLine", "start_line", "startline");
                                    setIntField(range, eLine, "endLine", "end", "toLine", "end_line", "endline");
                                    psiClone.ranges.add(range);
                                    String cloneCode = getStringField(cc, "cloneCode", "code", "snippet", "text");
                                    psiClone.cloneCodes.add(cloneCode == null ? "" : cloneCode);
                                }
                                if (psiClone.cloneCodes.size() > 0) psiClone.cloneCodeA = psiClone.cloneCodes.get(0);
                                if (psiClone.cloneCodes.size() > 1) psiClone.cloneCodeB = psiClone.cloneCodes.get(1);

                                det = new detection.DetectionResult();
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

                det.clones = resolveDetectedCloneRangesWithPsi(project, vf, originalSource, det.clones, viewer);
                det.clones = mergeOverlappingDetectedClones(originalSource, det.clones, viewer);

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

                detection.DetectedClone clone = chooseCloneToRefactor(project, vf, det.clones, viewer);
                if (clone == null) {
                    showNotification(project, "[Clone] Clone selection cancelled for: " + fileName, NotificationType.WARNING);
                    return;
                }

                clone = chooseCloneRangesToRefactor(project, vf, originalSource, clone, viewer);
                if (clone == null) {
                    showNotification(project, "[Clone] Clone range selection cancelled for: " + fileName, NotificationType.WARNING);
                    return;
                }
                showNotification(project, "[Clone] Clones detected in: " + fileName, NotificationType.INFORMATION);

                final java.util.List<CloneMethodSnapshot> watchedCloneMethods = captureCloneMethodSnapshots(project, vf, clone, viewer);
                trackedDocument = FileDocumentManager.getInstance().getDocument(vf);
                if (trackedDocument != null && watchedCloneMethods != null && !watchedCloneMethods.isEmpty()) {
                    final java.util.List<CloneMethodSnapshot> listenerSnapshots = watchedCloneMethods;
                    cloneMethodChangeListener = new DocumentListener() {
                        @Override
                        public void documentChanged(DocumentEvent event) {
                            if (isCancelled()) return;
                            String changedMethod = findModifiedCloneMethod(project, vf, listenerSnapshots);
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

                /* ---------- Retry Loop ---------- */
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        if (isCancelled()) {
                            showNotification(project, "[Clone] Cancelled by user.", NotificationType.WARNING);
                            return;
                        }

                    String changedMethodAtAttemptStart = findModifiedCloneMethod(project, vf, watchedCloneMethods);
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
                                    llmCaller
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
                                    llmCaller
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
                                    llmHint = "\nHint: LLM is not configured (check provider/model/API key in Settings).";
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
                        String proposedSource = rr.newSource;
                        logStage(viewer, "REFACTOR", "proposal generated (not applied yet)");

                        // ===== Show proposed refactored code (for debugging / transparency) =====
                        if (viewer != null) {
                            String src = proposedSource == null ? "" : proposedSource;
//                            int maxChars = REFACTOR_PROPOSAL_PREVIEW_CHARS; // keep console usable
//                            String shown = src.length() > maxChars ? (src.substring(0, maxChars) + "\n...<truncated>...") : src;
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

                        // ===== Usefulness Check (run BEFORE compilation/testing) =====
                        // If the refactoring is not useful, we treat the attempt as failed and retry.
                        boolean isUseful = true;

	                        if (wholeMethod != null) {
	                            java.util.List<usefulnessChecker.TargetMethodHint> targetMethodHints =
	                                    buildUsefulnessTargetHints(watchedCloneMethods);
	                            usefulnessChecker.UsefulnessResult urBeforeCompile =
	                                    usefulnessChecker.analyze(
	                                            project,
	                                            fileName,
	                                            currentSource,
	                                            proposedSource,
	                                            new usefulnessChecker.UsefulnessConfig(),
	                                            targetMethodHints
	                                    );

                            if (urBeforeCompile != null && !urBeforeCompile.isUseful) {
                                boolean overridden = false;
                                try {
                                    if (containsReasonName(urBeforeCompile.reasons, "EXTRACT_METHOD_NOT_FOUND")) {
                                        String[] wrappers = parseWrapperNamesFromUsefulnessDebug(extractUsefulnessDebugText(urBeforeCompile));
                                        if (wrappers != null && wrappers.length == 2
                                                && looksLikeValidExtractMethodDelegation(currentSource, proposedSource, wrappers[0], wrappers[1])) {
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
                                logStage(viewer, "USEFUL", "Not useful refactoring proposal" + msg);
	                                showNotification(project,
	                                        "[Clone] Refactor NOT recommended (attempt " + attempt + ")\n" +
	                                                "for: " + fileName + "\n \n" +
	                                                "Reason:\n" +
	                                                urBeforeCompile.reasons + "\n" +
	                                                definitionForReason(urBeforeCompile.reasons),
	                                        NotificationType.WARNING);

	                                String focusedProposedCode = buildFocusedFeedbackRefactoredCode(
	                                        project,
	                                        fileName,
	                                        currentSource,
	                                        proposedSource,
	                                        watchedCloneMethods
	                                );
	                                String feedbackPrompt = buildUsefulnessFeedbackPrompt(
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

                                feedback = """
Your previous refactoring attempt was rejected by the usefulness checker.

	[NOT_USEFUL_REFACTORED_CODE]
	```java
	%s
	```

[REASONS]
%s

[REASON_DEFINITION]
%s%s

	[REVISION_INSTRUCTION]
	%s
	""".formatted(
	                                        focusedProposedCode,
	                                        reasonsText,
	                                        reasonDefinition == null ? "" : reasonDefinition,
	                                        notesText,
	                                        feedbackPrompt == null ? "" : feedbackPrompt
	                                );
                                    useFeedbackOnlyPrompt = true;
                            }
                            } else if (urBeforeCompile != null) {
                                logStage(viewer, "USEFUL", "ok (before compile): score=" + urBeforeCompile.score +
                                        (urBeforeCompile.notes == null || urBeforeCompile.notes.isBlank() ? "" : (", notes=" + urBeforeCompile.notes)));
                            }

                        } else {
                            // Fragment path: use the fragment-aware analyzer.
                            try {
                                detection.CloneRange rA = (clone.ranges != null && clone.ranges.size() > 0) ? clone.ranges.get(0) : null;
                                detection.CloneRange rB = (clone.ranges != null && clone.ranges.size() > 1) ? clone.ranges.get(1) : null;

                                FragmentUsefulnessAnalyzer.LineRange lrA = (rA == null)
                                        ? new FragmentUsefulnessAnalyzer.LineRange(1, 1)
                                        : new FragmentUsefulnessAnalyzer.LineRange(rA.startLine, rA.endLine);
                                FragmentUsefulnessAnalyzer.LineRange lrB = (rB == null)
                                        ? new FragmentUsefulnessAnalyzer.LineRange(1, 1)
                                        : new FragmentUsefulnessAnalyzer.LineRange(rB.startLine, rB.endLine);

                                String[] ab = extractCloneCodeABFromReason(clone.reason);
                                java.util.List<String> cloneCodes = getDetectedCloneCodes(clone, currentSource);
                                String codeA = cloneCodes.size() > 0 ? firstNonBlank(cloneCodes.get(0), ab[0]) : ab[0];
                                String codeB = cloneCodes.size() > 1 ? firstNonBlank(cloneCodes.get(1), ab[1]) : ab[1];

                                // Fallbacks: if detection didn't embed code blocks, use the pasted snippet as A.
                                if (codeA == null || codeA.isBlank()) codeA = pastedSnippet == null ? "" : pastedSnippet;
                                if ((codeB == null || codeB.isBlank()) && cloneCodes.size() > 1) codeB = cloneCodes.get(1);

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
                                            ? "The fragment usefulness analyzer could not confidently validate this refactoring, so the proposal was rejected conservatively."
                                            : definitionForReason(frBeforeCompile.strategy);
                                    String msg = "Not useful refactoring proposal: strategy=" + strategyText +
                                             ", reasons=" + reasonsText;
                                    logStage(viewer, "USEFUL", "Not useful refactoring proposal" + msg);
	                                    showNotification(project,
	                                            "[Clone] Refactor NOT recommended (attempt " + attempt + ")\n" +
	                                                    "for: " + fileName + "\n \n" +
	                                                    "Reason:\n" +
	                                                    strategyText + "\n" +
	                                                    reasonDefinition,
	                                            NotificationType.WARNING);

	                                    String focusedProposedCode = buildFocusedFeedbackRefactoredCode(
	                                            project,
	                                            fileName,
	                                            currentSource,
	                                            proposedSource,
	                                            watchedCloneMethods
	                                    );
	                                    feedback = """
	Your previous refactoring attempt was rejected by the usefulness checker.

	[NOT_USEFUL_REFACTORED_CODE]
	```java
%s
```

[REASONS]
%s

[STRATEGY]
%s

[REASON_DEFINITION]
%s%s

	[REVISION_INSTRUCTION]
	Your refactoring is not useful. You must actually remove or significantly reduce the duplicated fragment in BOTH places. Avoid incomplete refactoring, deleting one side, or delegating only one side.
		""".formatted(
	                                            focusedProposedCode,
	                                            reasonsText,
	                                            strategyText,
	                                            reasonDefinition == null ? "" : reasonDefinition,
	                                            notesText
	                                    );
                                        useFeedbackOnlyPrompt = true;
                                } else if (frBeforeCompile != null) {
                                    logStage(viewer, "USEFUL", "ok(FRAGMENT, before compile): strategy=" + frBeforeCompile.strategy + ", score=" + frBeforeCompile.score +
                                            (frBeforeCompile.notes == null || frBeforeCompile.notes.isBlank() ? "" : (", notes=" + frBeforeCompile.notes)));
                                }

                                if (viewer != null) {
                                    logStage(viewer, "USEFUL", "fragment ranges(before compile): A=" + lrA + ", B=" + lrB +
                                            ", codeA.preview=" + previewOneLine(codeA, 120) +
                                            ", codeB.preview=" + previewOneLine(codeB, 120));
                                }
                            } catch (Throwable t) {
                                // If usefulness check fails, do NOT block the attempt.
                                logStage(viewer, "USEFUL", "fragment usefulness check failed (before compile): " + t.getMessage() + " (proceeding)");
                            }
                        }

                        if (!isUseful) {
                            // Do not compile/test/apply; retry with feedback.
                            javaBuildSupport.clearPatchedClassesDir();
                            continue;
                        }
                        // ===== End usefulness check =====

                        String changedMethodBeforeCompile = findModifiedCloneMethod(project, vf, watchedCloneMethods);
                        if (changedMethodBeforeCompile != null) {
                            logStage(viewer, "WATCH", "stopped before compile because cloned method changed: " + changedMethodBeforeCompile);
                            showNotification(project,
                                    "[Clone] Stopped because a cloned method was modified by the user: " + changedMethodBeforeCompile,
                                    NotificationType.WARNING);
                            cancelWorkflow(viewer);
                            return;
                        }
                        // Compile the proposed source to a temp classes dir, without touching the original file.
                        String ideCp = javaBuildSupport.buildProjectClasspathFromIde();
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
                        File patchedOutDir;
                        String compileLog;
                        try {
                            throwIfCancelled(CloneRefactorWorkflow::isCancelled);
                            patchedOutDir = javaBuildSupport.compileProposedSourceToTemp(ioFile, fileName, proposedSource, ideCp);
                            javaBuildSupport.setPatchedClassesDir(patchedOutDir);
                            compileLog = "BUILD SUCCESS\n";
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
                            feedback = cr == null ? "Compilation failed." : cr.summary;
                            useFeedbackOnlyPrompt = false;
                            logStage(viewer, "COMPILE", "failed: " + feedback);
                            showNotification(project, "[Clone] Compilation failed (attempt " + attempt + ") for: " + fileName + "\n" + feedback, NotificationType.ERROR);
                            continue;
                        }

                        logStage(viewer, "COMPILE", "ok (isolated)");
                        showNotification(project, "Compilation successful: Ready to run (attempt " + attempt + ") for: " + fileName, NotificationType.INFORMATION);

                        /* ===== Test ===== */
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

                        String changedMethodBeforeTest = findModifiedCloneMethod(project, vf, watchedCloneMethods);
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

                            String changedMethodBeforeApply = findModifiedCloneMethod(project, vf, watchedCloneMethods);
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
                                AntiCopyPasterUsageStatistics.getInstance(project).refactoringAccepted();
                                currentSource = proposedSource;
                                Files.writeString(ioFile.toPath(), currentSource, StandardCharsets.UTF_8);
                                logStage(viewer, "REFACTOR", "applied after verification");
                                showNotification(project, "[Clone] Tests passed. Refactor applied for: " + fileName, NotificationType.INFORMATION);
                            } else {
                                AntiCopyPasterUsageStatistics.getInstance(project).refactoringCancelled();
                                logStage(viewer, "REFACTOR", "verified but not applied (user cancelled)");
                                showNotification(project, "[Clone] Tests passed but changes were not applied (user cancelled): " + fileName, NotificationType.WARNING);
                            }

                            logStage(viewer, "WORKFLOW", "SUCCESS");
                            // Clear patched classes dir for subsequent runs
                            javaBuildSupport.clearPatchedClassesDir();
                            return;
                        }

                        feedback = tr == null ? "Tests failed." :
                                (tr.summary != null ? tr.summary : tr.raw);
                        useFeedbackOnlyPrompt = false;

                        logStage(viewer, "TEST", "failed");
                        showNotification(project, "[Clone] Tests failed (attempt " + attempt + ") for: " + fileName, NotificationType.WARNING);
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

    // ---- Snippet classification helpers (whole method vs fragment) ----

    /** Normalize code text for robust matching (ignore whitespace, line endings, and outer braces). */
    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        // Unify newlines and trim
        String t = s.replace("\r\n", "\n").replace("\r", "\n").trim();
        // Collapse whitespace to single spaces
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    /** If text looks like a Java block `{ ... }`, strip the outer braces (best-effort). */
    private static String stripOuterBraces(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            // Remove only the first and last char; keep inner formatting.
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static int[] findSnippetLineRangeInText(String fileSource, String pastedSnippet) {
        try {
            if (fileSource == null || pastedSnippet == null) return null;
            if (pastedSnippet.isBlank()) return null;

            int idx = fileSource.indexOf(pastedSnippet);
            if (idx < 0) return null;

            int startLine = 1;
            for (int i = 0; i < idx; i++) {
                if (fileSource.charAt(i) == '\n') startLine++;
            }

            int endIdx = Math.min(fileSource.length(), idx + pastedSnippet.length());
            int endLine = startLine;
            for (int i = idx; i < endIdx; i++) {
                if (fileSource.charAt(i) == '\n') endLine++;
            }

            return new int[]{startLine, endLine};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns 1-based {startLine,endLine} for a PSI element using the file document, or null on failure. */
    private static int[] elementLineRange(Project project, VirtualFile vf, PsiElement el) {
        try {
            if (project == null || project.isDisposed() || vf == null || el == null) return null;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            int startOffset = Math.max(0, el.getTextRange().getStartOffset());
            int endOffset = Math.max(startOffset, el.getTextRange().getEndOffset());

            int startLine = doc.getLineNumber(startOffset) + 1;
            int endLine = doc.getLineNumber(Math.max(0, endOffset - 1)) + 1;

            return new int[]{startLine, endLine};
        } catch (Throwable t) {
            return null;
        }
    }

    private static int getIntField(Object obj, String... names) {
        if (obj == null || names == null) return -1;
        Class<?> cls = obj.getClass();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            // public field
            try {
                java.lang.reflect.Field f = cls.getField(n);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
            // declared field
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
            // getter
            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(obj);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static String getStringField(Object obj, String... names) {
        if (obj == null || names == null) return null;
        Class<?> cls = obj.getClass();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                java.lang.reflect.Field f = cls.getField(n);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void setIntField(Object obj, int value, String... names) {
        if (obj == null || names == null) return;
        Class<?> cls = obj.getClass();
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            // public field
            try {
                java.lang.reflect.Field f = cls.getField(n);
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.set(obj, value);
                    return;
                }
            } catch (Throwable ignored) {}
            // declared field
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.set(obj, value);
                    return;
                }
            } catch (Throwable ignored) {}
            // setter
            try {
                String mname = "set" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname, int.class);
                m.invoke(obj, value);
                return;
            } catch (Throwable ignored) {}
            try {
                String mname = "set" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname, Integer.class);
                m.invoke(obj, value);
                return;
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Determine whether the pasted snippet covers an entire method.
     * Returns the host PsiMethod if the snippet matches a method (body or full), else null.
     */
    private static PsiMethod findWholeMethodCoveredBySnippet(Project project, VirtualFile vf, String fileSource, String pastedSnippet) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            if (pastedSnippet == null || pastedSnippet.isBlank()) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof PsiJavaFile)) return null;

            String pNorm = normalizeForMatch(pastedSnippet);
            String pNormNoBraces = normalizeForMatch(stripOuterBraces(pastedSnippet));

            for (PsiMethod m : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
                if (m == null) continue;

                // 1) Full method text match
                String mText = m.getText();
                if (!mText.isBlank()) {
                    String mNorm = normalizeForMatch(mText);
                    if (!pNorm.isBlank() && mNorm.equals(pNorm)) return m;
                    if (!pNormNoBraces.isBlank() && mNorm.equals(pNormNoBraces)) return m;
                }

                // 2) Method body match
                PsiElement body = m.getBody();
                if (body == null) continue;
                String bodyText = body.getText();
                if (bodyText == null) bodyText = "";

                String bodyNorm = normalizeForMatch(bodyText);
                String bodyNoBracesNorm = normalizeForMatch(stripOuterBraces(bodyText));

                // Common case: pasted == body without braces
                if (!pNorm.isBlank() && (bodyNorm.equals(pNorm) || bodyNoBracesNorm.equals(pNorm))) return m;
                if (!pNormNoBraces.isBlank() && (bodyNorm.equals(pNormNoBraces) || bodyNoBracesNorm.equals(pNormNoBraces))) return m;
            }

            // --- Fallback: old line-range coverage logic (best-effort) ---
            // This is less reliable because it depends on exact substring search.
            int[] sn = findSnippetLineRangeInText(fileSource, pastedSnippet);
            if (sn == null) return null;

            int idx = (fileSource == null) ? -1 : fileSource.indexOf(pastedSnippet);
            if (idx < 0) return null;

            PsiElement at = psiFile.findElementAt(Math.min(idx, Math.max(0, psiFile.getTextLength() - 1)));
            PsiMethod host = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
            if (host == null) return null;

            int[] mr = elementLineRange(project, vf, host);
            if (mr == null) return null;

            int snippetStart = sn[0];
            int snippetEnd = sn[1];
            int methodStart = mr[0];
            int methodEnd = mr[1];

            if (snippetStart <= methodStart && snippetEnd >= methodEnd) {
                return host;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // NOTE: RAG retrieval uses clone code (via buildRefactorRagQueryText) when available; ranges here are only for agent context.
    private static refactoring.DetectedClone convertClone(detection.DetectedClone c) {
        String representative = "";
        if (c != null) {
            java.util.List<String> cloneCodes = getDetectedCloneCodes(c, null);
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
                getDetectedCloneCodes(c, null),
                c.cloneCodeA,
                c.cloneCodeB
        );
    }


    // ===== Ablation toggle: set to false to disable RAG retrieval entirely =====
    private static final boolean ENABLE_REFACTOR_RAG = false;

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

    private static final class CloneSelectionOption {
        final detection.DetectedClone clone;
        final String label;
        final String details;

        CloneSelectionOption(detection.DetectedClone clone, String label, String details) {
            this.clone = clone;
            this.label = label == null ? "<unknown clone>" : label;
            this.details = details == null ? "" : details;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class CloneRangeSelectionOption {
        final int rangeIndex;
        final detection.CloneRange range;
        final String label;
        final String details;
        final String snippet;
        final String uniqueKey;

        CloneRangeSelectionOption(int rangeIndex,
                                  detection.CloneRange range,
                                  String label,
                                  String details,
                                  String snippet,
                                  String uniqueKey) {
            this.rangeIndex = rangeIndex;
            this.range = range;
            this.label = label == null ? "<unknown occurrence>" : label;
            this.details = details == null ? "" : details;
            this.snippet = snippet == null ? "" : snippet;
            this.uniqueKey = uniqueKey == null ? "" : uniqueKey;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static detection.DetectedClone chooseCloneToRefactor(Project project,
                                                                 VirtualFile vf,
                                                                 java.util.List<detection.DetectedClone> clones,
                                                                 Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) return null;

        java.util.ArrayList<CloneSelectionOption> options = new java.util.ArrayList<>();
        int ordinal = 1;
        for (detection.DetectedClone clone : clones) {
            if (clone == null) continue;
            CloneSelectionOption option = buildCloneSelectionOption(project, vf, clone, ordinal++);
            options.add(option);
            logStage(viewer, "DETECTION", "clone candidate: " + option.label);
        }

        if (options.isEmpty()) return null;
        if (options.size() == 1) {
            CloneSelectionOption only = options.get(0);
            logStage(viewer, "DETECTION", "single clone group after merge; skipping group chooser: " + only.label);
            return only.clone;
        }
        for (int i = 0; i < options.size(); i++) {
            CloneSelectionOption option = options.get(i);
            previewCloneRangeInEditor(project, vf, getRepresentativeRange(option.clone));
            int choice = showSequentialChoiceDialog(
                    project,
                    "Refactor Clone Candidate",
                    buildSequentialClonePrompt(option, i + 1, options.size()),
                    "Refactor This Clone",
                    (i + 1) < options.size() ? "Next Clone" : "Skip",
                    "Cancel"
            );
            if (choice == Messages.CANCEL) {
                logStage(viewer, "DETECTION", "clone selection cancelled");
                return null;
            }
            if (choice == Messages.YES) {
                logStage(viewer, "DETECTION", "selected clone: " + option.label);
                return option.clone;
            }
        }

        logStage(viewer, "DETECTION", "no clone candidate selected");
        return null;
    }

    private static detection.DetectedClone chooseCloneRangesToRefactor(Project project,
                                                                       VirtualFile vf,
                                                                       String fileSource,
                                                                       detection.DetectedClone clone,
                                                                       Consumer<String> viewer) {
        if (clone == null || clone.ranges == null || clone.ranges.size() <= 1) return clone;

        java.util.LinkedHashMap<String, CloneRangeSelectionOption> uniqueOptions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < clone.ranges.size(); i++) {
            CloneRangeSelectionOption option = buildCloneRangeSelectionOption(project, vf, fileSource, clone, i);
            if (option == null) continue;
            if (uniqueOptions.containsKey(option.uniqueKey)) continue;
            uniqueOptions.put(option.uniqueKey, option);
            logStage(viewer, "DETECTION", "clone range candidate: " + option.label);
        }
        java.util.ArrayList<CloneRangeSelectionOption> options = new java.util.ArrayList<>(uniqueOptions.values());
        if (options.size() <= 1) return clone;

        java.util.ArrayList<CloneRangeSelectionOption> selected = new java.util.ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            CloneRangeSelectionOption option = options.get(i);
            previewCloneRangeInEditor(project, vf, option.range);
            int choice = showSequentialChoiceDialog(
                    project,
                    "Select Clone Occurrence",
                    buildSequentialRangePrompt(option, i + 1, options.size(), selected.size()),
                    "Include",
                    "Exclude",
                    "Cancel"
            );
            if (choice == Messages.CANCEL) {
                logStage(viewer, "DETECTION", "clone range selection cancelled");
                return null;
            }
            if (choice == Messages.YES) {
                selected.add(option);
            }
        }

        if (selected.size() < 2) {
            showNotification(project,
                    "[Clone] Need at least two clone occurrences to refactor, but only " + selected.size() + " were selected.",
                    NotificationType.WARNING);
            logStage(viewer, "DETECTION", "clone range selection rejected: selected=" + selected.size());
            return null;
        }

        detection.DetectedClone selectedClone = buildSelectedCloneFromRanges(clone, selected, fileSource);
        logStage(viewer, "DETECTION", "selected clone ranges: " + summarizeSelectedRangeLabels(selected));
        return selectedClone;
    }

    private static CloneSelectionOption buildCloneSelectionOption(Project project,
                                                                  VirtualFile vf,
                                                                  detection.DetectedClone clone,
                                                                  int ordinal) {
        java.util.List<CloneMethodSnapshot> snapshots = captureCloneMethodSnapshots(project, vf, clone, null);
        String methodSummary = summarizeCloneMethods(snapshots);
        String rangeSummary = summarizeCloneRanges(clone == null ? null : clone.ranges);

        String label = "Clone " + ordinal;
        if (!methodSummary.isBlank()) {
            label += ": " + methodSummary;
        }
        if (!rangeSummary.isBlank()) {
            label += " [" + rangeSummary + "]";
        }

        java.util.List<String> cloneCodes = getDetectedCloneCodes(clone, null);
        String[] ab = extractCloneCodeABFromReason(clone == null ? null : clone.reason);
        String cloneCodeA = !cloneCodes.isEmpty() ? firstNonBlank(cloneCodes.get(0), ab[0]) : firstNonBlank(clone == null ? null : clone.cloneCodeA, ab[0]);
        String cloneCodeB = cloneCodes.size() > 1 ? firstNonBlank(cloneCodes.get(1), ab[1]) : firstNonBlank(clone == null ? null : clone.cloneCodeB, ab[1]);

        StringBuilder details = new StringBuilder();
        details.append("ID: ").append(clone == null || clone.id == null || clone.id.isBlank() ? "<unknown>" : clone.id).append("\n");
        if (!methodSummary.isBlank()) {
            details.append("Methods: ").append(methodSummary).append("\n");
        }
        if (!rangeSummary.isBlank()) {
            details.append("Ranges: ").append(rangeSummary).append("\n");
        }
        if (clone != null && clone.refactorType != null && !clone.refactorType.isBlank()) {
            details.append("Suggested refactor type: ").append(clone.refactorType).append("\n");
        }

        String reasonPreview = previewOneLine(clone == null ? "" : clone.reason, 320);
        if (reasonPreview != null && !reasonPreview.isBlank()) {
            details.append("\nReason preview:\n").append(reasonPreview).append("\n");
        }

        String codeAPreview = previewCodeForSelection(cloneCodeA);
        if (!codeAPreview.isBlank()) {
            details.append("\nClone A preview:\n").append(codeAPreview).append("\n");
        }

        String codeBPreview = previewCodeForSelection(cloneCodeB);
        if (!codeBPreview.isBlank()) {
            details.append("\nClone B preview:\n").append(codeBPreview).append("\n");
        }

        return new CloneSelectionOption(clone, label, details.toString().trim());
    }

    private static String summarizeCloneMethods(java.util.List<CloneMethodSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return "";
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (CloneMethodSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.displayName == null || snapshot.displayName.isBlank()) continue;
            names.add(snapshot.displayName);
        }
        return String.join(" <-> ", names);
    }

    private static String summarizeCloneRanges(java.util.List<detection.CloneRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return "";
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (detection.CloneRange range : ranges) {
            if (range == null) continue;
            parts.add(range.startLine + "-" + range.endLine);
        }
        return String.join(", ", parts);
    }

    private static String previewCodeForSelection(String code) {
        if (code == null || code.isBlank()) return "";
        String normalized = code.replace("\r\n", "\n").replace("\r", "\n").strip();
        if (normalized.length() > 800) {
            normalized = normalized.substring(0, 800) + "\n...<truncated>...";
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return "";
    }

    private static String buildSequentialClonePrompt(CloneSelectionOption option, int ordinal, int total) {
        if (option == null) return "Refactor this clone?";
        StringBuilder sb = new StringBuilder();
        sb.append("Clone candidate ").append(ordinal).append("/").append(total).append("\n\n");
        sb.append(option.label);
        sb.append("\n\nThe clone code has been highlighted in the editor.");
        sb.append("\n\nDo you want to refactor this clone?");
        return sb.toString();
    }

    private static String buildSequentialRangePrompt(CloneRangeSelectionOption option,
                                                     int ordinal,
                                                     int total,
                                                     int currentSelectedCount) {
        if (option == null) return "Include this clone occurrence?";
        StringBuilder sb = new StringBuilder();
        sb.append("Clone occurrence ").append(ordinal).append("/").append(total).append("\n");
        sb.append("Currently selected: ").append(currentSelectedCount).append("\n\n");
        sb.append(option.label);
        sb.append("\n\nThis clone occurrence has been highlighted in the editor.");
        sb.append("\n\nInclude this clone occurrence in the refactoring?");
        return sb.toString();
    }

    private static int showSequentialChoiceDialog(Project project,
                                                  String title,
                                                  String message,
                                                  String yesText,
                                                  String noText,
                                                  String cancelText) {
        final java.util.concurrent.atomic.AtomicInteger out = new java.util.concurrent.atomic.AtomicInteger(Messages.CANCEL);
        Runnable ui = () -> {
            try {
                int choice = Messages.showYesNoCancelDialog(
                        project,
                        message == null ? "" : message,
                        title == null ? "Clone Refactoring" : title,
                        yesText == null ? "Yes" : yesText,
                        noText == null ? "No" : noText,
                        cancelText == null ? "Cancel" : cancelText,
                        Messages.getQuestionIcon()
                );
                out.set(choice);
            } catch (Throwable ignored) {
                out.set(Messages.CANCEL);
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }
        return out.get();
    }

    private static detection.CloneRange getRepresentativeRange(detection.DetectedClone clone) {
        if (clone == null || clone.ranges == null || clone.ranges.isEmpty()) return null;
        return clone.ranges.get(0);
    }

    private static void previewCloneRangeInEditor(Project project,
                                                  VirtualFile vf,
                                                  detection.CloneRange range) {
        if (project == null || project.isDisposed() || vf == null || range == null) return;
        Runnable ui = () -> {
            try {
                OpenFileDescriptor descriptor = new OpenFileDescriptor(project, vf, Math.max(0, range.startLine - 1), 0);
                Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                if (editor == null) return;

                Document doc = editor.getDocument();
                if (doc == null || doc.getLineCount() <= 0) return;

                int startLine = Math.max(0, Math.min(range.startLine - 1, doc.getLineCount() - 1));
                int endLine = Math.max(startLine, Math.min(range.endLine - 1, doc.getLineCount() - 1));
                int startOffset = doc.getLineStartOffset(startLine);
                int endOffset = doc.getLineEndOffset(endLine);

                editor.getSelectionModel().setSelection(startOffset, endOffset);
                editor.getCaretModel().moveToOffset(startOffset);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            } catch (Throwable ignored) {
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }
    }

    private static java.util.List<String> getDetectedCloneCodes(detection.DetectedClone clone, String fileSource) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (clone == null) return out;

        if (clone.cloneCodes != null) {
            for (String code : clone.cloneCodes) {
                out.add(code == null ? "" : code);
            }
        }

        if (out.isEmpty()) {
            if (clone.cloneCodeA != null && !clone.cloneCodeA.isBlank()) out.add(clone.cloneCodeA);
            if (clone.cloneCodeB != null && !clone.cloneCodeB.isBlank()) out.add(clone.cloneCodeB);
        }

        int rangeCount = clone.ranges == null ? 0 : clone.ranges.size();
        while (out.size() < rangeCount) {
            detection.CloneRange range = (clone.ranges == null || out.size() >= clone.ranges.size()) ? null : clone.ranges.get(out.size());
            out.add(range == null ? "" : sliceSourceByCloneRange(fileSource, range));
        }
        return out;
    }

    private static String getDetectedCloneCodeAt(detection.DetectedClone clone,
                                                 int rangeIndex,
                                                 String fileSource) {
        java.util.List<String> codes = getDetectedCloneCodes(clone, fileSource);
        if (rangeIndex >= 0 && rangeIndex < codes.size()) {
            return codes.get(rangeIndex) == null ? "" : codes.get(rangeIndex);
        }
        return "";
    }

    private static CloneRangeSelectionOption buildCloneRangeSelectionOption(Project project,
                                                                            VirtualFile vf,
                                                                            String fileSource,
                                                                            detection.DetectedClone clone,
                                                                            int rangeIndex) {
        if (clone == null || clone.ranges == null || rangeIndex < 0 || rangeIndex >= clone.ranges.size()) return null;
        detection.CloneRange range = clone.ranges.get(rangeIndex);
        if (range == null) return null;

        PsiMethod method = findMethodContainingLine(project, vf, range.startLine);
        if (method == null) method = findMethodContainingLine(project, vf, range.endLine);
        String displayName = method == null ? "<unknown method>" : buildMethodDisplayName(method);
        String uniqueKey = range.startLine + ":" + range.endLine;

        String snippet = firstNonBlank(getDetectedCloneCodeAt(clone, rangeIndex, fileSource), sliceSourceByCloneRange(fileSource, range));
        String label = "Occurrence " + (rangeIndex + 1) + ": " + displayName + " [" + range.startLine + "-" + range.endLine + "]";
        StringBuilder details = new StringBuilder();
        details.append("Method: ").append(displayName).append("\n");
        details.append("Lines: ").append(range.startLine).append("-").append(range.endLine).append("\n");

        String snippetPreview = previewCodeForSelection(snippet);
        if (!snippetPreview.isBlank()) {
            details.append("\nCode preview:\n").append(snippetPreview);
        }

        return new CloneRangeSelectionOption(rangeIndex, range, label, details.toString().trim(), snippet, uniqueKey);
    }

    private static String buildRangeSelectionDetails(java.util.List<CloneRangeSelectionOption> selected) {
        if (selected == null || selected.isEmpty()) {
            return "No ranges selected.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Selected occurrences: ").append(selected.size()).append("\n\n");
        for (CloneRangeSelectionOption option : selected) {
            if (option == null) continue;
            sb.append(option.label).append("\n");
            if (option.details != null && !option.details.isBlank()) {
                sb.append(option.details).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static detection.DetectedClone buildSelectedCloneFromRanges(detection.DetectedClone original,
                                                                        java.util.List<CloneRangeSelectionOption> selected,
                                                                        String fileSource) {
        detection.DetectedClone clone = new detection.DetectedClone();
        clone.id = (original == null || original.id == null || original.id.isBlank())
                ? "selected_clone"
                : original.id + "_selected";
        clone.refactorType = original == null ? null : original.refactorType;
        clone.reason = original == null ? null : original.reason;
        clone.ranges = new java.util.ArrayList<>();
        clone.cloneCodes = new java.util.ArrayList<>();

        java.util.ArrayList<CloneRangeSelectionOption> ordered = new java.util.ArrayList<>(selected);
        ordered.sort(java.util.Comparator.comparingInt(o -> o.rangeIndex));

        for (CloneRangeSelectionOption option : ordered) {
            if (option == null || option.range == null) continue;
            detection.CloneRange copy = new detection.CloneRange();
            copy.startLine = option.range.startLine;
            copy.endLine = option.range.endLine;
            clone.ranges.add(copy);
            clone.cloneCodes.add(firstNonBlank(option.snippet, sliceSourceByCloneRange(fileSource, option.range)));
        }

        clone.cloneCodeA = clone.cloneCodes.isEmpty() ? "" : clone.cloneCodes.get(0);
        clone.cloneCodeB = clone.cloneCodes.size() > 1 ? clone.cloneCodes.get(1) : "";
        return clone;
    }

    private static detection.CloneRange getRangeAt(java.util.List<CloneRangeSelectionOption> options, int index) {
        if (options == null || index < 0 || index >= options.size()) return null;
        CloneRangeSelectionOption option = options.get(index);
        return option == null ? null : option.range;
    }

    private static String summarizeSelectedRangeLabels(java.util.List<CloneRangeSelectionOption> selected) {
        if (selected == null || selected.isEmpty()) return "";
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (CloneRangeSelectionOption option : selected) {
            if (option == null || option.label == null || option.label.isBlank()) continue;
            labels.add(option.label);
        }
        return String.join(" | ", labels);
    }

    private static String sliceSourceByCloneRange(String fileSource, detection.CloneRange range) {
        try {
            if (fileSource == null || fileSource.isBlank() || range == null) return "";
            String[] lines = fileSource.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
            if (lines.length == 0) return "";
            int start = Math.max(1, range.startLine);
            int end = Math.max(start, range.endLine);
            int startIdx = Math.min(lines.length, start) - 1;
            int endIdx = Math.min(lines.length, end) - 1;
            if (startIdx < 0 || endIdx < startIdx) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i <= endIdx; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().strip();
        } catch (Throwable t) {
            return "";
        }
    }

    private static java.util.List<detection.DetectedClone> resolveDetectedCloneRangesWithPsi(Project project,
                                                                                              VirtualFile vf,
                                                                                              String fileSource,
                                                                                              java.util.List<detection.DetectedClone> clones,
                                                                                              Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) return java.util.Collections.emptyList();

        java.util.ArrayList<detection.DetectedClone> resolved = new java.util.ArrayList<>();
        int changedCloneCount = 0;
        for (detection.DetectedClone clone : clones) {
            detection.DetectedClone adjusted = resolveSingleDetectedCloneWithPsi(project, vf, fileSource, clone);
            resolved.add(adjusted);

            String beforeSummary = summarizeCloneRanges(clone == null ? null : clone.ranges);
            String afterSummary = summarizeCloneRanges(adjusted == null ? null : adjusted.ranges);
            if (!java.util.Objects.equals(beforeSummary, afterSummary)) {
                changedCloneCount++;
                String cloneId = clone == null || clone.id == null || clone.id.isBlank() ? "<unknown>" : clone.id;
                logStage(viewer, "DETECTION", "psi-resolved clone ranges for " + cloneId + ": [" + beforeSummary + "] -> [" + afterSummary + "]");
            }
        }

        if (changedCloneCount > 0) {
            logStage(viewer, "DETECTION", "psi-resolved clone ranges: " + changedCloneCount + "/" + resolved.size() + " clone group(s)");
        }
        return resolved;
    }

    private static detection.DetectedClone resolveSingleDetectedCloneWithPsi(Project project,
                                                                             VirtualFile vf,
                                                                             String fileSource,
                                                                             detection.DetectedClone clone) {
        if (clone == null) return null;

        detection.DetectedClone adjusted = new detection.DetectedClone();
        adjusted.id = clone.id;
        adjusted.refactorType = clone.refactorType;
        adjusted.reason = clone.reason;
        adjusted.cloneCodes = new java.util.ArrayList<>();
        adjusted.ranges = new java.util.ArrayList<>();

        java.util.List<String> cloneCodes = getDetectedCloneCodes(clone, fileSource);
        int occurrenceCount = Math.max(
                cloneCodes.size(),
                clone.ranges == null ? 0 : clone.ranges.size()
        );

        for (int i = 0; i < occurrenceCount; i++) {
            detection.CloneRange rawRange = (clone.ranges == null || i >= clone.ranges.size()) ? null : clone.ranges.get(i);
            String snippet = i < cloneCodes.size() ? cloneCodes.get(i) : "";
            detection.CloneRange resolvedRange = resolveCloneRangeWithPsi(project, vf, fileSource, snippet, rawRange);
            if (resolvedRange != null) {
                adjusted.ranges.add(resolvedRange);
                adjusted.cloneCodes.add(snippet == null ? "" : snippet);
            }
        }

        adjusted.cloneCodeA = adjusted.cloneCodes.isEmpty() ? "" : adjusted.cloneCodes.get(0);
        adjusted.cloneCodeB = adjusted.cloneCodes.size() > 1 ? adjusted.cloneCodes.get(1) : "";
        return adjusted;
    }

    private static detection.CloneRange resolveCloneRangeWithPsi(Project project,
                                                                 VirtualFile vf,
                                                                 String fileSource,
                                                                 String snippet,
                                                                 detection.CloneRange rawRange) {
        PsiMethod rawHostMethod = findMethodContainingCloneRange(project, vf, rawRange);
        if (rawHostMethod != null) {
            detection.CloneRange fragmentRange = findFragmentRangeInMethod(project, vf, rawHostMethod, fileSource, snippet, rawRange);
            if (fragmentRange != null) {
                return fragmentRange;
            }
            if (rawRange != null) {
                return copyCloneRange(rawRange);
            }

            int[] methodLines = elementLineRange(project, vf, rawHostMethod);
            if (methodLines != null) {
                detection.CloneRange methodRange = new detection.CloneRange();
                methodRange.startLine = methodLines[0];
                methodRange.endLine = methodLines[1];
                return methodRange;
            }
        }

        PsiMethod method = findMethodForCloneSnippet(project, vf, fileSource, snippet, rawRange);
        if (method != null) {
            detection.CloneRange fragmentRange = findFragmentRangeInMethod(project, vf, method, fileSource, snippet, rawRange);
            if (fragmentRange != null) {
                return fragmentRange;
            }
            int[] methodLines = elementLineRange(project, vf, method);
            if (methodLines != null) {
                detection.CloneRange methodRange = new detection.CloneRange();
                methodRange.startLine = methodLines[0];
                methodRange.endLine = methodLines[1];
                return methodRange;
            }
        }
        return copyCloneRange(rawRange);
    }

    private static detection.CloneRange copyCloneRange(detection.CloneRange range) {
        if (range == null) return null;
        detection.CloneRange copy = new detection.CloneRange();
        copy.startLine = range.startLine;
        copy.endLine = range.endLine;
        return copy;
    }

    private static java.util.List<detection.DetectedClone> mergeOverlappingDetectedClones(String fileSource,
                                                                                           java.util.List<detection.DetectedClone> clones,
                                                                                           Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) return java.util.Collections.emptyList();

        java.util.ArrayList<detection.DetectedClone> working = new java.util.ArrayList<>();
        for (detection.DetectedClone clone : clones) {
            if (clone != null) working.add(clone);
        }
        if (working.size() <= 1) return working;

        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < working.size(); i++) {
                for (int j = i + 1; j < working.size(); j++) {
                    if (detectedClonesOverlap(working.get(i), working.get(j))) {
                        detection.DetectedClone merged = mergeDetectedClonePair(fileSource, working.get(i), working.get(j));
                        working.set(i, merged);
                        working.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }

        if (working.size() != clones.size()) {
            logStage(viewer, "DETECTION", "merged overlapping clone groups: " + clones.size() + " -> " + working.size());
        }
        return working;
    }

    private static boolean detectedClonesOverlap(detection.DetectedClone first, detection.DetectedClone second) {
        if (first == null || second == null || first.ranges == null || second.ranges == null) return false;
        for (detection.CloneRange left : first.ranges) {
            for (detection.CloneRange right : second.ranges) {
                if (cloneRangesOverlap(left, right)) return true;
            }
        }
        return false;
    }

    private static boolean cloneRangesOverlap(detection.CloneRange left, detection.CloneRange right) {
        if (left == null || right == null) return false;
        return left.startLine <= right.endLine && left.endLine >= right.startLine;
    }

    private static detection.DetectedClone mergeDetectedClonePair(String fileSource,
                                                                  detection.DetectedClone first,
                                                                  detection.DetectedClone second) {
        detection.DetectedClone merged = new detection.DetectedClone();
        merged.id = mergeCloneIds(first, second);
        merged.refactorType = firstNonBlank(first == null ? null : first.refactorType, second == null ? null : second.refactorType);
        merged.reason = mergeCloneReasons(first, second);
        java.util.ArrayList<MergedCloneOccurrence> mergedOccurrences = mergeCloneOccurrences(fileSource, first, second);
        merged.ranges = new java.util.ArrayList<>();
        merged.cloneCodes = new java.util.ArrayList<>();
        for (MergedCloneOccurrence occurrence : mergedOccurrences) {
            if (occurrence == null || occurrence.range == null) continue;
            merged.ranges.add(occurrence.range);
            merged.cloneCodes.add(occurrence.code == null ? "" : occurrence.code);
        }
        merged.cloneCodeA = merged.cloneCodes.isEmpty() ? "" : merged.cloneCodes.get(0);
        merged.cloneCodeB = merged.cloneCodes.size() > 1 ? merged.cloneCodes.get(1) : "";
        return merged;
    }

    private static String mergeCloneIds(detection.DetectedClone first, detection.DetectedClone second) {
        String a = first == null ? "" : first.id;
        String b = second == null ? "" : second.id;
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        if (a.equals(b)) return a;
        return a + "__" + b;
    }

    private static String mergeCloneReasons(detection.DetectedClone first, detection.DetectedClone second) {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        if (first != null && first.reason != null && !first.reason.isBlank()) parts.add(first.reason.strip());
        if (second != null && second.reason != null && !second.reason.isBlank()) parts.add(second.reason.strip());
        return String.join("\n\n", parts);
    }

    private static java.util.ArrayList<MergedCloneOccurrence> mergeCloneOccurrences(String fileSource,
                                                                                     detection.DetectedClone first,
                                                                                     detection.DetectedClone second) {
        java.util.LinkedHashMap<String, MergedCloneOccurrence> unique = new java.util.LinkedHashMap<>();
        addCloneOccurrences(unique, first, fileSource);
        addCloneOccurrences(unique, second, fileSource);
        java.util.ArrayList<MergedCloneOccurrence> out = new java.util.ArrayList<>(unique.values());
        out.sort((a, b) -> {
            int cmp = Integer.compare(a.range.startLine, b.range.startLine);
            return cmp != 0 ? cmp : Integer.compare(a.range.endLine, b.range.endLine);
        });
        return out;
    }

    private static void addCloneOccurrences(java.util.Map<String, MergedCloneOccurrence> out,
                                            detection.DetectedClone clone,
                                            String fileSource) {
        if (out == null || clone == null || clone.ranges == null) return;
        java.util.List<String> cloneCodes = getDetectedCloneCodes(clone, fileSource);
        for (int i = 0; i < clone.ranges.size(); i++) {
            detection.CloneRange range = clone.ranges.get(i);
            if (range == null) continue;
            String key = range.startLine + ":" + range.endLine;
            String code = i < cloneCodes.size() ? cloneCodes.get(i) : "";
            if (out.containsKey(key)) {
                MergedCloneOccurrence existing = out.get(key);
                if (existing != null && (existing.code == null || existing.code.isBlank()) && code != null && !code.isBlank()) {
                    existing.code = code;
                }
                continue;
            }
            detection.CloneRange copy = new detection.CloneRange();
            copy.startLine = range.startLine;
            copy.endLine = range.endLine;
            out.put(key, new MergedCloneOccurrence(copy, code));
        }
    }

    private static final class MergedCloneOccurrence {
        private final detection.CloneRange range;
        private String code;

        private MergedCloneOccurrence(detection.CloneRange range, String code) {
            this.range = range;
            this.code = code == null ? "" : code;
        }
    }

    /**
     * Show a diff in a modal dialog that has Apply/Cancel buttons.
     * Clicking Apply returns true; Cancel returns false.
     */
    private static boolean showDiffAndConfirmApply(Project project, String fileName, String before, String after) {
        if (project == null || project.isDisposed()) return false;
        final java.util.concurrent.atomic.AtomicBoolean decision = new java.util.concurrent.atomic.AtomicBoolean(false);

        Runnable ui = () -> {
            Disposable disp = Disposer.newDisposable("DiffPreview");
            try {
                DiffContentFactory f = DiffContentFactory.getInstance();
                var left = f.create(before == null ? "" : before);
                var right = f.create(after == null ? "" : after);

                String title = "Refactor Preview";
                String leftTitle = "Current";
                String rightTitle = "Proposed";
                SimpleDiffRequest req = new SimpleDiffRequest(title, left, right, leftTitle, rightTitle);

                DiffRequestPanel panel = DiffManager.getInstance().createRequestPanel(project, disp, null);
                panel.setRequest(req);

                DialogWrapper dialog = new DialogWrapper(project, true) {
                    {
                        setTitle(title);
                        setOKButtonText("Apply");
                        setCancelButtonText("Cancel");
                        init();
                    }

                    @Override
                    protected JComponent createCenterPanel() {
                        return panel.getComponent();
                    }
                };

                boolean ok = dialog.showAndGet(); // OK => Apply
                decision.set(ok);

            } catch (Throwable t) {
                decision.set(false);
            } finally {
                Disposer.dispose(disp);
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }

        return decision.get();
    }

    private static String buildFocusedFeedbackRefactoredCode(Project project,
                                                             String fileName,
                                                             String beforeSource,
                                                             String afterSource,
                                                             java.util.List<CloneMethodSnapshot> snapshots) {
        String fullSource = afterSource == null ? "" : afterSource;
        try {
            if (project == null || project.isDisposed() || fullSource.isBlank()) return fullSource;

            PsiJavaFile afterPsi = parseInMemoryJavaFile(project, fileName, fullSource);
            if (afterPsi == null) return fullSource;
            PsiJavaFile beforePsi = parseInMemoryJavaFile(project, fileName, beforeSource == null ? "" : beforeSource);

            java.util.LinkedHashMap<String, PsiMethod> afterMethods = collectAllMethodsByTrackingKey(afterPsi);
            if (afterMethods.isEmpty()) return fullSource;

            java.util.LinkedHashMap<String, PsiMethod> beforeMethods =
                    beforePsi == null ? new java.util.LinkedHashMap<>() : collectAllMethodsByTrackingKey(beforePsi);

            java.util.LinkedHashSet<String> targetKeys = collectTargetMethodKeys(snapshots);
            if (targetKeys.isEmpty()) return fullSource;

            java.util.LinkedHashSet<String> addedKeys = new java.util.LinkedHashSet<>(afterMethods.keySet());
            addedKeys.removeAll(beforeMethods.keySet());

            java.util.LinkedHashSet<String> helperKeys = collectRelevantHelperMethodKeys(afterMethods, targetKeys, addedKeys);

            StringBuilder sb = new StringBuilder();
            appendFocusedMethodSection(sb, "Target clone methods", targetKeys, afterMethods, snapshots, false);
            appendFocusedMethodSection(sb, "New helper methods", helperKeys, afterMethods, snapshots, true);

            String focused = sb.toString().trim();
            return focused.isEmpty() ? fullSource : focused;
        } catch (Throwable t) {
            return fullSource;
        }
    }

    private static String buildUsefulnessFeedbackPrompt(Project project,
                                                        String fileName,
                                                        String proposedSource,
                                                        java.util.List<usefulnessChecker.Reason> reasons,
                                                        java.util.List<CloneMethodSnapshot> snapshots) {
        if (!containsReasonName(reasons, "POST_EXTRACTION_CLONE_DELETION_DETECTED")) {
            return usefulnessChecker.buildFeedbackPrompt(reasons);
        }

        java.util.List<String> missingMethods = findMissingTargetMethodDisplayNames(project, fileName, proposedSource, snapshots);
        StringBuilder missingText = new StringBuilder();
        if (!missingMethods.isEmpty()) {
            missingText.append("\n\nMissing target clone methods in the proposed source:\n");
            for (String name : missingMethods) {
                if (name == null || name.isBlank()) continue;
                missingText.append("- ").append(name).append("\n");
            }
            while (missingText.length() > 0 && Character.isWhitespace(missingText.charAt(missingText.length() - 1))) {
                missingText.setLength(missingText.length() - 1);
            }
        }

        return """
Your previous refactoring was rejected because one or more target clone methods were deleted after extraction.

Problem:
A valid Extract Method refactoring must preserve all original target clone methods.%s

How to fix it:
- Restore every missing target clone method.
- Keep the helper method.
- Make all original target clone methods call the helper.

Important constraints:
- Do not delete target clone methods.
- Do not merge them.
- Only perform Extract Method.
Follow the required output format for the refactoring task.
""".formatted(missingText);
    }

    private static void appendFocusedMethodSection(StringBuilder sb,
                                                   String title,
                                                   java.util.LinkedHashSet<String> keys,
                                                   java.util.Map<String, PsiMethod> afterMethods,
                                                   java.util.List<CloneMethodSnapshot> snapshots,
                                                   boolean helperSection) {
        if (sb == null || keys == null || keys.isEmpty()) return;

        if (sb.length() > 0) sb.append("\n\n");
        sb.append("// ").append(title).append("\n");

        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            PsiMethod method = afterMethods == null ? null : afterMethods.get(key);
            if (method == null) {
                if (!helperSection) {
                    String displayName = findSnapshotDisplayName(snapshots, key);
                    sb.append("// Missing in proposed source: ").append(displayName == null ? key : displayName).append("\n");
                }
                continue;
            }

            String displayName = buildMethodDisplayName(method);
            if (displayName != null && !displayName.isBlank()) {
                sb.append("// ").append(displayName).append("\n");
            }
            sb.append(method.getText().strip()).append("\n\n");
        }

        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
    }

    private static java.util.LinkedHashSet<String> collectTargetMethodKeys(java.util.List<CloneMethodSnapshot> snapshots) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (snapshots == null || snapshots.isEmpty()) return out;
        for (CloneMethodSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            out.add(buildMethodTrackingKey(snapshot.className, snapshot.methodName, snapshot.parameterCount));
        }
        return out;
    }

    private static java.util.List<String> findMissingTargetMethodDisplayNames(Project project,
                                                                              String fileName,
                                                                              String proposedSource,
                                                                              java.util.List<CloneMethodSnapshot> snapshots) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            if (project == null || project.isDisposed() || proposedSource == null || proposedSource.isBlank()) return out;
            java.util.LinkedHashSet<String> targetKeys = collectTargetMethodKeys(snapshots);
            if (targetKeys.isEmpty()) return out;

            PsiJavaFile afterPsi = parseInMemoryJavaFile(project, fileName, proposedSource);
            java.util.LinkedHashMap<String, PsiMethod> afterMethods = collectAllMethodsByTrackingKey(afterPsi);
            for (String key : targetKeys) {
                if (key == null || key.isBlank()) continue;
                if (afterMethods.containsKey(key)) continue;
                String displayName = findSnapshotDisplayName(snapshots, key);
                out.add((displayName == null || displayName.isBlank()) ? key : displayName);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static java.util.LinkedHashSet<String> collectRelevantHelperMethodKeys(java.util.Map<String, PsiMethod> afterMethods,
                                                                                   java.util.Set<String> targetKeys,
                                                                                   java.util.Set<String> addedKeys) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (afterMethods == null || afterMethods.isEmpty() || targetKeys == null || targetKeys.isEmpty()
                || addedKeys == null || addedKeys.isEmpty()) {
            return out;
        }

        for (String targetKey : targetKeys) {
            PsiMethod targetMethod = afterMethods.get(targetKey);
            if (targetMethod == null) continue;
            out.addAll(collectCalledAddedMethodKeys(targetMethod, addedKeys));
        }
        if (!out.isEmpty()) return out;

        java.util.LinkedHashSet<String> targetClasses = new java.util.LinkedHashSet<>();
        for (String targetKey : targetKeys) {
            String className = extractClassNameFromTrackingKey(targetKey);
            if (className != null && !className.isBlank()) targetClasses.add(className);
        }

        for (String addedKey : addedKeys) {
            if (targetClasses.contains(extractClassNameFromTrackingKey(addedKey))) {
                out.add(addedKey);
            }
        }
        return out;
    }

    private static java.util.LinkedHashSet<String> collectCalledAddedMethodKeys(PsiMethod method,
                                                                                java.util.Set<String> addedKeys) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            if (method == null || addedKeys == null || addedKeys.isEmpty()) return out;
            PsiCodeBlock body = method.getBody();
            if (body == null) return out;

            java.util.Collection<PsiMethodCallExpression> calls =
                    PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
            if (calls == null || calls.isEmpty()) return out;

            for (PsiMethodCallExpression call : calls) {
                PsiMethod resolved = null;
                try {
                    resolved = call.resolveMethod();
                } catch (Throwable ignored) {
                }

                String key = null;
                if (resolved != null) {
                    key = buildMethodTrackingKey(resolved);
                }
                if ((key == null || key.isBlank()) && call.getMethodExpression() != null) {
                    String name = call.getMethodExpression().getReferenceName();
                    int arity = call.getArgumentList() == null ? 0 : call.getArgumentList().getExpressionCount();
                    key = findAddedMethodKeyByNameAndArity(addedKeys, name, arity);
                }

                if (key != null && addedKeys.contains(key)) out.add(key);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String findAddedMethodKeyByNameAndArity(java.util.Set<String> addedKeys,
                                                           String methodName,
                                                           int parameterCount) {
        if (addedKeys == null || addedKeys.isEmpty() || methodName == null || methodName.isBlank()) return null;
        String suffix = "#" + methodName + "#" + parameterCount;
        for (String key : addedKeys) {
            if (key != null && key.endsWith(suffix)) return key;
        }
        return null;
    }

    private static String findSnapshotDisplayName(java.util.List<CloneMethodSnapshot> snapshots, String trackingKey) {
        if (snapshots == null || snapshots.isEmpty() || trackingKey == null || trackingKey.isBlank()) return trackingKey;
        for (CloneMethodSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            String key = buildMethodTrackingKey(snapshot.className, snapshot.methodName, snapshot.parameterCount);
            if (trackingKey.equals(key)) return snapshot.displayName;
        }
        return trackingKey;
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

    private static java.util.LinkedHashMap<String, PsiMethod> collectAllMethodsByTrackingKey(PsiJavaFile javaFile) {
        java.util.LinkedHashMap<String, PsiMethod> out = new java.util.LinkedHashMap<>();
        if (javaFile == null) return out;
        PsiClass[] classes = javaFile.getClasses();
        if (classes == null) return out;
        for (PsiClass psiClass : classes) {
            collectMethodsByTrackingKeyRecursive(psiClass, out);
        }
        return out;
    }

    private static void collectMethodsByTrackingKeyRecursive(PsiClass psiClass,
                                                             java.util.LinkedHashMap<String, PsiMethod> out) {
        if (psiClass == null || out == null) return;
        for (PsiMethod method : psiClass.getMethods()) {
            String key = buildMethodTrackingKey(method);
            if (key != null && !key.isBlank()) out.putIfAbsent(key, method);
        }
        for (PsiClass inner : psiClass.getInnerClasses()) {
            collectMethodsByTrackingKeyRecursive(inner, out);
        }
    }

    private static String buildMethodTrackingKey(String className, String methodName, int parameterCount) {
        String safeClass = (className == null || className.isBlank()) ? "<no-class>" : className;
        String safeMethod = (methodName == null || methodName.isBlank()) ? "<unknown>" : methodName;
        return safeClass + "#" + safeMethod + "#" + parameterCount;
    }

    private static String extractClassNameFromTrackingKey(String trackingKey) {
        if (trackingKey == null || trackingKey.isBlank()) return "";
        int idx = trackingKey.indexOf('#');
        return idx < 0 ? trackingKey : trackingKey.substring(0, idx);
    }

    // Extract clone code blocks embedded by the detection agent in `clone.reason`.
    // Expected tags:
    //   [CLONE_CODE_A]...[/CLONE_CODE_A]
    //   [CLONE_CODE_B]...[/CLONE_CODE_B]
    private static String[] extractCloneCodeABFromReason(String reason) {
        try {
            if (reason == null || reason.isBlank()) return new String[]{"", ""};
            Matcher ma = Pattern.compile("(?s)\\[CLONE_CODE_A\\](.*?)\\[/CLONE_CODE_A\\]").matcher(reason);
            Matcher mb = Pattern.compile("(?s)\\[CLONE_CODE_B\\](.*?)\\[/CLONE_CODE_B\\]").matcher(reason);
            String a = ma.find() ? ma.group(1) : "";
            String b = mb.find() ? mb.group(1) : "";
            return new String[]{a == null ? "" : a.strip(), b == null ? "" : b.strip()};
        } catch (Throwable t) {
            return new String[]{"", ""};
        }
    }

    /**
     * Best-effort check for a very common valid Extract Method pattern:
     * - Two methods that were previously duplicated are now simple delegations to the SAME helper.
     * - The helper method is newly introduced in the proposed source.
     *
     * This is used as a safety valve for cases where pair-similarity heuristics fail (before==after==1.0).
     */
    private static boolean looksLikeValidExtractMethodDelegation(String beforeSource, String afterSource,
                                                                 String wrapperA, String wrapperB) {
        if (beforeSource == null || afterSource == null) return false;

        if (!afterSource.contains("private") || !afterSource.contains("(")) return false;

        if (wrapperA == null || wrapperA.isBlank() || wrapperB == null || wrapperB.isBlank()) return false;

        String helperA = findDelegationTarget(afterSource, wrapperA);
        String helperB = findDelegationTarget(afterSource, wrapperB);
        if (helperA == null || helperB == null) return false;
        if (!helperA.equals(helperB)) return false;

        if (!containsMethodDeclaration(afterSource, helperA)) return false;
        if (containsMethodDeclaration(beforeSource, helperA)) return false;

        String helperBody = extractMethodBody(afterSource, helperA);
        if (helperBody == null) return false;
        if (helperBody.trim().length() < 80) return false;

        return true;
    }

    /** Find `return helper(...);` target inside a named method. */
    private static String findDelegationTarget(String source, String methodName) {
        if (source == null || methodName == null) return null;
        try {
            // Very small regex: locate the method header, then capture first `return X(` within its body.
            Pattern p = Pattern.compile("(?s)\\b" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{(.*?)\\}");
            Pattern p2 = Pattern.compile("(?s)\\b" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{(.*)\\n\\s*\\}");
            Matcher m = p.matcher(source);
            if (!m.find()) {
                m = p2.matcher(source);
                if (!m.find()) return null;
            }
            String body = m.group(1);
            if (body == null) return null;
            Matcher r = Pattern.compile("\\breturn\\s+([A-Za-z_][\\w]*)\\s*\\(").matcher(body);
            if (r.find()) return r.group(1);
            Matcher c = Pattern.compile("\\b([A-Za-z_][\\w]*)\\s*\\(").matcher(body);
            if (c.find()) return c.group(1);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Best-effort check that a method declaration exists for the given name. */
    private static boolean containsMethodDeclaration(String source, String methodName) {
        if (source == null || methodName == null) return false;
        try {
            Pattern p = Pattern.compile("(?m)^(?:\\s*(?:public|protected|private|static|final|synchronized|abstract)\\s+)*[A-Za-z_][\\w<>\\[\\]]*\\s+" + Pattern.quote(methodName) + "\\s*\\(");
            return p.matcher(source).find();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Extract method body (text between outer braces) for a given method name. */
    private static String extractMethodBody(String source, String methodName) {
        if (source == null || methodName == null) return null;
        try {
            Pattern p = Pattern.compile("(?s)\\b" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{(.*?)\\}");
            Matcher m = p.matcher(source);
            if (!m.find()) return null;
            return m.group(1);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class CloneMethodSnapshot {
        final SmartPsiElementPointer<PsiMethod> pointer;
        final String className;
        final String methodName;
        final int parameterCount;
        final String exactMethodKey;
        final String baselineBodyText;
        final String displayName;

        CloneMethodSnapshot(SmartPsiElementPointer<PsiMethod> pointer,
                            String className,
                            String methodName,
                            int parameterCount,
                            String exactMethodKey,
                            String baselineBodyText,
                            String displayName) {
            this.pointer = pointer;
            this.className = className == null ? "<no-class>" : className;
            this.methodName = methodName == null ? "<unknown>" : methodName;
            this.parameterCount = parameterCount;
            this.exactMethodKey = exactMethodKey == null ? "" : exactMethodKey;
            this.baselineBodyText = baselineBodyText == null ? "" : baselineBodyText;
            this.displayName = displayName == null ? "<unknown>" : displayName;
        }
    }

    private static String buildMethodDisplayName(PsiMethod method) {
        if (method == null) return "<unknown>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";
        return className + "#" + method.getName();
    }

    private static String buildMethodTrackingKey(PsiMethod method) {
        if (method == null) return "<unknown>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";
        return className + "#" + method.getName() + "#" + method.getParameterList().getParametersCount();
    }

    private static String buildUsefulnessMethodKey(PsiMethod method) {
        if (method == null) return "<unknown>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";

        StringBuilder sb = new StringBuilder();
        sb.append(className).append("#").append(method.getName()).append("(");
        try {
            PsiParameter[] parameters = method.getParameterList() == null
                    ? new PsiParameter[0]
                    : method.getParameterList().getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(parameters[i].getType().getCanonicalText());
            }
        } catch (Throwable ignored) {
        }
        sb.append(")");
        return sb.toString();
    }

    private static String getMethodClassName(PsiMethod method) {
        if (method == null) return "<no-class>";
        PsiClass cls = method.getContainingClass();
        String className = cls == null ? "<no-class>"
                : (cls.getQualifiedName() != null ? cls.getQualifiedName() : cls.getName());
        if (className == null || className.isBlank()) className = "<no-class>";
        return className;
    }

    private static String getMethodBodyText(PsiMethod method) {
        if (method == null) return "";
        try {
            PsiElement body = method.getBody();
            if (body != null) {
                String text = body.getText();
                return text == null ? "" : text;
            }
            String text = method.getText();
            return text == null ? "" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String normalizeMethodBodyText(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            String s = text.replace("\r\n", "\n").replace('\r', '\n');
            s = s.replaceAll("//.*?(?=\n|$)", "");
            s = s.replaceAll("(?s)/\\*.*?\\*/", "");
            s = s.replaceAll("\\s+", " ").trim();
            return s;
        } catch (Throwable t) {
            return text;
        }
    }

    private static PsiMethod findMethodBySnapshot(Project project, VirtualFile vf, CloneMethodSnapshot snapshot) {
        try {
            if (project == null || project.isDisposed() || vf == null || snapshot == null) return null;

            if (snapshot.pointer != null) {
                PsiMethod pointed = snapshot.pointer.getElement();
                if (pointed != null && pointed.isValid()) {
                    String cls = getMethodClassName(pointed);
                    if (java.util.Objects.equals(snapshot.className, cls)
                            && java.util.Objects.equals(snapshot.methodName, pointed.getName())
                            && snapshot.parameterCount == pointed.getParameterList().getParametersCount()) {
                        return pointed;
                    }
                }
            }

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof PsiJavaFile javaFile)) return null;

            for (PsiClass psiClass : javaFile.getClasses()) {
                String cls = psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName();
                if (!java.util.Objects.equals(snapshot.className, cls)) continue;
                for (PsiMethod method : psiClass.getMethods()) {
                    if (!java.util.Objects.equals(snapshot.methodName, method.getName())) continue;
                    if (snapshot.parameterCount != method.getParameterList().getParametersCount()) continue;
                    return method;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static PsiMethod findMethodContainingLine(Project project, VirtualFile vf, int oneBasedLine) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            if (oneBasedLine <= 0) return null;

            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof PsiJavaFile)) return null;
            if (doc.getLineCount() <= 0) return null;

            int zeroBasedLine = Math.max(0, Math.min(oneBasedLine - 1, doc.getLineCount() - 1));
            int startOffset = doc.getLineStartOffset(zeroBasedLine);
            int endOffset = Math.max(startOffset, doc.getLineEndOffset(zeroBasedLine) - 1);

            PsiElement at = psiFile.findElementAt(startOffset);
            if (at == null) at = psiFile.findElementAt(endOffset);
            if (at == null) return null;

            return PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean methodContainsSnippet(PsiMethod method, String snippet) {
        if (method == null || snippet == null || snippet.isBlank()) return false;
        try {
            String rawSnippet = snippet.strip();
            String rawSnippetNoBraces = stripOuterBraces(rawSnippet);
            String normalizedSnippet = normalizeForMatch(rawSnippet);
            String normalizedSnippetNoBraces = normalizeForMatch(rawSnippetNoBraces);

            String methodText = method.getText();
            if (methodText != null && !methodText.isBlank()) {
                if (methodText.contains(rawSnippet)) return true;
                if (!rawSnippetNoBraces.isBlank() && methodText.contains(rawSnippetNoBraces)) return true;

                String normalizedMethodText = normalizeForMatch(methodText);
                if (!normalizedSnippet.isBlank() && normalizedMethodText.contains(normalizedSnippet)) return true;
                if (!normalizedSnippetNoBraces.isBlank() && normalizedMethodText.contains(normalizedSnippetNoBraces)) return true;
            }

            PsiElement body = method.getBody();
            String bodyText = body == null ? "" : body.getText();
            if (!bodyText.isBlank()) {
                if (bodyText.contains(rawSnippet)) return true;
                if (!rawSnippetNoBraces.isBlank() && bodyText.contains(rawSnippetNoBraces)) return true;

                String normalizedBodyText = normalizeForMatch(bodyText);
                if (!normalizedSnippet.isBlank() && normalizedBodyText.contains(normalizedSnippet)) return true;
                if (!normalizedSnippetNoBraces.isBlank() && normalizedBodyText.contains(normalizedSnippetNoBraces)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean textMatchesSnippet(String candidateText, String snippet) {
        if (candidateText == null || candidateText.isBlank() || snippet == null || snippet.isBlank()) return false;

        String rawSnippet = snippet.strip();
        String rawSnippetNoBraces = stripOuterBraces(rawSnippet);
        String normalizedSnippet = normalizeForMatch(rawSnippet);
        String normalizedSnippetNoBraces = normalizeForMatch(rawSnippetNoBraces);
        String normalizedCandidate = normalizeForMatch(candidateText);

        if (candidateText.contains(rawSnippet)) return true;
        if (!rawSnippetNoBraces.isBlank() && candidateText.contains(rawSnippetNoBraces)) return true;
        if (!normalizedSnippet.isBlank() && normalizedCandidate.equals(normalizedSnippet)) return true;
        if (!normalizedSnippetNoBraces.isBlank() && normalizedCandidate.equals(normalizedSnippetNoBraces)) return true;
        if (!normalizedSnippet.isBlank() && normalizedCandidate.contains(normalizedSnippet)) return true;
        return !normalizedSnippetNoBraces.isBlank() && normalizedCandidate.contains(normalizedSnippetNoBraces);
    }

    private static boolean snippetMatchesWholeMethod(PsiMethod method, String snippet) {
        if (method == null || snippet == null || snippet.isBlank()) return false;
        String methodText = method.getText();
        PsiCodeBlock body = method.getBody();
        String bodyText = body == null ? "" : body.getText();
        return textMatchesSnippet(methodText, snippet)
                || textMatchesSnippet(bodyText, snippet)
                || textMatchesSnippet(stripOuterBraces(bodyText), snippet);
    }

    private static detection.CloneRange offsetLineRange(Project project,
                                                        VirtualFile vf,
                                                        int startOffset,
                                                        int endOffset) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            int safeStart = Math.max(0, Math.min(startOffset, doc.getTextLength()));
            int safeEnd = Math.max(safeStart, Math.min(endOffset, doc.getTextLength()));
            detection.CloneRange range = new detection.CloneRange();
            range.startLine = doc.getLineNumber(safeStart) + 1;
            range.endLine = doc.getLineNumber(Math.max(safeStart, safeEnd - 1)) + 1;
            return range;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PsiMethod findMethodContainingCloneRange(Project project,
                                                            VirtualFile vf,
                                                            detection.CloneRange range) {
        if (range == null) return null;
        PsiMethod start = findMethodContainingLine(project, vf, range.startLine);
        PsiMethod end = findMethodContainingLine(project, vf, range.endLine);
        if (start != null && end != null) {
            String startKey = buildMethodTrackingKey(start);
            String endKey = buildMethodTrackingKey(end);
            if (startKey.equals(endKey)) return start;
        }
        return start != null ? start : end;
    }

    private static detection.CloneRange findFragmentRangeInMethod(Project project,
                                                                  VirtualFile vf,
                                                                  PsiMethod method,
                                                                  String fileSource,
                                                                  String snippet,
                                                                  detection.CloneRange rawRange) {
        if (method == null || snippet == null || snippet.isBlank()) return null;

        if (snippetMatchesWholeMethod(method, snippet)) {
            int[] methodLines = elementLineRange(project, vf, method);
            if (methodLines != null) {
                detection.CloneRange methodRange = new detection.CloneRange();
                methodRange.startLine = methodLines[0];
                methodRange.endLine = methodLines[1];
                return methodRange;
            }
        }

        PsiRangeCandidate best = findBestStatementSequenceCandidate(project, vf, method, fileSource, snippet, rawRange);
        PsiRangeCandidate single = findBestSingleElementCandidate(project, vf, method, snippet, rawRange);
        if (isBetterPsiRangeCandidate(single, best)) {
            best = single;
        }
        return best == null ? null : best.range;
    }

    private static PsiRangeCandidate findBestStatementSequenceCandidate(Project project,
                                                                        VirtualFile vf,
                                                                        PsiMethod method,
                                                                        String fileSource,
                                                                        String snippet,
                                                                        detection.CloneRange rawRange) {
        if (method == null || fileSource == null || fileSource.isBlank() || snippet == null || snippet.isBlank()) return null;

        PsiRangeCandidate best = null;
        java.util.Collection<PsiCodeBlock> blocks = PsiTreeUtil.findChildrenOfType(method, PsiCodeBlock.class);
        for (PsiCodeBlock block : blocks) {
            if (block == null) continue;
            PsiStatement[] statements = block.getStatements();
            for (int start = 0; start < statements.length; start++) {
                for (int end = start; end < statements.length; end++) {
                    PsiStatement first = statements[start];
                    PsiStatement last = statements[end];
                    if (first == null || last == null) continue;

                    int startOffset = first.getTextRange().getStartOffset();
                    int endOffset = last.getTextRange().getEndOffset();
                    if (startOffset < 0 || endOffset <= startOffset || endOffset > fileSource.length()) continue;

                    String candidateText = fileSource.substring(startOffset, endOffset);
                    if (!textMatchesSnippet(candidateText, snippet)) continue;

                    detection.CloneRange range = offsetLineRange(project, vf, startOffset, endOffset);
                    PsiRangeCandidate candidate = buildPsiRangeCandidate(range, rawRange);
                    if (isBetterPsiRangeCandidate(candidate, best)) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static PsiRangeCandidate findBestSingleElementCandidate(Project project,
                                                                    VirtualFile vf,
                                                                    PsiMethod method,
                                                                    String snippet,
                                                                    detection.CloneRange rawRange) {
        if (method == null || snippet == null || snippet.isBlank()) return null;

        PsiRangeCandidate best = null;
        for (PsiElement element : PsiTreeUtil.findChildrenOfAnyType(method, PsiStatement.class, PsiCodeBlock.class)) {
            if (element == null) continue;
            if (element instanceof PsiCodeBlock && element == method.getBody()) continue;
            if (!textMatchesSnippet(element.getText(), snippet)) continue;

            int[] lines = elementLineRange(project, vf, element);
            if (lines == null) continue;

            detection.CloneRange range = new detection.CloneRange();
            range.startLine = lines[0];
            range.endLine = lines[1];
            PsiRangeCandidate candidate = buildPsiRangeCandidate(range, rawRange);
            if (isBetterPsiRangeCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static PsiRangeCandidate buildPsiRangeCandidate(detection.CloneRange range,
                                                            detection.CloneRange preferredRange) {
        if (range == null) return null;
        int distance = preferredRange == null
                ? 0
                : Math.abs(range.startLine - preferredRange.startLine) + Math.abs(range.endLine - preferredRange.endLine);
        int span = Math.max(0, range.endLine - range.startLine);
        return new PsiRangeCandidate(range, distance, span);
    }

    private static boolean isBetterPsiRangeCandidate(PsiRangeCandidate candidate,
                                                     PsiRangeCandidate best) {
        if (candidate == null) return false;
        if (best == null) return true;
        if (candidate.distanceScore != best.distanceScore) {
            return candidate.distanceScore < best.distanceScore;
        }
        if (candidate.spanScore != best.spanScore) {
            return candidate.spanScore < best.spanScore;
        }
        if (candidate.range.startLine != best.range.startLine) {
            return candidate.range.startLine < best.range.startLine;
        }
        return candidate.range.endLine < best.range.endLine;
    }

    private static final class PsiRangeCandidate {
        private final detection.CloneRange range;
        private final int distanceScore;
        private final int spanScore;

        private PsiRangeCandidate(detection.CloneRange range, int distanceScore, int spanScore) {
            this.range = range;
            this.distanceScore = distanceScore;
            this.spanScore = spanScore;
        }
    }

    private static PsiMethod findMethodForCloneSnippet(Project project,
                                                       VirtualFile vf,
                                                       String fileSource,
                                                       String snippet,
                                                       detection.CloneRange preferredRange) {
        try {
            if (project == null || project.isDisposed() || vf == null) return null;
            if (snippet == null || snippet.isBlank()) return null;

            PsiMethod preferredHost = findMethodContainingCloneRange(project, vf, preferredRange);
            if (preferredHost != null && (methodContainsSnippet(preferredHost, snippet) || snippetMatchesWholeMethod(preferredHost, snippet))) {
                return preferredHost;
            }

            PsiMethod exactWhole = findWholeMethodCoveredBySnippet(project, vf, fileSource, snippet);
            if (exactWhole != null) return exactWhole;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (!(psiFile instanceof PsiJavaFile)) return null;

            PsiMethod best = null;
            long bestScore = Long.MAX_VALUE;
            for (PsiMethod method : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
                if (!methodContainsSnippet(method, snippet)) continue;

                long score = 0L;
                if (preferredRange != null) {
                    int[] methodLines = elementLineRange(project, vf, method);
                    if (methodLines != null) {
                        int deltaStart = Math.abs(methodLines[0] - preferredRange.startLine);
                        int deltaEnd = Math.abs(methodLines[1] - preferredRange.endLine);
                        score = (long) deltaStart + deltaEnd;
                    }
                }

                if (best == null || score < bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addCloneMethodSnapshot(java.util.Map<String, CloneMethodSnapshot> out,
                                               Project project,
                                               PsiMethod method,
                                               Consumer<String> viewer) {
        try {
            if (out == null || project == null || project.isDisposed() || method == null) return;

            String key = buildMethodTrackingKey(method);
            if (out.containsKey(key)) return;

            SmartPsiElementPointer<PsiMethod> ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
            String displayName = buildMethodDisplayName(method);
            String className = getMethodClassName(method);
            String methodName = method.getName();
            int parameterCount = method.getParameterList().getParametersCount();
            String exactMethodKey = buildUsefulnessMethodKey(method);
            String baselineBodyText = normalizeMethodBodyText(getMethodBodyText(method));
            out.put(key, new CloneMethodSnapshot(ptr, className, methodName, parameterCount, exactMethodKey, baselineBodyText, displayName));
            logStage(viewer, "WATCH", "tracking cloned method: " + displayName);
        } catch (Throwable t) {
            logStage(viewer, "WATCH", "failed to snapshot cloned method: " + t.getMessage());
        }
    }

	    private static java.util.List<CloneMethodSnapshot> captureCloneMethodSnapshots(
		            Project project,
		            VirtualFile vf,
            detection.DetectedClone clone,
            Consumer<String> viewer
    ) {
        java.util.LinkedHashMap<String, CloneMethodSnapshot> out = new java.util.LinkedHashMap<>();
        try {
            if (project == null || project.isDisposed() || vf == null || clone == null || clone.ranges == null) {
                return new java.util.ArrayList<>();
            }

            String fileSource = "";
	            try {
	                Document doc = FileDocumentManager.getInstance().getDocument(vf);
	                if (doc != null) fileSource = doc.getText();
	            } catch (Throwable ignored) {}

	            java.util.List<String> cloneCodes = getDetectedCloneCodes(clone, fileSource);
	            int occurrenceCount = Math.max(
	                    cloneCodes.size(),
	                    clone.ranges == null ? 0 : clone.ranges.size()
	            );
	            for (int i = 0; i < occurrenceCount; i++) {
	                detection.CloneRange range = (clone.ranges == null || i >= clone.ranges.size()) ? null : clone.ranges.get(i);
	                String code = i < cloneCodes.size() ? cloneCodes.get(i) : "";

	                PsiMethod method = findMethodForCloneSnippet(project, vf, fileSource, code, range);
	                if (method == null && range != null) {
	                    method = findMethodContainingLine(project, vf, range.startLine);
	                }
	                if (method == null && range != null) {
	                    method = findMethodContainingLine(project, vf, range.endLine);
	                }
	                if (method == null) continue;
	                addCloneMethodSnapshot(out, project, method, viewer);
	            }
	        } catch (Throwable t) {
	            logStage(viewer, "WATCH", "failed to capture cloned method snapshots: " + t.getMessage());
	        }
	        return new java.util.ArrayList<>(out.values());
	    }

	    private static java.util.List<usefulnessChecker.TargetMethodHint> buildUsefulnessTargetHints(
	            java.util.List<CloneMethodSnapshot> snapshots
	    ) {
	        java.util.ArrayList<usefulnessChecker.TargetMethodHint> out = new java.util.ArrayList<>();
	        if (snapshots == null || snapshots.isEmpty()) return out;

	        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
	        for (CloneMethodSnapshot snapshot : snapshots) {
	            if (snapshot == null) continue;
	            String className = snapshot.className == null ? "" : snapshot.className;
	            String methodName = snapshot.methodName == null ? "" : snapshot.methodName;
	            String key = snapshot.exactMethodKey == null || snapshot.exactMethodKey.isBlank()
	                    ? (className + "#" + methodName + "#" + snapshot.parameterCount)
	                    : snapshot.exactMethodKey;
	            if (!seen.add(key)) continue;
	            out.add(new usefulnessChecker.TargetMethodHint(className, methodName, snapshot.parameterCount, snapshot.exactMethodKey));
	        }
	        return out;
	    }


    private static String findModifiedCloneMethod(Project project,
                                                  VirtualFile vf,
                                                  java.util.List<CloneMethodSnapshot> snapshots) {
        try {
            if (snapshots == null || snapshots.isEmpty()) return null;
            for (CloneMethodSnapshot snapshot : snapshots) {
                if (snapshot == null) continue;

                PsiMethod method = findMethodBySnapshot(project, vf, snapshot);
                if (method == null || !method.isValid()) {
                    continue;
                }

                String currentBodyText = normalizeMethodBodyText(getMethodBodyText(method));
                if (!java.util.Objects.equals(snapshot.baselineBodyText, currentBodyText)) {
                    return snapshot.displayName;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

}
