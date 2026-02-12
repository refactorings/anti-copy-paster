package org.jetbrains.research.anticopypaster.workflow;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;

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
import org.jetbrains.research.anticopypaster.agents.refactor;
import org.jetbrains.research.anticopypaster.agents.compile;
import org.jetbrains.research.anticopypaster.agents.testing;
import org.jetbrains.research.anticopypaster.agents.ExtractMethodUsefulnessAnalyzer;
import org.jetbrains.research.anticopypaster.agents.FragmentUsefulnessAnalyzer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerMessageCategory;

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

/**
 * Central workflow entry for multi-agent clone refactoring.
 *
 * Detection → Refactor → Compile → Test
 */
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
        logStage(viewer, "WORKFLOW", "CANCELLED by user (viewer closed)");
    }

    private static java.util.function.Consumer<String> cancelAwareViewer(java.util.function.Consumer<String> viewer) {
        if (viewer == null) return null;
        return (msg) -> {
            if (isCancelled()) return;
            try { viewer.accept(msg); } catch (Throwable ignored) {}
        };
    }

    private static void throwIfCancelled(java.util.function.Consumer<String> viewer) {
        if (isCancelled()) throw new RuntimeException("CANCELLED");
    }

    private static final Map<String, ConsoleView> CONSOLE_BY_TITLE = new ConcurrentHashMap<>();

    /** Open (or reuse) a console tab and return a writer for streaming lines into it. */
    private static Consumer<String> openViewer(Project project, String title) {
        final java.util.concurrent.atomic.AtomicReference<ConsoleView> consoleRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> writerRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable createOrReuse = () -> {
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = twm.getToolWindow(ToolWindowId.RUN);
            if (toolWindow == null) {
                toolWindow = twm.getToolWindow("AntiCopyPaster");
                if (toolWindow == null) {
                    toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable("AntiCopyPaster"));
                }
            }

            var cm = toolWindow.getContentManager();
            ConsoleView console = CONSOLE_BY_TITLE.get(title);
            Content content = cm.findContent(title);

            if (console == null || content == null) {
                console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();

// Wrap console with a top bar that has an X button.
                javax.swing.JButton closeBtn = new javax.swing.JButton("✕");
                closeBtn.setFocusable(false);
                closeBtn.setMargin(new java.awt.Insets(2, 8, 2, 8));
                closeBtn.addActionListener(e -> {
                    java.util.function.Consumer<String> w = writerRef.get();
                    cancelWorkflow(w);
                    try {
                        Content ctn = cm.findContent(title);
                        if (ctn != null) {
                            cm.removeContent(ctn, true);
                        }
                    } catch (Throwable ignored) {}
                });

                javax.swing.JPanel topBar = new javax.swing.JPanel(new java.awt.BorderLayout());
                topBar.add(closeBtn, java.awt.BorderLayout.EAST);

                javax.swing.JPanel wrapper = new javax.swing.JPanel(new java.awt.BorderLayout());
                wrapper.add(topBar, java.awt.BorderLayout.NORTH);
                wrapper.add(console.getComponent(), java.awt.BorderLayout.CENTER);

                Content newContent = ContentFactory.getInstance().createContent(wrapper, title, true);

// If the user closes the tab via the content's close button, cancel the workflow too.
                try {
                    com.intellij.openapi.util.Disposer.register(newContent, () -> {
                        java.util.function.Consumer<String> w = writerRef.get();
                        cancelWorkflow(w);
                    });
                } catch (Throwable ignored) {}

                if (content != null) cm.removeContent(content, true);
                cm.addContent(newContent);
                cm.setSelectedContent(newContent);
                CONSOLE_BY_TITLE.put(title, console);
            } else {
                console.clear();
                cm.setSelectedContent(content);
            }

            toolWindow.activate(null);
            consoleRef.set(CONSOLE_BY_TITLE.get(title));
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            createOrReuse.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(createOrReuse);
        }

        // Throttled EDT printing
        final java.util.concurrent.ConcurrentLinkedQueue<String> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Avoid self-reference initialization issues by storing the runnable in a reference.
        final java.util.concurrent.atomic.AtomicReference<Runnable> flushRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable flush = () -> {
            scheduled.set(false);
            ConsoleView c = consoleRef.get();
            if (c == null) return;

            StringBuilder sb = new StringBuilder();
            String s;
            int maxLines = 200;
            while (maxLines-- > 0 && (s = queue.poll()) != null) {
                sb.append(s);
                if (!s.endsWith("\n")) sb.append('\n');
            }
            if (sb.length() > 0) {
                c.print(sb.toString(), ConsoleViewContentType.NORMAL_OUTPUT);
            }

            // If more remains, schedule another flush (use flushRef to avoid self-reference warnings)
            if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) {
                Runnable next = flushRef.get();
                if (next != null) {
                    ApplicationManager.getApplication().invokeLater(next, ModalityState.any());
                }
            }
        };

        flushRef.set(flush);

        Consumer<String> writer = line -> {
            if (line == null) return;
            if (isCancelled()) return;
            queue.add(line);
            if (scheduled.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater(flushRef.get(), ModalityState.any());
            }
        };

        writerRef.set(writer);

        return writer;
    }

    /* ============================================================
     * Public Entry
     * ============================================================ */

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

    /* ============================================================
     * Core Workflow
     * ============================================================ */

    private static void runOnSingleFile(Project project, VirtualFile vf, String pastedSnippet) {

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                resetCancelFlag();
                String fileName = vf.getName();
                File ioFile = new File(vf.getPath());
                String originalSource = Files.readString(ioFile.toPath(), StandardCharsets.UTF_8);

                Consumer<String> viewer = cancelAwareViewer(openViewer(project, "Clone Workflow Output"));
                _LAST_PROJECT_FOR_TESTS = project;
                _LAST_TEST_VIEWER = viewer;

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

                // Read max attempts from Settings (iteration slider)
                int maxAttempts = 3;
                try {
                    ProjectSettingsState st = ProjectSettingsState.getInstance(project);
                    if (st != null) {
                        maxAttempts = Math.max(1, st.getMaxAttempts());
                    }
                } catch (Throwable ignored) {
                    // keep default
                }
                logStage(viewer, "SETTINGS", "maxAttempts=" + maxAttempts);

                // Resolve LLM from settings (provider/model/api key/base/version)
                LLM = LlmClientFactory.fromProjectSettings(project, viewer);

                logStage(viewer, "START", fileName);
                notify(project, "[Clone] Workflow started for: " + fileName, NotificationType.INFORMATION);

                if (LLM instanceof NoopLlmClient) {
                    notify(project,
                            "[Clone] LLM is not configured (missing/invalid provider settings or API key). LLM calls will return empty and detection will always be 'no clones'. Configure provider/model/API key in Settings.",
                            NotificationType.ERROR);
                }

                detection detectionAgent = new detection();
                refactor refactorAgent = new refactor();
                compile compileAgent = new compile();
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
                        notify(project, "[Clone] LLM call failed: " + e.getMessage(), NotificationType.ERROR);
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
                logStage(viewer, "DETECTION", "raw result: " + (det == null ? "null" : ("clones=" + (det.clones == null ? "null" : det.clones.size()))));

                if (det == null || det.clones == null || det.clones.isEmpty()) {
                    logStage(viewer, "DETECTION", "no clones");
                    notify(project, "[Clone] No clones detected in: " + fileName, NotificationType.INFORMATION);
                    return;
                }

                detection.DetectedClone clone = det.clones.get(0);
                logStage(viewer, "DETECTION", "clone found: " + clone.id);
                notify(project, "[Clone] Clones detected in: " + fileName + " (id=" + clone.id + ")", NotificationType.INFORMATION);

                // Build the RAG query text for refactoring few-shot retrieval.
                // Prefer clone code if the detection agent already includes it; otherwise fall back to best-effort extraction.
                String refactorRagQuery = buildRefactorRagQueryText(originalSource, clone);

                // Precompute RAG guidance once per file (it will be prepended to refactor feedback each attempt).
                String refactorRagGuidance = "";
                boolean skipRagForModel = false;
                try {
                    ProjectSettingsState stForRag = ProjectSettingsState.getInstance(project);
                    if (stForRag != null) {
                        String modelName = stForRag.getAiderModel();
                        if (modelName != null && modelName.toLowerCase(Locale.ROOT).contains("gpt-3.5")) {
                            skipRagForModel = true;
                            logStage(viewer, "RAG", "Skipping RAG for model: " + modelName);
                        }
                    }
                } catch (Throwable ignored) {
                    // ignore and proceed normally
                }

                if (!skipRagForModel) {
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
                } else {
                    refactorRagGuidance = "";
                }

//                if (viewer != null) {
//                    String q = refactorRagQuery == null ? "" : refactorRagQuery;
//                    if (q.length() > 600) q = q.substring(0, 600) + "...";
//                    logStage(viewer, "RAG", "refactor query preview: " + q.replace("\n", "\\n"));
//                    logStage(viewer, "RAG", "refactor guidance chars: " + (refactorRagGuidance == null ? 0 : refactorRagGuidance.length()));
//                }

                String currentSource = originalSource;
                String feedback = null;

                /* ---------- Retry Loop ---------- */
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        if (isCancelled()) {
                            notify(project, "[Clone] Cancelled by user.", NotificationType.WARNING);
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


                        refactor.RefactorResult rr =
                                refactorAgent.refactorFile(
                                        fileName,
                                        currentSource,
                                        convertClone(clone),
                                        feedbackForRefactor,
                                        llmCaller
                                );


                        if (rr == null || rr.newSource == null || rr.newSource.isBlank()) {
                            feedback = "Refactor produced empty or invalid output.";
                            logStage(viewer, "REFACTOR", "failed");
                            notify(project, "[Clone] Refactor failed on attempt " + attempt + " for: " + fileName, NotificationType.WARNING);
                            continue;
                        }

                        // Do NOT apply immediately. We will compile/test the proposed source in an isolated temp output first.
                        String proposedSource = rr.newSource;
                        logStage(viewer, "REFACTOR", "proposal generated (not applied yet)");

                        // ===== Show proposed refactored code (for debugging / transparency) =====
                        if (viewer != null) {
                            String src = proposedSource == null ? "" : proposedSource;
                            int maxChars = REFACTOR_PROPOSAL_PREVIEW_CHARS; // keep console usable
                            String shown = src.length() > maxChars ? (src.substring(0, maxChars) + "\n...<truncated>...") : src;
                            viewer.accept("[REFACTOR_CODE] proposedSource (showing up to " + maxChars + " chars):\n" + shown);
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
                            ExtractMethodUsefulnessAnalyzer.UsefulnessResult urBeforeCompile =
                                    ExtractMethodUsefulnessAnalyzer.analyze(
                                            project,
                                            fileName,
                                            currentSource,
                                            proposedSource,
                                            new ExtractMethodUsefulnessAnalyzer.UsefulnessConfig()
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
//                                    String msg = "Not useful refactoring proposal (before compile): score=" + urBeforeCompile.score + ", reasons=" + urBeforeCompile.reasons +
//                                            (urBeforeCompile.notes == null || urBeforeCompile.notes.isBlank() ? "" : (", notes=" + urBeforeCompile.notes));
                                    logStage(viewer, "USEFUL", "Not useful refactoring proposal");
                                    notify(project,
                                            "[Clone] Refactor is NOT useful (attempt " + attempt + ") for: " + fileName + "\n",
                                            NotificationType.WARNING);

                                    feedback = "Your Extract Method refactoring is not useful. " +
                                            "Please do a real Extract Method that removes duplication in BOTH places. " +
                                            "Extract the entire duplicated code into a new helper method.\n" +
                                            "The extracted method must include all statements in the clone region,\n" +
                                            "including method calls, control flow, and calls to super methods.\n" +
                                            "Do not leave any duplicated statements in the original methods. avoid deleting one side, and avoid delegating to unrelated existing methods.";
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
//                                    String msg = "Not useful FRAGMENT refactoring proposal (before compile): strategy=" + frBeforeCompile.strategy +
//                                            ", score=" + frBeforeCompile.score + ", reasons=" + frBeforeCompile.reasons +
//                                            (frBeforeCompile.notes == null || frBeforeCompile.notes.isBlank() ? "" : (", notes=" + frBeforeCompile.notes));
                                    logStage(viewer, "USEFUL", "Not useful refactoring proposal");
                                    notify(project,
                                            "[Clone] Refactor is NOT useful (attempt " + attempt + ") for: " + fileName + "\n",
                                            NotificationType.WARNING);

                                    feedback = "Your refactoring is not useful. " +
                                            "You must actually remove or significantly reduce the duplicated fragment in BOTH places. " +
                                            "Avoid incomplete refactoring, deleting one side, or delegating only one side.";
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

                        // Compile the proposed source to a temp classes dir, without touching the original file.
                        String ideCp = buildProjectClasspathFromIde(project);
                        File patchedOutDir;
                        String compileLog;
                        try {
                            throwIfCancelled(viewer);
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

                        compile.CompileResult cr = compileAgent.analyze(fileName, compileLog);

                        if (cr == null || !"compile_ok".equals(cr.status)) {
                            feedback = cr == null ? "Compilation failed." : cr.summary;
                            logStage(viewer, "COMPILE", "failed: " + feedback);
                            notify(project, "[Clone] Compile failed (attempt " + attempt + ") for: " + fileName + "\n" + feedback, NotificationType.ERROR);
                            continue;
                        }

                        logStage(viewer, "COMPILE", "ok (isolated)");
                        notify(project, "[Clone] Compile OK (attempt " + attempt + ") for: " + fileName, NotificationType.INFORMATION);

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
                            notify(project, "[Clone] Test skipped (attempt " + attempt + ") for: " + fileName + " (cannot resolve class FQN)", NotificationType.WARNING);
                            continue;
                        }

                        testing.TestRunRequest treq =
                                new testing.TestRunRequest(
                                        project.getBasePath(),
                                        targetFqn,
                                        null,
                                        false
                                );

                        throwIfCancelled(viewer);
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

                            // Now that compile+test passed, ask user whether to apply the refactor to the real file.
                            boolean applyNow = showDiffAndConfirmApply(project, fileName, currentSource, proposedSource);
                            if (applyNow) {
                                currentSource = proposedSource;
                                Files.writeString(ioFile.toPath(), currentSource, StandardCharsets.UTF_8);
                                logStage(viewer, "REFACTOR", "applied after verification");
                                notify(project, "[Clone] Tests passed. Refactor applied for: " + fileName, NotificationType.INFORMATION);
                            } else {
                                logStage(viewer, "REFACTOR", "verified but not applied (user cancelled)");
                                notify(project, "[Clone] Tests passed but changes were not applied (user cancelled): " + fileName, NotificationType.WARNING);
                            }

                            logStage(viewer, "WORKFLOW", "SUCCESS");
                            // Clear patched classes dir for subsequent runs
                            _LAST_PATCHED_CLASSES_DIR = null;
                            return;
                        }

                        feedback = tr == null ? "Tests failed." :
                                (tr.summary != null ? tr.summary : tr.raw);

                        logStage(viewer, "TEST", "failed");
                        notify(project, "[Clone] Tests failed (attempt " + attempt + ") for: " + fileName, NotificationType.WARNING);
                }

                logStage(viewer, "WORKFLOW", "FAILED after retries");
                notify(project, "[Clone] Workflow failed after retries for: " + vf.getName(), NotificationType.ERROR);

            } catch (Exception e) {
                if (isCancelled() || (e.getMessage() != null && e.getMessage().contains("CANCELLED"))) {
                    notify(project, "[Clone] Cancelled by user.", NotificationType.WARNING);
                    return;
                }
                e.printStackTrace();
                notify(project, "[Clone] Workflow crashed: " + e.getMessage(), NotificationType.ERROR);
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

    /** Best-effort: find pasted snippet line range in the given source text. Returns {startLine,endLine} 1-based, or null if not found. */
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

            // --- Robust match by PSI method text (preferred) ---
            // Many times the user copies the *whole method body* (not including the signature).
            // So we compare the pasted snippet against:
            //   (1) method.getBody().getText()   -> includes outer braces
            //   (2) body text without braces     -> pure body
            //   (3) method.getText()             -> full method (signature + body)
            // Matching ignores whitespace differences.
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

    private static String extractUsefulnessDebugText(Object ur) {
        try {
            if (ur == null) return "";

            // Try common field names
            String[] fieldNames = new String[]{"debug", "debugLines", "debugLine", "pairDebug", "details", "debugInfo"};
            for (String fn : fieldNames) {
                try {
                    java.lang.reflect.Field f = ur.getClass().getField(fn);
                    Object v = f.get(ur);
                    String s = stringifyDebugValue(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field f = ur.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    Object v = f.get(ur);
                    String s = stringifyDebugValue(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
            }

            // Try common getters
            String[] getterNames = new String[]{"getDebug", "getDebugLines", "getDetails", "debug", "debugLines"};
            for (String mn : getterNames) {
                try {
                    java.lang.reflect.Method m = ur.getClass().getMethod(mn);
                    Object v = m.invoke(ur);
                    String s = stringifyDebugValue(v);
                    if (s != null && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
            }

            // Fall back to notes (often contains debug-ish info)
            try {
                java.lang.reflect.Field f = ur.getClass().getField("notes");
                Object v = f.get(ur);
                String s = stringifyDebugValue(v);
                if (s != null && !s.isBlank()) return s;
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = ur.getClass().getDeclaredField("notes");
                f.setAccessible(true);
                Object v = f.get(ur);
                String s = stringifyDebugValue(v);
                if (s != null && !s.isBlank()) return s;
            } catch (Throwable ignored) {}

            return String.valueOf(ur);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String stringifyDebugValue(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof java.util.List) {
            java.util.List<?> lst = (java.util.List<?>) v;
            if (lst.isEmpty()) return "";
            Object first = lst.get(0);
            return first == null ? "" : String.valueOf(first);
        }
        return String.valueOf(v);
    }

    /* ============================================================
     * Compile
     * ============================================================ */

    private static String runCompile(Project project) {
        try {
            String basePath = project == null ? null : project.getBasePath();
            if (basePath == null || basePath.isBlank()) {
                return "Compile error: project basePath is empty.";
            }

            File base = new File(basePath);

            File pom = new File(base, "pom.xml");
            File gradle = new File(base, "build.gradle");
            File gradleKts = new File(base, "build.gradle.kts");
            File ant = new File(base, "build.xml");

            // 1) Maven
            if (pom.exists()) {
                ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "test-compile");
                pb.directory(base);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                return readProcessOutput(p);
            }

            // 2) Gradle (prefer wrapper)
            if (gradle.exists() || gradleKts.exists()) {
                boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                File wrapper = new File(base, isWindows ? "gradlew.bat" : "gradlew");
                String exe = wrapper.exists() ? wrapper.getAbsolutePath() : "gradle";

                ProcessBuilder pb = new ProcessBuilder(exe, "-q", "testClasses");
                pb.directory(base);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                return readProcessOutput(p);
            }

            // 3) Ant
            if (ant.exists()) {
                // Try common "compile" target first; if it fails, run default target.
                String out1 = runCommandBestEffort(base, new String[]{"ant", "-q", "compile"});
                if (out1 != null && out1.toLowerCase(Locale.ROOT).contains("build failed")) {
                    String out2 = runCommandBestEffort(base, new String[]{"ant", "-q"});
                    return (out2 == null ? out1 : out2);
                }
                return out1 == null ? "" : out1;
            }

            // 4) Fallback: IntelliJ compiler (works for any imported project/module)
            // This does NOT require pom/gradle/build.xml, but the project must be correctly imported
            // and have an SDK configured.
            final StringBuilder sb = new StringBuilder();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    CompilerManager.getInstance(project).make(new CompileStatusNotification() {
                        @Override
                        public void finished(boolean aborted, int errors, int warnings, CompileContext compileContext) {
                            sb.append("[IDEA] aborted=").append(aborted)
                              .append(", errors=").append(errors)
                              .append(", warnings=").append(warnings)
                              .append("\n");

                            if (compileContext != null) {
                                for (CompilerMessageCategory cat : CompilerMessageCategory.values()) {
                                    var msgs = compileContext.getMessages(cat);
                                    if (msgs == null) continue;
                                    for (var m : msgs) {
                                        // Format similar to javac/maven style for the compile agent parser
                                        if (m.getVirtualFile() != null) {
                                            sb.append(m.getVirtualFile().getPath());
                                            Integer line = tryGetCompilerMessageLine(m);
                                            if (line != null && line > 0) sb.append(":").append(line);
                                            sb.append(": ");
                                        }
                                        sb.append(cat.name()).append(": ").append(m.getMessage()).append("\n");
                                    }
                                }
                            }

                            // Emit a recognizable success/fail token for your compile agent
                            if (errors == 0 && !aborted) {
                                sb.append("BUILD SUCCESS\n");
                            } else {
                                sb.append("BUILD FAILED\n");
                            }
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    sb.append("Compile error: ").append(t.getMessage()).append("\n");
                    sb.append("BUILD FAILED\n");
                    latch.countDown();
                }
            }, ModalityState.any());

            // Wait for IDE compile to finish (up to 5 minutes)
            boolean ok = latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!ok) {
                return "Compile error: IDE build timed out.\nBUILD FAILED\n";
            }
            return sb.toString();

        } catch (Exception e) {
            return "Compile error: " + e.getMessage();
        }
    }

    private static String runCommandBestEffort(File baseDir, String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(baseDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return readProcessOutput(p);
        } catch (Throwable t) {
            return "Compile error: failed to run command: " + String.join(" ", cmd) + " (" + t.getMessage() + ")";
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

            // 1) Materialize EvoSuite jar from plugin resources (src/main/resources/tools/...)
            File evosuiteJar = materializeResourceToTempFile("tools/evosuite-1.2.0.jar", "evosuite-1.2.0", ".jar");
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

            // 4) Run EvoSuite (generation only; does not execute the generated tests here)
            // Common EvoSuite properties: -Dtest_dir / -Dreport_dir
            List<String> cmd = new java.util.ArrayList<>();

            String javaExe = resolveJavaExecutable(_LAST_PROJECT_FOR_TESTS);
            cmd.add(javaExe);

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

            cmd.add("-jar");
            cmd.add(evosuiteJar.getAbsolutePath());
            cmd.add("-class");
            cmd.add(targetClass);
            cmd.add("-projectCP");
            cmd.add(projectCp);
            cmd.add("-Dtest_dir=" + testDir.getAbsolutePath());
            cmd.add("-Dreport_dir=" + reportDir.getAbsolutePath());
            cmd.add("-Dsandbox=false");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(base);
            pb.redirectErrorStream(true);

            String forcedJavaHome = deriveJavaHome(javaExe);
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

            // If tests were generated, convert the primary *_ESTest.java into a pure JUnit4 test
            // (removing EvoSuite runtime/scaffolding) and copy ONLY the converted test into src/test/java.
            // This avoids requiring EvoSuite runtime to execute tests.
            String convertedTestFqn = "";
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

                String rawTest = java.nio.file.Files.readString(estestPath, java.nio.charset.StandardCharsets.UTF_8);
                if (rawTest == null) rawTest = "";

                if (looksLikeLiteralNNewlineCorruption(rawTest)) {
                    rawTest = repairLiteralNNewlineCorruption(rawTest);
                }
                rawTest = normalizeBrokenNewlines(rawTest);

                String outClass = simpleName + "_EvoSuiteJUnit4Test";
                String converted = convertEvoSuiteToPureJUnit4(rawTest, outClass);

                // Write converted file next to the original under the same package-relative structure.
                java.nio.file.Path rel = testDir.toPath().relativize(estestPath);
                java.nio.file.Path outRel = rel;
                try {
                    String relStr = rel.toString();
                    if (relStr.endsWith(estestName)) {
                        outRel = java.nio.file.Paths.get(relStr.substring(0, relStr.length() - estestName.length()) + outClass + ".java");
                    }
                } catch (Throwable ignored) {
                    // keep outRel as rel
                }

                java.nio.file.Path convertedPath = testDir.toPath().resolve(outRel);
                java.nio.file.Files.createDirectories(convertedPath.getParent());
                java.nio.file.Files.writeString(convertedPath, converted, java.nio.charset.StandardCharsets.UTF_8);

                convertedTestFqn = extractFqnFromJavaSource(converted, outClass);
                _LAST_CONVERTED_TEST_FQN = convertedTestFqn;

                // Copy ONLY the converted test into src/test/java (preserve package dirs).
                // Also proactively delete any previously-copied EvoSuite originals/scaffolding to avoid lingering compile errors.
                File projectBase = new File(projectDir);
                File srcTestJava = new File(projectBase, "src/test/java");
                if (!srcTestJava.exists() && !srcTestJava.mkdirs()) {
                    return "[EVOSUITE]\n" +
                            "exitCode=" + exit + "\n" +
                            "status=tests_failed\n" +
                            "reason=failed to create src/test/java\n";
                }

                java.nio.file.Path target = srcTestJava.toPath().resolve(outRel);
                java.nio.file.Files.createDirectories(target.getParent());

                // Delete old originals if they exist in the destination folder (from earlier runs)
                try {
                    String est = estestName; // e.g., Foo_ESTest.java
                    String scaff = simpleName + "_ESTest_scaffolding.java";
                    java.nio.file.Path oldEst = target.getParent().resolve(est);
                    java.nio.file.Path oldScaff = target.getParent().resolve(scaff);
                    if (java.nio.file.Files.exists(oldEst)) {
                        java.nio.file.Files.delete(oldEst);
                    }
                    if (java.nio.file.Files.exists(oldScaff)) {
                        java.nio.file.Files.delete(oldScaff);
                    }
                } catch (Throwable ignored) {
                    // best-effort cleanup
                }

                java.nio.file.Files.copy(convertedPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                if (viewer != null) {
                    viewer.accept("[EvoSuite] Converted test written: " + target);
                    if (convertedTestFqn != null && !convertedTestFqn.isBlank()) {
                        viewer.accept("[EvoSuite] Converted test FQN: " + convertedTestFqn);
                    }
                }
            }

            // Now actually run tests using Maven or Gradle
            String testOutput = runProjectTests(base, viewer);

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

        } catch (Exception e) {
            return "Test error: " + e.getMessage();
        }
    }

    // Helper to run Maven/Gradle tests, or fallback to JUnitCore
    private static String runProjectTests(File baseDir, Consumer<String> viewer) {
        try {
            File pom = new File(baseDir, "pom.xml");
            File gradle = new File(baseDir, "build.gradle");
            File gradleKts = new File(baseDir, "build.gradle.kts");

            // 1. Maven/Gradle: Delegate to build tool
            if (pom.exists() || gradle.exists() || gradleKts.exists()) {
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
                File srcTest = new File(baseDir, "src/test/java");
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

            // Ensure JUnit/Hamcrest are on CP (JUnit 4.12 needs hamcrest at runtime)
            File junit = materializeResourceToTempFile("tools/junit-4.12.jar", "junit", ".jar");
            File hamcrest = materializeResourceToTempFile("tools/hamcrest-core-1.3.jar", "hamcrest", ".jar");

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

            // STEP 3 (NEW): Compile the test file manually
            if (viewer != null) viewer.accept("[TEST] Compiling generated test...");
            File compiledClassesDir;
            try {
                compiledClassesDir = compileFile(ideProject, testFile, runCp);
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
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();

        // If user cancels, destroy the process.
        Thread killer = new Thread(() -> {
            try {
                while (p.isAlive()) {
                    if (isCancelled()) {
                        try {
                            if (viewer != null) viewer.accept("[WORKFLOW] Cancel requested; killing process...");
                        } catch (Throwable ignored) {}
                        try { p.destroy(); } catch (Throwable ignored) {}
                        try { p.destroyForcibly(); } catch (Throwable ignored) {}
                        break;
                    }
                    try { Thread.sleep(120); } catch (InterruptedException ie) { break; }
                }
            } catch (Throwable ignored) {}
        }, "acp-cancel-killer");
        killer.setDaemon(true);
        killer.start();

        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
                if (viewer != null && !isCancelled()) viewer.accept(line);
                if (isCancelled()) break;
            }
        } catch (Throwable ignored) {
            // best-effort
        }

        int exit;
        try {
            exit = p.waitFor();
        } catch (Throwable t) {
            exit = -1;
        }

        if (isCancelled()) {
            try { p.destroyForcibly(); } catch (Throwable ignored) {}
            return new ProcessRun(-1, sb.toString() + "\n[CANCELLED]\n");
        }

        return new ProcessRun(exit, sb.toString());
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

            // Use the first module
            com.intellij.openapi.module.Module m = modules[0];

            java.util.Set<String> paths = new java.util.LinkedHashSet<>();

            // 1. Module dependencies
            com.intellij.util.PathsList orderEntries = com.intellij.openapi.roots.OrderEnumerator.orderEntries(m)
                    .recursively().withoutSdk().classes().getPathsList();
            paths.addAll(orderEntries.getPathList());

            // 2. Compiler outputs (Production & Test)
            com.intellij.openapi.roots.CompilerModuleExtension ext = com.intellij.openapi.roots.CompilerModuleExtension.getInstance(m);
            if (ext != null) {
                if (ext.getCompilerOutputPath() != null)
                    paths.add(ext.getCompilerOutputPath().getPath());
                if (ext.getCompilerOutputPathForTests() != null)
                    paths.add(ext.getCompilerOutputPathForTests().getPath());
            }

            // 3. Heuristic: If test output is missing, try to infer it from production path
            // FIX: Only add the inferred path if it actually exists!
            java.util.List<String> addedPaths = new java.util.ArrayList<>(paths);
            for (String p : addedPaths) {
                if (p.contains("/classes/production/")) {
                    String testPath = p.replace("/classes/production/", "/classes/test/");
                    if (new File(testPath).exists()) {
                        paths.add(testPath);
                    }
                } else if (p.contains("/out/production/")) {
                    String testPath = p.replace("/out/production/", "/out/test/");
                    if (new File(testPath).exists()) {
                        paths.add(testPath);
                    }
                }
            }

            // Filter out any non-existent paths just to be safe for EvoSuite
            java.util.Set<String> validPaths = new java.util.LinkedHashSet<>();
            for (String p : paths) {
                if (new File(p).exists()) {
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

    /**
     * IntelliJ platform APIs differ by version; some CompilerMessage implementations expose getLine()/getColumn(),
     * others do not. This helper tries to read line number reflectively and returns null if unavailable.
     */
    private static Integer tryGetCompilerMessageLine(Object compilerMessage) {
        if (compilerMessage == null) return null;
        try {
            java.lang.reflect.Method m = compilerMessage.getClass().getMethod("getLine");
            Object v = m.invoke(compilerMessage);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
            // method not present or not accessible
        }
        return null;
    }

    // NOTE: RAG retrieval uses clone code (via buildRefactorRagQueryText) when available; ranges here are only for agent context.
    private static refactor.DetectedClone convertClone(detection.DetectedClone c) {
        return new refactor.DetectedClone(
                c.id,
                c.ranges.stream()
                        .map(r -> new refactor.CloneRange(r.startLine, r.endLine))
                        .toList(),
                c.refactorType,
                c.reason
        );
    }


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

    private static void logStage(String stage, String msg) {
        System.out.printf(Locale.ROOT, "[%s] %s%n", stage, msg);
    }

    private static void logStage(Consumer<String> viewer, String stage, String msg) {
        logStage(stage, msg);
        if (viewer != null) {
            viewer.accept("[" + stage + "] " + (msg == null ? "" : msg));
        }
    }

    private static void notify(Project project, String message, NotificationType type) {
        if (project == null || project.isDisposed()) return;

        Runnable r = () -> {
            try {
                Notification n = new Notification(
                        "AntiCopyPaster",
                        "AntiCopyPaster",
                        message == null ? "" : message,
                        type == null ? NotificationType.INFORMATION : type
                );
                Notifications.Bus.notify(n, project);
            } catch (Throwable ignored) {
                // best-effort notification
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            r.run();
        } else {
            ApplicationManager.getApplication().invokeLater(r, ModalityState.any());
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

    /**
     * Compiles a single Java file to a temp directory.
     * Required because the new test file is not yet known to the IDE's build system.
     */
    private static File compileFile(Project project, File sourceFile, String classpath) throws Exception {
        File outputDir = java.nio.file.Files.createTempDirectory("temp_test_classes").toFile();
        outputDir.deleteOnExit();

        // Resolve javac (assume it's next to java)
        String javaExe = resolveJavaExecutable(project);
        String javacExe = javaExe.replace("java.exe", "javac.exe").replace("bin" + File.separator + "java", "bin" + File.separator + "javac");

        // Use system javac if inferred path doesn't exist
        if (!new File(javacExe).exists()) javacExe = "javac";

        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javacExe);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("-d");
        cmd.add(outputDir.getAbsolutePath());
        cmd.add(sourceFile.getAbsolutePath());

        // Run compilation
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readProcessOutput(p);

        if (p.exitValue() != 0) {
            throw new RuntimeException("Compilation failed:\n" + out);
        }
        return outputDir;
    }

    private static String repairLiteralNNewlineCorruption(String code) {
        if (code == null) return "";
        // Best-effort: replace standalone 'n' that appears where a newline should be.
        String out = code;
        out = out.replace("npackage ", "\npackage ");
        out = out.replace(";n", ";\n");
        out = out.replace("{n", "{\n");
        out = out.replace("}n", "}\n");
        out = out.replace(")n", ")\n");
        return out;
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
     * Converts EvoSuite-generated test code to pure JUnit4 code.
     * Critical Fix: expanded filter list to include MockJFileChooser and other UI/IO mocks.
     */
    private static String convertEvoSuiteToPureJUnit4(String code, String outputClassName) {
        if (code == null) return "";

        // 1. Normalize line endings
        String s = code.replace("\r\n", "\n").replace("\r", "\n");

        // 2. Line-based cleanup
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // Remove specific imports
            if (trimmed.startsWith("import org.evosuite.")) continue;
            if (trimmed.startsWith("import org.junit.runner.RunWith")) continue;

            // Remove class annotations
            if (trimmed.startsWith("@RunWith(EvoRunner.class)")) continue;
            if (trimmed.startsWith("@EvoRunnerParameters")) continue;

            // Remove @BeforeClass / @AfterClass and their method bodies
            if (trimmed.startsWith("@BeforeClass") || trimmed.startsWith("@AfterClass")) {
                continue;
            }

            // [Critical Fix]: Comprehensive filter list for EvoSuite runtime symbols
            if (line.contains("org.evosuite.") ||
                    line.contains("RuntimeSettings.") ||
                    line.contains("MockFramework.") ||
                    line.contains("EvoRunner") ||
                    line.contains("EvoSuite") ||
                    line.contains("verifyException") ||
                    // Date/Time mocks
                    line.contains("MockDateFormat") ||
                    line.contains("MockDate") ||
                    line.contains("MockCalendar") ||
                    line.contains("MockRandom") ||
                    // IO/File mocks
                    line.contains("MockFile") ||
                    line.contains("MockFileSystemView") ||
                    line.contains("MockFileInputStream") ||
                    line.contains("MockFileOutputStream") ||
                    line.contains("FileSystemHandling") ||
                    // UI/AWT/Swing mocks (This fixes your current error)
                    line.contains("MockJFileChooser") ||  // <--- Added
                    line.contains("MockToolkit") ||
                    line.contains("MockGraphics") ||
                    line.contains("MockResources") ||
                    line.contains("MockComponent")) {
                continue;
            }

            // Remove extends ..._ESTest_scaffolding
            if (line.contains("class ") && line.contains("extends") && line.contains("_ESTest_scaffolding")) {
                line = line.replaceAll("extends\\s+[A-Za-z0-9_]+_ESTest_scaffolding", "");
            }

            // Rename class
            if (line.contains("class ") && line.contains("_ESTest")) {
                line = line.replaceAll("class\\s+[A-Za-z0-9_]+_ESTest", "class " + outputClassName);
            }

            sb.append(line).append("\n");
        }

        String result = sb.toString();

        // 3. Ensure necessary JUnit imports exist
        if (!result.contains("import org.junit.Test;")) {
            result = "import org.junit.Test;\n" + result;
        }
        if (!result.contains("import static org.junit.Assert")) {
            result = "import static org.junit.Assert.*;\n" + result;
        }

        return result;
    }

    /** Extract FQN from a java source string using its package decl and class name fallback. */
    private static String extractFqnFromJavaSource(String javaSource, String classNameFallback) {
        if (javaSource == null) return "";
        String pkg = "";
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w\\.]*)\\s*;\\s*$")
                .matcher(javaSource);
        if (pm.find()) pkg = pm.group(1);

        String cls = "";
        java.util.regex.Matcher cm = java.util.regex.Pattern
                .compile("(?m)^\\s*public\\s+class\\s+([A-Za-z_][\\w]*)\\b")
                .matcher(javaSource);
        if (cm.find()) cls = cm.group(1);
        if (cls == null || cls.isBlank()) cls = (classNameFallback == null ? "" : classNameFallback);

        if (pkg == null || pkg.isBlank()) return cls == null ? "" : cls;
        if (cls == null || cls.isBlank()) return pkg;
        return pkg + "." + cls;
    }

    /**
     * Show a diff in a modal dialog that has Apply/Cancel buttons.
     * Clicking Apply returns true; Cancel returns false.
     */
    private static boolean showDiffAndConfirmApply(Project project, String fileName, String before, String after) {
        if (project == null || project.isDisposed()) return false;
        final java.util.concurrent.atomic.AtomicBoolean decision = new java.util.concurrent.atomic.AtomicBoolean(false);

        Runnable ui = () -> {
            Disposable disp = Disposer.newDisposable("AntiCopyPasterDiffPreview");
            try {
                DiffContentFactory f = DiffContentFactory.getInstance();
                var left = f.create(before == null ? "" : before);
                var right = f.create(after == null ? "" : after);

                String title = "AntiCopyPaster Refactor Preview";
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

        // Compile with javac using the provided classpath and output directory
        String javaExe = resolveJavaExecutable(project);
        String javacExe = javaExe.replace("java.exe", "javac.exe")
                .replace("bin" + File.separator + "java", "bin" + File.separator + "javac");
        if (!new File(javacExe).exists()) javacExe = "javac";

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javacExe);
        cmd.add("-cp");
        cmd.add(classpath == null ? "" : classpath);
        cmd.add("-d");
        cmd.add(tempOut.getAbsolutePath());
        cmd.add(tempJava.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readProcessOutput(p);
        if (p.exitValue() != 0) {
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

    private static String previewOneLine(String s, int max) {
        if (s == null) return "";
        String v = s.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").strip();
        if (v.length() > max) v = v.substring(0, max) + "...";
        return v;
    }

    private static String[] parseWrapperNamesFromUsefulnessDebug(String debugText) {
        try {
            if (debugText == null || debugText.isBlank()) return null;

            String s = debugText;

            if (s == null) return null;

            // ...#rotate(double) <-> ...#rotate_Cloned(double)
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("#([A-Za-z_][\\w$]*)\\s*\\([^)]*\\)\\s*<->\\s*[^#]*#([A-Za-z_][\\w$]*)\\s*\\(")
                    .matcher(s);

            if (!m.find()) return null;

            String a = m.group(1);
            String b = m.group(2);
            if (a == null || a.isBlank() || b == null || b.isBlank()) return null;

            return new String[]{a, b};
        } catch (Throwable t) {
            return null;
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
}

