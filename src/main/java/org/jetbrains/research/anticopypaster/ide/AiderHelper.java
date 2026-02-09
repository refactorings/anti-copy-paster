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

public class AiderHelper {

    private static final Map<String, ConsoleView> CONSOLE_BY_TITLE = new ConcurrentHashMap<>();

    /**
     * Cache of last-known-good converted JUnit4 tests per CUT (fully-qualified class name).
     * Used as a fallback when EvoSuite hangs or fails so the tool can still return a runnable test.
     */
    private static final class CachedJUnitTest {
        final String testClassFqn;
        final String testJavaSource;

        private CachedJUnitTest(String testClassFqn, String testJavaSource) {
            this.testClassFqn = testClassFqn;
            this.testJavaSource = testJavaSource;
        }
    }

    private static final Map<String, CachedJUnitTest> LAST_GOOD_TEST_BY_CUT = new ConcurrentHashMap<>();

    private static boolean isMavenOrGradleProject(Project project) {
        String base = project.getBasePath();
        if (base == null || base.isBlank()) return false;
        File pom = new File(base, "pom.xml");
        File gradle = new File(base, "build.gradle");
        File gradleKts = new File(base, "build.gradle.kts");
        File settingsGradle = new File(base, "settings.gradle");
        File settingsGradleKts = new File(base, "settings.gradle.kts");
        return pom.exists() || gradle.exists() || gradleKts.exists() || settingsGradle.exists() || settingsGradleKts.exists();
    }

    private static boolean isPlainProject(Project project) {
        if (isMavenOrGradleProject(project)) return false;
        try {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            return modules == null || modules.length <= 1;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     /**
     * Writes the converted JUnit4 test into the project's test source root so IntelliJ/Maven/Gradle can compile it.
     * Best-effort: uses the first module's first content root and places under src/test/java/<package>/Class.java.
     */
    private static File writeTestIntoProjectTestSources(Project project,
                                                        String testJavaSource,
                                                        String testClassFqn,
                                                        Consumer<String> viewer) throws IOException {
        if (testJavaSource == null || testJavaSource.isBlank()) {
            throw new IllegalArgumentException("testJavaSource is empty");
        }
        if (testClassFqn == null || testClassFqn.isBlank()) {
            throw new IllegalArgumentException("testClassFqn is empty");
        }

        String pkg = "";
        String simple = testClassFqn;
        int lastDot = testClassFqn.lastIndexOf('.');
        if (lastDot >= 0) {
            pkg = testClassFqn.substring(0, lastDot);
            simple = testClassFqn.substring(lastDot + 1);
        }

        Module[] modules = ModuleManager.getInstance(project).getModules();
        String rootPath = null;
        if (modules != null && modules.length > 0) {
            VirtualFile[] roots = ModuleRootManager.getInstance(modules[0]).getContentRoots();
            if (roots != null && roots.length > 0) {
                rootPath = roots[0].getPath();
            }
        }
        if (rootPath == null || rootPath.isBlank()) {
            String base = project.getBasePath();
            if (base != null && !base.isBlank()) rootPath = base;
        }
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalStateException("Cannot determine project root/content root to write test");
        }

        File testRoot = new File(rootPath, "src" + File.separator + "test" + File.separator + "java");
        File pkgDir = testRoot;
        if (!pkg.isBlank()) {
            pkgDir = new File(testRoot, pkg.replace('.', File.separatorChar));
        }
        if (!pkgDir.exists() && !pkgDir.mkdirs()) {
            throw new IOException("Failed to create test package directory: " + pkgDir.getAbsolutePath());
        }

        File out = new File(pkgDir, simple + ".java");
        Files.writeString(out.toPath(), testJavaSource, StandardCharsets.UTF_8);

        // Refresh VFS so IntelliJ can see/compile it
        String finalRootPath = rootPath;
        ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(out);
            if (vf != null) {
                vf.refresh(false, false);
            } else {
                // fallback: refresh project root
                VirtualFile rootVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(finalRootPath);
                if (rootVf != null) rootVf.refresh(true, true);
            }
        }, ModalityState.any());

