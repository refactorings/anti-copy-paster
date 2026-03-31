package org.jetbrains.research.anticopypaster.workflow;
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
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import com.intellij.openapi.application.ApplicationManager;
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
import javax.swing.*;
import org.jetbrains.research.anticopypaster.llm.LlmClient;
import org.jetbrains.research.anticopypaster.llm.LlmClientFactory;
import org.jetbrains.research.anticopypaster.llm.NoopLlmClient;
import org.jetbrains.research.anticopypaster.rag.RagService;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

public final class CloneRefactorWorkflow {
    private static final String REFACTOR_RAG_DB_RESOURCE = "rag/refactor_database.csv";

    // RAG retrieval tuning
    private static final int REFACTOR_RAG_TOP_K = 5;
    private static final int REFACTOR_RAG_MAX_CHARS = 8000;

    // Refactor proposal preview (console)
    private static final int REFACTOR_PROPOSAL_PREVIEW_CHARS = 12000;

    // Captured for buildProjectClasspathFromIde + streaming viewers in test runner
    private static volatile Project _LAST_PROJECT_FOR_TESTS = null;
    private static volatile Consumer<String> _LAST_TEST_VIEWER = null;
    private static volatile String _LAST_TARGET_FQN = null;

    // Hard-cancel support
    private static final AtomicReference<Thread> _CURRENT_WORKFLOW_THREAD = new AtomicReference<>();
    private static final AtomicReference<Process> _CURRENT_PROCESS = new AtomicReference<>();
    private static final AtomicLong _WORKFLOW_RUN_ID = new AtomicLong(0L);

    // Last converted (pure JUnit4) EvoSuite test FQN (best-effort), if conversion succeeded.
    private static volatile String _LAST_CONVERTED_TEST_FQN = null;

    // If set, points to a temp directory containing compiled classes for the *proposed* refactoring.
    // This directory should be prepended to the runtime classpath so EvoSuite/JUnit see the refactored class
    // without modifying the original project output.
    private static volatile String _LAST_PATCHED_CLASSES_DIR = null;

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

                _LAST_PROJECT_FOR_TESTS = project;
                _LAST_TEST_VIEWER = viewer;

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
                        // lightweight debug: show if model is returning nothing
                        String preview = resp == null ? "null" : resp.strip();
                        if (preview.length() > 300) preview = preview.substring(0, 300) + "...";
                        logStage(viewer, "LLM", "response preview: " + preview.replace("\n", "\\n"));
                        return resp == null ? "" : resp;
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

