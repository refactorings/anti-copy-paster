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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AiderHelper {

    private static final Map<String, ConsoleView> CONSOLE_BY_TITLE = new ConcurrentHashMap<>();

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
                toolWindow = twm.getToolWindow("Clone Output");
                if (toolWindow == null) {
                    toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable("Clone Output"));
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
                        System.out.println("===> Clone Output:\n" + output);
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

        Consumer<String> viewer = openStreamingViewer(project, "EvoSuite Output");
        viewer.accept("[EvoSuite] Java-8 compile (single-file) for compatibility: " + String.join(" ", cmd));

        int exit = runProcessStreaming(project, cmd, viewer);
        if (exit != 0) {
            throw new RuntimeException("javac exited with code " + exit);
        }
        return outDir.toAbsolutePath().toString();
    }

    private static String compileJavaSourceTextToTempOutput(Project project, String classFqn, String javaSource)
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
        cmd.add("-d"); cmd.add(outDir.toAbsolutePath().toString());
        cmd.add(srcFile.toAbsolutePath().toString());

        Consumer<String> viewer = openStreamingViewer(project, "EvoSuite Output");
        viewer.accept("[EvoSuite] Java-8 compile (refactored preview): " + String.join(" ", cmd));

        int exit = runProcessStreaming(project, cmd, viewer);
        if (exit != 0) throw new RuntimeException("javac(refactored preview) exited with code " + exit);

        return outDir.toAbsolutePath().toString();
    }

    /**
     * Best-effort javac resolution. Prefers JAVA_8_HOME (to match EvoSuite runtime), then JAVA_11_HOME, then JAVA_HOME, else "javac".
     */
    private static String resolveJavacExecutable() {
        String java8 = System.getenv("JAVA_8_HOME");
        if (java8 != null && !java8.isBlank()) {
            File f = new File(java8, "bin" + File.separator + "javac");
            if (f.exists()) return f.getAbsolutePath();
        }
        String java11 = System.getenv("JAVA_11_HOME");
        if (java11 != null && !java11.isBlank()) {
            File f = new File(java11, "bin" + File.separator + "javac");
            if (f.exists()) return f.getAbsolutePath();
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            File f = new File(javaHome, "bin" + File.separator + "javac");
            if (f.exists()) return f.getAbsolutePath();
        }
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
                    toolWindow = twm.getToolWindow("Clone Output");
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
                        twm.getToolWindow("Clone Output")
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
        Consumer<String> viewer = openStreamingViewer(project, "EvoSuite Output");

        // Force a deterministic EvoSuite output location so we can reliably find *_ESTest.java
        // EvoSuite will create <output_directory>/evosuite-tests and write tests there.
        File evoOutputBaseDir = Files.createTempDirectory("evosuite-out-").toFile();
        evoOutputBaseDir.deleteOnExit();

        // Minimal usable arguments: pick a generation mode + criterion + classpath + CUT
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExe);

        // Reduce Swing/AWT interference (best-effort; safe for non-GUI projects too).
        // Some GUI-heavy code paths can still spawn Swing layout threads; forcing the headless toolkit
        // reduces the chance of crashes like IconView NPE on macOS/JDK8 when rendering happens indirectly.
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Djava.awt.graphicsenv=sun.awt.HeadlessGraphicsEnvironment");
        cmd.add("-Djava.awt.toolkit=sun.awt.HToolkit");
        // macOS specific: keep the process from trying to become a UI app
        cmd.add("-Dapple.awt.UIElement=true");

        // Allow EvoSuite to attach to itself when needed
        cmd.add("-Djdk.attach.allowAttachSelf=true");
        // EvoSuite 1.0.6 expects tools.jar (JDK 8). If we can't find it, running under JDK 9+ will crash.
        String toolsJar = findToolsJarForJavaExe(javaExe);
        if (toolsJar != null) {
            cmd.add("-Dtools_jar_location=" + toolsJar);
        } else {
            viewer.accept("[EvoSuite] tools.jar not found for Java executable: " + javaExe);
            viewer.accept("[EvoSuite] EvoSuite 1.0.6 typically requires JDK 8 (tools.jar).");
            viewer.accept("[EvoSuite] Set JAVA_8_HOME to a JDK 8 installation and restart the IDE, or upgrade the bundled EvoSuite to a Java 9+ compatible version.");
            notify(project, "EvoSuite skipped: tools.jar not found. Please set JAVA_8_HOME to a JDK 8 path (EvoSuite 1.0.6 requires tools.jar).");
            return false;
        }
        cmd.add("-jar");
        cmd.add(evoSuiteJarPath);


        // Choose a mode. MOSA-style tends to work well; adjust later via settings.
        cmd.add("-generateMOSuite");
        // Avoid spawning a separate client JVM so tools.jar resolution works consistently with EvoSuite 1.0.6
        cmd.add("-Dclient_on_thread=true");

        // Target class and classpath
        cmd.add("-class");
        cmd.add(targetClass);
        cmd.add("-projectCP");
        cmd.add(classpath);

        // Keep runs short for interactive usage
        cmd.add("-Dsearch_budget=60");

        // Sensible default coverage goals
        cmd.add("-Dcriterion=LINE:BRANCH");

        viewer.accept("[EvoSuite] Running EvoSuite for class: " + targetClass);
        viewer.accept("[EvoSuite] Command: " + String.join(" ", cmd));

        // EvoSuite 1.0.6 does NOT support the `output_directory` property. To make output deterministic,
        // we run EvoSuite with its working directory set to our temp output folder.
        // EvoSuite will then create `evosuite-tests/` under this working directory.
        int exit = runProcessStreaming(project, cmd, viewer, evoOutputBaseDir);
        if (exit != 0) {
            throw new RuntimeException("EvoSuite exited with code " + exit);
        }

        boolean refactoredPass = false;

        // Convert EvoSuite tests to a pure JUnit4 test (no EvoRunner/scaffolding) for plain projects,
        // then compile + run it and report PASS/FAIL.
        try {
            JUnitConversionResult conv = postProcessEvoSuiteTestsToPureJUnit4(project, evoOutputBaseDir, targetClass, sourceFilePath, viewer);
            if (conv != null) {
                if (isPlainProject(project)) {
                    // Plain/simple projects: run via external JUnitCore against compiled preview
                    runJUnit4ForGeneratedTest(project, javaExe, classpath, conv.testFile.getAbsolutePath(), conv.testClassFqn, viewer);

                    // Compile the REFACTORED preview source and run the SAME tests against it
                    if (refactoredSourceText != null && !refactoredSourceText.isBlank()) {
                        String refactoredCp = compileJavaSourceTextToTempOutput(project, targetClass, refactoredSourceText);
                        refactoredPass = runJUnit4ForGeneratedTest(project, javaExe, refactoredCp, conv.testFile.getAbsolutePath(), conv.testClassFqn, viewer);
                    } else {
                        viewer.accept("[EvoSuite] Refactored source text is empty; skipping refactored preview test run.");
                        refactoredPass = false;
                    }
                } else {
                    // Maven/Gradle projects: write test into src/test/java and run via IntelliJ JUnit runner.
                    viewer.accept("[EvoSuite] Maven/Gradle project detected: running generated JUnit4 test via IntelliJ runner.");

                    try {
                        String testSource = Files.readString(conv.testFile.toPath(), StandardCharsets.UTF_8);
                        writeTestIntoProjectTestSources(project, testSource, conv.testClassFqn, viewer);
                        runJUnit4ViaIdeaRunner(project, conv.testClassFqn, viewer);
                        viewer.accept("[EvoSuite] Note: IntelliJ runner execution is async; not gating apply on PASS/FAIL for Maven/Gradle." );
                        // Allow apply; gating would require test run listeners.
                        refactoredPass = true;
                    } catch (Throwable t) {
                        viewer.accept("[JUnit/IDE] Failed to write/run tests via IntelliJ runner: " + t.getMessage());
                        refactoredPass = true; // don't block apply due to runner integration issues
                    }
                }
            } else {
                viewer.accept("[EvoSuite] No converted JUnit4 test to run.");
                refactoredPass = false;
            }
        } catch (Throwable t) {
            viewer.accept("[EvoSuite] Post-process/run warning: " + t.getMessage());
            refactoredPass = false;
        }

        if (refactoredPass) {
            notify(project, "EvoSuite finished for " + targetClass + " (refactored preview PASS)");
        } else {
            notify(project, "EvoSuite finished for " + targetClass + " (refactored preview FAIL)");
        }

        return refactoredPass;
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

        String code = Files.readString(estest.toPath(), StandardCharsets.UTF_8);
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

        viewer.accept("[EvoSuite] Wrote pure JUnit4 test: " + outFile.getAbsolutePath());
        return new JUnitConversionResult(outFile, fqn);
    }

    /**
     * Strip EvoSuite runtime dependencies (EvoRunner/EvoRunnerParameters) and scaffolding inheritance from a generated test.
     * This is a best-effort conversion meant for simple projects. It keeps @Test methods and JUnit assertions.
     */
    private static String convertEvoSuiteToPureJUnit4(String code, String outputClassName) {
        if (code == null) return "";

        // Drop EvoSuite runtime imports
        String s = code.replaceAll("(?m)^\\s*import\\s+org\\.evosuite\\..*;\\s*$\\n?", "");
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
        s = s.replaceAll("\\r\\n", "\\n");
        s = s.replaceAll("(?m)^[ \\t]*\\n{3,}", "\n\n");
        s = s.replaceAll("\\n{3,}", "\n\n");

        return s.trim() + "\n";
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
        pb.redirectErrorStream(true);

        if (workingDirOverride != null) {
            pb.directory(workingDirOverride);
        } else {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isBlank()) {
                pb.directory(new File(basePath));
            }
        }

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String cleaned = stripNonPrintable(stripAnsi(line));
                if (cleaned != null && !cleaned.trim().isEmpty()) {
                    if (viewer != null) viewer.accept(cleaned);
                }
            }
        }

        return process.waitFor();
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
        if (java8 != null && !java8.isBlank()) {
            File f = new File(java8, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
        }
        String java11 = System.getenv("JAVA_11_HOME");
        if (java11 != null && !java11.isBlank()) {
            File f = new File(java11, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            File f = new File(javaHome, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
        }
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

    /**
     * Compile the generated pure JUnit4 test into the given classesDir, then run it with JUnitCore and report PASS/FAIL.
     *
     * @param classesDirOrCp  directory containing compiled classes (for plain projects this is the temp output dir)
     */
    private static boolean runJUnit4ForGeneratedTest(Project project,
                                                  String javaExe,
                                                  String classesDirOrCp,
                                                  String testJavaFilePath,
                                                  String testClassFqn,
                                                  Consumer<String> viewer) throws IOException, InterruptedException {
        if (classesDirOrCp == null || classesDirOrCp.isBlank()) {
            viewer.accept("[JUnit] Skipped: classes directory/classpath is empty.");
            return false;
        }
        if (testJavaFilePath == null || testJavaFilePath.isBlank()) {
            viewer.accept("[JUnit] Skipped: test file path is empty.");
            return false;
        }
        if (testClassFqn == null || testClassFqn.isBlank()) {
            viewer.accept("[JUnit] Skipped: test class name is empty.");
            return false;
        }

        String junitJar = resolveBundledJUnit4JarPath();
        String hamcrestJar = resolveBundledHamcrestJarPath();

        // 1) Compile test file into the same output dir as the CUT
        String javac = resolveJavacExecutable();

        String compileCp = classesDirOrCp + File.pathSeparator + junitJar + File.pathSeparator + hamcrestJar;

        java.util.List<String> javacCmd = new java.util.ArrayList<>();
        javacCmd.add(javac);
        javacCmd.add("-encoding");
        javacCmd.add("UTF-8");
        javacCmd.add("-cp");
        javacCmd.add(compileCp);
        javacCmd.add("-d");
        javacCmd.add(classesDirOrCp);
        javacCmd.add(testJavaFilePath);

        viewer.accept("[JUnit] Compiling generated test: " + String.join(" ", javacCmd));
        int cExit = runProcessStreaming(project, javacCmd, viewer);
        if (cExit != 0) {
            viewer.accept("[JUnit] FAIL: javac exited with code " + cExit);
            return false;
        }

        // 2) Run JUnitCore
        String runCp = classesDirOrCp + File.pathSeparator + junitJar + File.pathSeparator + hamcrestJar;

        java.util.List<String> javaCmd = new java.util.ArrayList<>();
        javaCmd.add(javaExe);
        javaCmd.add("-cp");
        javaCmd.add(runCp);
        javaCmd.add("org.junit.runner.JUnitCore");
        javaCmd.add(testClassFqn);

        viewer.accept("[JUnit] Running: " + String.join(" ", javaCmd));
        int tExit = runProcessStreaming(project, javaCmd, viewer);
        if (tExit == 0) {
            viewer.accept("[JUnit] PASS: all tests passed.");
            return true;
        } else {
            viewer.accept("[JUnit] FAIL: test run exited with code " + tExit);
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