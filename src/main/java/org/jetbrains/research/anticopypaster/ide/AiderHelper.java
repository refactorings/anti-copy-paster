package org.jetbrains.research.anticopypaster.ide;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import java.util.function.Consumer;
import java.nio.file.StandardCopyOption;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import org.jetbrains.research.anticopypaster.rag.RagService;
import org.jetbrains.research.anticopypaster.statistics.CloneUsageStatistics;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

public class AiderHelper {

    private static final Map<String, ConsoleView> CONSOLE_BY_TITLE = new ConcurrentHashMap<>();

    // RAG defaults to mirror the multi-agent refactor baseline (refactor.java)
    private static final String DEFAULT_REFACTOR_DB_PATH = "refactor_database.csv";
    private static final int DEFAULT_RAG_TOP_K = 2;
    private static final int DEFAULT_RAG_MAX_CHARS = 700;

    /**
     * Opens (or reuses) a tool window console viewer for streaming output.
     * Ensures that updates to the console are throttled to avoid flooding the EDT queue.
     *
     * @param project the IntelliJ project
     * @param title   title of the console tab
     * @return a consumer that accepts lines to print to the console
     */
    public static Consumer<String> openStreamingViewer(Project project, String title) {
        final java.util.concurrent.atomic.AtomicReference<ConsoleView> consoleRef = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable createOrReuse = () -> {
            // Ensure tool window exists (Run preferred, else custom)
            ToolWindowManager twm = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = twm.getToolWindow(ToolWindowId.RUN);
            if (toolWindow == null) {
                toolWindow = twm.getToolWindow("Output");
                if (toolWindow == null) {
                    toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable("Output"));
                }
            }

            com.intellij.ui.content.ContentManager cm = toolWindow.getContentManager();

            // Try to reuse existing console for this title
            ConsoleView console = CONSOLE_BY_TITLE.get(title);
            Content content = cm.findContent(title);

            if (console == null || content == null) {
                // Create new console + content if missing
                console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
                Content newContent = ContentFactory.getInstance().createContent(console.getComponent(), title, true);

                // If a stale tab with the same title exists, remove it first
                if (content != null) {
                    cm.removeContent(content, true);
                }
                cm.addContent(newContent);
                cm.setSelectedContent(newContent);
                CONSOLE_BY_TITLE.put(title, console);
            } else {
                // Reuse tab and clear its console output
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

        // Writer that prints to the console on EDT, but throttles updates to avoid flooding the EDT queue.
        final java.util.concurrent.ConcurrentLinkedQueue<String> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

        final java.util.concurrent.atomic.AtomicReference<Runnable> flushRef = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable flush = () -> {
            scheduled.set(false);
            ConsoleView console = consoleRef.get();
            if (console == null) return;

            StringBuilder sb = new StringBuilder();
            String s;
            int maxLines = 200; // drain up to N lines per UI tick
            while (maxLines-- > 0 && (s = queue.poll()) != null) {
                sb.append(s);
                if (!s.endsWith("\n")) sb.append('\n');
            }
            if (sb.length() > 0) {
                console.print(sb.toString(), ConsoleViewContentType.NORMAL_OUTPUT);
            }

            // If more remains, schedule another flush
            if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater(flushRef.get(), ModalityState.any());
            }
        };
        flushRef.set(flush);

        return line -> {
            if (line == null) return;
            queue.add(line);
            if (scheduled.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater(flush, ModalityState.any());
            }
        };
    }