                        logStage(viewer, "DETECTION", "NiCad file saved: " + nicadOut);
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
                                }

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

                int detectedCloneCount = 1;
                if (det.clones != null) {
                    for (detection.DetectedClone c : det.clones) {
                        if (c != null && c.ranges != null) {
                            detectedCloneCount += c.ranges.size() - 1;
                        }
                    }
                }
                logStage(viewer, "DETECTION", "detected clone range count=" + detectedCloneCount);

                if (detectedCloneCount < minimumCloneCount) {
                    logStage(viewer, "DETECTION", "stopped: detected clone range count " + detectedCloneCount +
                            " is smaller than minimumCloneCount=" + minimumCloneCount + " for Clone_multiagent. This parameter is set by the user in the settings and can be adjusted based on your needs.");
                    showNotification(project,
                            "[Clone] Only " + detectedCloneCount + " clone range(s) detected in: " + fileName +
                                    ". Need at least " + minimumCloneCount + " to continue.",
                            NotificationType.INFORMATION);
                    return;
                }

                detection.DetectedClone clone = det.clones.get(0);
                logStage(viewer, "DETECTION", "clone found: " + clone.id);
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
                    logStage(viewer, "WATCH", "tracking " + watchedCloneMethods.size() + " cloned method(s) for user edits");
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
                        String combinedFeedback = "";
                        if (refactorRagGuidance != null && !refactorRagGuidance.isBlank()) {
                            combinedFeedback += refactorRagGuidance.strip() + "\n\n";
                        }
                        if (feedback != null && !feedback.isBlank()) {
                            combinedFeedback += "[PREVIOUS_FEEDBACK]\n" + feedback.strip() + "\n";
                        }
                        String feedbackForRefactor = combinedFeedback.isBlank() ? null : combinedFeedback;


                        refactoring.RefactorResult rr =
                                refactorAgent.refactorFile(
                                        fileName,
                                        currentSource,
                                        convertClone(clone),
                                        feedbackForRefactor,
                                        llmCaller
                                );


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
                                    if (urBeforeCompile.reasons != null && urBeforeCompile.reasons.contains("EXTRACT_METHOD_NOT_CONFIRMED")) {
                                        String[] wrappers = parseWrapperNamesFromUsefulnessDebug(extractUsefulnessDebugText(urBeforeCompile));
                                        if (wrappers != null && wrappers.length == 2
                                                && looksLikeValidExtractMethodDelegation(currentSource, proposedSource, wrappers[0], wrappers[1])) {
                                            overridden = true;
                                            isUseful = true;
                                            logStage(viewer, "USEFUL", "override: both wrappers delegate to the same extracted helper (EXTRACT_METHOD_NOT_CONFIRMED)");
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

                                String feedbackPrompt = usefulnessChecker.buildFeedbackPrompt(urBeforeCompile.reasons);
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
                                        proposedSource == null ? "" : proposedSource,
                                        reasonsText,
                                        reasonDefinition == null ? "" : reasonDefinition,
                                        notesText,
                                        feedbackPrompt == null ? "" : feedbackPrompt
                                );
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
                                String codeA = ab[0];
                                String codeB = ab[1];

                                // Fallbacks: if detection didn't embed code blocks, use the pasted snippet as A.
                                if (codeA == null || codeA.isBlank()) codeA = pastedSnippet == null ? "" : pastedSnippet;

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

                                if (frBeforeCompile != null && !frBeforeCompile.isUseful) {
                                    isUseful = false;
                                    String msg = "Not useful refactoring proposal: strategy=" + frBeforeCompile.strategy +
                                             ", reasons=" + frBeforeCompile.reasons;
                                    logStage(viewer, "USEFUL", "Not useful refactoring proposal" + msg);
                                    showNotification(project,
                                            "[Clone] Refactor NOT recommended (attempt " + attempt + ")\n" +
                                                    "for: " + fileName + "\n \n" +
                                                    "Reason:\n" +
                                                    frBeforeCompile.strategy + "\n" +
                                                    definitionForReason(frBeforeCompile.strategy),
                                            NotificationType.WARNING);

                                    String reasonsText = String.valueOf(frBeforeCompile.reasons);
                                    String strategyText = String.valueOf(frBeforeCompile.strategy);
                                    String reasonDefinition = definitionForReason(frBeforeCompile.strategy);
                                    String notesText = (frBeforeCompile.notes == null || frBeforeCompile.notes.isBlank())
                                            ? ""
                                            : ("\n\n[USEFULNESS_NOTES]\n" + frBeforeCompile.notes);

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
                                            proposedSource == null ? "" : proposedSource,
                                            reasonsText,
                                            strategyText,
                                            reasonDefinition == null ? "" : reasonDefinition,
                                            notesText
                                    );
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
                            _LAST_PATCHED_CLASSES_DIR = null;
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
                        String ideCp = buildProjectClasspathFromIde(project);
                        String ideSourcePath = buildProjectSourcepathFromIde(project);
                        ideCp = buildCompileClasspathWithSourceRoots(project, ideCp);
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
                            patchedOutDir = compileProposedSourceToTemp(project, ioFile, fileName, proposedSource, ideCp);
                            _LAST_PATCHED_CLASSES_DIR = patchedOutDir == null ? null : patchedOutDir.getAbsolutePath();
                            compileLog = "BUILD SUCCESS\n";
                        } catch (Exception ce) {
                            _LAST_PATCHED_CLASSES_DIR = null;
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
                            logStage(viewer, "COMPILE", "failed: " + feedback);
                            showNotification(project, "[Clone] Compilation failed (attempt " + attempt + ") for: " + fileName + "\n" + feedback, NotificationType.ERROR);
                            continue;
                        }

                        logStage(viewer, "COMPILE", "ok (isolated)");
                        showNotification(project, "Compilation successful: Ready to run (attempt " + attempt + ") for: " + fileName, NotificationType.INFORMATION);

                        /* ===== Test ===== */
                        // Resolve target FQN from the proposed source (PSI still reflects the original file until we apply).
                        String targetFqn = resolvePrimaryClassFqn(proposedSource, fileName);
                        if (targetFqn == null) targetFqn = "";

                        if (targetFqn.isBlank()) {
                            // Fallback #1: regex-based parsing from the in-memory source we just produced
                            String f1 = resolvePrimaryClassFqn(currentSource, fileName);
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
                                String f2 = resolvePrimaryClassFqn(onDisk, fileName);
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
                        _LAST_TARGET_FQN = targetFqn;

                        if (targetFqn == null || targetFqn.isBlank()) {
                            feedback = "Test skipped: target class FQN could not be resolved.";
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
                                        CloneRefactorWorkflow::runTests,
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
                                currentSource = proposedSource;
                                Files.writeString(ioFile.toPath(), currentSource, StandardCharsets.UTF_8);
                                logStage(viewer, "REFACTOR", "applied after verification");
                                showNotification(project, "[Clone] Tests passed. Refactor applied for: " + fileName, NotificationType.INFORMATION);
                            } else {
                                logStage(viewer, "REFACTOR", "verified but not applied (user cancelled)");
                                showNotification(project, "[Clone] Tests passed but changes were not applied (user cancelled): " + fileName, NotificationType.WARNING);
                            }

                            logStage(viewer, "WORKFLOW", "SUCCESS");
                            // Clear patched classes dir for subsequent runs
                            _LAST_PATCHED_CLASSES_DIR = null;
                            return;
                        }

                        feedback = tr == null ? "Tests failed." :
                                (tr.summary != null ? tr.summary : tr.raw);

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

    /* ============================================================
     * Test
     * ============================================================ */

    private static String runTests(testing.TestRunRequest req) {
        try {
            if (req == null) return "Test error: request is null.";
            String projectDir = getReqString(req, "projectDir", "projectPath", "baseDir");
            if (projectDir == null || projectDir.isBlank()) return "Test error: projectDir is empty.";

            String targetClass = getReqString(req, "targetClass", "targetClassFqn", "classFqn", "className", "targetFqn");
            Consumer<String> viewer = (_LAST_TEST_VIEWER != null) ? _LAST_TEST_VIEWER : null;
            if (viewer != null) viewer.accept("[TEST] received targetClass=" + (targetClass == null ? "null" : targetClass));

            // Fallback: some TestRunRequest implementations may not store the target class in an accessible field.
            if (targetClass == null || targetClass.isBlank() || "all".equalsIgnoreCase(targetClass)) {
                String fallback = _LAST_TARGET_FQN;
                if (fallback != null && !fallback.isBlank()) {
                    targetClass = fallback;
                    if (viewer != null) viewer.accept("[TEST] targetClass missing in request; using workflow fallback: " + targetClass);
                }
            }

            if (targetClass == null || targetClass.isBlank() || "all".equalsIgnoreCase(targetClass)) {
                return "Test error: targetClass (FQN) is empty. Cannot run EvoSuite without a class name.";
            }

            // 1) Materialize EvoSuite jar to a stable per-project path (avoid broken IntelliJ library roots)
            File evosuiteJar = materializeResourceToProjectLib(new File(projectDir), "tools/evosuite-1.2.0.jar", "evosuite-1.2.0.jar");
            if (evosuiteJar == null || !evosuiteJar.exists()) {
                return "Test error: EvoSuite jar not found in resources at tools/evosuite-1.2.0.jar";
            }

            // 2) Build project classpath from IntelliJ (module output + dependencies)
            String projectCp = buildProjectClasspathFromIde(_LAST_PROJECT_FOR_TESTS);
            if (_LAST_PATCHED_CLASSES_DIR != null && !_LAST_PATCHED_CLASSES_DIR.isBlank()) {
                // Prepend patched output so the refactored class shadows the original compiled class.
                projectCp = _LAST_PATCHED_CLASSES_DIR + File.pathSeparator + (projectCp == null ? "" : projectCp);
            }
            if (projectCp == null || projectCp.isBlank()) {
                return "Test error: IDE classpath is empty. Make sure the project is imported and has an SDK.";
            }

            // 3) Output directories
            File base = new File(projectDir);
            File outRoot = new File(base, ".anticopypaster" + File.separator + "evosuite-tests");
            if (!outRoot.exists() && !outRoot.mkdirs()) {
                return "Test error: failed to create output dir: " + outRoot.getAbsolutePath();
            }
            File testDir = new File(outRoot, "tests");
            File reportDir = new File(outRoot, "reports");
            testDir.mkdirs();
            reportDir.mkdirs();
            // Ensure IntelliJ resolves native EvoSuite tests (EvoRunner/runtime) in the editor and build.
            ensureTestDependencies(_LAST_PROJECT_FOR_TESTS, base, evosuiteJar);

            // 4) Run EvoSuite (generation only; does not execute the generated tests here)
            // Common EvoSuite properties: -Dtest_dir / -Dreport_dir
            List<String> cmd = new java.util.ArrayList<>();

            String javaExe = resolveJavaExecutable(_LAST_PROJECT_FOR_TESTS);
            cmd.add(javaExe);
            String forcedJavaHome = deriveJavaHome(javaExe);

            String jv = readJavaVersion(javaExe);
            int major = parseJavaMajorVersion(jv);
            if (viewer != null) {
                String vv = (jv == null ? "" : jv.strip());
                if (vv.length() > 800) vv = vv.substring(0, 800) + "\n...<truncated>...";
                viewer.accept("[EvoSuite] java -version:\n" + vv);
                viewer.accept("[EvoSuite] detected java major=" + major);
            }

            // If Java 9+, add module opens to avoid InaccessibleObjectException (XStream reflective access)
            if (major >= 9) {
                cmd.add("--add-opens");
                cmd.add("java.base/java.lang=ALL-UNNAMED");
                cmd.add("--add-opens");
                cmd.add("java.base/java.util=ALL-UNNAMED");
                cmd.add("--add-opens");
                cmd.add("java.base/java.io=ALL-UNNAMED");
                cmd.add("--add-opens");
                cmd.add("java.desktop/java.awt=ALL-UNNAMED");
                cmd.add("-Djava.awt.headless=true");
            }

            // IMPORTANT: On Java 8, EvoSuite/ByteBuddy agent attachment relies on the Attach API provider from tools.jar.
            // When running with `-jar`, tools.jar is NOT on the system classpath, and AttachProvider.providers() may return empty.
            // So for Java 8 we launch via `-cp tools.jar;evosuite.jar org.evosuite.EvoSuite`.
            if (major == 8) {
                String toolsJar = null;
                try {
                    if (forcedJavaHome != null && !forcedJavaHome.isBlank()) {
                        File tj = new File(forcedJavaHome, "lib" + File.separator + "tools.jar");
                        if (tj.exists()) toolsJar = tj.getAbsolutePath();
                    }
                } catch (Throwable ignored) {}

                String cpLaunch;
                if (toolsJar != null && !toolsJar.isBlank()) {
                    cpLaunch = toolsJar + File.pathSeparator + evosuiteJar.getAbsolutePath();
                } else {
                    // Fallback: still run with EvoSuite jar only (best-effort).
                    cpLaunch = evosuiteJar.getAbsolutePath();
                    if (viewer != null) viewer.accept("[EvoSuite] WARN: tools.jar not found under JAVA_HOME; agent attach may fail on Java 8.");
                }

                cmd.add("-cp");
                cmd.add(cpLaunch);
                cmd.add("org.evosuite.EvoSuite");
            } else {
                cmd.add("-jar");
                cmd.add(evosuiteJar.getAbsolutePath());
            }

            cmd.add("-class");
            cmd.add(targetClass);
            cmd.add("-projectCP");
            cmd.add(projectCp);
            cmd.add("-Dtest_dir=" + testDir.getAbsolutePath());
            cmd.add("-Dreport_dir=" + reportDir.getAbsolutePath());
            cmd.add("-Dsandbox=false");
            // Disable EvoSuite testability transformation: it can rewrite the SUT bytecode and
            // generate calls to synthetic "*Clone" methods (eg, getDataClone) that don't exist
            // in the original sources, causing generated tests to fail compilation.
            cmd.add("-Dtestability_transformation=false");
            // Some EvoSuite versions also expose this toggle as "TT".
            cmd.add("-DTT=false");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(base);
            pb.redirectErrorStream(true);

            if (forcedJavaHome != null) {
                if (viewer != null) viewer.accept("[EvoSuite] Enforcing JAVA_HOME=" + forcedJavaHome);
                pb.environment().put("JAVA_HOME", forcedJavaHome);
            }

            if (viewer != null) viewer.accept("[EvoSuite] Running: " + String.join(" ", cmd));

            ProcessRun pr = runProcessStreaming(pb, viewer);

            String evOut = pr == null ? "" : (pr.output == null ? "" : pr.output);
            int exit = pr == null ? -1 : pr.exitCode;

            // EvoSuite sometimes returns exitCode=0 even when generation fails.
            // Detect failure by common markers and by checking whether any test files were generated.
            boolean hasFailureMarker = containsAnyIgnoreCase(evOut,
                    "problem for ",
                    "failed to generate",
                    "no statistics has been saved",
                    "error while initializing target class",
                    "no converter available",
                    "conversionexception",
                    "inaccessibleobjectexception",
                    "fatal",
                    "exception in thread");

            boolean hasGeneratedTests = false;
            try {
                if (testDir.exists()) {
                    // EvoSuite may generate tests under package subdirectories (e.g., tests/org/...)
                    hasGeneratedTests = java.nio.file.Files.walk(testDir.toPath())
                            .anyMatch(p -> p != null && p.toString().endsWith(".java"));
                }
            } catch (Throwable ignored) {
                // ignore
            }

            // If tests were generated, run them in *native EvoSuite form* (keep *_ESTest.java + scaffolding + EvoRunner).
            String nativeTestFqn = "";
            if (hasGeneratedTests) {
                String simpleName;
                try {
                    int idx = targetClass.lastIndexOf('.');
                    simpleName = idx >= 0 ? targetClass.substring(idx + 1) : targetClass;
                } catch (Throwable t) {
                    simpleName = targetClass;
                }
                if (simpleName == null) simpleName = "";

                String estestName = simpleName + "_ESTest.java";
                java.nio.file.Path estestPath = findFileRecursivelyByName(testDir.toPath(), estestName);
                if (estestPath == null) {
                    // Fallback: first *_ESTest.java under testDir
                    estestPath = findFirstFileBySuffix(testDir.toPath(), "_ESTest.java");
                }
                if (estestPath == null) {
                    return "[EVOSUITE]\n" +
                            "exitCode=" + exit + "\n" +
                            "status=tests_failed\n" +
                            "reason=generated tests folder exists, but no *_ESTest.java found\n";
                }

                // Determine scaffolding file next to *_ESTest.java
                java.nio.file.Path scaffPath = null;
                try {
                    String scaffName = simpleName + "_ESTest_scaffolding.java";
                    java.nio.file.Path candidate = estestPath.getParent().resolve(scaffName);
                    if (java.nio.file.Files.exists(candidate)) scaffPath = candidate;
                } catch (Throwable ignored) {
                    // ignore
                }
                if (scaffPath == null) {
                    // Fallback: find any *_ESTest_scaffolding.java
                    scaffPath = findFirstFileBySuffix(testDir.toPath(), "_ESTest_scaffolding.java");
                }

                String rawTest = java.nio.file.Files.readString(estestPath, java.nio.charset.StandardCharsets.UTF_8);
                if (rawTest == null) rawTest = "";
                if (looksLikeLiteralNNewlineCorruption(rawTest)) {
                    rawTest = repairLiteralNNewlineCorruption(rawTest);
                }
                rawTest = normalizeBrokenNewlines(rawTest);

                nativeTestFqn = resolvePrimaryClassFqn(rawTest, estestName);
                _LAST_CONVERTED_TEST_FQN = nativeTestFqn; // reuse existing field as "last generated test to run"

                // Copy native EvoSuite tests into test (preserve package dirs).
                File projectBase = new File(projectDir);
                // Use a standalone test directory instead of src/test/java to avoid IntelliJ package mismatch
                File srcTestJava = new File(projectBase, "test");
                if (!srcTestJava.exists() && !srcTestJava.mkdirs()) {
                    return "[EVOSUITE]\n" +
                            "exitCode=" + exit + "\n" +
                            "status=tests_failed\n" +
                            "reason=failed to create test\n";
                }

                // Preserve package-relative structure
                java.nio.file.Path rel = testDir.toPath().relativize(estestPath);
                java.nio.file.Path targetEst = srcTestJava.toPath().resolve(rel);
                java.nio.file.Files.createDirectories(targetEst.getParent());

                // Clean up any previously-copied converted version for this class
                try {
                    String convertedName = simpleName + "_EvoSuiteJUnit4Test.java";
                    java.nio.file.Path oldConverted = targetEst.getParent().resolve(convertedName);
                    if (java.nio.file.Files.exists(oldConverted)) {
                        java.nio.file.Files.delete(oldConverted);
                    }
                } catch (Throwable ignored) {
                    // best-effort
                }

                // Copy *_ESTest.java
                java.nio.file.Files.copy(estestPath, targetEst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // Copy scaffolding if present
                if (scaffPath != null && java.nio.file.Files.exists(scaffPath)) {
                    java.nio.file.Path scaffRel = testDir.toPath().relativize(scaffPath);
                    java.nio.file.Path targetScaff = srcTestJava.toPath().resolve(scaffRel);
                    java.nio.file.Files.createDirectories(targetScaff.getParent());
                    java.nio.file.Files.copy(scaffPath, targetScaff, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                if (viewer != null) {
                    viewer.accept("[EvoSuite] Native test written: " + targetEst);
                    if (scaffPath != null) viewer.accept("[EvoSuite] Native scaffolding written: " + srcTestJava.toPath().resolve(testDir.toPath().relativize(scaffPath)));
                    if (nativeTestFqn != null && !nativeTestFqn.isBlank()) {
                        viewer.accept("[EvoSuite] Native test FQN: " + nativeTestFqn);
                    }
                }
            }

            // Now actually run tests. For native EvoSuite tests we must run via JUnitCore with EvoSuite runtime on the classpath.
            // (Build tools usually won't have EvoSuite runtime configured.)
            String testOutput = runProjectTests(base, viewer, evosuiteJar);

            boolean testSuccess =
                    testOutput != null &&
                    testOutput.contains("BUILD SUCCESS");

            String finalStatus = testSuccess ? "tests_passed" : "tests_failed";

            String excerpt = evOut;
            if (excerpt.length() > 6000) excerpt = excerpt.substring(0, 6000) + "\n...<truncated>...";

            return "[EVOSUITE]\n" +
                    "exitCode=" + exit + "\n" +
                    "status=" + finalStatus + "\n" +
                    "generatedTests=" + hasGeneratedTests + "\n" +
                    "outputDir=" + outRoot.getAbsolutePath() + "\n" +
                    "---output---\n" +
                    testOutput;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Test error: CANCELLED";
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return "Test error: CANCELLED";
            }
            return "Test error: " + e.getMessage();
        }
    }

    private static void ensureTestDependencies(Project project, File baseDir, File evosuiteJar) {
        try {
            if (project == null || project.isDisposed()) return;

            // Materialize JUnit/Hamcrest to a stable on-disk location under the project so IDE libraries don't break.
            File junit = materializeResourceToProjectLib(baseDir, "tools/junit-4.12.jar", "junit-4.12.jar");
            File hamcrest = materializeResourceToProjectLib(baseDir, "tools/hamcrest-core-1.3.jar", "hamcrest-core-1.3.jar");

            // Also accept user-provided libs/ if resource extraction failed.
            if ((junit == null || !junit.exists()) && baseDir != null) {
                File libJunit = new File(baseDir, "libs" + File.separator + "junit-4.12.jar");
                if (libJunit.exists()) junit = libJunit;
            }
            if ((hamcrest == null || !hamcrest.exists()) && baseDir != null) {
                File libHam = new File(baseDir, "libs" + File.separator + "hamcrest-core-1.3.jar");
                if (libHam.exists()) hamcrest = libHam;
            }

            final File finalJunit = junit;
            final File finalHamcrest = hamcrest;
            final File finalEvo = evosuiteJar;

            // Add dependencies + mark project/test as test sources root.
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            com.intellij.openapi.module.Module[] modules = com.intellij.openapi.module.ModuleManager.getInstance(project).getModules();
                            if (modules == null || modules.length == 0) return;
                            com.intellij.openapi.module.Module module = modules[0];

                            com.intellij.openapi.roots.ModifiableRootModel model = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).getModifiableModel();

                            // 1) Mark project/test as Test Sources Root (best-effort)
                            try {
                                if (baseDir != null) {
                                    File srcTestJava = new File(baseDir, "test");
                                    if (srcTestJava.exists()) {
                                        String url = com.intellij.openapi.vfs.VfsUtil.pathToUrl(srcTestJava.getAbsolutePath());
                                        for (com.intellij.openapi.roots.ContentEntry ce : model.getContentEntries()) {
                                            if (ce == null) continue;
                                            // Avoid duplicates
                                            boolean already = false;
                                            for (com.intellij.openapi.roots.SourceFolder sf : ce.getSourceFolders()) {
                                                if (sf != null && url.equals(sf.getUrl()) && sf.isTestSource()) {
                                                    already = true;
                                                    break;
                                                }
                                            }
                                            if (!already) {
                                                // Only add if this content entry contains the folder
                                                try {
                                                    com.intellij.openapi.vfs.VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(srcTestJava);
                                                    if (vf != null) {
                                                        com.intellij.openapi.vfs.VirtualFile root = ce.getFile();
                                                        if (root != null && com.intellij.openapi.vfs.VfsUtilCore.isAncestor(root, vf, true)) {
                                                            ce.addSourceFolder(url, true);
                                                            break;
                                                        }
                                                    }
                                                } catch (Throwable ignored) {
                                                    // ignore
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }

                            // 2) Add TEST-scoped module library if missing
                            final String libName = "AntiCopyPaster-TestLib";
                            com.intellij.openapi.roots.libraries.LibraryTable lt = model.getModuleLibraryTable();
                            com.intellij.openapi.roots.libraries.Library existing = lt.getLibraryByName(libName);

                            if (existing == null) {
                                com.intellij.openapi.roots.libraries.Library lib = lt.createLibrary(libName);
                                com.intellij.openapi.roots.libraries.Library.ModifiableModel lm = lib.getModifiableModel();

                                try {
                                    if (finalJunit != null && finalJunit.exists()) {
                                        lm.addRoot(com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(finalJunit), com.intellij.openapi.roots.OrderRootType.CLASSES);
                                    }
                                    if (finalHamcrest != null && finalHamcrest.exists()) {
                                        lm.addRoot(com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(finalHamcrest), com.intellij.openapi.roots.OrderRootType.CLASSES);
                                    }
                                    if (finalEvo != null && finalEvo.exists()) {
                                        lm.addRoot(com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(finalEvo), com.intellij.openapi.roots.OrderRootType.CLASSES);
                                    }
                                } catch (Throwable ignored) {
                                    // ignore
                                }

                                lm.commit();

                                com.intellij.openapi.roots.LibraryOrderEntry entry = model.addLibraryEntry(lib);
                                entry.setScope(com.intellij.openapi.roots.DependencyScope.TEST);
                            } else {
                                // Ensure scope is TEST (best-effort)
                                try {
                                    for (com.intellij.openapi.roots.OrderEntry oe : model.getOrderEntries()) {
                                        if (oe instanceof com.intellij.openapi.roots.LibraryOrderEntry) {
                                            com.intellij.openapi.roots.LibraryOrderEntry loe = (com.intellij.openapi.roots.LibraryOrderEntry) oe;
                                            if (loe.getLibraryName() != null && libName.equals(loe.getLibraryName())) {
                                                loe.setScope(com.intellij.openapi.roots.DependencyScope.TEST);
                                                break;
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                    // ignore
                                }

                                // Repair broken roots if they point to deleted temp files.
                                try {
                                    com.intellij.openapi.roots.libraries.Library.ModifiableModel lm = existing.getModifiableModel();
                                    // Remove any missing roots
                                    for (String url : lm.getUrls(com.intellij.openapi.roots.OrderRootType.CLASSES)) {
                                        try {
                                            com.intellij.openapi.vfs.VirtualFile vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(url);
                                            if (vf == null || !vf.exists()) {
                                                lm.removeRoot(url, com.intellij.openapi.roots.OrderRootType.CLASSES);
                                            }
                                        } catch (Throwable ignored) {
                                            // ignore
                                        }
                                    }
                                    // Re-add expected roots from stable locations
                                    if (finalJunit != null && finalJunit.exists()) {
                                        lm.addRoot(com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(finalJunit), com.intellij.openapi.roots.OrderRootType.CLASSES);
                                    }
                                    if (finalHamcrest != null && finalHamcrest.exists()) {
                                        lm.addRoot(com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(finalHamcrest), com.intellij.openapi.roots.OrderRootType.CLASSES);
                                    }
                                    if (finalEvo != null && finalEvo.exists()) {
                                        lm.addRoot(com.intellij.openapi.vfs.VfsUtil.getUrlForLibraryRoot(finalEvo), com.intellij.openapi.roots.OrderRootType.CLASSES);
                                    }
                                    lm.commit();
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }

                            model.commit();
                        } catch (Throwable t) {
                            // best-effort: do not fail workflow on IDE config issues
                        }
                    });
                } catch (Throwable ignored) {
                    // ignore
                }
            }, com.intellij.openapi.application.ModalityState.any());

        } catch (Throwable ignored) {
            // ignore
        }
    }

    // Helper to run Maven/Gradle tests, or fallback to JUnitCore
    private static String runProjectTests(File baseDir, Consumer<String> viewer, File evosuiteJar) {
        try {
            File pom = new File(baseDir, "pom.xml");
            File gradle = new File(baseDir, "build.gradle");
            File gradleKts = new File(baseDir, "build.gradle.kts");

            // 1. Maven/Gradle: Delegate to build tool
            // NOTE: Native EvoSuite tests require EvoSuite runtime on the classpath. Build tools typically don't include it.
            // So if we have a generated EvoSuite test to run, prefer the JUnitCore path below.
            if ((pom.exists() || gradle.exists() || gradleKts.exists()) && (_LAST_CONVERTED_TEST_FQN == null || _LAST_CONVERTED_TEST_FQN.isBlank())) {
                ProcessBuilder pb;
                if (pom.exists()) {
                    pb = new ProcessBuilder("mvn", "-q", "test");
                } else {
                    boolean isWin = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
                    String exe = new File(baseDir, isWin ? "gradlew.bat" : "gradlew").exists()
                            ? (isWin ? ".\\gradlew.bat" : "./gradlew") : "gradle";
                    pb = new ProcessBuilder(exe, "test");
                }
                pb.directory(baseDir);
                pb.redirectErrorStream(true);

                if (isCancelled()) return "[CANCELLED]\n";
                if (viewer != null) viewer.accept("[TEST] Running build tool tests...");
                ProcessRun pr = runProcessStreaming(pb, viewer);
                String out = pr == null ? "" : (pr.output == null ? "" : pr.output);
                return out;
            }

            // 2. Fallback: Run generated test via JUnitCore
            if (viewer != null) viewer.accept("[TEST] No build tool found. Running via JUnitCore...");

            Project ideProject = _LAST_PROJECT_FOR_TESTS;
            if (ideProject == null || ideProject.isDisposed()) {
                return "Error: IDE project is unavailable.";
            }

            // Locate the test file
            String testFqn = _LAST_CONVERTED_TEST_FQN;
            File testFile = null;

            // Try resolving file from FQN if possible, or search dir
            if (testFqn != null) {
                // Heuristic search for the file we just wrote
                File srcTest = new File(baseDir, "test");
                String path = testFqn.replace('.', File.separatorChar) + ".java";
                testFile = new File(srcTest, path);
                if (!testFile.exists()) {
                    File tempRoot = new File(baseDir, ".anticopypaster" + File.separator + "evosuite-tests" + File.separator + "tests");
                    testFile = new File(tempRoot, path);
                }
            }

            if (testFile == null || !testFile.exists()) {
                return "Error: Could not locate test file for " + testFqn;
            }

            // Prepare Classpath
            String cp = buildProjectClasspathFromIde(ideProject);

            // Ensure tests compile/run against the same (possibly patched) classes that EvoSuite used.
            if (_LAST_PATCHED_CLASSES_DIR != null && !_LAST_PATCHED_CLASSES_DIR.isBlank()) {
                cp = _LAST_PATCHED_CLASSES_DIR + File.pathSeparator + (cp == null ? "" : cp);
            }

            // Ensure JUnit/Hamcrest are on CP (JUnit 4.12 needs hamcrest at runtime)
            File junit = materializeResourceToProjectLib(baseDir, "tools/junit-4.12.jar", "junit-4.12.jar");
            File hamcrest = materializeResourceToProjectLib(baseDir, "tools/hamcrest-core-1.3.jar", "hamcrest-core-1.3.jar");

            // Ensure EvoSuite runtime is on CP for native *_ESTest tests (EvoRunner, RuntimeSettings, mocks, etc.)
            File evoRuntime = evosuiteJar;

            // Fallbacks: try project libs/ and local Maven repo if the resource is missing
            if (hamcrest == null || !hamcrest.exists()) {
                File libHamcrest = new File(baseDir, "libs" + File.separator + "hamcrest-core-1.3.jar");
                if (libHamcrest.exists()) {
                    hamcrest = libHamcrest;
                } else {
                    File m2Hamcrest = new File(System.getProperty("user.home"),
                            ".m2" + File.separator + "repository" + File.separator +
                                    "org" + File.separator + "hamcrest" + File.separator +
                                    "hamcrest-core" + File.separator + "1.3" + File.separator +
                                    "hamcrest-core-1.3.jar");
                    if (m2Hamcrest.exists()) {
                        hamcrest = m2Hamcrest;
                    }
                }
            }

            if (junit == null || !junit.exists()) {
                File libJunit = new File(baseDir, "libs" + File.separator + "junit-4.12.jar");
                if (libJunit.exists()) junit = libJunit;
            }

            String runCp = cp;
            if (junit != null && junit.exists()) runCp += File.pathSeparator + junit.getAbsolutePath();
            if (hamcrest != null && hamcrest.exists()) runCp += File.pathSeparator + hamcrest.getAbsolutePath();
            if (evoRuntime != null && evoRuntime.exists()) runCp += File.pathSeparator + evoRuntime.getAbsolutePath();

            // STEP 3: Compile the generated test file(s) manually.
            // Native EvoSuite tests require compiling both *_ESTest.java and *_ESTest_scaffolding.java.
            if (viewer != null) viewer.accept("[TEST] Compiling generated test (native EvoSuite)...");
            File compiledClassesDir;
            try {
                java.util.List<File> sources = new java.util.ArrayList<>();
                sources.add(testFile);

                // Add scaffolding if it exists next to the test
                try {
                    String tf = testFile.getName();
                    if (tf.endsWith("_ESTest.java")) {
                        String base = tf.substring(0, tf.length() - "_ESTest.java".length());
                        File scaff = new File(testFile.getParentFile(), base + "_ESTest_scaffolding.java");
                        if (scaff.exists()) sources.add(scaff);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }

                compiledClassesDir = compileFiles(ideProject, sources, runCp);
            } catch (Exception e) {
                return "Compilation Error: " + e.getMessage();
            }

            // Add compiled output to classpath
            runCp = compiledClassesDir.getAbsolutePath() + File.pathSeparator + runCp;

            // STEP 4: Run JUnitCore
            String javaExe = resolveJavaExecutable(ideProject);
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(runCp);
            cmd.add("org.junit.runner.JUnitCore");
            cmd.add(testFqn);

            if (viewer != null) viewer.accept("[TEST] Executing: " + String.join(" ", cmd));
            if (viewer != null && (hamcrest == null || !hamcrest.exists())) {
                viewer.accept("[TEST] WARN: hamcrest-core-1.3.jar not found; JUnit 4.12 will fail with NoClassDefFoundError.");
            }
            if (viewer != null && (evoRuntime == null || !evoRuntime.exists())) {
                viewer.accept("[TEST] WARN: EvoSuite runtime jar not found; native *_ESTest tests will fail to compile/run (missing EvoRunner/runtime).");
            }

            ProcessBuilder jpb = new ProcessBuilder(cmd);
            jpb.directory(baseDir);
            jpb.redirectErrorStream(true);

            ProcessRun pr = runProcessStreaming(jpb, viewer);
            String out = pr.output == null ? "" : pr.output;

            if (out.contains("OK (") && !containsAnyIgnoreCase(out, "FAILURES!!!")) {
                return out + "\nBUILD SUCCESS\n";
            }
            return out + "\nBUILD FAILED\n";

        } catch (Exception e) {
            return "Execution Error: " + e.getMessage();
        }
    }

    /* ============================================================
     * Helpers
     * ============================================================ */

    private static final class ProcessRun {
        final int exitCode;
        final String output;
        ProcessRun(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static ProcessRun runProcessStreaming(ProcessBuilder pb, java.util.function.Consumer<String> viewer) throws Exception {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        Thread killer = null;

        try {
            p = pb.start();
            _CURRENT_PROCESS.set(p);
            final Process proc = p;

            killer = new Thread(() -> {
                try {
                    while (proc.isAlive()) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) {
                            try {
                                if (viewer != null) viewer.accept("[WORKFLOW] Cancel requested; killing process...");
                            } catch (Throwable ignored) {}
                            try { proc.destroy(); } catch (Throwable ignored) {}
                            try {
                                if (proc.isAlive()) proc.destroyForcibly();
                            } catch (Throwable ignored) {}
                            break;
                        }
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }, "acp-cancel-killer");
            killer.setDaemon(true);
            killer.start();

            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("CANCELLED");
                    }
                    sb.append(line).append("\n");
                    if (viewer != null && !isCancelled()) viewer.accept(line);
                    if (isCancelled()) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Throwable ignored) {
                // best-effort
            }

            int exit;
            try {
                exit = proc.waitFor();
            } catch (InterruptedException e) {
                try { proc.destroy(); } catch (Throwable ignored) {}
                try {
                    if (proc.isAlive()) proc.destroyForcibly();
                } catch (Throwable ignored) {}
                Thread.currentThread().interrupt();
                throw e;
            } catch (Throwable t) {
                exit = -1;
            }

            if (isCancelled() || Thread.currentThread().isInterrupted()) {
                try { proc.destroy(); } catch (Throwable ignored) {}
                try {
                    if (proc.isAlive()) proc.destroyForcibly();
                } catch (Throwable ignored) {}
                return new ProcessRun(-1, sb.toString() + "\n[CANCELLED]\n");
            }

            return new ProcessRun(exit, sb.toString());
        } finally {
            if (killer != null) {
                try { killer.interrupt(); } catch (Throwable ignored) {}
            }
            if (p != null) {
                _CURRENT_PROCESS.compareAndSet(p, null);
            }
        }
    }

    /**
     * Extract a resource on the classpath to a temp file (for tools shipped under src/main/resources).
     */
    private static File materializeResourceToTempFile(String resourcePath, String prefix, String suffix) throws IOException {
        InputStream in = CloneRefactorWorkflow.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) return null;
        File tmp = File.createTempFile(prefix, suffix);
        tmp.deleteOnExit();
        try (in; OutputStream out = new FileOutputStream(tmp)) {
            in.transferTo(out);
        }
        return tmp;
    }

    /**
     * Reflection-based access to keep workflow compatible across evolving agent DTOs.
     * Tries public fields first, then getter methods.
     */
    private static String getReqString(Object req, String... names) {
        if (req == null || names == null) return null;
        Class<?> cls = req.getClass();

        for (String n : names) {
            if (n == null || n.isBlank()) continue;

            // 1) Public field
            try {
                java.lang.reflect.Field f = cls.getField(n);
                Object v = f.get(req);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
                // ignore
            }

            // 2) Declared field (private/protected/package)
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(req);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
                // ignore
            }

            // 3) Getter method (getX)
            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(req);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
                // ignore
            }

            // 4) Declared getter (non-public)
            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getDeclaredMethod(mname);
                m.setAccessible(true);
                Object v = m.invoke(req);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
                // ignore
            }

            // 5) Boolean-style getter (isX)
            try {
                String mname = "is" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(req);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return null;
    }

    /**
     * Resolve the primary class FQN from a Java source text (best-effort).
     * This avoids PSI dependencies and works even before compilation.
     */
    private static String resolvePrimaryClassFqn(String javaSource, String fileName) {
        if (javaSource == null) return "";
        String pkg = "";
        Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w\\.]*)\\s*;").matcher(javaSource);
        if (pm.find()) pkg = pm.group(1);

        // Prefer the first public top-level class/interface/enum/record, else fall back to file basename.
        Matcher cm = Pattern.compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)?(class|interface|enum|record)\\s+([A-Za-z_][\\w]*)\\b").matcher(javaSource);
        String simple = null;
        if (cm.find()) {
            simple = cm.group(2);
        } else {
            Matcher c2 = Pattern.compile("(?m)^\\s*(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)?(class|interface|enum|record)\\s+([A-Za-z_][\\w]*)\\b").matcher(javaSource);
            if (c2.find()) simple = c2.group(2);
        }
        if (simple == null || simple.isBlank()) {
            if (fileName != null && fileName.endsWith(".java")) {
                simple = fileName.substring(0, fileName.length() - 5);
            } else {
                simple = "";
            }
        }
        if (pkg.isBlank()) return simple;
        if (simple.isBlank()) return pkg;
        return pkg + "." + simple;
    }

    /**
     * Builds classpath from IDE module settings.
     * Includes production and test output paths, ensuring they exist on disk.
     */
    private static String buildProjectClasspathFromIde(Project project) {
        if (project == null || project.isDisposed()) return "";
        try {
            com.intellij.openapi.module.Module[] modules = com.intellij.openapi.module.ModuleManager.getInstance(project).getModules();
            if (modules == null || modules.length == 0) return "";

            java.util.Set<String> paths = new java.util.LinkedHashSet<>();

            for (com.intellij.openapi.module.Module m : modules) {
                if (m == null || m.isDisposed()) continue;

                try {
                    com.intellij.util.PathsList orderEntries = com.intellij.openapi.roots.OrderEnumerator.orderEntries(m)
                            .recursively().withoutSdk().classes().getPathsList();
                    paths.addAll(orderEntries.getPathList());
                } catch (Throwable ignored) {
                }

                try {
                    com.intellij.openapi.roots.CompilerModuleExtension ext =
                            com.intellij.openapi.roots.CompilerModuleExtension.getInstance(m);
                    if (ext != null) {
                        if (ext.getCompilerOutputPath() != null) {
                            paths.add(ext.getCompilerOutputPath().getPath());
                        }
                        if (ext.getCompilerOutputPathForTests() != null) {
                            paths.add(ext.getCompilerOutputPathForTests().getPath());
                        }
                    }
                } catch (Throwable ignored) {
                }

                // Fallback for old multi-module projects: include source roots too.
                try {
                    com.intellij.openapi.roots.ModuleRootManager rootManager =
                            com.intellij.openapi.roots.ModuleRootManager.getInstance(m);
                    if (rootManager != null) {
                        for (com.intellij.openapi.vfs.VirtualFile root : rootManager.getSourceRoots(false)) {
                            if (root != null && root.getPath() != null && !root.getPath().isBlank()) {
                                paths.add(root.getPath());
                            }
                        }
                        for (com.intellij.openapi.vfs.VirtualFile root : rootManager.getSourceRoots(true)) {
                            if (root != null && root.getPath() != null && !root.getPath().isBlank()) {
                                paths.add(root.getPath());
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // Heuristic: include common output dirs anywhere under the project if they exist.
            try {
                String basePath = project.getBasePath();
                if (basePath != null && !basePath.isBlank()) {
                    java.nio.file.Path base = java.nio.file.Paths.get(basePath);
                    java.nio.file.Files.walk(base)
                            .filter(p -> p != null && java.nio.file.Files.isDirectory(p))
                            .forEach(p -> {
                                String s = p.toString();
                                if (s.endsWith(java.io.File.separator + "target" + java.io.File.separator + "classes")
                                        || s.endsWith(java.io.File.separator + "target" + java.io.File.separator + "test-classes")
                                        || s.endsWith(java.io.File.separator + "classes" + java.io.File.separator + "production")
                                        || s.endsWith(java.io.File.separator + "classes" + java.io.File.separator + "test")
                                        || s.endsWith(java.io.File.separator + "out" + java.io.File.separator + "production")
                                        || s.endsWith(java.io.File.separator + "out" + java.io.File.separator + "test")) {
                                    paths.add(s);
                                }
                            });
                }
            } catch (Throwable ignored) {
            }

            java.util.Set<String> validPaths = new java.util.LinkedHashSet<>();
            for (String p : paths) {
                if (p != null && !p.isBlank() && new File(p).exists()) {
                    validPaths.add(p);
                }
            }

            return String.join(File.pathSeparator, validPaths);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Given a path to a java executable (e.g. /path/to/jdk/bin/java),
     * returns the likely JAVA_HOME (e.g. /path/to/jdk).
     */
    private static String deriveJavaHome(String javaExe) {
        if (javaExe == null || javaExe.isBlank()) return null;
        File f = new File(javaExe);
        // Walk up: java -> bin -> JAVA_HOME
        File bin = f.getParentFile();
        if (bin != null && "bin".equalsIgnoreCase(bin.getName())) {
            File home = bin.getParentFile();
            if (home != null && home.exists()) {
                return home.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Best-effort java executable resolution. Prefers project SDK, JAVA_8_HOME, JAVA_HOME, else "java".
     */
    private static String resolveJavaExecutable(Project project) {
        // 0) Prefer explicit Java 8 home if provided (EvoSuite 1.2.0 is most stable on Java 8).
        String java8 = System.getenv("JAVA_8_HOME");
        if (java8 != null && !java8.isBlank()) {
            File f = new File(java8, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
            File fExe = new File(java8, "bin" + File.separator + "java.exe");
            if (fExe.exists()) return fExe.getAbsolutePath();
        }

        // 1) Use IntelliJ Project SDK if available.
        try {
            if (project != null && !project.isDisposed()) {
                com.intellij.openapi.projectRoots.Sdk sdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getProjectSdk();
                if (sdk != null) {
                    String home = sdk.getHomePath();
                    if (home != null && !home.isBlank()) {
                        File f = new File(home, "bin" + File.separator + "java");
                        if (f.exists()) return f.getAbsolutePath();
                        File fExe = new File(home, "bin" + File.separator + "java.exe");
                        if (fExe.exists()) return fExe.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 2) Fallback to JAVA_HOME
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            File f = new File(javaHome, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
            File fExe = new File(javaHome, "bin" + File.separator + "java.exe");
            if (fExe.exists()) return fExe.getAbsolutePath();
        }

        return "java";
    }

    private static boolean containsAnyIgnoreCase(String haystack, String... needles) {
        if (haystack == null) return false;
        String h = haystack.toLowerCase(java.util.Locale.ROOT);
        if (needles == null) return false;
        for (String n : needles) {
            if (n == null) continue;
            if (h.contains(n.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    private static String readProcessOutput(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        p.waitFor();
        return sb.toString();
    }

    // NOTE: RAG retrieval uses clone code (via buildRefactorRagQueryText) when available; ranges here are only for agent context.
    private static refactoring.DetectedClone convertClone(detection.DetectedClone c) {
        String representative = "";
        if (c != null) {
            if (c.cloneCodeA != null && !c.cloneCodeA.isBlank()) {
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

    /** Run `java -version` and return output for debugging (stdout+stderr). */
    private static String readJavaVersion(String javaExe) {
        if (javaExe == null || javaExe.isBlank()) return "";
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return readProcessOutput(p);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Best-effort parse of Java major version from `java -version` output. */
    private static int parseJavaMajorVersion(String javaVersionOutput) {
        if (javaVersionOutput == null || javaVersionOutput.isBlank()) return -1;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("version\\s+\\\"([^\\\"]+)\\\"")
                .matcher(javaVersionOutput);

        if (!m.find()) return -1;

        String ver = m.group(1);
        if (ver == null) return -1;
        ver = ver.trim();

        try {
            if (ver.startsWith("1.")) {
                // 1.8.x => major 8
                String[] parts = ver.split("\\.");
                if (parts.length >= 2) return Integer.parseInt(parts[1]);
                return -1;
            }
            // 9+ => first token is major
            String[] parts = ver.split("\\.");
            return Integer.parseInt(parts[0]);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Recursively find the first file with the given name under root. */
    private static java.nio.file.Path findFileRecursivelyByName(java.nio.file.Path root, String fileName) {
        if (root == null || fileName == null || fileName.isBlank()) return null;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(root)) {
            return s.filter(p -> p != null && p.getFileName() != null && fileName.equals(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Recursively find the first file ending with the given suffix under root. */
    private static java.nio.file.Path findFirstFileBySuffix(java.nio.file.Path root, String suffix) {
        if (root == null || suffix == null || suffix.isBlank()) return null;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(root)) {
            return s.filter(p -> p != null && p.toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean looksLikeLiteralNNewlineCorruption(String code) {
        if (code == null) return false;
        // common corruption patterns observed when stdout/stderr encoding or newline normalization goes wrong
        return code.contains("npackage ") || code.contains(";n") || code.contains("{n") || code.contains("}n") || code.contains(")n");
    }

    private static String repairLiteralNNewlineCorruption(String code) {
        if (code == null) return "";

        // IMPORTANT: Only repair *very specific* patterns that strongly indicate newline corruption.

        String s = code;

        // High-confidence boundary patterns where a newline became 'n'
        s = s.replace(";n", ";\n");
        s = s.replace(")n", ")\n");
        s = s.replace("{n", "{\n");
        s = s.replace("}n", "}\n");

        // Keyword boundaries at start-of-line only (avoid touching identifiers like nodeFigure0)
        s = s.replaceAll("(?m)^[\\t ]*n(?=package\\s)", "");
        s = s.replaceAll("(?m)^[\\t ]*n(?=import\\s)", "");
        s = s.replaceAll("(?m)^[\\t ]*n(?=(public|private|protected)\\b)", "");
        s = s.replaceAll("(?m)^[\\t ]*n(?=(class|interface|enum|record)\\b)", "");

        // Also handle common inline corruption
        s = s.replace("npackage ", "\npackage ");
        s = s.replace("nimport ", "\nimport ");

        return s;
    }

    private static String normalizeBrokenNewlines(String code) {
        if (code == null) return "";
        // Normalize CRLF/LF first
        String out = code.replace("\r\n", "\n").replace("\r", "\n");
        // If we still see lots of ";n"-like artifacts, split them.
        out = out.replaceAll(";\\s*n+", ";\n");
        out = out.replaceAll("\\{\\s*n+", "{\n");
        out = out.replaceAll("\\}\\s*n+", "}\n");
        return out;
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

    /**
     * Compile the proposed refactored source to a temp output directory, without modifying the original source file.
     * The caller should prepend the returned output directory to the runtime classpath so tests execute against
     * the refactored class.
     */
    private static File compileProposedSourceToTemp(Project project,
                                                    File originalFile,
                                                    String fileName,
                                                    String proposedSource,
                                                    String classpath) throws Exception {
        if (originalFile == null) throw new IllegalArgumentException("originalFile is null");
        if (proposedSource == null) throw new IllegalArgumentException("proposedSource is null");

        // Create temp workspace
        File tempSrcRoot = java.nio.file.Files.createTempDirectory("acp_refactor_src_").toFile();
        tempSrcRoot.deleteOnExit();
        File tempOut = java.nio.file.Files.createTempDirectory("acp_refactor_out_").toFile();
        tempOut.deleteOnExit();

        // Determine relative path under source root (best effort): preserve package directories.
        String pkg = "";
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w\\.]*)\\s*;\\s*$")
                .matcher(proposedSource);
        if (pm.find()) {
            pkg = pm.group(1);
            if (pkg == null) pkg = "";
        }

        String relDir = pkg.isBlank() ? "" : pkg.replace('.', File.separatorChar);
        File dir = relDir.isBlank() ? tempSrcRoot : new File(tempSrcRoot, relDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Failed to create temp source dir: " + dir.getAbsolutePath());
        }

        String fn = (fileName == null || fileName.isBlank()) ? originalFile.getName() : fileName;
        if (fn == null || fn.isBlank()) fn = "Refactored.java";
        File tempJava = new File(dir, fn);
        java.nio.file.Files.writeString(tempJava.toPath(), proposedSource, java.nio.charset.StandardCharsets.UTF_8);
        tempJava.deleteOnExit();

        // Resolve javac strictly from Project SDK (DO NOT fall back to PATH)
        if (project == null || project.isDisposed()) {
            throw new RuntimeException("Cannot compile: project is null or disposed");
        }

        com.intellij.openapi.projectRoots.Sdk sdk =
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null || sdk.getHomePath() == null || sdk.getHomePath().isBlank()) {
            throw new RuntimeException("Cannot resolve Project SDK for compilation");
        }

        File javacFile = new File(sdk.getHomePath(),
                "bin" + File.separator + (System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac"));

        if (!javacFile.exists()) {
            throw new RuntimeException("javac not found under Project SDK: " + javacFile.getAbsolutePath());
        }

        String javacExe = javacFile.getAbsolutePath();
        String sourcepath = buildProjectSourcepathFromIde(project);

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javacExe);
        cmd.add("-encoding");
        cmd.add("UTF-8");
        addJavacTargetFlags(cmd, sdk.getHomePath(), resolveProjectTargetMajor(project));
        cmd.add("-cp");
        cmd.add(classpath == null ? "" : classpath);
        if (sourcepath != null && !sourcepath.isBlank()) {
            cmd.add("-sourcepath");
            cmd.add(sourcepath);
        }
        cmd.add("-d");
        cmd.add(tempOut.getAbsolutePath());
        cmd.add(tempJava.getAbsolutePath());

        try {
            logStage("COMPILE", "javac classpath:\n" + (classpath == null ? "" : classpath));
            logStage("COMPILE", "javac sourcepath:\n" + (sourcepath == null ? "" : sourcepath));
        } catch (Throwable ignored) {
            // ignore
        }

        // First attempt: UTF-8
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readProcessOutput(p);
        int exitCode;
        try { exitCode = p.exitValue(); } catch (Throwable t) { exitCode = -1; }

        // If encoding error, retry with ISO-8859-1
        if (exitCode != 0 && out != null && out.contains("unmappable character")) {
            logStage("COMPILE", "Retry with ISO-8859-1 due to encoding error...");

            java.util.List<String> retryCmd = new java.util.ArrayList<>(cmd);
            for (int i = 0; i < retryCmd.size() - 1; i++) {
                if ("-encoding".equals(retryCmd.get(i))) {
                    retryCmd.set(i + 1, "ISO-8859-1");
                    break;
                }
            }

            ProcessBuilder pb2 = new ProcessBuilder(retryCmd);
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            out = readProcessOutput(p2);
            try { exitCode = p2.exitValue(); } catch (Throwable t) { exitCode = -1; }
        }

        if (exitCode != 0) {
            throw new RuntimeException("Compilation failed:\n" + out);
        }

        return tempOut;
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

    /**
     * Compile multiple Java source files into a temp output directory.
     * Used for native EvoSuite tests where both *_ESTest.java and *_ESTest_scaffolding.java must be compiled together.
     */
    private static File compileFiles(Project project, java.util.List<File> sourceFiles, String classpath) throws Exception {
        File outputDir = java.nio.file.Files.createTempDirectory("temp_test_classes").toFile();
        outputDir.deleteOnExit();

        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new RuntimeException("Compilation failed: no source files provided");
        }
        for (File f : sourceFiles) {
            if (f == null || !f.exists()) {
                throw new RuntimeException("Compilation failed: missing source file: " + (f == null ? "null" : f.getAbsolutePath()));
            }
        }

        if (project == null || project.isDisposed()) {
            throw new RuntimeException("Cannot compile tests: project is null or disposed");
        }

        com.intellij.openapi.projectRoots.Sdk sdk =
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null || sdk.getHomePath() == null || sdk.getHomePath().isBlank()) {
            throw new RuntimeException("Cannot resolve Project SDK for test compilation");
        }

        File javacFile = new File(sdk.getHomePath(),
                "bin" + File.separator + (System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac"));

        if (!javacFile.exists()) {
            throw new RuntimeException("javac not found under Project SDK: " + javacFile.getAbsolutePath());
        }

        String javacExe = javacFile.getAbsolutePath();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javacExe);
        cmd.add("-encoding");
        cmd.add("UTF-8");
        addJavacTargetFlags(cmd, sdk.getHomePath(), resolveProjectTargetMajor(project));
        cmd.add("-cp");
        cmd.add(classpath == null ? "" : classpath);
        cmd.add("-d");
        cmd.add(outputDir.getAbsolutePath());
        for (File f : sourceFiles) {
            cmd.add(f.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readProcessOutput(p);
        int code;
        try { code = p.exitValue(); } catch (Throwable t) { code = -1; }
        if (code != 0) {
            throw new RuntimeException("Compilation failed:\n" + out);
        }
        return outputDir;
    }

    /**
     * Build a javac sourcepath from all IDE modules.
     * This is needed for old multi-module projects that do not have compiled class output directories.
     */
    private static String buildProjectSourcepathFromIde(Project project) {
        if (project == null || project.isDisposed()) return "";
        try {
            java.util.Set<String> roots = new java.util.LinkedHashSet<>();

            com.intellij.openapi.module.Module[] modules =
                    com.intellij.openapi.module.ModuleManager.getInstance(project).getModules();
            if (modules != null) {
                for (com.intellij.openapi.module.Module m : modules) {
                    if (m == null || m.isDisposed()) continue;
                    try {
                        com.intellij.openapi.roots.ModuleRootManager rootManager =
                                com.intellij.openapi.roots.ModuleRootManager.getInstance(m);
                        if (rootManager == null) continue;

                        for (com.intellij.openapi.vfs.VirtualFile root : rootManager.getSourceRoots(false)) {
                            if (root != null) {
                                String p = root.getPath();
                                if (p != null && !p.isBlank() && new File(p).exists()) {
                                    roots.add(p);
                                }
                            }
                        }
                        for (com.intellij.openapi.vfs.VirtualFile root : rootManager.getSourceRoots(true)) {
                            if (root != null) {
                                String p = root.getPath();
                                if (p != null && !p.isBlank() && new File(p).exists()) {
                                    roots.add(p);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }

            // Generic fallback for old or non-standard multi-module projects.
            // Do not hardcode project/module names. Instead, discover likely Java source roots by shape.
            try {
                String basePath = project.getBasePath();
                if (basePath != null && !basePath.isBlank()) {
                    java.nio.file.Path base = java.nio.file.Paths.get(basePath);
                    java.nio.file.Files.walk(base)
                            .filter(p -> p != null && java.nio.file.Files.isDirectory(p))
                            .forEach(p -> {
                                try {
                                    String norm = p.toString().replace('\\', '/');
                                    String name = p.getFileName() == null ? "" : p.getFileName().toString();

                                    boolean looksLikeSourceRoot =
                                            norm.endsWith("/src/main/java")
                                                    || norm.endsWith("/src/test/java")
                                                    || norm.endsWith("/src")
                                                    || norm.endsWith("/test")
                                                    || norm.endsWith("/tests")
                                                    || "src".equals(name)
                                                    || "test".equals(name)
                                                    || "tests".equals(name);

                                    if (!looksLikeSourceRoot) return;
                                    if (!containsJavaFilesUnder(p, 6)) return;

                                    String candidate = p.toString();
                                    if (new File(candidate).exists()) {
                                        roots.add(candidate);
                                    }
                                } catch (Throwable ignored2) {
                                    // ignore this candidate
                                }
                            });
                }
            } catch (Throwable ignored) {
                // ignore
            }

            return String.join(File.pathSeparator, roots);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Merge an existing classpath with all source roots from the IDE so javac can resolve project-internal classes
     * even when there are no compiled output directories yet.
     */
    private static String buildCompileClasspathWithSourceRoots(Project project, String classpath) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        try {
            if (classpath != null && !classpath.isBlank()) {
                String[] cpEntries = classpath.split(java.util.regex.Pattern.quote(File.pathSeparator));
                for (String entry : cpEntries) {
                    if (entry != null && !entry.isBlank() && new File(entry).exists()) {
                        paths.add(entry);
                    }
                }
            }

        String sourcepath = buildProjectSourcepathFromIde(project);

            if (sourcepath != null && !sourcepath.isBlank()) {
                String[] sourceEntries = sourcepath.split(java.util.regex.Pattern.quote(File.pathSeparator));
                for (String entry : sourceEntries) {
                    if (entry != null && !entry.isBlank() && new File(entry).exists()) {
                        paths.add(entry);
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return String.join(File.pathSeparator, paths);
    }

    /**
     * Resolve the desired target bytecode major version for compilation.
     * Best-effort: prefer Project SDK version; fall back to parsing `java -version` from the SDK; default to 8.
     */
    private static int resolveProjectTargetMajor(Project project) {
        try {
            if (project == null || project.isDisposed()) return 8;
            com.intellij.openapi.projectRoots.Sdk sdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null) {
                // 1) Try SDK version string
                try {
                    String vs = sdk.getVersionString();
                    int m = parseMajorFromText(vs);
                    if (m > 0) return m;
                } catch (Throwable ignored) {}

                // 2) Try SDK java -version
                try {
                    String home = sdk.getHomePath();
                    String javaExe = javaExecutableFromSdkHome(home);
                    if (javaExe != null && !javaExe.isBlank()) {
                        String out = readJavaVersion(javaExe);
                        int m = parseJavaMajorVersion(out);
                        if (m > 0) return m;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return 8;
    }

    /** Best-effort parse a Java major version from a text like "JavaSDK 1.8", "17", "corretto-11", etc. */
    private static int parseMajorFromText(String s) {
        if (s == null || s.isBlank()) return -1;
        String t = s.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("1.8")) return 8;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})").matcher(t);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 8 && v <= 99) return v;
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** Build an absolute java executable path from an SDK home. */
    private static String javaExecutableFromSdkHome(String home) {
        try {
            if (home == null || home.isBlank()) return "";
            boolean isWin = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            File f = new File(home, "bin" + File.separator + (isWin ? "java.exe" : "java"));
            return f.exists() ? f.getAbsolutePath() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Add appropriate target flags to a javac command.
     * - If javac is 9+, prefer `--release <target>`.
     * - If javac is 8, use `-source/-target` (with 1.8 spelling for Java 8).
     */
    private static void addJavacTargetFlags(java.util.List<String> cmd, String sdkHome, int targetMajor) {
        if (cmd == null) return;
        int target = (targetMajor > 0 ? targetMajor : 8);

        int javacMajor = -1;
        try {
            String javaExe = javaExecutableFromSdkHome(sdkHome);
            if (javaExe != null && !javaExe.isBlank()) {
                String out = readJavaVersion(javaExe);
                javacMajor = parseJavaMajorVersion(out);
            }
        } catch (Throwable ignored) {}
        if (javacMajor <= 0) javacMajor = target; // best-effort

        if (javacMajor >= 9) {
            cmd.add("--release");
            cmd.add(String.valueOf(target));
        } else {
            // JDK 8
            cmd.add("-source");
            cmd.add(target == 8 ? "1.8" : String.valueOf(target));
            cmd.add("-target");
            cmd.add(target == 8 ? "1.8" : String.valueOf(target));
        }
    }

    /**
     * Copy a bundled JAR resource to a stable per-project location so IntelliJ libraries do not break
     * when OS temp directories are cleaned.
     */
    private static File materializeResourceToProjectLib(File baseDir, String resourcePath, String fileName) {
        try {
            if (baseDir == null) {
                // Fallback to temp if we do not know the project directory.
                return materializeResourceToTempFile(resourcePath, "acp-lib", ".jar");
            }
            File libDir = new File(baseDir, ".anticopypaster" + File.separator + "ide-libs");
            if (!libDir.exists()) libDir.mkdirs();
            File out = new File(libDir, fileName);
            if (out.exists() && out.length() > 0) return out;

            try (java.io.InputStream in = CloneRefactorWorkflow.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) return null;
                try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        os.write(buf, 0, r);
                    }
                }
            }
            return out.exists() ? out : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class CloneMethodSnapshot {
        final SmartPsiElementPointer<PsiMethod> pointer;
        final String className;
        final String methodName;
        final int parameterCount;
        final String baselineBodyText;
        final String displayName;

        CloneMethodSnapshot(SmartPsiElementPointer<PsiMethod> pointer,
                            String className,
                            String methodName,
                            int parameterCount,
                            String baselineBodyText,
                            String displayName) {
            this.pointer = pointer;
            this.className = className == null ? "<no-class>" : className;
            this.methodName = methodName == null ? "<unknown>" : methodName;
            this.parameterCount = parameterCount;
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

            for (detection.CloneRange range : clone.ranges) {
                if (range == null) continue;

                PsiMethod method = findMethodContainingLine(project, vf, range.startLine);
                if (method == null) method = findMethodContainingLine(project, vf, range.endLine);
                if (method == null) continue;

                String key = buildMethodTrackingKey(method);
                if (out.containsKey(key)) continue;
                SmartPsiElementPointer<PsiMethod> ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
                String displayName = buildMethodDisplayName(method);
                String className = getMethodClassName(method);
                String methodName = method.getName();
                int parameterCount = method.getParameterList().getParametersCount();
                String baselineBodyText = normalizeMethodBodyText(getMethodBodyText(method));
                out.put(key, new CloneMethodSnapshot(ptr, className, methodName, parameterCount, baselineBodyText, displayName));
                logStage(viewer, "WATCH", "tracking cloned method: " + displayName);
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
	            String key = className + "#" + methodName + "#" + snapshot.parameterCount;
	            if (!seen.add(key)) continue;
	            out.add(new usefulnessChecker.TargetMethodHint(className, methodName, snapshot.parameterCount));
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

    private static boolean containsJavaFilesUnder(java.nio.file.Path dir, int maxDepth) {
        if (dir == null || maxDepth < 0) return false;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir, maxDepth)) {
            return s.anyMatch(p -> {
                try {
                    return p != null
                            && java.nio.file.Files.isRegularFile(p)
                            && p.getFileName() != null
                            && p.getFileName().toString().endsWith(".java");
                } catch (Throwable ignored) {
                    return false;
                }
            });
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsSpecificJavaFileUnder(java.nio.file.Path dir, String relativeUnixPath, int maxDepth) {
        if (dir == null || relativeUnixPath == null || relativeUnixPath.isBlank() || maxDepth < 0) return false;
        final String expectedSuffix = "/" + relativeUnixPath.replace('\\', '/');
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir, maxDepth)) {
            return s.anyMatch(p -> {
                try {
                    if (p == null || !java.nio.file.Files.isRegularFile(p) || p.getFileName() == null) return false;
                    String norm = p.toString().replace('\\', '/');
                    return norm.endsWith(expectedSuffix);
                } catch (Throwable ignored) {
                    return false;
                }
            });
        } catch (Throwable t) {
            return false;
        }
    }
}
