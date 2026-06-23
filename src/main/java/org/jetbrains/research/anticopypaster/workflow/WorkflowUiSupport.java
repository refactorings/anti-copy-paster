package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class WorkflowUiSupport {
    private static final DateTimeFormatter LOG_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<String, ConsoleView> CONSOLE_BY_PROJECT_AND_TITLE = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicReference<String> STDOUT_LAST_STAGE =
            new java.util.concurrent.atomic.AtomicReference<>();

    private WorkflowUiSupport() {}

    static BufferedWriter openLogWriter(Project project, String targetFileName, String modelName) {
        try {
            String basePath = project == null ? null : project.getBasePath();
            if (basePath == null || basePath.isBlank()) return null;

            File dir = new File(basePath, ".anticopypaster" + File.separator + "logs");
            if (!dir.exists()) dir.mkdirs();

            String ts = LocalDateTime.now().format(LOG_TS_FMT);
            String safeFile = sanitizeForFilename(targetFileName);
            String safeModel = sanitizeForFilename(modelName);
            File out = new File(dir, safeFile + "." + safeModel + "." + ts + ".log");

            return Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }

    static Consumer<String> teeViewer(Consumer<String> viewer, BufferedWriter logWriter) {
        final java.util.concurrent.atomic.AtomicReference<String> lastStage = new java.util.concurrent.atomic.AtomicReference<>();
        return (line) -> {
            if (line == null) return;
            String formattedLine = addStageSpacing(line, lastStage);
            try {
                if (viewer != null) viewer.accept(formattedLine);
            } catch (Throwable ignored) {
            }
            try {
                if (logWriter != null) {
                    logWriter.write(formattedLine);
                    if (!formattedLine.endsWith("\n")) logWriter.write("\n");
                    logWriter.flush();
                }
            } catch (Throwable ignored) {
            }
        };
    }

    static Consumer<String> cancelAwareViewer(Consumer<String> viewer, BooleanSupplier isCancelled) {
        if (viewer == null) return null;
        return (msg) -> {
            if (isCancelled != null && isCancelled.getAsBoolean()) return;
            try {
                viewer.accept(msg);
            } catch (Throwable ignored) {
            }
        };
    }

    static void throwIfCancelled(BooleanSupplier isCancelled) {
        if ((isCancelled != null && isCancelled.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("CANCELLED");
        }
    }

    static Consumer<String> openViewer(Project project,
                                       String title,
                                       Consumer<Consumer<String>> onCancel,
                                       BooleanSupplier isCancelled) {
        final java.util.concurrent.atomic.AtomicReference<ConsoleView> consoleRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Consumer<String>> writerRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable createOrReuse = () -> {
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = twm.getToolWindow(ToolWindowId.RUN);
            if (toolWindow == null) {
                toolWindow = twm.getToolWindow("AntiCopyPaster");
                if (toolWindow == null) {
                    toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable("AntiCopyPaster"));
                }
            }

            toolWindow.setTitleActions(List.of(
                    new com.intellij.openapi.actionSystem.AnAction(
                            "Stop Workflow",
                            "Stop current workflow",
                            com.intellij.icons.AllIcons.Actions.Suspend
                    ) {
                        @Override
                        public void actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent e) {
                            if (onCancel != null) onCancel.accept(writerRef.get());
                        }
                    }
            ));

            var cm = toolWindow.getContentManager();
            String consoleKey = consoleKey(project, title);
            ConsoleView console = CONSOLE_BY_PROJECT_AND_TITLE.get(consoleKey);
            Content content = cm.findContent(title);

            if (console == null || content == null) {
                console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();

                javax.swing.JPanel wrapper = new javax.swing.JPanel(new java.awt.BorderLayout());
                wrapper.add(console.getComponent(), java.awt.BorderLayout.CENTER);

                Content newContent = ContentFactory.getInstance().createContent(wrapper, title, true);

                try {
                    final ConsoleView consoleForContent = console;
                    com.intellij.openapi.util.Disposer.register(newContent, () -> {
                        CONSOLE_BY_PROJECT_AND_TITLE.remove(consoleKey, consoleForContent);
                        if (onCancel != null) onCancel.accept(writerRef.get());
                    });
                } catch (Throwable ignored) {
                }

                if (content != null) cm.removeContent(content, true);
                cm.addContent(newContent);
                cm.setSelectedContent(newContent);
                CONSOLE_BY_PROJECT_AND_TITLE.put(consoleKey, console);
            } else {
                console.clear();
                cm.setSelectedContent(content);
            }

            toolWindow.activate(null);
            consoleRef.set(console);
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            createOrReuse.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(createOrReuse);
        }

        final java.util.concurrent.ConcurrentLinkedQueue<String> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
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
            if (isCancelled != null && isCancelled.getAsBoolean()) return;
            queue.add(line);
            if (scheduled.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater(flushRef.get(), ModalityState.any());
            }
        };

        writerRef.set(writer);
        return writer;
    }

    private static String consoleKey(Project project, String title) {
        String safeTitle = title == null ? "" : title;
        if (project == null) {
            return "<no-project>\n" + safeTitle;
        }

        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            return basePath + "\n" + safeTitle;
        }

        return Integer.toHexString(System.identityHashCode(project)) + "\n" + safeTitle;
    }

    static void logStage(String stage, String msg) {
        String line = String.format(Locale.ROOT, "[%s] %s", stage, msg);
        System.out.println(addStageSpacing(line, STDOUT_LAST_STAGE));
    }

    static void logStage(Consumer<String> viewer, String stage, String msg) {
        logStage(stage, msg);
        if (viewer != null) {
            viewer.accept("[" + stage + "] " + (msg == null ? "" : msg));
        }
    }

    static void showNotification(Project project, String message, NotificationType type) {
        String content = message == null ? "" : message;
        if (content.contains("\n")) {
            content = escapeHtml(content).replace("\n", "<br/>");
            content = "<html>" + content + "</html>";
        }
        Notification n = new Notification("AntiCopyPaster", "Clone Refactoring", content, type);
        Notifications.Bus.notify(n, project);
    }

    /**
     * Show a diff in an IDE editor tab and wait for the user's Apply/Cancel choice.
     * Closing the tab counts as Cancel.
     */
    static boolean showDiffAndConfirmApply(Project project, String fileName, String before, String after) {
        if (project == null || project.isDisposed()) return false;
        if (ApplicationManager.getApplication().isDispatchThread()) {
            showNotification(
                    project,
                    "[Clone] Refactor preview could not wait for a tab decision on the UI thread.",
                    NotificationType.WARNING
            );
            return false;
        }

        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<VirtualFile> diffFileRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Disposable> listenerDisposableRef = new java.util.concurrent.atomic.AtomicReference<>();

        Consumer<Boolean> complete = accepted -> {
            if (!completed.compareAndSet(false, true)) return;
            decision.complete(Boolean.TRUE.equals(accepted));
            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFile diffFile = diffFileRef.getAndSet(null);
                Disposable listenerDisposable = listenerDisposableRef.getAndSet(null);
                try {
                    if (!project.isDisposed() && diffFile != null) {
                        FileEditorManager.getInstance(project).closeFile(diffFile);
                    }
                } finally {
                    if (listenerDisposable != null) {
                        Disposer.dispose(listenerDisposable);
                    }
                }
            }, ModalityState.any());
        };

        Runnable ui = () -> {
            Disposable listenerDisposable = Disposer.newDisposable("RefactorDiffPreviewTab");
            listenerDisposableRef.set(listenerDisposable);
            Disposer.register(project, listenerDisposable);
            Disposer.register(listenerDisposable, () -> complete.accept(false));
            try {
                DiffContentFactory f = DiffContentFactory.getInstance();
                var left = f.create(before == null ? "" : before);
                var right = f.create(after == null ? "" : after);

                String normalizedFileName = fileName == null || fileName.isBlank() ? "Refactor" : fileName;
                String title = "Refactor Preview: " + normalizedFileName;
                SimpleDiffRequest req = new SimpleDiffRequest(title, left, right, "Current", "Proposed");
                req.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
                req.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, List.of(
                        new DiffDecisionAction(
                                "Apply Refactor",
                                "Apply this refactoring",
                                AllIcons.Actions.Checked,
                                () -> complete.accept(true)
                        ),
                        new DiffDecisionAction(
                                "Cancel Refactor",
                                "Do not apply this refactoring",
                                AllIcons.Actions.Cancel,
                                () -> complete.accept(false)
                        )
                ));

                SimpleDiffVirtualFile diffFile = new SimpleDiffVirtualFile(req);
                diffFileRef.set(diffFile);

                project.getMessageBus().connect(listenerDisposable).subscribe(
                        FileEditorManagerListener.FILE_EDITOR_MANAGER,
                        new FileEditorManagerListener() {
                            @Override
                            public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                                if (file == diffFile) {
                                    complete.accept(false);
                                }
                            }
                        }
                );

                FileEditorManager.getInstance(project).openFile(diffFile, true);
            } catch (Throwable t) {
                Disposable disposableToCleanUp = listenerDisposableRef.getAndSet(null);
                if (disposableToCleanUp != null) {
                    Disposer.dispose(disposableToCleanUp);
                }
                complete.accept(false);
            }
        };

        ApplicationManager.getApplication().invokeAndWait(ui);

        boolean accepted;
        try {
            accepted = decision.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            accepted = false;
        } catch (ExecutionException e) {
            accepted = false;
        }

        if (!project.isDisposed()) {
            if (accepted) {
                AntiCopyPasterUsageStatistics.getInstance(project).refactoringApplied();
            } else {
                AntiCopyPasterUsageStatistics.getInstance(project).refactoringCancelled();
            }
        }
        return accepted;
    }

    private static final class DiffDecisionAction extends AnAction {
        private final Runnable onChosen;

        private DiffDecisionAction(String text, String description, Icon icon, Runnable onChosen) {
            super(text, description, icon);
            this.onChosen = onChosen;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (onChosen != null) {
                onChosen.run();
            }
        }
    }

    private static String addStageSpacing(String line, java.util.concurrent.atomic.AtomicReference<String> lastStage) {
        String stage = extractStage(line);
        if (stage == null || stage.isBlank()) {
            return line;
        }

        String previous = lastStage.getAndSet(stage);
        if (previous == null || previous.equals(stage) || line.startsWith("\n")) {
            return line;
        }
        return "\n" + line;
    }

    private static String extractStage(String line) {
        if (line == null || !line.startsWith("[")) return null;
        int end = line.indexOf(']');
        if (end <= 1) return null;
        return line.substring(1, end);
    }

    private static String sanitizeForFilename(String s) {
        if (s == null) return "unknown";
        String t = s.trim();
        if (t.isEmpty()) return "unknown";
        t = t.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (t.length() > 80) t = t.substring(0, 80);
        return t;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replace("&", "&amp;");
        t = t.replace("<", "&lt;");
        t = t.replace(">", "&gt;");
        t = t.replace("\"", "&quot;");
        return t;
    }
}
