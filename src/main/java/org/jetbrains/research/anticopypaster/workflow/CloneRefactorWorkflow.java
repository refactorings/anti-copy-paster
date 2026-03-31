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
                                    if (containsReasonName(urBeforeCompile.reasons, "EXTRACT_METHOD_NOT_CONFIRMED")) {
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
                            javaBuildSupport.clearPatchedClassesDir();
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

}