    /**
     * Runs a quick Aider‑based clone check for the file and, if clones are indicated, asks the user whether to refactor.
     * Copies the file to a temp path, streams detector output, and prompts for a refactor preview on positive signals.
     *
     * @param project    the IntelliJ project
     * @param file       file to analyze for clones
     * @param provider   model provider identifier (e.g., OpenAI, Google, Anthropic, Azure, Deepseek, xAI)
     * @param model      model name (provider‑specific; some are normalized inside)
     * @param apikey     API key to expose via environment variables to the Aider process
     * @param aiderPath  path to the {@code aider} executable
     * @param apiBase    optional API base (used by Azure and some custom deployments)
     * @param apiVersion optional API version (used by Azure)
     */
    public static void checkAndSuggestRefactor(Project project, VirtualFile file, String provider, String model, String apikey, String aiderPath, String apiBase, String apiVersion) {
        String fileName = file.getName();
        notify(project, "Clone is running clone detection on " + fileName + "...");
        String filePath = file.getPath();

        try {
            File originalFile = new File(filePath);
            File tempFile = File.createTempFile("aider_clonecheck_", ".java");
            Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String tempFilePath = tempFile.getAbsolutePath();
            // Fairness: prevent Aider from pulling external web context by auto-scraping URLs in the file header/comments.
            try {
                String tmp = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);
                String sanitized = neutralizeHttpUrls(tmp);
                if (!sanitized.equals(tmp)) {
                    Files.writeString(tempFile.toPath(), sanitized, StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
                // best-effort only
            }

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Consumer<String> viewer = openStreamingViewer(project, "Clone Detection Output");
                    String output = runAiderWithPromptStreaming(project, aiderPath, tempFilePath,
                            "Please detect any clones in this file. Response with either 'clones found' or 'no clones found'", provider, model, apikey, apiBase, apiVersion, viewer);

                    if (output != null && containsDuplicateHint(output)) {
                        System.out.println("===> Output:\n" + output);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            int choice = Messages.showYesNoDialog(
                                    project,
                                    "Clone found clones in " + fileName + ". Do you want to refactor it?",
                                    "Code Refactoring",
                                    Messages.getQuestionIcon()
                            );
                            if (choice == Messages.YES) {
                                runRefactorWithPreview(project, fileName, filePath, provider, model, apikey, aiderPath, apiBase, apiVersion);
                            }
                        });
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "Clone did not detect any clones in the file " + fileName + ".");
                        });
                    }

                } catch (Exception e) {
                    notify(project, "Clone Error: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            notify(project, "Clone Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Show the "Apply Refactoring" dialog on the EDT, but perform the actual file overwrite on a pooled thread
     * to avoid freezing the UI (slow operations on EDT).
     */
    private static void promptApplyRefactoring(Project project,
                                               String fileName,
                                               java.nio.file.Path originalPath,
                                               String refactoredContent) {
        ApplicationManager.getApplication().invokeLater(() -> {
            int choice = Messages.showYesNoDialog(
                    project,
                    "Do you want to apply the refactored code to " + fileName + "?",
                    "Apply Refactoring",
                    Messages.getQuestionIcon()
            );

            if (choice == Messages.YES) {
                AntiCopyPasterUsageStatistics.getInstance(project).refactoringApplied();
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        Files.write(originalPath, refactoredContent.getBytes(StandardCharsets.UTF_8));

                        // Refresh VFS on EDT after writing so the editor picks up changes
                        ApplicationManager.getApplication().invokeLater(() -> {
                            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(originalPath);
                            if (vf != null) {
                                vf.refresh(false, false);
                            }
                            notify(project, "File " + fileName + " has been updated with refactored version.");
                        }, ModalityState.any());
                    } catch (IOException e) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "Failed to overwrite file " + fileName + ": " + e.getMessage());
                        }, ModalityState.any());
                    }
                });
            } else {
                AntiCopyPasterUsageStatistics.getInstance(project).refactoringCancelled();
                notify(project, "Refactoring for file " + fileName + " was canceled.");
            }
        }, ModalityState.any());
    }

    /**
     * Performs an Extract‑Method style refactor via Aider on a temp copy, shows a side‑by‑side diff, and
     * applies the result to the original file only if the user confirms.
     *
     * @param project    the IntelliJ project
     * @param fileName   display name used in messages
     * @param filePath   absolute path to the source file to refactor
     * @param provider   LLM provider identifier
     * @param model      model name (may be normalized)
     * @param apikey     API key for the provider
     * @param aiderPath  path to the {@code aider} executable
     * @param apiBase    optional API base (e.g., Azure endpoint)
     * @param apiVersion optional API version (for Azure)
     */
    private static void runRefactorWithPreview(Project project, String fileName, String filePath, String provider, String model, String apikey, String aiderPath, String apiBase, String apiVersion) {
        notify(project, "Clone is running code refactoring on " + fileName + "...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                File originalFile = new File(filePath);
                File tempFile = File.createTempFile("aider_refactor_", ".java");
                Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                // Fairness: prevent Aider from pulling external web context by auto-scraping URLs in the file header/comments.
                try {
                    String tmp = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);
                    String sanitized = neutralizeHttpUrls(tmp);
                    if (!sanitized.equals(tmp)) {
                        Files.writeString(tempFile.toPath(), sanitized, StandardCharsets.UTF_8);
                    }
                } catch (Throwable ignored) {
                    // best-effort only
                }

                Consumer<String> viewer = openStreamingViewer(project, "Clone Refactoring Output");

                // Build a fair-comparison prompt that mirrors the multi-agent refactor baseline (refactor.java)
                String originalForPrompt = "";
                try {
                    originalForPrompt = Files.readString(originalFile.toPath(), StandardCharsets.UTF_8);
                    // Also neutralize URLs inside the prompt copy.
                    originalForPrompt = neutralizeHttpUrls(originalForPrompt);
                } catch (Throwable t) {
                    originalForPrompt = "";
                }

                // Optional RAG: use the (truncated) file as the query when we don't have a representative clone snippet.
                String rag = "";
                try {
                    String query = safeTruncate(originalForPrompt, DEFAULT_RAG_MAX_CHARS);
                    rag = RagService.buildRefactorRagGuidance(project, DEFAULT_REFACTOR_DB_PATH, query, DEFAULT_RAG_TOP_K, DEFAULT_RAG_MAX_CHARS);
                } catch (Throwable ignored) {
                    rag = "";
                }

                String refactorPrompt = buildAiderRefactorPrompt(fileName, originalForPrompt, rag);

                // Aider typically uses diff-based edit formats (SEARCH/REPLACE) and applies edits directly to the file.
                // For stability and fairness, we ignore stdout formatting and read the final refactored file from disk.
                String output = null;
                try {
                    output = runAiderWithPromptStreaming(project, aiderPath, tempFile.getAbsolutePath(),
                            refactorPrompt,
                            provider, model, apikey, apiBase, apiVersion, viewer);
                } catch (Throwable tRun) {
                    notify(project, "Aider refactor run failed: " + tRun.getMessage());
                }
                if (output != null) {
                    System.out.println("===> Refactor output (stdout):\n" + output);
                }

                // Always read the on-disk content after Aider finishes; this is the source of truth.
                String originalContent = Files.readString(originalFile.toPath(), StandardCharsets.UTF_8);
                String refactoredContent = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);

                // If Aider did not change the temp file, try a best-effort fallback: extract a full-file Java code block.
                // (Some models can be configured to output whole files.)
                if (originalContent.equals(refactoredContent) && output != null) {
                    String fenced = extractJavaCodeBlock(output);
                    if (fenced != null && !fenced.isBlank()) {
                        try {
                            Files.writeString(tempFile.toPath(), fenced, StandardCharsets.UTF_8);
                            refactoredContent = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);
                        } catch (IOException ioe) {
                            notify(project, "Failed to write extracted refactored content to temp file: " + ioe.getMessage());
                        }
                    }
                }

                // `refactoredContent` may have been reassigned above, so it is not effectively-final.
                // Capture stable values for use in lambdas below.
                final String finalRefactoredContent = refactoredContent;
                final String finalOriginalContent = originalContent;

                if (!finalOriginalContent.equals(finalRefactoredContent)) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // Show diff window
                        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
                        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                                "Refactor Preview: Compare Original and Refactored Code",
                                contentFactory.create(finalOriginalContent),
                                contentFactory.create(finalRefactoredContent),
                                "Original",
                                "Refactored"
                        );
                        DiffManager.getInstance().showDiff(project, diffRequest);
                    }, ModalityState.any());

                    // While the user is previewing the diff, run EvoSuite test generation instead of sleeping.
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        // === EvoSuite integration (usable baseline) ===

                        // 1) Resolve EvoSuite jar by extracting the bundled resource to a temp file (Option B)
                        String evoSuiteJarPath;
                        try {
                            evoSuiteJarPath = resolveBundledEvoSuiteJarPath();
                        } catch (Exception ex) {
                            notify(project, "EvoSuite skipped: failed to load bundled EvoSuite jar from resources: " + ex.getMessage());
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), finalRefactoredContent);
                            return;
                        }

                        // 2) Build a robust classpath from IntelliJ (compiler outputs + libraries)
                        String classpath;
                        try {
                            classpath = buildClasspathFromIde(project);
                        } catch (Throwable t) {
                            notify(project, "EvoSuite skipped: failed to build classpath from IDE: " + t.getMessage());
                            classpath = null;
                        }

                        // IMPORTANT:
                        //  - For real projects (even if they have a single module), we must NOT use single-file compilation.
                        //    Single-file output misses dependencies and will cause ClassNotFoundException in EvoSuite.
                        //  - Only fall back to single-file compilation when the IDE classpath is empty (i.e., project not imported/compiled).
                        if (classpath == null || classpath.isBlank()) {
                            notify(project, "EvoSuite: IDE classpath is empty. Trying a Java-8 single-file fallback (works only for plain one-file demos).");
                            try {
                                String tempCp = compileSingleJavaFileToTempOutput(project, filePath);
                                if (tempCp != null && !tempCp.isBlank()) {
                                    classpath = tempCp;
                                    notify(project, "EvoSuite: compiled current file with Java 8 into a temp output directory for compatibility.");
                                }
                            } catch (Throwable t) {
                                notify(project, "EvoSuite skipped: classpath is empty and Java-8 fallback compilation failed: " + t.getMessage());
                                promptApplyRefactoring(project, fileName, originalFile.toPath(), finalRefactoredContent);
                                return;
                            }
                        } else {
                            // Maven/Gradle OR plain IntelliJ module projects: always prefer the IDE/module classpath.
                            // This is required for multi-class dependencies (e.g., JHotDraw).
                            notify(project, "EvoSuite: using IDE/module classpath (recommended for real projects)." );
                        }

                        if (classpath == null || classpath.isBlank()) {
                            notify(project, "EvoSuite skipped: classpath is still empty. Make sure the project is imported and compiled.");
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), finalRefactoredContent);
                            return;
                        }

                        // 3) Resolve the fully-qualified class name via PSI (not by guessing from paths)
                        String classFqn = null;
                        try {
                            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(filePath);
                            if (vf != null) {
                                classFqn = resolveTopLevelClassFqn(project, vf);
                            }
                        } catch (Throwable t) {
                            // best-effort only
                        }

                        if (classFqn == null || classFqn.isBlank()) {
                            notify(project, "EvoSuite skipped: failed to resolve class FQN for " + fileName + ". Ensure it is a Java file with a package/class declaration.");
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), finalRefactoredContent);
                            return;
                        }

                        // 4) (no-op, logic moved above)
                        // 5) Resolve Java executable (prefer JAVA_8_HOME for EvoSuite 1.0.6)
                        String javaExe = resolveJavaExecutable();

                        // 5) Run EvoSuite while previewing (and run generated JUnit4 against the REFACTORED preview before applying)
                        boolean refactoredPass = false;
                        try {
                            refactoredPass = runEvoSuiteOnClass(project, javaExe, evoSuiteJarPath, classpath, classFqn, filePath, finalRefactoredContent);
                        } catch (Throwable t) {
                            notify(project, "EvoSuite failed during preview: " + t.getMessage());
                            refactoredPass = false;
                        }

                        if (refactoredPass) {
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), finalRefactoredContent);
                        } else {
                            notify(project, "Refactored preview tests FAILED. Not applying changes to " + fileName + ".");
                        }
                    });
                } else {
                    notify(project, "No changes in refactored code for file " + fileName + ".");
                }

            } catch (Exception e) {
                notify(project, "Refactor failed for file " + fileName + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Compile a single Java source file into a temporary output directory using Java 8 (javac),
     * and return that directory. Intended for plain/single-file projects so EvoSuite 1.0.6
     * (running on Java 8) can load compatible bytecode.
     */
    private static String compileSingleJavaFileToTempOutput(Project project, String javaFilePath)
            throws IOException, InterruptedException {
        if (javaFilePath == null || javaFilePath.isBlank()) return null;
        File src = new File(javaFilePath);
        if (!src.exists() || !src.isFile()) return null;

        java.nio.file.Path outDir = java.nio.file.Files.createTempDirectory("evosuite-classes-");
        outDir.toFile().deleteOnExit();

        String javac = resolveJavacExecutable();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javac);
        cmd.add("-encoding");
        cmd.add("UTF-8");
        cmd.add("-d");
        cmd.add(outDir.toAbsolutePath().toString());
        cmd.add(src.getAbsolutePath());

        Consumer<String> viewer = openStreamingViewer(project, "JUnit Test Output");
        viewer.accept("[EvoSuite] Java-8 compile (single-file) for compatibility: " + String.join(" ", cmd));

        int exit = runProcessStreaming(project, cmd, viewer);
        if (exit != 0) {
            throw new RuntimeException("javac exited with code " + exit);
        }
        return outDir.toAbsolutePath().toString();
    }

    private static String compileJavaSourceTextToTempOutput(Project project, String classFqn, String javaSource, String projectCp)
            throws IOException, InterruptedException {
        if (classFqn == null || classFqn.isBlank()) throw new IllegalArgumentException("classFqn is empty");
        if (javaSource == null || javaSource.isBlank()) throw new IllegalArgumentException("javaSource is empty");

        String simpleName = classFqn;
        String pkg = "";
        int lastDot = classFqn.lastIndexOf('.');
        if (lastDot >= 0) {
            pkg = classFqn.substring(0, lastDot);
            simpleName = classFqn.substring(lastDot + 1);
        }

        java.nio.file.Path srcRoot = java.nio.file.Files.createTempDirectory("refactor-src-");
        srcRoot.toFile().deleteOnExit();

        java.nio.file.Path pkgDir = srcRoot;
        if (!pkg.isBlank()) {
            pkgDir = srcRoot.resolve(pkg.replace('.', File.separatorChar));
            java.nio.file.Files.createDirectories(pkgDir);
        }

        java.nio.file.Path srcFile = pkgDir.resolve(simpleName + ".java");
        java.nio.file.Files.writeString(srcFile, javaSource, StandardCharsets.UTF_8);

        java.nio.file.Path outDir = java.nio.file.Files.createTempDirectory("evosuite-refactored-classes-");
        outDir.toFile().deleteOnExit();

        String javac = resolveJavacExecutable();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javac);
        cmd.add("-encoding"); cmd.add("UTF-8");
        if (projectCp != null && !projectCp.isBlank()) {
            cmd.add("-cp");
            cmd.add(projectCp);
        }
        cmd.add("-d"); cmd.add(outDir.toAbsolutePath().toString());
        cmd.add(srcFile.toAbsolutePath().toString());

        Consumer<String> viewer = openStreamingViewer(project, "JUnit Test Output");
        viewer.accept("[EvoSuite] Java-8 compile (refactored preview): " + String.join(" ", cmd));

        int exit = runProcessStreaming(project, cmd, viewer);
        if (exit != 0) throw new RuntimeException("javac(refactored preview) exited with code " + exit);

        return outDir.toAbsolutePath().toString();
    }

    /**
     * Resolve an executable under a Java home (or bin dir) in an OS-aware way.
     * On Windows, appends .exe.
     */
    private static String resolveExeUnderHome(String home, String exeBaseName) {
        if (home == null || home.isBlank()) return null;

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String exeName = isWindows ? (exeBaseName + ".exe") : exeBaseName;

        // Typical layout: <JAVA_HOME>/bin/<exe>
        File f = new File(home, "bin" + File.separator + exeName);
        if (f.exists() && f.isFile()) return f.getAbsolutePath();

        // If caller passed a bin directory directly (e.g., D:\\bin)
        File f2 = new File(home, exeName);
        if (f2.exists() && f2.isFile()) return f2.getAbsolutePath();

        return null;
    }

    /**
     * Read a Java home from environment variables first, then from JVM system properties.
     * This lets the plugin work both when launched from a shell and when JAVA_8_HOME/JAVA_11_HOME
     * is passed through IntelliJ VM options (for example: -DJAVA_8_HOME=/path/to/jdk8).
     */
    private static String getConfiguredJavaHome(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v;
        return null;
    }

    /**
     * Best-effort javac resolution. Prefers JAVA_8_HOME (to match EvoSuite runtime), then JAVA_11_HOME, then JAVA_HOME, else "javac".
     */
    private static String resolveJavacExecutable() {
        String java8 = getConfiguredJavaHome("JAVA_8_HOME");
        String p = resolveExeUnderHome(java8, "javac");
        if (p != null) return p;

        try {
            Project project = ProjectManager.getInstance().getOpenProjects().length > 0
                    ? ProjectManager.getInstance().getOpenProjects()[0]
                    : null;
            if (project != null) {
                ProjectRootManager prm = ProjectRootManager.getInstance(project);
                if (prm != null && prm.getProjectSdk() != null && prm.getProjectSdk().getHomePath() != null) {
                    p = resolveExeUnderHome(prm.getProjectSdk().getHomePath(), "javac");
                    if (p != null) return p;
                }
            }
        } catch (Throwable ignored) {
            // best-effort only
        }

        String java11 = getConfiguredJavaHome("JAVA_11_HOME");
        p = resolveExeUnderHome(java11, "javac");
        if (p != null) return p;

        String javaHome = getConfiguredJavaHome("JAVA_HOME");
        p = resolveExeUnderHome(javaHome, "javac");
        if (p != null) return p;

        return "javac";
    }

    /**
     * Heuristically checks whether Aider's output indicates that clones were found.
     * It requires the phrase "clones found" and rejects "no clones found".
     *
     * @param output raw stdout collected from the Aider process
     * @return {@code true} if the output suggests clones were found; {@code false} otherwise
     */
    private static boolean containsDuplicateHint(String output) {
        String normalized = output.toLowerCase().trim();
        return normalized.contains("clones found") && !normalized.contains("no clones found");
    }

    /**
     * Runs Aider in streaming mode, forwarding cleaned stdout lines to the given viewer and returning the full output.
     * Also normalizes provider/model identifiers (e.g., deepseek/, azure/, xai/).
     *
     * @param project    current project (working directory and notifications)
     * @param aiderPath  path to {@code aider}
     * @param filePath   path to the file to include in context
     * @param prompt     instruction to send
     * @param provider   provider identifier
     * @param model      model name (may be normalized with a provider prefix)
     * @param apikey     API key for the provider
     * @param apiBase    optional API base
     * @param apiVersion optional API version
     * @param viewer     consumer that receives each cleaned line as it arrives; may be {@code null}
     * @return the full combined output captured from the Aider subprocess
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the process is interrupted
     */
    public static String runAiderWithPromptStreaming(Project project, String aiderPath, String filePath, String prompt,
                                                     String provider, String model, String apikey,
                                                     String apiBase, String apiVersion, Consumer<String> viewer)
            throws IOException, InterruptedException {
        provider = normalizeProviderName(provider);

        if (provider.equals("DeepSeek")) {
            model = "deepseek/" + model;
        }
        if (provider.equals("Azure")) {
            model = "azure/" + model;
        }
        if (provider.equals("xAI")) {
            model = "xai/" + model;
        }

        if (provider.equals("Ollama")) {
            model = "ollama_chat/" + model;
        }
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add(aiderPath);
        args.add("--model");
        args.add(model);
        args.add("--yes");
        args.add("--message");
        args.add(prompt);
        args.add(filePath);
        if (model != null && model.toLowerCase().contains("gpt-5")) {
            args.add("--no-stream");
            args.add("--check-model-accepts-settings");
            try {
                java.nio.file.Path emptySettings = java.nio.file.Files.createTempFile("aider_model_settings_", ".json");
                java.nio.file.Files.writeString(emptySettings, "{}", java.nio.charset.StandardCharsets.UTF_8);
                args.add("--model-settings-file");
                args.add(emptySettings.toString());
            } catch (IOException ioe) {
                System.err.println("[Clone] Failed to create empty model settings file: " + ioe.getMessage());
            }
        }
        return runCommand(project, provider,
                apikey,
                apiBase,
                apiVersion,
                viewer,
                args.toArray(new String[0])
        );
    }

    /**
     * Executes an external process with provider‑specific environment variables, cleans and optionally streams stdout,
     * and returns the combined output as a string. Sets the project root as the working directory and filters noisy lines.
     *
     * @param project    IntelliJ project (used for working directory and notifications)
     * @param provider   provider identifier (case‑insensitive)
     * @param apikey     API key to export in the environment
     * @param apiBase    optional API base (Azure)
     * @param apiVersion optional API version (Azure)
     * @param viewer     optional streaming sink for cleaned lines (may be {@code null})
     * @param command    full command array to run (executable plus args)
     * @return combined stdout from the process
     * @throws IOException          if process start fails
     * @throws InterruptedException if waiting on the process is interrupted
     * @throws RuntimeException     if the process exits non‑zero
     */
    private static String runCommand(Project project, String provider, String apikey, String apiBase,
                                     String apiVersion, Consumer<String> viewer, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // Ensure Aider runs in a writable working directory (project root) so it can write .aider.input.history
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            pb.directory(new File(basePath));
        }
        switch (provider.toUpperCase()) {
            case "OPENAI" -> {
                pb.environment().put("OPENAI_API_KEY", apikey);
                // Ensure no conflicting providers leak into this run
                pb.environment().remove("AZURE_API_KEY");
                pb.environment().remove("AZURE_API_VERSION");
                pb.environment().remove("AZURE_API_BASE");
                pb.environment().remove("OLLAMA_API_BASE");
                // Remove any temperature-related env vars that might inject unsupported sampling
                pb.environment().remove("OPENAI_TEMPERATURE");
                pb.environment().remove("AIDER_TEMPERATURE");
                // Also remove common LiteLLM extra/default params envs if present
                pb.environment().remove("LITELLM_PARAMS");
                pb.environment().remove("LITELLM_DEFAULT_PARAMS");
                // Drop any env var whose key contains "TEMPERATURE"
                for (String k : new java.util.HashSet<>(pb.environment().keySet())) {
                    if (k != null && k.toUpperCase().contains("TEMPERATURE")) {
                        pb.environment().remove(k);
                    }
                }
            }
            case "GOOGLE" -> {
                pb.environment().put("GEMINI_API_KEY", apikey);
                pb.environment().put("AIDER_GEMINI_PROVIDER", "google-ai-studio");
            }
            case "ANTHROPIC" -> pb.environment().put("ANTHROPIC_API_KEY", apikey);
            case "DEEPSEEK" -> pb.environment().put("DEEPSEEK_API_KEY", apikey);
            case "OLLAMA" -> {
                if (apiBase == null || apiBase.isBlank()) {
                    apiBase = "http://127.0.0.1:11434";
                }
                apiBase = normalizeOllamaApiBase(apiBase);
                pb.environment().put("OLLAMA_API_BASE", apiBase);

                pb.environment().remove("AZURE_API_KEY");
                pb.environment().remove("AZURE_API_VERSION");
                pb.environment().remove("AZURE_API_BASE");
                pb.environment().remove("OPENAI_API_KEY");
            }
            case "AZURE" -> {
                if (apiBase == null || apiBase.isBlank()) {
                    throw new IllegalArgumentException("Azure provider selected but API base is empty");
                }
                if (apiBase.contains("11434")) {
                    System.err.println("[Clone] Warning: Azure API base points to 11434 (Ollama). This will 404.");
                }
                pb.environment().put("AZURE_API_KEY", apikey);
                pb.environment().put("AZURE_API_VERSION", apiVersion);
                pb.environment().put("AZURE_API_BASE", apiBase);

                pb.environment().remove("OLLAMA_API_BASE");
                pb.environment().remove("OPENAI_API_KEY");
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        // Diagnostics: show whether per-user or per-project Aider config files exist and if they mention temperature
        try {
            java.util.List<java.io.File> cfgs = new java.util.ArrayList<>();
            String home = System.getProperty("user.home");
            String work = (pb.directory() != null ? pb.directory().getAbsolutePath() : null);
            if (home != null) {
                cfgs.add(new java.io.File(home, ".aider.conf"));
                cfgs.add(new java.io.File(home, ".aider.conf.yml"));
                cfgs.add(new java.io.File(home, ".aider.conf.yaml"));
                cfgs.add(new java.io.File(home, ".aider.conf.json"));
            }
            if (work != null) {
                cfgs.add(new java.io.File(work, ".aider.conf"));
                cfgs.add(new java.io.File(work, ".aider.conf.yml"));
                cfgs.add(new java.io.File(work, ".aider.conf.yaml"));
                cfgs.add(new java.io.File(work, ".aider.conf.json"));
            }
            for (java.io.File f : cfgs) {
                if (f != null && f.exists() && f.isFile()) {
                    String p = f.getAbsolutePath();
                    String content = java.nio.file.Files.readString(f.toPath());
                    boolean mentionsTemp = content.toLowerCase().contains("temperature");
                    String msg = "[Clone] Detected Aider config: " + p + (mentionsTemp ? " (contains 'temperature')" : "");
                    System.out.println(msg);
                    if (viewer != null) viewer.accept(msg);
                }
            }
        } catch (Throwable t) {
            System.err.println("[Clone] Config scan failed: " + t.getMessage());
        }
        // Hint many CLIs to avoid ANSI color output in non-TTY environments
        pb.environment().put("NO_COLOR", "1");

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            // Filter specific known noisy warnings
            if (line.contains("initialize prompt toolkit") || line.contains("cmd.exe")) {
                continue;
            }
            // Strip ANSI codes and non-printable control characters to avoid garbled output
            String cleaned = stripNonPrintable(stripAnsi(line));
            if (cleaned == null || cleaned.trim().isEmpty()) {
                continue;
            }
            if (viewer != null) {
                viewer.accept(cleaned);
            }
            output.append(cleaned).append("\n");
        }

        String lowerOutput = output.toString().toLowerCase();
        if (lowerOutput.contains("token limit") && (lowerOutput.contains("exceed") || lowerOutput.contains("exceeded") || lowerOutput.contains("exceeds"))) {
            notify(project, "Warning: Your request exceeded the model's token limit. Please reduce the file size.");
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command));
        }

        return output.toString();
    }

    private static String normalizeProviderName(String provider) {
        if (provider == null) {
            return "";
        }
        String trimmed = provider.trim();
        if ("Google".equalsIgnoreCase(trimmed) || "Gemini".equalsIgnoreCase(trimmed)) {
            return "Google";
        }
        if ("OpenAI".equalsIgnoreCase(trimmed)) {
            return "OpenAI";
        }
        if ("Anthropic".equalsIgnoreCase(trimmed)) {
            return "Anthropic";
        }
        if ("DeepSeek".equalsIgnoreCase(trimmed)) {
            return "DeepSeek";
        }
        if ("Azure".equalsIgnoreCase(trimmed)) {
            return "Azure";
        }
        if ("Ollama".equalsIgnoreCase(trimmed)) {
            return "Ollama";
        }
        if ("xAI".equalsIgnoreCase(trimmed)) {
            return "xAI";
        }
        return trimmed;
    }

    /**
     * Shows a simple informational notification under the "Aider Refactoring" group.
     *
     * @param project IntelliJ project used as the notification context
     * @param content message body to display
     */
    private static void notify(Project project, String content) {
        Notification notification = new Notification(
                "AiderRefactor",
                "Clone Refactoring",
                content,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }

    /**
     * Removes ANSI escape sequences from a line, which prevents garbled output in the UI.
     *
     * @param s input string possibly containing ANSI codes
     * @return the input without ANSI escape sequences (or {@code null} if input is null)
     */
    private static String stripAnsi(String s) {
        if (s == null) return null;
        return s.replaceAll("\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
    }

    /**
     * Filters out non‑printable control characters (except tab/newline/CR) and surrogate code points
     * that tend to appear as broken glyphs in console output.
     *
     * @param s input string
     * @return sanitized string with only printable characters and standard whitespace
     */
    private static String stripNonPrintable(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t' || ch >= 0x20) {
                // Filter out surrogate code points that often appear as garbled remnants
                if (!Character.isSurrogate(ch)) {
                    sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    private static String normalizeOllamaApiBase(String rawApiBase) {
        String normalized = rawApiBase == null ? "" : rawApiBase.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/v1".length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
    /**
     * Best-effort fallback: extracts the first fenced code block from {@code text} whose language is either empty
     * or starts with "java". Normally Aider applies edits directly to the file, so this is only used when the
     * temp file remained unchanged but stdout contains a full-file output.
     */
    private static String extractJavaCodeBlock(String text) {
        if (text == null) return null;
        int i = 0;
        while (i < text.length()) {
            int fenceStart = text.indexOf("```", i);
            if (fenceStart == -1) return null;
            int lineEnd = text.indexOf('\n', fenceStart + 3);
            if (lineEnd == -1) return null;
            String lang = text.substring(fenceStart + 3, lineEnd).trim().toLowerCase();
            // accept "java" or empty language tag
            if (lang.isEmpty() || lang.startsWith("java")) {
                int fenceEnd = text.indexOf("```", lineEnd + 1);
                if (fenceEnd == -1) return null;
                return text.substring(lineEnd + 1, fenceEnd).trim();
            }
            i = lineEnd + 1;
        }
        return null;
    }

    /**
     * Prevent Aider from auto-scraping URLs found in source comments/headers by neutralizing http(s) links.
     * This keeps the comparison fair (no external web context) and avoids pandoc/scrape errors.
     */
    private static String neutralizeHttpUrls(String s) {
        if (s == null || s.isEmpty()) return s;
        // Replace schemes only (keep the rest readable)
        return s.replace("https://", "hxxps://").replace("http://", "hxxp://");
    }

    private static String safeTruncate(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (maxChars <= 0) return t;
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars) + "\n...<truncated>...";
    }

    /**
     * A fair-comparison refactor prompt that mirrors the multi-agent baseline in agents/refactor.java.
     * We include the full file source (or empty if unreadable) and optionally attach a few-shot RAG bundle.
     */
    private static String buildAiderRefactorPrompt(String fileName, String fileSource, String ragExamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Java refactor agent.\n");
        sb.append("You have a single file to modify: ").append(fileName).append("\n");
        sb.append("The file source is below:\n");
        sb.append("```\n").append(fileSource == null ? "" : fileSource).append("\n```\n\n");

//        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
//            sb.append("Here are some few-shot RAG examples to guide you:\n");
//            sb.append(ragExamples).append("\n\n");
//        }

        sb.append("Instructions:\n");
        sb.append("- Use ONLY Extract Method (and creating private helper methods) to remove clones.\n");
        sb.append("- Do NOT use any other refactoring type (e.g., Rename, Move Method, Introduce Parameter Object, etc.).\n");
        sb.append("- Restrict modifications only to this file.\n");
        sb.append("- Preserve package and import statements exactly.\n");
        sb.append("- Keep public API signatures unchanged where possible.\n");
        sb.append("- Minimize edits outside the clone regions.\n");
        sb.append("- Apply edits directly to the file.\n");
        sb.append("- If you output anything, keep it short and do not include SEARCH/REPLACE blocks unless necessary.\n");
        sb.append("- Do not include explanations.\n");

        return sb.toString();
    }


    /**
     * Runs EvoSuite on a given fully-qualified class name using an external EvoSuite jar.
     * Simplified workflow-aligned implementation.
     */
    private static boolean runEvoSuiteOnClass(Project project,
                                              String javaExe,
                                              String evoSuiteJarPath,
                                              String classpath,
                                              String targetClass,
                                              String sourceFilePath,
                                              String refactoredSourceText) throws IOException, InterruptedException {

        Consumer<String> viewer = openStreamingViewer(project, "EvoSuite Output");

        // --- Print actual java -version output ---
        ProcessBuilder versionPb = new ProcessBuilder(javaExe, "-version");
        versionPb.redirectErrorStream(true);
        Process versionProcess = versionPb.start();

        StringBuilder versionOut = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(versionProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                versionOut.append(line).append("\n");
            }
        }
        versionProcess.waitFor();

        String versionText = versionOut.toString();
        viewer.accept("[EvoSuite] resolved java executable: " + javaExe);
        viewer.accept("[EvoSuite] java -version:\n" + versionText);

        int javaMajor = parseJavaMajorVersion(versionText);
        viewer.accept("[EvoSuite] Parsed major version: " + javaMajor);

        File outDir = Files.createTempDirectory("evosuite-tests-").toFile();
        outDir.deleteOnExit();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExe);

        // Add module opens if Java 9+
        if (javaMajor >= 9) {
            cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
            cmd.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            cmd.add("--add-opens=java.base/java.io=ALL-UNNAMED");
            cmd.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        }

        cmd.add("-Xmx2048m");
        cmd.add("-jar");
        cmd.add(evoSuiteJarPath);
        cmd.add("-Dsandbox=false");
        cmd.add("-Djunit_check=FALSE");
        cmd.add("-generateSuite");
        cmd.add("-class");
        cmd.add(targetClass);
        cmd.add("-projectCP");
        cmd.add(classpath);

        cmd.add("-Dsearch_budget=60");
        cmd.add("-Dglobal_timeout=120");
        cmd.add("-Dcriterion=LINE");
        cmd.add("-Dtest_dir=" + outDir.getAbsolutePath());

        viewer.accept("[EvoSuite] Command: " + String.join(" ", cmd));

        java.util.Map<String, String> env = new java.util.HashMap<>();

        String forcedJavaHome = deriveJavaHomeFromJavaExe(javaExe);
        if (forcedJavaHome != null && !forcedJavaHome.isBlank()) {
            env.put("JAVA_HOME", forcedJavaHome);

            // Ensure forked processes that call plain "java" also use this JDK
            String oldPath = System.getenv("PATH");
            String javaBin = forcedJavaHome + File.separator + "bin";
            if (oldPath == null || oldPath.isBlank()) {
                env.put("PATH", javaBin);
            } else {
                env.put("PATH", javaBin + File.pathSeparator + oldPath);
            }

            viewer.accept("[EvoSuite] Enforce JAVA_HOME=" + forcedJavaHome);
            viewer.accept("[EvoSuite] Prepend PATH with " + javaBin);
        }

        // Defensive fallback: ONLY if Java 9+ is actually used, open required modules for XStream/Reflection.
        // On Java 8, "--add-opens" is an unrecognized option and will crash the JVM.
        if (javaMajor >= 9) {
            String oldJto = System.getenv("JAVA_TOOL_OPTIONS");
            String opens = " --add-opens=java.base/java.util=ALL-UNNAMED"
                    + " --add-opens=java.base/java.lang=ALL-UNNAMED"
                    + " --add-opens=java.base/java.io=ALL-UNNAMED"
                    + " --add-opens=java.desktop/java.awt=ALL-UNNAMED";
            env.put("JAVA_TOOL_OPTIONS", (oldJto == null ? "" : oldJto) + opens);
        }

        int exit = runProcessStreaming(project, cmd, viewer, null, env);

        if (exit != 0) {
            viewer.accept("[EvoSuite] Generation failed (exit=" + exit + ")");
            return false;
        }

        viewer.accept("[EvoSuite] Generation finished successfully.");

        // --- After generation: locate native ESTest and run it against the refactored preview ---
        NativeEvoSuiteTest nativeTest;
        try {
            nativeTest = locateNativeEvoSuiteTests(project, outDir, targetClass, sourceFilePath, viewer);
        } catch (Throwable t) {
            viewer.accept("[EvoSuite] Failed to locate native ESTest: " + t.getMessage());
            return false;
        }

        if (nativeTest == null) {
            viewer.accept("[EvoSuite] No native ESTest found to execute.");
            return false;
        }

        // Compile the refactored preview source so it overrides the project's compiled class
        String refactoredOut;
        try {
            refactoredOut = compileJavaSourceTextToTempOutput(project, targetClass, refactoredSourceText, classpath);
        } catch (Throwable t) {
            viewer.accept("[EvoSuite] Failed to compile refactored preview for test run: " + t.getMessage());
            return false;
        }

        Consumer<String> junitViewer = openStreamingViewer(project, "EvoSuite Test Run");
        boolean ok;
        try {
            ok = runNativeEvoSuiteJUnit4(project, javaExe, classpath, refactoredOut, nativeTest, junitViewer, evoSuiteJarPath);
        } catch (Throwable t) {
            if (junitViewer != null) junitViewer.accept("[TEST] Failed to run native ESTest: " + t.getMessage());
            ok = false;
        }

        if (ok) {
            if (junitViewer != null) junitViewer.accept("[TEST] Native EvoSuite tests PASSED.");
        } else {
            if (junitViewer != null) junitViewer.accept("[TEST] Native EvoSuite tests FAILED.");
        }

        return ok;
    }

    private static String deriveJavaHomeFromJavaExe(String javaExe) {
        if (javaExe == null || javaExe.isBlank()) return null;
        try {
            java.io.File f = new java.io.File(javaExe);
            // .../Contents/Home/bin/java  -> .../Contents/Home
            java.io.File bin = f.getParentFile();
            if (bin == null) return null;
            java.io.File home = bin.getParentFile();
            if (home == null) return null;
            if (new java.io.File(home, "bin").exists()) return home.getAbsolutePath();
            return home.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Parse major Java version from `java -version` output.
     * Supports Java 8 style:  java version "1.8.0_XXX"
     * and Java 9+ style:      java version "11.0.X" / openjdk version "17.0.X"
     */
    private static int parseJavaMajorVersion(String javaVersionOutput) {
        if (javaVersionOutput == null) return -1;
        String s = javaVersionOutput;

        // Common forms include lines like:
        //   java version "1.8.0_452"
        //   openjdk version "11.0.22" 2024-01-16
        //   openjdk version "17" 2021-09-14
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)\\b(?:openjdk|java)\\s+version\\s+\\\"([^\\\"]+)\\\"")
                .matcher(s);
        String ver = null;
        if (m.find()) {
            ver = m.group(1);
        } else {
            // Fallback: sometimes the first quoted token is the version
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\\\"(\\d+(?:\\.\\d+){0,2}(?:_\\d+)?)\\\"").matcher(s);
            if (m2.find()) ver = m2.group(1);
        }
        if (ver == null || ver.isBlank()) return -1;

        ver = ver.trim();
        // Java 8: 1.8.x -> major 8
        if (ver.startsWith("1.")) {
            String[] parts = ver.split("\\.");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            return -1;
        }

        // Java 9+: starts with major
        java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("^(\\d+)").matcher(ver);
        if (m3.find()) {
            try {
                return Integer.parseInt(m3.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }


    private static final class NativeEvoSuiteTest {
        final File estestFile;
        final String estestFqn;
        final File scaffoldingFile;
        final String scaffoldingFqn;

        private NativeEvoSuiteTest(File estestFile, String estestFqn, File scaffoldingFile, String scaffoldingFqn) {
            this.estestFile = estestFile;
            this.estestFqn = estestFqn;
            this.scaffoldingFile = scaffoldingFile;
            this.scaffoldingFqn = scaffoldingFqn;
        }
    }

    /**
     * Locate native EvoSuite tests (<CUT>_ESTest.java and <CUT>_ESTest_scaffolding.java) under evosuite-tests.
     */
    private static NativeEvoSuiteTest locateNativeEvoSuiteTests(Project project,
                                                                File evoOutputBaseDir,
                                                                String targetClassFqn,
                                                                String sourceFilePath,
                                                                Consumer<String> viewer) throws IOException {
        String simpleName = targetClassFqn;
        if (simpleName != null && simpleName.contains(".")) {
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
        }
        if (simpleName == null || simpleName.isBlank()) {
            throw new IllegalArgumentException("targetClassFqn is empty");
        }

        File evoDir = null;
        File estest = null;
        File scaffolding = null;

        String estestName = simpleName + "_ESTest.java";
        String scaffName = simpleName + "_ESTest_scaffolding.java";

        if (evoOutputBaseDir != null) {
            // Newer flow: when we pass -Dtest_dir=<dir>, EvoSuite writes directly into that directory
            // (possibly with package subfolders). So first search the base dir itself.
            evoDir = evoOutputBaseDir;
            estest = findFileRecursivelyByName(evoDir, estestName);
            scaffolding = findFileRecursivelyByName(evoDir, scaffName);

            // Legacy/alternate layout: <base>/evosuite-tests/**
            if ((estest == null || !estest.exists())) {
                File legacy = new File(evoOutputBaseDir, "evosuite-tests");
                File e2 = findFileRecursivelyByName(legacy, estestName);
                File s2 = findFileRecursivelyByName(legacy, scaffName);
                if (e2 != null && e2.exists()) {
                    evoDir = legacy;
                    estest = e2;
                    scaffolding = s2;
                }
            }
        }

        if ((estest == null || !estest.exists()) && project.getBasePath() != null) {
            File base = new File(project.getBasePath());
            evoDir = new File(base, "evosuite-tests");
            estest = findFileRecursivelyByName(evoDir, estestName);
            scaffolding = findFileRecursivelyByName(evoDir, scaffName);
        }

        if ((estest == null || !estest.exists()) && sourceFilePath != null && !sourceFilePath.isBlank()) {
            File src = new File(sourceFilePath);
            File parent = src.getParentFile();
            if (parent != null) {
                evoDir = new File(parent, "evosuite-tests");
                estest = findFileRecursivelyByName(evoDir, estestName);
                scaffolding = findFileRecursivelyByName(evoDir, scaffName);
            }
        }

        if (estest == null || !estest.exists()) {
            if (viewer != null) {
                viewer.accept("[EvoSuite] No *_ESTest.java found. Expected: " + estestName
                        + (evoDir != null ? (" under: " + evoDir.getAbsolutePath()) : ""));
            }
            return null;
        }
        if (scaffolding == null || !scaffolding.exists()) {
            if (viewer != null) {
                viewer.accept("[EvoSuite] Warning: scaffolding file not found. Expected: " + scaffName
                        + ". Running ESTest only may fail.");
            }
        }

        String estCode = Files.readString(estest.toPath(), StandardCharsets.UTF_8);
        String estPkg = extractPackageName(estCode);
        String estFqn = (estPkg == null || estPkg.isBlank()) ? (simpleName + "_ESTest") : (estPkg + "." + simpleName + "_ESTest");

        String scFqn = null;
        if (scaffolding != null && scaffolding.exists()) {
            String scCode = Files.readString(scaffolding.toPath(), StandardCharsets.UTF_8);
            String scPkg = extractPackageName(scCode);
            scFqn = (scPkg == null || scPkg.isBlank()) ? (simpleName + "_ESTest_scaffolding") : (scPkg + "." + simpleName + "_ESTest_scaffolding");
        }

        if (viewer != null) {
            viewer.accept("[EvoSuite] Native test found: " + estest.getAbsolutePath());
            if (scaffolding != null && scaffolding.exists()) {
                viewer.accept("[EvoSuite] Native scaffolding found: " + scaffolding.getAbsolutePath());
            }
            viewer.accept("[EvoSuite] Native test FQN: " + estFqn);
        }

        return new NativeEvoSuiteTest(estest, estFqn, scaffolding, scFqn);
    }

    private static String buildRunClasspath(String extraFirstCp, String tempOut, String projectCp, String evoSuiteJarPath) {
        String sep = File.pathSeparator;
        StringBuilder sb = new StringBuilder();
        if (extraFirstCp != null && !extraFirstCp.isBlank()) sb.append(extraFirstCp).append(sep);
        if (tempOut != null && !tempOut.isBlank()) sb.append(tempOut).append(sep);
        if (projectCp != null && !projectCp.isBlank()) sb.append(projectCp);
        if (evoSuiteJarPath != null && !evoSuiteJarPath.isBlank()) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(evoSuiteJarPath);
        }
        return sb.toString();
    }

    /**
     * Compile and run native EvoSuite tests via JUnitCore.
     * Adds EvoSuite jar to the classpath so the generated tests compile/run.
     */
    private static boolean runNativeEvoSuiteJUnit4(Project project,
                                                   String javaExe,
                                                   String projectCp,
                                                   String extraFirstCp,
                                                   NativeEvoSuiteTest nativeTest,
                                                   Consumer<String> junitViewer,
                                                   String evoSuiteJarPath) throws IOException, InterruptedException {
        if (nativeTest == null) return false;

        java.nio.file.Path outDir = java.nio.file.Files.createTempDirectory("temp_test_classes");
        outDir.toFile().deleteOnExit();

        String javac = resolveJavacExecutable();

        String cpForCompile = buildRunClasspath(extraFirstCp, outDir.toAbsolutePath().toString(), projectCp, evoSuiteJarPath);

        java.util.List<String> javacCmd = new java.util.ArrayList<>();
        javacCmd.add(javac);
        javacCmd.add("-encoding");
        javacCmd.add("UTF-8");
        javacCmd.add("-cp");
        javacCmd.add(cpForCompile);
        javacCmd.add("-d");
        javacCmd.add(outDir.toAbsolutePath().toString());
        if (nativeTest.scaffoldingFile != null && nativeTest.scaffoldingFile.exists()) {
            javacCmd.add(nativeTest.scaffoldingFile.getAbsolutePath());
        }
        javacCmd.add(nativeTest.estestFile.getAbsolutePath());

        if (junitViewer != null) {
            junitViewer.accept("[TEST] Compiling generated test (native EvoSuite)...");
            junitViewer.accept("[TEST] Executing: " + String.join(" ", javacCmd));
        }
        int cExit = runProcessStreaming(project, javacCmd, junitViewer);
        if (cExit != 0) {
            if (junitViewer != null) junitViewer.accept("[TEST] Compilation failed (exit=" + cExit + ")");
            return false;
        }

        String cpForRun = buildRunClasspath(extraFirstCp, outDir.toAbsolutePath().toString(), projectCp, evoSuiteJarPath);

        java.util.List<String> runCmd = new java.util.ArrayList<>();
        runCmd.add(javaExe);
        runCmd.add("-cp");
        runCmd.add(cpForRun);
        runCmd.add("org.junit.runner.JUnitCore");
        runCmd.add(nativeTest.estestFqn);

        if (junitViewer != null) {
            junitViewer.accept("[TEST] Running native EvoSuite tests via JUnitCore...");
            junitViewer.accept("[TEST] Executing: " + String.join(" ", runCmd));
        }

        int exit = runProcessStreaming(project, runCmd, junitViewer);
        return exit == 0;
    }

    /**
     * EvoSuite output sometimes gets corrupted so that newlines become literal
     * 'n' / 'nn' tokens (e.g., "/*n", "nnpackage", ";nimport", "npublic").
     *
     * This method repairs those cases conservatively WITHOUT hard-coding lots of keywords.
     * It relies mainly on structural Java separators: ; { } )
     */
    private static String normalizeBrokenNewlines(String input) {
        if (input == null) return null;

        String s = input;

        // 1) Fix escaped-newline forms first: "\\n" / "\\r\\n"
        //    (some subprocess logs double-escape content)
        if (s.contains("\\n") || s.contains("\\r\\n")) {
            s = s.replace("\\r\\n", "\n");
            s = s.replace("\\n", "\n");
        }

        // 2) Decide whether this looks like the "literal n instead of newline" corruption.
        int realNewlines = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') realNewlines++;
        }

        // Structural corruption markers (language-agnostic, not keyword-based)
        int markers = 0;
        markers += countOccurrences(s, ";n");
        markers += countOccurrences(s, "{n");
        markers += countOccurrences(s, "}n");
        markers += countOccurrences(s, ")n");
        markers += countOccurrences(s, "/*n");
        markers += countOccurrences(s, "*/nn");

        // ALWAYS repair if we see ANY corruption markers
        boolean suspicious = markers >= 1;
        if (!suspicious) return s;

        // 3) Repair common header comment corruption
        s = s.replace("/*n", "/*\n");
        s = s.replace("*/nn", "*/\n\n");

        // 4) Repair the most reliable structural cases (no keyword dependency)
        //    after statement terminators and braces/parens
        s = s.replaceAll("(?s)(?<=[;{}\\)])nn(?=\\s)", "\n\n");
        s = s.replaceAll("(?s)(?<=[;{}\\)])n(?=\\s)", "\n");


        // 4b) Repair common block-comment/annotation joins where the newline marker got glued as a literal 'n'
        //     Example: "EvoSuiten * ..." or ")n  @Test".
        s = s.replaceAll("(?s)(?<=\\*/)nn(?=\\s*\\*)", "\n\n");
        s = s.replaceAll("(?s)(?<=\\*/)n(?=\\s*\\*)", "\n");
        s = s.replaceAll("(?s)(?<=[A-Za-z0-9_])nn(?=\\s*@)", "\n\n");
        s = s.replaceAll("(?s)(?<=[A-Za-z0-9_])n(?=\\s*@)", "\n");

        // 4c) Targeted repairs for known split identifiers in EvoSuite/JUnit headers (Windows corruption)
        //     Examples seen: "org.junit.ru\n\ner.RunWith", "EvoRu\n\ner", "EvoRu\n\nerParameters", "EvoSuiten *".
        s = s.replaceAll("(?s)org\\.junit\\.ru\\s*\\n\\s*er\\b", "org.junit.runner");
        s = s.replaceAll("(?s)EvoRu\\s*\\n\\s*erParameters\\b", "EvoRunnerParameters");
        s = s.replaceAll("(?s)EvoRu\\s*\\n\\s*er\\b", "EvoRunner");
        s = s.replaceAll("(?s)EvoSuite\\s*n\\s*\\*", "EvoSuite\\n *");
        s = s.replaceAll("(?s)(\\d{4})n(?=\\s*\\*/)", "$1\n");

        // 5) Handle cases where 'n' is glued to the next token (no whitespace),
        //    but ONLY in very safe "file header" zones: package/import lines
        //    (keep it small; these are the only two that must be on separate lines)
        s = s.replaceAll("(?m)^\\s*nn(?=\\s*(package|import)\\b)", "\n\n");
        s = s.replaceAll("(?m)^\\s*n(?=\\s*(package|import)\\b)", "\n");
        s = s.replaceAll("(?m)(?<![A-Za-z0-9_])nn(?=(package|import)\\b)", "\n\n");
        s = s.replaceAll("(?m)(?<![A-Za-z0-9_])n(?=(package|import)\\b)", "\n");
        s = s.replaceAll("(?m);n(?=\\s*import\\b)", ";\n");

        // 6) If still heavily corrupted, do a bounded repair:
        //    replace whitespace-bounded standalone n/nn tokens only.
        int remaining = 0;
        remaining += countOccurrences(s, ";n");
        remaining += countOccurrences(s, "{n");
        remaining += countOccurrences(s, "}n");
        remaining += countOccurrences(s, ")n");
        if (remaining >= 3 && realNewlines < 20) {
            s = s.replaceAll("(?s)(?<=\\s)nn(?=\\s)", "\n\n");
            s = s.replaceAll("(?s)(?<=\\s)n(?=\\s)", "\n");

            // Also repair glued cases in comments/annotations in highly-corrupted outputs
            s = s.replaceAll("(?s)nn(?=\\s*\\*)", "\n\n");
            s = s.replaceAll("(?s)n(?=\\s*\\*)", "\n");
            s = s.replaceAll("(?s)nn(?=\\s*@)", "\n\n");
            s = s.replaceAll("(?s)n(?=\\s*@)", "\n");
        }

        // 7) Normalize line endings & collapse excessive blank lines
        s = s.replace("\r\n", "\n");
        s = s.replace("\r", "\n");
        s = s.replaceAll("\n{3,}", "\n\n");

        // 8) Very common EOF case
        s = s.replaceAll("(?m)\\}\\s*n\\s*$", "}\n");

        return s;
    }

    private static int countOccurrences(String s, String needle) {
    /**
     * Best-effort detection of the major Java version for the given java executable.
     *
     * We parse `java -version` output.
     * - Java 8 prints:  java version "1.8.0_..."
     * - Java 9+ prints: java version "11.0. ..." / "17.0. ..." etc.
     */
        if (s == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while (true) {
            int hit = s.indexOf(needle, idx);
            if (hit < 0) break;
            count++;
            idx = hit + needle.length();
        }
        return count;
    }

    private static int runProcessStreaming(Project project, java.util.List<String> cmd, Consumer<String> viewer)
            throws IOException, InterruptedException {
        return runProcessStreaming(project, cmd, viewer, null);
    }

    /**
     * Runs an external process, streams stdout/stderr to the viewer, and returns the exit code.
     * If {@code workingDirOverride} is non-null, it will be used as the process working directory.
     * Otherwise, uses project base path as working directory when available.
     */
    private static int runProcessStreaming(Project project,
                                           java.util.List<String> cmd,
                                           Consumer<String> viewer,
                                           File workingDirOverride)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true); // combine stdout and stderr

        if (workingDirOverride != null) {
            pb.directory(workingDirOverride);
        } else {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isEmpty()) {
                pb.directory(new File(basePath));
            }
        }

        Process process = pb.start();

        // Track last time we saw output; if output stops for too long, treat as hang.
        final java.util.concurrent.atomic.AtomicLong lastOutputAt = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        Thread outputGobbler = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                StringBuilder buf = new StringBuilder();
                int b;
                while ((b = is.read()) != -1) {
                    char ch = (char) b;
                    // EvoSuite often prints progress using carriage returns (\r) without newlines.
                    if (ch == '\n' || ch == '\r') {
                        if (buf.length() > 0) {
                            String cleaned = stripNonPrintable(stripAnsi(buf.toString()));
                            if (cleaned != null && !cleaned.trim().isEmpty()) {
                                lastOutputAt.set(System.currentTimeMillis());
                                if (viewer != null) viewer.accept(cleaned);
                            }
                            buf.setLength(0);
                        }
                    } else {
                        buf.append(ch);
                    }
                }
                // flush any remaining partial line
                if (buf.length() > 0) {
                    String cleaned = stripNonPrintable(stripAnsi(buf.toString()));
                    if (cleaned != null && !cleaned.trim().isEmpty()) {
                        lastOutputAt.set(System.currentTimeMillis());
                        if (viewer != null) viewer.accept(cleaned);
                    }
                }
            } catch (IOException e) {
                // stream closes when process exits; ignore
            }
        });
        outputGobbler.setDaemon(true);
        outputGobbler.start();

        int maxWaitSeconds = 240;
        int globalTimeoutSeconds = -1;

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            for (String a : cmd) {
                if (a != null && a.startsWith("-Dglobal_timeout=")) {
                    String v = a.substring("-Dglobal_timeout=".length()).trim();
                    globalTimeoutSeconds = Integer.parseInt(v);
                    int buffer = isWindows ? 30 : 90;
                    maxWaitSeconds = Math.max(45, globalTimeoutSeconds + buffer);
                    break;
                }
            }
        } catch (Throwable ignored) {
            // keep defaults
        }

        if (isWindows) {
            maxWaitSeconds = Math.min(maxWaitSeconds, 150);
        }

        long start = System.currentTimeMillis();
        long lastBeat = start;
        while (true) {
            boolean finished = process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) break;

            long now = System.currentTimeMillis();
            long elapsedSec = (now - start) / 1000;

            if (viewer != null && (now - lastBeat) >= 15_000) {
                viewer.accept("[INFO] Process still running... elapsed=" + elapsedSec + "s (max=" + maxWaitSeconds + "s)");
                lastBeat = now;
            }

            long silentMs = now - lastOutputAt.get();
            long silentThresholdMs = isWindows ? 45_000 : 90_000;
            if (silentMs >= silentThresholdMs) {
                if (viewer != null) viewer.accept("[ERROR] Process appears hang (no output for " + (silentMs / 1000) + "s). Killing it forcefully...");
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return -1;
            }

            if (elapsedSec >= maxWaitSeconds) {
                if (viewer != null) viewer.accept("[ERROR] Process timed out (elapsed=" + elapsedSec + "s). Killing it forcefully...");
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return -1;
            }
        }

        outputGobbler.join(2000);
        return process.exitValue();
    }

    private static int runProcessStreaming(Project project,
                                           java.util.List<String> cmd,
                                           Consumer<String> viewer,
                                           File workingDirOverride,
                                           java.util.Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true); // combine stdout and stderr

        if (workingDirOverride != null) {
            pb.directory(workingDirOverride);
        } else {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isEmpty()) {
                pb.directory(new File(basePath));
            }
        }

        if (envOverrides != null && !envOverrides.isEmpty()) {
            pb.environment().putAll(envOverrides);
        }

        // Prevent a parent-shell JAVA_TOOL_OPTIONS (eg, --add-opens) from breaking Java 8.
        // We only add module opens explicitly when running on Java 9+ in the command line.
        if (pb.environment().containsKey("JAVA_TOOL_OPTIONS")) {
            String jto = pb.environment().get("JAVA_TOOL_OPTIONS");
            if (jto != null && jto.contains("--add-opens")) {
                pb.environment().remove("JAVA_TOOL_OPTIONS");
            }
        }

        Process process = pb.start();

        // Track last time we saw output; if output stops for too long, treat as hang.
        final java.util.concurrent.atomic.AtomicLong lastOutputAt = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        Thread outputGobbler = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                StringBuilder buf = new StringBuilder();
                int b;
                while ((b = is.read()) != -1) {
                    char ch = (char) b;
                    // EvoSuite often prints progress using carriage returns (\r) without newlines.
                    if (ch == '\n' || ch == '\r') {
                        if (buf.length() > 0) {
                            String cleaned = stripNonPrintable(stripAnsi(buf.toString()));
                            if (cleaned != null && !cleaned.trim().isEmpty()) {
                                lastOutputAt.set(System.currentTimeMillis());
                                if (viewer != null) viewer.accept(cleaned);
                            }
                            buf.setLength(0);
                        }
                    } else {
                        buf.append(ch);
                    }
                }
                // flush any remaining partial line
                if (buf.length() > 0) {
                    String cleaned = stripNonPrintable(stripAnsi(buf.toString()));
                    if (cleaned != null && !cleaned.trim().isEmpty()) {
                        lastOutputAt.set(System.currentTimeMillis());
                        if (viewer != null) viewer.accept(cleaned);
                    }
                }
            } catch (IOException e) {
                // stream closes when process exits; ignore
            }
        });
        outputGobbler.setDaemon(true);
        outputGobbler.start();

        int maxWaitSeconds = 240;
        int globalTimeoutSeconds = -1;

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            for (String a : cmd) {
                if (a != null && a.startsWith("-Dglobal_timeout=")) {
                    String v = a.substring("-Dglobal_timeout=".length()).trim();
                    globalTimeoutSeconds = Integer.parseInt(v);
                    int buffer = isWindows ? 30 : 90;
                    maxWaitSeconds = Math.max(45, globalTimeoutSeconds + buffer);
                    break;
                }
            }
        } catch (Throwable ignored) {
            // keep defaults
        }

        if (isWindows) {
            maxWaitSeconds = Math.min(maxWaitSeconds, 150);
        }

        long start = System.currentTimeMillis();
        long lastBeat = start;
        while (true) {
            boolean finished = process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) break;

            long now = System.currentTimeMillis();
            long elapsedSec = (now - start) / 1000;

            if (viewer != null && (now - lastBeat) >= 15_000) {
                viewer.accept("[INFO] Process still running... elapsed=" + elapsedSec + "s (max=" + maxWaitSeconds + "s)");
                lastBeat = now;
            }

            long silentMs = now - lastOutputAt.get();
            long silentThresholdMs = isWindows ? 45_000 : 90_000;
            if (silentMs >= silentThresholdMs) {
                if (viewer != null) viewer.accept("[ERROR] Process appears hang (no output for " + (silentMs / 1000) + "s). Killing it forcefully...");
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return -1;
            }

            if (elapsedSec >= maxWaitSeconds) {
                if (viewer != null) viewer.accept("[ERROR] Process timed out (elapsed=" + elapsedSec + "s). Killing it forcefully...");
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return -1;
            }
        }

        outputGobbler.join(2000);
        return process.exitValue();
    }

    /**
     * Build a classpath suitable for EvoSuite from IntelliJ project model:
     * compiler output paths + library class roots. Filters duplicate entries, .zip, junit*, hamcrest*.
     */
    private static String buildClasspathFromIde(Project project) {
        StringBuilder cp = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module m : modules) {
            // Compiler output (bytecode) – critical for EvoSuite
            CompilerModuleExtension ext = CompilerModuleExtension.getInstance(m);
            if (ext != null && ext.getCompilerOutputPath() != null) {
                String out = ext.getCompilerOutputPath().getPath();
                if (out != null && !out.isBlank() && seen.add(out)) {
                    cp.append(out).append(File.pathSeparator);
                }
            }

            // Libraries (dependencies)
            java.util.List<String> libs = ModuleRootManager.getInstance(m)
                    .orderEntries()
                    .librariesOnly()
                    .getPathsList()
                    .getPathList();

            for (String lib : libs) {
                if (lib == null || lib.isBlank()) continue;
                if (seen.contains(lib)) continue;
                if (lib.endsWith(".zip")) continue;

                // filter junit/hamcrest to avoid conflicts; EvoSuite-generated tests will use our own later
                String libName = new File(lib).getName();
                String lower = libName.toLowerCase();
                if (lower.startsWith("junit") || lower.startsWith("hamcrest")) continue;

                if (seen.add(lib)) {
                    cp.append(lib).append(File.pathSeparator);
                }
            }
        }

        // Trim trailing separator
        if (cp.length() > 0 && cp.charAt(cp.length() - 1) == File.pathSeparatorChar) {
            cp.setLength(cp.length() - 1);
        }
        return cp.toString();
    }

    /**
     * Resolve the top-level class fully-qualified name (FQN) for a Java file using PSI.
     * Returns the first top-level class FQN in the file.
     */
    private static String resolveTopLevelClassFqn(Project project, VirtualFile vf) {
        return com.intellij.openapi.application.ReadAction.compute(() -> {
            PsiFile psi = PsiManager.getInstance(project).findFile(vf);
            if (!(psi instanceof PsiJavaFile)) return null;

            PsiJavaFile javaFile = (PsiJavaFile) psi;
            String pkg = javaFile.getPackageName();

            PsiClass[] classes = javaFile.getClasses();
            if (classes == null || classes.length == 0) return null;

            String name = classes[0].getName();
            if (name == null || name.isBlank()) return null;

            if (pkg == null || pkg.isBlank()) return name;
            return pkg + "." + name;
        });
    }

    /**
     * Best-effort Java executable resolution for EvoSuite.
     *
     * EvoSuite 1.0.6 expects tools.jar (JDK 8). So we prefer JAVA_8_HOME first.
     * Then fall back to JAVA_11_HOME, JAVA_HOME, else "java".
     */
    private static String resolveJavaExecutable() {
        // 0) Prefer explicit Java 8 home if provided
        String java8 = getConfiguredJavaHome("JAVA_8_HOME");
        String p = resolveExeUnderHome(java8, "java");
        if (p != null) return p;

        // 1) Use IntelliJ Project SDK if available
        try {
            Project project = ProjectManager.getInstance().getOpenProjects().length > 0
                    ? ProjectManager.getInstance().getOpenProjects()[0]
                    : null;
            if (project != null) {
                ProjectRootManager prm = ProjectRootManager.getInstance(project);
                if (prm != null && prm.getProjectSdk() != null && prm.getProjectSdk().getHomePath() != null) {
                    p = resolveExeUnderHome(prm.getProjectSdk().getHomePath(), "java");
                    if (p != null) return p;
                }
            }
        } catch (Throwable ignored) {
            // best-effort only
        }

        // 2) Fall back to JAVA_11_HOME, then JAVA_HOME
        String java11 = getConfiguredJavaHome("JAVA_11_HOME");
        p = resolveExeUnderHome(java11, "java");
        if (p != null) return p;

        String javaHome = getConfiguredJavaHome("JAVA_HOME");
        p = resolveExeUnderHome(javaHome, "java");
        if (p != null) return p;

        return "java";
    }

    /**
     * Option B: EvoSuite jar is bundled inside the plugin resources.
     * This method extracts it to a temp file and returns the absolute path.
     *
     * Expected resource path (inside plugin jar):
     *   /tools/evosuite-1.2.0.jar
     *
     * Put the jar at: src/main/resources/tools/evosuite-1.2.0.jar
     */
    private static String resolveBundledEvoSuiteJarPath() throws IOException {
        // Resource inside the plugin jar
        String resourcePath = "/tools/evosuite-1.2.0.jar";

        InputStream in = AiderHelper.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath + " (ensure src/main/resources/tools/evosuite-1.2.0.jar exists)");
        }

        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("evosuite-", ".jar");
        // Ensure it gets cleaned up when the JVM exits (best-effort)
        tmp.toFile().deleteOnExit();

        try (in) {
            java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        return tmp.toAbsolutePath().toString();
    }

    /**
     * Extract the package name from Java source text, or null if none.
     */
    private static String extractPackageName(String javaSource) {
        if (javaSource == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([a-zA-Z0-9_\\.]+)\\s*;\\s*$")
                .matcher(javaSource);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * EvoSuite commonly writes generated tests under package directories inside `evosuite-tests/`.
     * This helper searches for a file by name recursively (best-effort).
     */
    private static File findFileRecursivelyByName(File rootDir, String fileName) {
        try {
            if (rootDir == null || fileName == null || fileName.isBlank()) return null;
            if (!rootDir.exists() || !rootDir.isDirectory()) return null;

            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(rootDir.toPath())) {
                java.util.Optional<java.nio.file.Path> hit = s
                        .filter(p -> p != null && p.getFileName() != null && fileName.equals(p.getFileName().toString()))
                        .findFirst();
                return hit.map(java.nio.file.Path::toFile).orElse(null);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