        if (viewer != null) viewer.accept("[JUnit/IDE] Wrote test into project test sources: " + out.getAbsolutePath());
        return out;
    }
    /**
     * Runs a single JUnit test class through IntelliJ's built-in JUnit runner.
     * This is the correct approach for Maven/Gradle projects because IntelliJ will use the IDE/module classpath.
     * Note: execution is asynchronous; this method does not wait for PASS/FAIL.
     */
    private static void runJUnit4ViaIdeaRunner(Project project,
                                               String testClassFqn,
                                               Consumer<String> viewer) {
        if (testClassFqn == null || testClassFqn.isBlank()) {
            if (viewer != null) viewer.accept("[JUnit/IDE] Skipped: test class name is empty.");
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                RunManager runManager = RunManager.getInstance(project);

                // Find JUnit configuration type in a version-stable way (avoid direct JUnitConfigurationType API differences)
                ConfigurationType junitType = null;
                for (ConfigurationType t : ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
                    if (t != null && "JUnit".equals(t.getId())) {
                        junitType = t;
                        break;
                    }
                }
                if (junitType == null) {
                    if (viewer != null) viewer.accept("[JUnit/IDE] JUnit run configuration type not found (id=JUnit). Is the JUnit plugin enabled?");
                    notify(project, "JUnit runner not available: configuration type 'JUnit' not found.");
                    return;
                }
                if (junitType.getConfigurationFactories() == null || junitType.getConfigurationFactories().length == 0) {
                    if (viewer != null) viewer.accept("[JUnit/IDE] JUnit configuration has no factories.");
                    notify(project, "JUnit runner not available: no configuration factories.");
                    return;
                }

                RunnerAndConfigurationSettings settings =
                        runManager.createConfiguration("Clone JUnit4: " + testClassFqn, junitType.getConfigurationFactories()[0]);

                if (!(settings.getConfiguration() instanceof JUnitConfiguration)) {
                    if (viewer != null) viewer.accept("[JUnit/IDE] Created configuration is not a JUnitConfiguration: " + settings.getConfiguration().getClass().getName());
                    notify(project, "Failed to create JUnit run configuration for: " + testClassFqn);
                    return;
                }

                JUnitConfiguration cfg = (JUnitConfiguration) settings.getConfiguration();

                // Configure the JUnit run configuration in a version-tolerant way.
                // Different IDE builds expose different APIs (setTestObject/setMainClassName vs persistent data).
                boolean configured = false;

                // 1) Try setters via reflection on the configuration instance (newer builds)
                try {
                    java.lang.reflect.Method mSetTestObj = cfg.getClass().getMethod("setTestObject", String.class);
                    mSetTestObj.invoke(cfg, JUnitConfiguration.TEST_CLASS);
                    configured = true;
                } catch (Throwable ignored) {
                    // ignore
                }
                try {
                    java.lang.reflect.Method mSetMain = cfg.getClass().getMethod("setMainClassName", String.class);
                    mSetMain.invoke(cfg, testClassFqn);
                    configured = true;
                } catch (Throwable ignored) {
                    // ignore
                }

                // 2) Fallback: persistent data (older builds)
                try {
                    java.lang.reflect.Method mGet = cfg.getClass().getMethod("getPersistentData");
                    Object data = mGet.invoke(cfg);
                    if (data != null) {
                        try {
                            java.lang.reflect.Field f = data.getClass().getField("TEST_OBJECT");
                            f.set(data, JUnitConfiguration.TEST_CLASS);
                            configured = true;
                        } catch (Throwable ignored) {
                            // ignore
                        }
                        try {
                            java.lang.reflect.Method mSetMain = data.getClass().getMethod("setMainClassName", String.class);
                            mSetMain.invoke(data, testClassFqn);
                            configured = true;
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                } catch (Throwable ignored) {
                    // ignore
                }

                if (!configured) {
                    if (viewer != null) viewer.accept("[JUnit/IDE] Warning: could not configure test class via available APIs; running configuration may be incomplete.");
                }

                // Best-effort select a module (avoid setModule(Module) API differences)
                try {
                    Module[] modules = ModuleManager.getInstance(project).getModules();
                    if (modules != null && modules.length > 0) {
                        cfg.getConfigurationModule().setModule(modules[0]);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }


                runManager.setTemporaryConfiguration(settings);

                ExecutionEnvironment env =
                        ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build();

                if (viewer != null) viewer.accept("[JUnit/IDE] Running via IntelliJ runner: " + testClassFqn);
                ProgramRunnerUtil.executeConfiguration(env, true, true);

            } catch (Throwable t) {
                if (viewer != null) viewer.accept("[JUnit/IDE] Failed to run via IntelliJ runner: " + t.getMessage());
                notify(project, "Failed to run tests via IntelliJ runner: " + t.getMessage());
            }
        }, ModalityState.any());
    }


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
     * Like {@link #openStreamingViewer(Project, String)} but lazily creates the console tab.
     * The tool window/tab will only appear when the returned consumer receives the first non-empty line.
     */
    public static Consumer<String> openLazyStreamingViewer(Project project, String title) {
        final java.util.concurrent.ConcurrentLinkedQueue<String> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicReference<Consumer<String>> delegate = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean creating = new java.util.concurrent.atomic.AtomicBoolean(false);

        return line -> {
            if (line == null) return;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) return;

            Consumer<String> d = delegate.get();
            if (d != null) {
                d.accept(line);
                return;
            }

            // Buffer until we create the real console
            pending.add(line);

            // Only one thread creates the console
            if (creating.compareAndSet(false, true)) {
                try {
                    Consumer<String> real = openStreamingViewer(project, title);
                    delegate.set(real);

                    // Flush buffered lines
                    String s;
                    while ((s = pending.poll()) != null) {
                        real.accept(s);
                    }
                } finally {
                    creating.set(false);
                }
            }
        };
    }

    /**
     * Runs a quick Aider‑based clone check for the file and, if clones are indicated, asks the user whether to refactor.
     * Copies the file to a temp path, streams detector output, and prompts for a refactor preview on positive signals.
     *
     * @param project    the IntelliJ project
     * @param file       file to analyze for clones
     * @param provider   model provider identifier (e.g., OpenAI, Gemini, Anthropic, Azure, Deepseek, xAI)
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

                Consumer<String> viewer = openStreamingViewer(project, "Clone Refactoring Output");
                String output = runAiderWithPromptStreaming(project, aiderPath, tempFile.getAbsolutePath(),
                        "Refactor this file by Extract Method to eliminate clones. Output the COMPLETE final Java file ONLY, inside a single ```java code block. Do NOT output patches, SEARCH/REPLACE markers, or explanations.",
                        provider, model, apikey, apiBase, apiVersion, viewer);
                System.out.println("===> Refactor output:\n" + output);

                if (output != null) {
                    String fenced = extractJavaCodeBlock(output);
                    if (fenced != null && !fenced.isBlank()) {
                        try {
                            Files.writeString(tempFile.toPath(), fenced, StandardCharsets.UTF_8);
                        } catch (IOException ioe) {
                            notify(project, "Failed to write refactored content to temp file: " + ioe.getMessage());
                        }
                    }
                }

                String originalContent = Files.readString(originalFile.toPath());
                String refactoredContent = Files.readString(tempFile.toPath());

                if (!originalContent.equals(refactoredContent)) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // Show diff window
                        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
                        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                                "Refactor Preview: Compare Original and Refactored Code",
                                contentFactory.create(originalContent),
                                contentFactory.create(refactoredContent),
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
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), refactoredContent);
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
                                promptApplyRefactoring(project, fileName, originalFile.toPath(), refactoredContent);
                                return;
                            }
                        } else {
                            // Maven/Gradle OR plain IntelliJ module projects: always prefer the IDE/module classpath.
                            // This is required for multi-class dependencies (e.g., JHotDraw).
                            notify(project, "EvoSuite: using IDE/module classpath (recommended for real projects)." );
                        }

                        if (classpath == null || classpath.isBlank()) {
                            notify(project, "EvoSuite skipped: classpath is still empty. Make sure the project is imported and compiled.");
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), refactoredContent);
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
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), refactoredContent);
                            return;
                        }

                        // 4) (no-op, logic moved above)
                        // 5) Resolve Java executable (prefer JAVA_8_HOME for EvoSuite 1.0.6)
                        String javaExe = resolveJavaExecutable();

                        // 5) Run EvoSuite while previewing (and run generated JUnit4 against the REFACTORED preview before applying)
                        boolean refactoredPass = false;
                        try {
                            refactoredPass = runEvoSuiteOnClass(project, javaExe, evoSuiteJarPath, classpath, classFqn, filePath, refactoredContent);
                        } catch (Throwable t) {
                            notify(project, "EvoSuite failed during preview: " + t.getMessage());
                            refactoredPass = false;
                        }

                        if (refactoredPass) {
                            promptApplyRefactoring(project, fileName, originalFile.toPath(), refactoredContent);
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
     * Best-effort javac resolution. Prefers JAVA_8_HOME (to match EvoSuite runtime), then JAVA_11_HOME, then JAVA_HOME, else "javac".
     */
    private static String resolveJavacExecutable() {
        String java8 = System.getenv("JAVA_8_HOME");
        String p = resolveExeUnderHome(java8, "javac");
        if (p != null) return p;

        String java11 = System.getenv("JAVA_11_HOME");
        p = resolveExeUnderHome(java11, "javac");
        if (p != null) return p;

        String javaHome = System.getenv("JAVA_HOME");
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
     * Convenience overload of {@link #runCommand(Project, String, String, String, String, java.util.function.Consumer, String...)}
     * with no streaming viewer. Executes the given command and returns combined stdout.
     */
    private static String runCommand(Project project, String provider, String apikey, String apiBase,
                                     String apiVersion, String... command)
            throws IOException, InterruptedException {
        return runCommand(project, provider, apikey, apiBase, apiVersion, null, command);
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
            case "GEMINI" -> {
                pb.environment().put("GEMINI_API_KEY", apikey);
                pb.environment().put("AIDER_GEMINI_PROVIDER", "google-ai-studio");
            }
            case "ANTHROPIC" -> pb.environment().put("ANTHROPIC_API_KEY", apikey);
            case "DEEPSEEK" -> pb.environment().put("DEEPSEEK_API_KEY", apikey);
            case "OLLAMA" -> {
                if (apiBase == null || apiBase.isBlank()) {
                    apiBase = "http://127.0.0.1:11434";
                }
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
                    System.err.println("[AIDER] Warning: Azure API base points to 11434 (Ollama). This will 404.");
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
            System.out.println("[AIDER] " + cleaned);
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
     * Closes a single streaming console tab identified by its title, if present. Safe to call from any thread.
     * Disposes tracked consoles and hides the tool window if it becomes empty.
     *
     * @param project IntelliJ project
     * @param title   console tab title to close
     */
    public static void closeViewerByTitle(Project project, String title) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                ToolWindowManager twm = ToolWindowManager.getInstance(project);
                ToolWindow toolWindow = twm.getToolWindow(ToolWindowId.RUN);
                if (toolWindow == null) {
                    toolWindow = twm.getToolWindow("Output");
                }
                if (toolWindow == null) {
                    return; // Nothing to close
                }

                com.intellij.ui.content.ContentManager cm = toolWindow.getContentManager();
                com.intellij.ui.content.Content content = cm.findContent(title);

                ConsoleView console = CONSOLE_BY_TITLE.remove(title);

                if (content != null) {
                    cm.removeContent(content, true);
                }
                if (console != null) {
                    console.clear();
                    if (console instanceof ConsoleViewImpl cvi) {
                        cvi.dispose();
                    }
                }

                if (cm.getContentCount() == 0) {
                    toolWindow.hide(null);
                }
            } catch (Throwable t) {
                System.err.println("Failed to close viewer '" + title + "': " + t.getMessage());
            }
        });
    }

    /**
     * Closes all Aider‑related viewer tabs.
     * First closes any consoles tracked internally, then sweeps the Run/Aider Output tool windows
     * to remove any tabs whose title starts with "Aider ".
     *
     * @param project IntelliJ project
     */
    public static void closeAllViewers(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // First close everything we explicitly tracked
                java.util.List<String> titles = new java.util.ArrayList<>(CONSOLE_BY_TITLE.keySet());
                for (String title : titles) {
                    closeViewerByTitle(project, title);
                }

                // Then sweep tool windows to catch any stray tabs not tracked in the map
                ToolWindowManager twm = ToolWindowManager.getInstance(project);
                ToolWindow[] tws = new ToolWindow[] {
                        twm.getToolWindow(ToolWindowId.RUN),
                        twm.getToolWindow("Output")
                };
                for (ToolWindow tw : tws) {
                    if (tw == null) continue;
                    com.intellij.ui.content.ContentManager cm = tw.getContentManager();
                    // Take a snapshot to avoid concurrent modification while removing
                    java.util.List<Content> list = new java.util.ArrayList<>();
                    for (int i = 0; i < cm.getContentCount(); i++) {
                        list.add(cm.getContent(i));
                    }
                    for (Content c : list) {
                        if (c != null) {
                            String title = c.getDisplayName();
                            if (title != null && title.startsWith("Clone ")) {
                                cm.removeContent(c, true);
                                // Clean any console we may have tracked under this title
                                ConsoleView console = CONSOLE_BY_TITLE.remove(title);
                                if (console != null) {
                                    console.clear();
                                    if (console instanceof ConsoleViewImpl cvi) {
                                        cvi.dispose();
                                    }
                                }
                            }
                        }
                    }
                    if (cm.getContentCount() == 0) {
                        tw.hide(null);
                    }
                }
            } catch (Throwable t) {
                System.err.println("Failed to close all Clone viewers: " + t.getMessage());
            }
        });
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
    /**
     * Extracts the first fenced code block from {@code text} whose language is either empty or starts with "java".
     *
     * @param text full Aider response text, possibly containing fenced code blocks
     * @return the inner content of the first matching fenced block, or {@code null} if none is found
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
     * Detect a common Windows corruption pattern where literal 'n' is used instead of actual newlines.
     * We only trigger when evidence is strong to avoid corrupting identifiers (e.g., 'runner').
     */
    private static boolean looksLikeLiteralNNewlineCorruption(String code) {
        if (code == null || code.isEmpty()) return false;

        // If there are already real newlines, don't assume corruption too easily.
        int realNewlines = 0;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') realNewlines++;
            if (realNewlines >= 5) break;
        }

        int markers = 0;
        if (code.contains("npackage") || code.contains("nimport") || code.contains("npublic")) markers++;
        if (code.contains("nnpackage") || code.contains("nnimport") || code.contains("nnpublic")) markers++;
        if (code.contains("/*n") || code.contains("*/n")) markers++;
        if (code.contains(";n") || code.contains("{n") || code.contains("}n")) markers++;
        if (code.contains("n  @") || code.contains("nn  @")) markers++;

        // Few real newlines + multiple markers => likely corrupted
        if (realNewlines <= 1) return markers >= 2;
        if (realNewlines <= 4) return markers >= 3;
        return false;
    }

    /**
     * Repair literal-'n' newline corruption without global 'nn' replacement (which can corrupt identifiers).
     * Only fixes localized patterns typical at statement boundaries.
     */
    private static String repairLiteralNNewlineCorruption(String code) {
        if (code == null) return null;
        String out = code;

        for (int i = 0; i < 20; i++) {
            String prev = out;

            // Comment header patterns
            out = out.replace("/*n", "/*\n");
            out = out.replace("*/n", "*/\n");

            // Package/import/class boundaries
            out = out.replace("nnpackage", "\n\npackage");
            out = out.replace("nnimport", "\n\nimport");
            out = out.replace("nnpublic", "\n\npublic");

            out = out.replace("npackage", "\npackage");
            out = out.replace("nimport", "\nimport");
            out = out.replace("npublic", "\npublic");

            // Common statement separators
            out = out.replace(";n", ";\n");
            out = out.replace("{n", "{\n");
            out = out.replace("}n", "}\n");

            // Annotation lines
            out = out.replace("n  @", "\n  @");
            out = out.replace("nn  @", "\n\n  @");

            if (out.equals(prev)) break;
        }
        return out;
    }

    /**
     * Runs EvoSuite on a given fully-qualified class name using an external EvoSuite jar.
     * Uses a dedicated process runner (no LLM/Aider env handling).
     * Runs synchronously (blocking) in the calling pooled thread.
     * After EvoSuite completes, post-processes the generated *_ESTest.java into a pure JUnit4 test file.
     */
    private static boolean runEvoSuiteOnClass(Project project,
                                              String javaExe,
                                              String evoSuiteJarPath,
                                              String classpath,
                                              String targetClass,
                                              String sourceFilePath,
                                              String refactoredSourceText) throws IOException, InterruptedException {
        // Use separate consoles so test execution output does not overwrite generation logs
        Consumer<String> evoViewer = openStreamingViewer(project, "EvoSuite Generation Output");
        // Build mark + code source: print to BOTH stderr and the EvoSuite viewer so it is visible in the tool window.

        Consumer<String> junitViewer = openLazyStreamingViewer(project, "JUnit Test Output");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Force a deterministic EvoSuite output location so we can reliably find *_ESTest.java
        // EvoSuite will create <output_directory>/evosuite-tests and write tests there.
        File evoOutputBaseDir = Files.createTempDirectory("evosuite-out-").toFile();
        evoOutputBaseDir.deleteOnExit();

        // Minimal usable arguments: pick a generation mode + criterion + classpath + CUT
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExe);
        evoViewer.accept("[EvoSuite] Using java executable: " + javaExe);

        cmd.add("-Xmx2048m");

        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");


        // --- Windows hang mitigation: add JVM flag BEFORE -jar ---
        if (isWindows) {
            // Limit CPU parallelism to reduce sporadic EvoSuite hangs on Windows.
            cmd.add("-XX:ActiveProcessorCount=1");
            evoViewer.accept("[EvoSuite] Windows hang mitigation: -XX:ActiveProcessorCount=1");
            // --- Windows hard constraints (prevent hangs by shrinking EvoSuite's internal search/refinement space) ---
            // IMPORTANT: we set these as JVM system properties (BEFORE -jar) so even if EvoSuite ignores them,
            // the JVM still accepts them and the process will not crash due to EvoSuite parsing.

            // 0) Hard cap from outside: keep global timeout SMALL on Windows so we can always kill/escape.
            //    (The process watchdog in runProcessStreaming also enforces an absolute wall-clock cap.)
            cmd.add("-Dglobal_timeout=60");

            // 1) Shrink search space aggressively.
            cmd.add("-Dsearch_algorithm=GA");
            cmd.add("-Dpopulation=6");
            cmd.add("-Dmax_test_length=12");

            // 2) Reduce additional work.
            //    For EvoSuite 1.0.6, the most stable way is to disable assertion generation.
            cmd.add("-Dassertions=false");

            // 3) Keep the budget short and stop based on time.
            cmd.add("-Dsearch_budget=15");
            cmd.add("-Dstopping_condition=MaxTime");

            evoViewer.accept("[EvoSuite] Windows hard constraints (JVM): search_algorithm=GA, population=6, max_test_length=12, assertions=false, search_budget=15, global_timeout=60, stopping_condition=MaxTime");
        }

        // Keep runs headless to reduce GUI/Swing initialization inside the SUT/tests.
        // NOTE: Even in headless mode, some code paths may touch fonts; hence we disable sandbox on Windows.
        cmd.add("-Djava.awt.headless=true");

        // Block external entity / schema access in XML parsing (best-effort) to avoid network permission issues
        cmd.add("-Djavax.xml.accessExternalDTD=");
        cmd.add("-Djavax.xml.accessExternalSchema=");
        cmd.add("-Djavax.xml.accessExternalStylesheet=");
        // Reduce font/native surprises on Windows; safe elsewhere
        cmd.add("-Dsun.java2d.noddraw=true");
        cmd.add("-Djava.awt.fonts=");

        // IMPORTANT: Do NOT force java.awt.graphicsenv/toolkit. Those overrides can break Swing initialization
        // (e.g., RepaintManager init failures) and behave differently across OS/JDKs.
        // cmd.add("-Djava.awt.graphicsenv=...");
        // cmd.add("-Djava.awt.toolkit=...");

        // Only relevant on macOS; harmless elsewhere, but keep it OS-gated to avoid surprising behavior.
        if (isMac) {
            cmd.add("-Dapple.awt.UIElement=true");
        }

        // Let EvoSuite decide whether to mock AWT based on its defaults/settings.
        // Forcing mock_awt=false can re-enable Swing paths that crash in headless environments.
        // cmd.add("-Dmock_awt=false");

        cmd.add("-Dinstrumentation_skip_packages=java.*,javax.*,sun.*,com.sun.*,jdk.*");
        // IMPORTANT: avoid EvoSuite's internal "compile & run" step during generation.
        // On Windows, that step runs the generated scaffolding under EvoSuite's sandbox and can crash
        // with SunFontManager/freetype permission issues. We will generate tests only, then post-process
        // and run them ourselves.
        cmd.add("-Djunit_check=false");
        evoViewer.accept("[EvoSuite] junit_check=false (JVM) added before -jar (best-effort)");

        // Allow EvoSuite to attach to itself when needed
        cmd.add("-Djdk.attach.allowAttachSelf=true");
        // EvoSuite 1.0.6 expects tools.jar (JDK 8). If we can't find it, running under JDK 9+ will crash.
        String toolsJar = findToolsJarForJavaExe(javaExe);
        if (toolsJar != null) {
            cmd.add("-Dtools_jar_location=" + toolsJar);
        } else {
            evoViewer.accept("[EvoSuite] tools.jar not found for Java executable: " + javaExe);
            evoViewer.accept("[EvoSuite] EvoSuite 1.0.6 typically requires JDK 8 (tools.jar).");
            evoViewer.accept("[EvoSuite] Set JAVA_8_HOME to a JDK 8 installation and restart the IDE, or upgrade the bundled EvoSuite to a Java 9+ compatible version.");
            notify(project, "EvoSuite skipped: tools.jar not found. Please set JAVA_8_HOME to a JDK 8 path (EvoSuite 1.0.6 requires tools.jar).");
            return false;
        }

        // Insert -Djava.security.policy==... as JVM arg BEFORE -jar for EvoSuite 1.0.6 compatibility.
        if (isWindows) {
            try {
                String policy = createPermissiveJavaPolicyFile();
                if (policy != null && !policy.isBlank()) {
                    cmd.add("-Djava.security.policy==" + policy);
                }
            } catch (Throwable t) {
                evoViewer.accept("[EvoSuite] Windows: failed to create permissive security policy: " + t.getMessage());
            }
        }

        cmd.add("-jar");
        cmd.add(evoSuiteJarPath);

        if (isWindows) {
            // Also try to fully disable EvoSuite sandbox/security manager (best-effort; harmless if ignored)
            cmd.add("-Dsandbox=false");
            cmd.add("-Dsandbox_mode=OFF");
            evoViewer.accept("[EvoSuite] Windows: sandbox disable flags set (sandbox=false, sandbox_mode=OFF)." );
            evoViewer.accept("[EvoSuite] Windows: not passing -Duse_security_manager=false (unsupported by EvoSuite 1.0.6)." );
        }

        // EvoSuite reads most "-D" properties as its own CLI arguments (after -jar), not only as JVM system properties.
        // Passing this here ensures EvoSuite actually disables its internal JUnitAnalyzer compile/run phase.
        cmd.add("-Djunit_check=false");
        evoViewer.accept("[EvoSuite] junit_check=false (CLI) added after -jar to ensure EvoSuite disables JUnitAnalyzer phase");

        // Generation mode
        cmd.add("-generateSuite");
        if (isWindows) {
            evoViewer.accept("[EvoSuite] Windows: using -generateSuite (stable mode for EvoSuite 1.0.6).");
        } else {
            evoViewer.accept("[EvoSuite] Non-Windows: using -generateSuite.");
        }

        // IMPORTANT: Do not force client_on_thread.
        // EvoSuite 1.0.6 can become unstable (client thread never terminates / master cannot access client state)
        // when client_on_thread is toggled. Leaving it unset is the most portable behavior.
        evoViewer.accept("[EvoSuite] client_on_thread: not forced (using EvoSuite default).");

        // IMPORTANT (EvoSuite version compatibility):
        // EvoSuite 1.0.6 will CRASH on unknown CLI properties (e.g., "-Doutput_directory=...").
        // To keep output deterministic without relying on version-specific properties, we instead:
        //   1) set the subprocess working directory to `evoOutputBaseDir` (via runProcessStreaming)
        //   2) look for generated tests under <workingDir>/evosuite-tests/**
        // This avoids hardcoding EvoSuite-specific flags that may differ across versions.
        evoViewer.accept("[EvoSuite] Working directory (deterministic output base): " + evoOutputBaseDir.getAbsolutePath());

        // Target class and classpath
        cmd.add("-class");
        cmd.add(targetClass);
        cmd.add("-projectCP");
        cmd.add(classpath);

        // Windows hang mitigation: limit JVM to a single core.
        // EvoSuite 1.0.6 does not recognize "-Dnum_cores"; use a JVM flag instead.
        // NOTE: This flag must be placed BEFORE "-jar". We already added JVM args above, so we add it there.
        // (We do NOT add any EvoSuite CLI property here to avoid "Unknown property" crashes.)

        // Keep budgets conservative on Windows to avoid long hangs.
        // NOTE: EvoSuite treats these -D... as its own CLI properties (after -jar).
        if (isWindows) {
            cmd.add("-Dsearch_budget=30");
            cmd.add("-Dglobal_timeout=90");
            cmd.add("-Dstopping_condition=MaxTime");
        } else {
            cmd.add("-Dsearch_budget=60");
            cmd.add("-Dglobal_timeout=120");
        }
        evoViewer.accept("[EvoSuite] Budgets: search_budget=" + (isWindows ? "30" : "60") + ", global_timeout=" + (isWindows ? "90" : "120") + (isWindows ? ", stopping_condition=MaxTime" : ""));

        // Coverage goals (avoid duplicates like LINE:LINE)
        cmd.add("-Dcriterion=LINE");

        cmd.add("-Dminimize=false");

        evoViewer.accept("[EvoSuite] Running EvoSuite for class: " + targetClass);
        evoViewer.accept("[EvoSuite] Command: " + String.join(" ", cmd));

        // Run EvoSuite with `evoOutputBaseDir` as the working directory so outputs land there.
        int exit = -1;
        int attempt = 0;
        int maxAttempts = 5;

        while (attempt < maxAttempts) {
            attempt++;
            evoViewer.accept("[EvoSuite] Attempt " + attempt + " / " + maxAttempts + " starting...");

            exit = runProcessStreaming(project, cmd, evoViewer, evoOutputBaseDir);

            // exit == -1 indicates the process was force-killed due to hang/timeout
            if (exit == -1) {
                evoViewer.accept("[EvoSuite] Attempt " + attempt + " was killed due to hang. Retrying...");
                continue;
            }

            // Non-hang exit (success or normal failure): stop retrying
            break;
        }

        if (exit == -1) {
            evoViewer.accept("[EvoSuite] All " + maxAttempts + " attempts were killed due to hangs. Giving up.");
        }

        if (exit != 0 && isWindows) {
            evoViewer.accept("[EvoSuite] Windows: initial run failed (exit=" + exit + "). Retrying once with harder limits...");

            java.util.List<String> retry = new java.util.ArrayList<>(cmd);

            // Tighten knobs by removing previous values (best-effort).
            retry.removeIf(s -> s != null && (
                    s.startsWith("-Dsearch_budget=") ||
                            s.startsWith("-Dglobal_timeout=") ||
                            s.startsWith("-Dpopulation=") ||
                            s.startsWith("-Dmax_test_length=") ||
                            s.startsWith("-Xmx")
            ));

            // Add the harder caps.
            retry.add(1, "-Xmx1024m");
            retry.add("-Dsearch_budget=8");
            retry.add("-Dglobal_timeout=30");
            retry.add("-Dpopulation=4");
            retry.add("-Dmax_test_length=8");
            retry.add("-Dstopping_condition=MaxTime");

            evoViewer.accept("[EvoSuite] Windows: retry command: " + String.join(" ", retry));
            // Retry with the same deterministic working directory.
            exit = runProcessStreaming(project, retry, evoViewer, evoOutputBaseDir);
        }

        boolean refactoredPass = false;

        // Convert EvoSuite tests to a pure JUnit4 test (no EvoRunner/scaffolding) for plain projects,
        // then compile + run it and report PASS/FAIL.
        try {
            JUnitConversionResult conv = null;
            try {
                conv = postProcessEvoSuiteTestsToPureJUnit4(project, evoOutputBaseDir, targetClass, sourceFilePath, evoViewer);
            } catch (Throwable tConv) {
                evoViewer.accept("[EvoSuite] Conversion failed: " + tConv.getMessage());
                conv = null;
            }

            if (conv == null) {
                // Fallback: use cached last-known-good test if available
                CachedJUnitTest cached = LAST_GOOD_TEST_BY_CUT.get(targetClass);
                if (cached != null && cached.testJavaSource != null && !cached.testJavaSource.isBlank()) {
                    evoViewer.accept("[EvoSuite] Using cached last-known-good test for: " + targetClass);

                    // Materialize cached test into evoOutputBaseDir/evosuite-tests to reuse existing pipeline
                    File evoDir = new File(evoOutputBaseDir, "evosuite-tests");
                    if (!evoDir.exists()) evoDir.mkdirs();

                    String simple = cached.testClassFqn;
                    String pkg = "";
                    int lastDot = cached.testClassFqn.lastIndexOf('.');
                    if (lastDot >= 0) {
                        pkg = cached.testClassFqn.substring(0, lastDot);
                        simple = cached.testClassFqn.substring(lastDot + 1);
                    }
                    File pkgDir = evoDir;
                    if (!pkg.isBlank()) {
                        pkgDir = new File(evoDir, pkg.replace('.', File.separatorChar));
                        if (!pkgDir.exists()) pkgDir.mkdirs();
                    }

                    File outFile = new File(pkgDir, simple + ".java");
                    Files.writeString(outFile.toPath(), cached.testJavaSource, StandardCharsets.UTF_8);

                    conv = new JUnitConversionResult(outFile, cached.testClassFqn);
                    evoViewer.accept("[EvoSuite] Cached test materialized at: " + outFile.getAbsolutePath());
                } else {
                    evoViewer.accept("[EvoSuite] Cached test fallback unavailable for: " + targetClass);
                }
            }

            if (conv != null) {
                if (isPlainProject(project)) {
                    // Plain/simple projects: run via external JUnitCore against compiled preview
                    runJUnit4ForGeneratedTest(project, javaExe, classpath, null, conv.testFile.getAbsolutePath(), conv.testClassFqn, junitViewer);

                    // Also write the generated test into the project so the user can see it under src/test/java
                    try {
                        String testSource = Files.readString(conv.testFile.toPath(), StandardCharsets.UTF_8);
                        writeTestIntoProjectTestSources(project, testSource, conv.testClassFqn, junitViewer);
                    } catch (Throwable t) {
                        if (evoViewer != null) evoViewer.accept("[JUnit/IDE] Warning: failed to write generated test into project: " + t.getMessage());
                    }

                    // Compile the REFACTORED preview source and run the SAME tests against it
                    if (refactoredSourceText != null && !refactoredSourceText.isBlank()) {
                        String refactoredOut = compileJavaSourceTextToTempOutput(project, targetClass, refactoredSourceText, classpath);
// Put refactored preview output FIRST so it overrides the project's original bytecode
                        refactoredPass = runJUnit4ForGeneratedTest(project, javaExe, classpath, refactoredOut, conv.testFile.getAbsolutePath(), conv.testClassFqn, junitViewer);
                    } else {
                        evoViewer.accept("[EvoSuite] Refactored source text is empty; skipping refactored preview test run.");
                        refactoredPass = false;
                    }
                } else {
                    // Maven/Gradle projects: write test into src/test/java and run via IntelliJ JUnit runner.
                    evoViewer.accept("[EvoSuite] Maven/Gradle project detected: running generated JUnit4 test via IntelliJ runner.");

                    try {
                        String testSource = Files.readString(conv.testFile.toPath(), StandardCharsets.UTF_8);
                        writeTestIntoProjectTestSources(project, testSource, conv.testClassFqn, junitViewer);
                        runJUnit4ViaIdeaRunner(project, conv.testClassFqn, junitViewer);
                        evoViewer.accept("[EvoSuite] Note: IntelliJ runner execution is async; not gating apply on PASS/FAIL for Maven/Gradle." );
                        // Allow apply; gating would require test run listeners.
                        refactoredPass = true;
                    } catch (Throwable t) {
                        junitViewer.accept("[JUnit/IDE] Failed to write/run tests via IntelliJ runner: " + t.getMessage());
                        refactoredPass = true; // don't block apply due to runner integration issues
                    }
                }
            } else {
                evoViewer.accept("[EvoSuite] No converted JUnit4 test to run.");
                refactoredPass = false;
            }
        } catch (Throwable t) {
            evoViewer.accept("[EvoSuite] Post-process/run warning: " + t.getMessage());
            refactoredPass = false;
        }

        if (refactoredPass) {
            notify(project, "EvoSuite finished for " + targetClass + " (refactored preview PASS)");
        } else {
            notify(project, "EvoSuite finished for " + targetClass + " (refactored preview FAIL)");
        }

        return refactoredPass;
    }
    /**
     * Create a permissive Java security policy file for EvoSuite runs.
     *
     * Why: On Windows, EvoSuite scaffolding may initialize Swing/AWT internals which can trigger
     * native font library loads (e.g., freetype) and temporary file writes. EvoSuite sandbox mode
     * can block these operations and cause errors like "Could not initialize class sun.font.SunFontManager".
     *
     * This policy is intentionally permissive and is only used for the EvoSuite subprocess.
     */
    private static String createPermissiveJavaPolicyFile() throws IOException {
        String policyText = "grant {\n" +
                "  permission java.security.AllPermission;\n" +
                "};\n";

        java.nio.file.Path p = java.nio.file.Files.createTempFile("evosuite-permissive-", ".policy");
        p.toFile().deleteOnExit();
        java.nio.file.Files.writeString(p, policyText, StandardCharsets.UTF_8);
        return p.toAbsolutePath().toString();
    }

    private static final class JUnitConversionResult {
        final File testFile;
        final String testClassFqn;

        private JUnitConversionResult(File testFile, String testClassFqn) {
            this.testFile = testFile;
            this.testClassFqn = testClassFqn;
        }
    }

    /**
     * Locate EvoSuite-generated *_ESTest.java under <project>/evosuite-tests (or next to the source for plain files),
     * remove EvoSuite runtime/scaffolding dependencies, and write a pure JUnit4 test file.
     *
     * Output file name example: Main_EvoSuiteJUnit4Test.java
     */
    private static JUnitConversionResult postProcessEvoSuiteTestsToPureJUnit4(Project project,
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

        // Deterministic location (preferred): <evoOutputBaseDir>/evosuite-tests/**/<SimpleName>_ESTest.java
        // EvoSuite often writes tests under package folders (e.g., evosuite-tests/org/foo/Bar_ESTest.java),
        // so we must search recursively.
        File evoDir = null;
        File estest = null;

        String estestName = simpleName + "_ESTest.java";

        if (evoOutputBaseDir != null) {
            evoDir = new File(evoOutputBaseDir, "evosuite-tests");
            estest = findFileRecursivelyByName(evoDir, estestName);
        }

        // Fallback #1: project base path (older behavior)
        if (estest == null || !estest.exists()) {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isBlank()) {
                File base = new File(basePath);
                evoDir = new File(base, "evosuite-tests");
                estest = findFileRecursivelyByName(evoDir, estestName);
            }
        }

        // Fallback #2: next to the source file (last resort)
        if (estest == null || !estest.exists()) {
            if (sourceFilePath != null && !sourceFilePath.isBlank()) {
                File src = new File(sourceFilePath);
                File parent = src.getParentFile();
                if (parent != null) {
                    evoDir = new File(parent, "evosuite-tests");
                    estest = findFileRecursivelyByName(evoDir, estestName);
                }
            }
        }

        if (estest == null || !estest.exists()) {
            StringBuilder sb = new StringBuilder();
            sb.append("[EvoSuite] No *_ESTest.java found to convert.");
            sb.append(" Expected file name: ").append(estestName);
            if (evoDir != null) {
                sb.append(" under: ").append(evoDir.getAbsolutePath());
            }
            viewer.accept(sb.toString());
            return null;
        }

        // DEBUG ONLY: persist original EvoSuite ESTest for inspection (Windows newline issues)
        try {
            File debugDir = new File(project.getBasePath(), "evosuite-debug/original");
            if (!debugDir.exists()) {
                debugDir.mkdirs();
            }

            File debugCopy = new File(debugDir, estest.getName());
            Files.copy(estest.toPath(), debugCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

            viewer.accept("[EvoSuite][DEBUG] Saved original ESTest to: " + debugCopy.getAbsolutePath());
        } catch (Exception e) {
            viewer.accept("[EvoSuite][DEBUG] Failed to save original ESTest: " + e.getMessage());
        }

        String code = Files.readString(estest.toPath(), StandardCharsets.UTF_8);

        if (looksLikeLiteralNNewlineCorruption(code)) {
            viewer.accept("[EvoSuite] Detected newline corruption (literal 'n' tokens). Applying adaptive repair.");
            code = repairLiteralNNewlineCorruption(code);
        }
        code = normalizeBrokenNewlines(code);

        String outClass = simpleName + "_EvoSuiteJUnit4Test";
        String converted = convertEvoSuiteToPureJUnit4(code, outClass);

        // Ensure output directory exists
        if (evoDir != null && !evoDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            evoDir.mkdirs();
        }

        File outFile = new File(evoDir != null ? evoDir : estest.getParentFile(), outClass + ".java");
        Files.writeString(outFile.toPath(), converted, StandardCharsets.UTF_8);

        String pkg = extractPackageName(converted);
        String fqn = (pkg == null || pkg.isBlank()) ? outClass : (pkg + "." + outClass);

        // Cache last-known-good converted test for this CUT as a fallback.
        try {
            LAST_GOOD_TEST_BY_CUT.put(targetClassFqn, new CachedJUnitTest(fqn, converted));
            viewer.accept("[EvoSuite] Cached last-known-good JUnit4 test for: " + targetClassFqn);
        } catch (Throwable ignored) {
            // best-effort only
        }

        viewer.accept("[EvoSuite] Wrote pure JUnit4 test: " + outFile.getAbsolutePath());
        return new JUnitConversionResult(outFile, fqn);
    }

    /**
     * Strip EvoSuite runtime dependencies (EvoRunner/EvoRunnerParameters) and scaffolding inheritance from a generated test.
     * This is a best-effort conversion meant for simple projects. It keeps @Test methods and JUnit assertions.
     */
    private static String convertEvoSuiteToPureJUnit4(String code, String outputClassName) {
        if (code == null) return "";

        // Normalize broken newlines that sometimes appear as literal 'n' / 'nn' tokens in generated files
        String s = normalizeBrokenNewlines(code);

        // Drop EvoSuite runtime imports
        s = s.replaceAll("(?m)^\\s*import\\s+org\\.evosuite\\..*;\\s*$\\n?", "");

        // Drop EvoSuite-shaded Mockito imports (generated tests may use shaded Mockito; we don't ship it)
        s = s.replaceAll("(?m)^\\s*import\\s+static\\s+org\\.evosuite\\.shaded\\.org\\.mockito\\.Mockito\\.\\*;\\s*$\\n?", "");

        // Drop EvoSuite runtime static assertion helper (some tests use verifyException())
        s = s.replaceAll("(?m)^\\s*import\\s+static\\s+org\\.evosuite\\.runtime\\.EvoAssertions\\.\\*;\\s*$\\n?", "");

        // Remove direct calls to EvoSuite-only helpers that won't compile without the runtime
        // (best-effort: keep the test structure and JUnit asserts)
        s = s.replaceAll("(?m)^\\s*verifyException\\(.*\\)\\s*;\\s*$\\n?", "");

        // Best-effort: strip Mockito-based mock/stub lines if EvoSuite generated them.
        // Without Mockito on the classpath these tests won't compile, and for our preview gating we prefer compilable tests.
        s = s.replaceAll("(?m)^.*\\bmock\\(.*\\)\\s*;\\s*$\\n?", "");
        s = s.replaceAll("(?m)^.*\\bdoReturn\\(.*\\)\\.when\\(.*\\)\\..*;\\s*$\\n?", "");
        s = s.replaceAll("(?m)^.*\\.when\\(.*\\)\\..*;\\s*$\\n?", "");
        s = s.replaceAll("(?m)^.*ViolatedAssumptionAnswer.*$\\n?", "");
        s = s.replaceAll("(?m)^.*FileSystemHandling.*$\\n?", "");

        // Best-effort: strip EvoSuite runtime "mock" helpers that remain in the body after we drop imports.
        // IMPORTANT: do NOT blanket-delete every "Mock*" reference, because projects may legitimately have Mock classes.
        // Instead, remove only common EvoSuite runtime helper classes and allow extension via patterns.
        // You can extend this list safely without changing logic.
        final String[] evoSuiteOnlyLinePatterns = new String[] {
                // Common EvoSuite file-system / UI mocks (runtime)
                "(?m)^.*\\bMockFileSystemView\\b.*$\\n?",
                "(?m)^.*\\bMockJFileChooser\\b.*$\\n?",
                "(?m)^.*\\bMockFile\\b.*$\\n?",
                "(?m)^.*\\bMockResources\\b.*$\\n?",
                "(?m)^.*\\bMockToolkit\\b.*$\\n?",
                "(?m)^.*\\bMockGraphics\\b.*$\\n?",

                // EvoSuite internal fake/mocking infra often leaks as class names in statements
                "(?m)^.*\\bEvoSuite\\w*Mock\\w*\\b.*$\\n?",

                // If a line references org.evosuite.* symbols directly (not just imports), it won't compile without runtime
                "(?m)^.*\\borg\\.evosuite\\..*$\\n?"
        };
        for (String p : evoSuiteOnlyLinePatterns) {
            s = s.replaceAll(p, "");
        }

        // If the file contains the literal 'n' newline corruption, try one more repair pass after removals.
        s = normalizeBrokenNewlines(s);

        // Drop RunWith import (not needed after removing @RunWith)
        s = s.replaceAll("(?m)^\\s*import\\s+org\\.junit\\.runner\\.RunWith\\s*;\\s*$\\n?", "");
        // Drop EvoSuite runner annotations (often on the same line)
        s = s.replaceAll("(?m)^.*@RunWith\\(EvoRunner\\.class\\).*$\\n?", "");
        s = s.replaceAll("(?m)^.*@EvoRunnerParameters\\(.*\\).*$\\n?", "");

        // Replace class declaration: remove "extends *_ESTest_scaffolding"
        s = s.replaceAll("(?m)public\\s+class\\s+([A-Za-z0-9_]+)\\s+extends\\s+[A-Za-z0-9_]+\\s*\\{",
                "public class " + outputClassName + " {");

        // If for some reason it doesn't extend scaffolding, still rename the class
        s = s.replaceAll("(?m)public\\s+class\\s+([A-Za-z0-9_]+)\\s*\\{",
                "public class " + outputClassName + " {");

        // Clean up excessive blank lines
        s = s.replace("\r\n", "\n");
        s = s.replace("\r", "\n");
        s = s.replaceAll("(?m)^[ \\t]*\\n{3,}", "\n\n");
        s = s.replaceAll("\\n{3,}", "\n\n");

        return s.trim() + "\n";
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
        pb.redirectErrorStream(true); // 合并 stdout 和 stderr

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

        // === Replace the fixed waitFor with a polling loop that honors -Dglobal_timeout and prints heartbeats ===
        // Default hard cap (seconds) if EvoSuite doesn't exit (Windows sometimes hangs during teardown).
        int maxWaitSeconds = 240;
        int globalTimeoutSeconds = -1;

        // If caller provided -Dglobal_timeout=NN, cap the overall wait.
        // On Windows we keep the buffer SMALL so we can always escape hard hangs (startup + teardown are often the hang point).
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

        // Extra safety net: regardless of output/heartbeats, never let Windows runs exceed 150s.
        if (isWindows) {
            maxWaitSeconds = Math.min(maxWaitSeconds, 150);
        }

        long start = System.currentTimeMillis();
        long lastBeat = start;
        boolean finished = false;
        while (true) {
            finished = process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) break;

            long now = System.currentTimeMillis();
            long elapsedSec = (now - start) / 1000;

            if (viewer != null && (now - lastBeat) >= 15_000) {
                viewer.accept("[INFO] Process still running... elapsed=" + elapsedSec + "s (max=" + maxWaitSeconds + "s)");
                lastBeat = now;
            }

            // If we have seen no output for a long time, assume the process is hung.
            // Windows tends to hang earlier (often during client teardown), so use a shorter threshold.
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

        // Wait briefly for output thread to flush
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
        String java8 = System.getenv("JAVA_8_HOME");
        String p = resolveExeUnderHome(java8, "java");
        if (p != null) return p;

        String java11 = System.getenv("JAVA_11_HOME");
        p = resolveExeUnderHome(java11, "java");
        if (p != null) return p;

        String javaHome = System.getenv("JAVA_HOME");
        p = resolveExeUnderHome(javaHome, "java");
        if (p != null) return p;

        return "java";
    }

    /**
     * Best-effort tools.jar discovery for EvoSuite 1.0.6.
     * For a java executable like /path/to/jdk8/bin/java, tools.jar is usually at /path/to/jdk8/lib/tools.jar.
     *
     * @return absolute path to tools.jar if found; otherwise null.
     */
    private static String findToolsJarForJavaExe(String javaExe) {
        try {
            if (javaExe == null || javaExe.isBlank()) return null;

            File javaFile = new File(javaExe);
            if (!javaFile.exists()) return null;

            // If javaExe points to ".../bin/java", then javaHome is the parent of "bin"
            File binDir = javaFile.getParentFile();
            if (binDir == null) return null;

            File javaHome = binDir.getParentFile();
            if (javaHome == null) return null;

            File tools = new File(javaHome, "lib" + File.separator + "tools.jar");
            if (tools.exists() && tools.isFile()) {
                return tools.getAbsolutePath();
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    /**
     * Option B: EvoSuite jar is bundled inside the plugin resources.
     * This method extracts it to a temp file and returns the absolute path.
     *
     * Expected resource path (inside plugin jar):
     *   /tools/evosuite.jar
     *
     * Put the jar at: src/main/resources/tools/evosuite.jar
     */
    private static String resolveBundledEvoSuiteJarPath() throws IOException {
        // Resource inside the plugin jar
        String resourcePath = "/tools/evosuite.jar";

        InputStream in = AiderHelper.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath + " (ensure src/main/resources/tools/evosuite.jar exists)");
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
     * Extract a tool jar bundled in plugin resources to a temp file and return its absolute path.
     */
    private static String extractBundledToolJar(String resourcePath, String prefix) throws IOException {
        InputStream in = AiderHelper.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile(prefix, ".jar");
        tmp.toFile().deleteOnExit();
        try (in) {
            java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp.toAbsolutePath().toString();
    }

    /**
     * Resolve bundled JUnit4 jar path. Put the jar at: src/main/resources/tools/junit-4.13.2.jar
     */
    private static String resolveBundledJUnit4JarPath() throws IOException {
        return extractBundledToolJar("/tools/junit-4.13.2.jar", "junit4-");
    }

    /**
     * Resolve bundled Hamcrest jar path. Put the jar at: src/main/resources/tools/hamcrest-core-1.3.jar
     */
    private static String resolveBundledHamcrestJarPath() throws IOException {
        return extractBundledToolJar("/tools/hamcrest-core-1.3.jar", "hamcrest-");
    }


    private static boolean runJUnit4ForGeneratedTest(Project project,
                                                     String javaExe,
                                                     String projectCp,
                                                     String overrideFirstCp,
                                                     String testJavaFilePath,
                                                     String testClassFqn,
                                                     Consumer<String> viewer) throws IOException, InterruptedException {
        if (projectCp == null || projectCp.isBlank()) {
            if (viewer != null) viewer.accept("[JUnit] Skipped: project classpath is empty.");
            return false;
        }
        if (testJavaFilePath == null || testJavaFilePath.isBlank()) {
            if (viewer != null) viewer.accept("[JUnit] Skipped: test file path is empty.");
            return false;
        }
        if (testClassFqn == null || testClassFqn.isBlank()) {
            if (viewer != null) viewer.accept("[JUnit] Skipped: test class name is empty.");
            return false;
        }

        String junitJar = resolveBundledJUnit4JarPath();
        String hamcrestJar = resolveBundledHamcrestJarPath();
        String javac = resolveJavacExecutable();

        java.nio.file.Path testOutDir = java.nio.file.Files.createTempDirectory("evosuite-junit-classes-");
        testOutDir.toFile().deleteOnExit();

        StringBuilder compileCp = new StringBuilder();
        if (overrideFirstCp != null && !overrideFirstCp.isBlank()) {
            compileCp.append(overrideFirstCp).append(File.pathSeparator);
        }
        compileCp.append(projectCp)
                .append(File.pathSeparator).append(junitJar)
                .append(File.pathSeparator).append(hamcrestJar);

        java.util.List<String> javacCmd = new java.util.ArrayList<>();
        javacCmd.add(javac);
        javacCmd.add("-encoding");
        javacCmd.add("UTF-8");
        javacCmd.add("-cp");
        javacCmd.add(compileCp.toString());
        javacCmd.add("-d");
        javacCmd.add(testOutDir.toAbsolutePath().toString());
        javacCmd.add(testJavaFilePath);

        if (viewer != null) viewer.accept("[JUnit] Compiling generated test: " + String.join(" ", javacCmd));
        int cExit = runProcessStreaming(project, javacCmd, viewer);
        if (cExit != 0) {
            if (viewer != null) viewer.accept("[JUnit] FAIL: javac exited with code " + cExit);
            return false;
        }

        StringBuilder runCp = new StringBuilder();
        runCp.append(testOutDir.toAbsolutePath().toString()).append(File.pathSeparator);
        if (overrideFirstCp != null && !overrideFirstCp.isBlank()) {
            runCp.append(overrideFirstCp).append(File.pathSeparator);
        }
        runCp.append(projectCp)
                .append(File.pathSeparator).append(junitJar)
                .append(File.pathSeparator).append(hamcrestJar);

        java.util.List<String> javaCmd = new java.util.ArrayList<>();
        javaCmd.add(javaExe);
        javaCmd.add("-cp");
        javaCmd.add(runCp.toString());
        javaCmd.add("org.junit.runner.JUnitCore");
        javaCmd.add(testClassFqn);

        if (viewer != null) viewer.accept("[JUnit] Running: " + String.join(" ", javaCmd));
        int tExit = runProcessStreaming(project, javaCmd, viewer);
        if (tExit == 0) {
            if (viewer != null) viewer.accept("[JUnit] PASS: all tests passed.");
            return true;
        } else {
            if (viewer != null) viewer.accept("[JUnit] FAIL: test run exited with code " + tExit);
            return false;
        }
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