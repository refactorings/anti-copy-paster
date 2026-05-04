package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.PathsList;
import org.jetbrains.research.anticopypaster.agents.testing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class WorkflowJavaBuildSupport {
    private final Project project;
    private final Consumer<String> viewer;
    private final AtomicReference<Process> currentProcessRef;
    private final BooleanSupplier isCancelled;

    private volatile String lastConvertedTestFqn;
    private volatile String lastPatchedClassesDir;
    private volatile String lastTargetFqn;
    private volatile String lastTestBootstrapClass;
    private volatile String lastTestBootstrapMethod;
    private volatile boolean lastDisableEvoSuiteStaticReset;
    private volatile String lastEvoSuiteFallbackClassesDir;

    static final class CompileAttempt {
        final boolean success;
        final File outputDir;
        final String output;

        private CompileAttempt(boolean success, File outputDir, String output) {
            this.success = success;
            this.outputDir = outputDir;
            this.output = output == null ? "" : output;
        }

        String toCompileLog() {
            if (success) {
                return "BUILD SUCCESS\n" + output;
            }
            return "BUILD FAILED\nCompilation failed:\n" + output;
        }
    }

    WorkflowJavaBuildSupport(Project project,
                             Consumer<String> viewer,
                             AtomicReference<Process> currentProcessRef,
                             BooleanSupplier isCancelled) {
        this.project = project;
        this.viewer = viewer;
        this.currentProcessRef = currentProcessRef;
        this.isCancelled = isCancelled;
    }

    void setTargetFqn(String targetFqn) {
        this.lastTargetFqn = blankToNull(targetFqn);
    }

    void setPatchedClassesDir(File patchedClassesDir) {
        this.lastPatchedClassesDir = patchedClassesDir == null ? null : blankToNull(patchedClassesDir.getAbsolutePath());
    }

    void clearPatchedClassesDir() {
        this.lastPatchedClassesDir = null;
    }

    String buildProjectClasspathFromIde() {
        if (project == null || project.isDisposed()) return "";
        try {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            if (modules == null || modules.length == 0) return "";

            Set<String> paths = new LinkedHashSet<>();

            for (Module module : modules) {
                if (module == null || module.isDisposed()) continue;

                try {
                    PathsList orderEntries = OrderEnumerator.orderEntries(module)
                            .recursively().withoutSdk().classes().getPathsList();
                    paths.addAll(orderEntries.getPathList());
                } catch (Exception e) {
                    logBestEffortFailure("collect module classpath entries", e);
                }

                try {
                    CompilerModuleExtension ext = CompilerModuleExtension.getInstance(module);
                    if (ext != null) {
                        if (ext.getCompilerOutputPath() != null) {
                            paths.add(ext.getCompilerOutputPath().getPath());
                        }
                        if (ext.getCompilerOutputPathForTests() != null) {
                            paths.add(ext.getCompilerOutputPathForTests().getPath());
                        }
                    }
                } catch (Exception e) {
                    logBestEffortFailure("collect compiler output paths", e);
                }

                try {
                    ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                    if (rootManager != null) {
                        for (VirtualFile root : rootManager.getSourceRoots(false)) {
                            if (root != null && root.getPath() != null && !root.getPath().isBlank()) {
                                paths.add(root.getPath());
                            }
                        }
                        for (VirtualFile root : rootManager.getSourceRoots(true)) {
                            if (root != null && root.getPath() != null && !root.getPath().isBlank()) {
                                paths.add(root.getPath());
                            }
                        }
                    }
                } catch (Exception e) {
                    logBestEffortFailure("collect module source roots for classpath", e);
                }
            }

            try {
                String basePath = project.getBasePath();
                if (basePath != null && !basePath.isBlank()) {
                    Path base = Paths.get(basePath);
                    Files.walk(base)
                            .filter(p -> p != null && Files.isDirectory(p))
                            .forEach(p -> {
                                String s = p.toString();
                                if (s.endsWith(File.separator + "target" + File.separator + "classes")
                                        || s.endsWith(File.separator + "target" + File.separator + "test-classes")
                                        || s.endsWith(File.separator + "classes" + File.separator + "production")
                                        || s.endsWith(File.separator + "classes" + File.separator + "test")
                                        || s.endsWith(File.separator + "out" + File.separator + "production")
                                        || s.endsWith(File.separator + "out" + File.separator + "test")) {
                                    paths.add(s);
                                }
                            });
                }
            } catch (Exception e) {
                logBestEffortFailure("scan project output directories", e);
            }

            Set<String> validPaths = new LinkedHashSet<>();
            for (String path : paths) {
                if (path != null && !path.isBlank() && new File(path).exists()) {
                    validPaths.add(path);
                }
            }

            return String.join(File.pathSeparator, validPaths);
        } catch (Exception e) {
            logBestEffortFailure("build project classpath from IDE", e);
            return "";
        }
    }

    String buildProjectSourcepathFromIde() {
        if (project == null || project.isDisposed()) return "";
        try {
            Set<String> roots = new LinkedHashSet<>();

            Module[] modules = ModuleManager.getInstance(project).getModules();
            if (modules != null) {
                for (Module module : modules) {
                    if (module == null || module.isDisposed()) continue;
                    try {
                        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                        if (rootManager == null) continue;

                        for (VirtualFile root : rootManager.getSourceRoots(false)) {
                            if (root != null) {
                                String path = root.getPath();
                                if (path != null && !path.isBlank() && new File(path).exists()) {
                                    roots.add(path);
                                }
                            }
                        }
                        for (VirtualFile root : rootManager.getSourceRoots(true)) {
                            if (root != null) {
                                String path = root.getPath();
                                if (path != null && !path.isBlank() && new File(path).exists()) {
                                    roots.add(path);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logBestEffortFailure("collect module source roots", e);
                    }
                }
            }

            try {
                String basePath = project.getBasePath();
                if (basePath != null && !basePath.isBlank()) {
                    Path base = Paths.get(basePath);
                    Files.walk(base)
                            .filter(p -> p != null && Files.isDirectory(p))
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
                                } catch (Exception e) {
                                    logBestEffortFailure("inspect candidate source root", e);
                                }
                            });
                }
            } catch (Exception e) {
                logBestEffortFailure("scan project source roots", e);
            }

            return String.join(File.pathSeparator, roots);
        } catch (Exception e) {
            logBestEffortFailure("build project sourcepath from IDE", e);
            return "";
        }
    }

    String buildCompileClasspathWithSourceRoots(String classpath) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        try {
            if (classpath != null && !classpath.isBlank()) {
                String[] cpEntries = classpath.split(Pattern.quote(File.pathSeparator));
                for (String entry : cpEntries) {
                    if (entry != null && !entry.isBlank() && new File(entry).exists()) {
                        paths.add(entry);
                    }
                }
            }

            String sourcepath = buildProjectSourcepathFromIde();
            if (sourcepath != null && !sourcepath.isBlank()) {
                String[] sourceEntries = sourcepath.split(Pattern.quote(File.pathSeparator));
                for (String entry : sourceEntries) {
                    if (entry != null && !entry.isBlank() && new File(entry).exists()) {
                        paths.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            logBestEffortFailure("build compile classpath with source roots", e);
        }
        return String.join(File.pathSeparator, paths);
    }

    String resolvePrimaryClassFqn(String javaSource, String fileName) {
        if (javaSource == null) return "";
        String pkg = "";
        Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w\\.]*)\\s*;").matcher(javaSource);
        if (pm.find()) pkg = pm.group(1);

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

    File compileProposedSourceToTemp(File originalFile,
                                     String fileName,
                                     String proposedSource,
                                     String classpath) throws Exception {
        CompileAttempt attempt = compileProposedSourceToTempAttempt(originalFile, fileName, proposedSource, classpath);
        if (!attempt.success) {
            throw new RuntimeException("Compilation failed:\n" + attempt.output);
        }
        return attempt.outputDir;
    }

    CompileAttempt compileProposedSourceToTempAttempt(File originalFile,
                                                      String fileName,
                                                      String proposedSource,
                                                      String classpath) throws Exception {
        if (originalFile == null) throw new IllegalArgumentException("originalFile is null");
        if (proposedSource == null) throw new IllegalArgumentException("proposedSource is null");

        File tempSrcRoot = Files.createTempDirectory("acp_refactor_src_").toFile();
        tempSrcRoot.deleteOnExit();
        File tempOut = Files.createTempDirectory("acp_refactor_out_").toFile();
        tempOut.deleteOnExit();

        String pkg = "";
        Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w\\.]*)\\s*;\\s*$")
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
        Files.writeString(tempJava.toPath(), proposedSource, StandardCharsets.UTF_8);
        tempJava.deleteOnExit();

        if (project == null || project.isDisposed()) {
            throw new RuntimeException("Cannot compile: project is null or disposed");
        }

        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null || sdk.getHomePath() == null || sdk.getHomePath().isBlank()) {
            throw new RuntimeException("Cannot resolve Project SDK for compilation");
        }

        File javacFile = new File(
                sdk.getHomePath(),
                "bin" + File.separator + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javac.exe" : "javac")
        );
        if (!javacFile.exists()) {
            throw new RuntimeException("javac not found under Project SDK: " + javacFile.getAbsolutePath());
        }

        String sourcepath = buildProjectSourcepathFromIde();
        List<String> cmd = new ArrayList<>();
        cmd.add(javacFile.getAbsolutePath());
        cmd.add("-encoding");
        cmd.add("UTF-8");
        addJavacTargetFlags(cmd, sdk.getHomePath(), resolveProjectTargetMajor());
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
            WorkflowUiSupport.logStage("COMPILE", "javac classpath:\n" + (classpath == null ? "" : classpath));
            WorkflowUiSupport.logStage("COMPILE", "javac sourcepath:\n" + (sourcepath == null ? "" : sourcepath));
        } catch (Exception e) {
            logBestEffortFailure("log javac inputs", e);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String out = readProcessOutput(process);
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            logBestEffortFailure("read javac process exit code", e);
            exitCode = -1;
        }

        if (exitCode != 0 && out != null && out.contains("unmappable character")) {
            WorkflowUiSupport.logStage("COMPILE", "Retry with ISO-8859-1 due to encoding error...");

            List<String> retryCmd = new ArrayList<>(cmd);
            for (int i = 0; i < retryCmd.size() - 1; i++) {
                if ("-encoding".equals(retryCmd.get(i))) {
                    retryCmd.set(i + 1, "ISO-8859-1");
                    break;
                }
            }

            ProcessBuilder retryBuilder = new ProcessBuilder(retryCmd);
            retryBuilder.redirectErrorStream(true);
            Process retryProcess = retryBuilder.start();
            out = readProcessOutput(retryProcess);
            try {
                exitCode = retryProcess.exitValue();
            } catch (IllegalThreadStateException e) {
                logBestEffortFailure("read javac retry process exit code", e);
                exitCode = -1;
            }
        }

        if (exitCode != 0) {
            return new CompileAttempt(false, tempOut, out);
        }

        return new CompileAttempt(true, tempOut, out);
    }

    String runTests(testing.TestRunRequest req) {
        try {
            lastConvertedTestFqn = null;
            lastTestBootstrapClass = null;
            lastTestBootstrapMethod = null;
            lastDisableEvoSuiteStaticReset = false;
            lastEvoSuiteFallbackClassesDir = null;

            if (req == null) return "Test error: request is null.";
            String projectDir = getReqString(req, "projectDir", "projectPath", "baseDir");
            if (projectDir == null || projectDir.isBlank()) return "Test error: projectDir is empty.";

            String targetClass = getReqString(req, "targetClass", "targetClassFqn", "classFqn", "className", "targetFqn");
            if (viewer != null) viewer.accept("[TEST] received targetClass=" + (targetClass == null ? "null" : targetClass));

            if (targetClass == null || targetClass.isBlank() || "all".equalsIgnoreCase(targetClass)) {
                String fallback = lastTargetFqn;
                if (fallback != null && !fallback.isBlank()) {
                    targetClass = fallback;
                    if (viewer != null) viewer.accept("[TEST] targetClass missing in request; using workflow fallback: " + targetClass);
                }
            }

            if (targetClass == null || targetClass.isBlank() || "all".equalsIgnoreCase(targetClass)) {
                return "Test error: targetClass (FQN) is empty. Cannot run EvoSuite without a class name.";
            }

            lastTestBootstrapClass = getReqString(req, "testBootstrapClass", "junitBootstrapClass", "bootstrapClass");
            lastTestBootstrapMethod = getReqString(req, "testBootstrapMethod", "junitBootstrapMethod", "bootstrapMethod");
            lastDisableEvoSuiteStaticReset = getReqBoolean(req, false,
                    "disableEvoSuiteStaticReset",
                    "evosuiteDisableStaticReset",
                    "disableStaticReset",
                    "resetStaticFieldsFalse");

            File evosuiteJar = materializeResourceToProjectLib(new File(projectDir), "tools/evosuite-1.2.0.jar", "evosuite-1.2.0.jar");
            if (evosuiteJar == null || !evosuiteJar.exists()) {
                return "Test error: EvoSuite jar not found in resources at tools/evosuite-1.2.0.jar";
            }

            String projectCp = buildProjectClasspathFromIde();
            if (lastPatchedClassesDir != null && !lastPatchedClassesDir.isBlank()) {
                projectCp = lastPatchedClassesDir + File.pathSeparator + (projectCp == null ? "" : projectCp);
            }
            if (projectCp == null || projectCp.isBlank()) {
                return "Test error: IDE classpath is empty. Make sure the project is imported and has an SDK.";
            }

            File base = new File(projectDir);
            File outRoot = new File(base, ".anticopypaster" + File.separator + "evosuite-tests");
            if (!outRoot.exists() && !outRoot.mkdirs()) {
                return "Test error: failed to create output dir: " + outRoot.getAbsolutePath();
            }
            File testDir = new File(outRoot, "tests");
            File reportDir = new File(outRoot, "reports");
            testDir.mkdirs();
            reportDir.mkdirs();

            String fallbackClassesDir = ensureTargetClassOnClasspath(targetClass, projectCp, outRoot);
            if (fallbackClassesDir != null && !fallbackClassesDir.isBlank()) {
                lastEvoSuiteFallbackClassesDir = fallbackClassesDir;
                projectCp = fallbackClassesDir + File.pathSeparator + projectCp;
            }

            ensureTestDependencies(base, evosuiteJar);

            List<String> cmd = new ArrayList<>();

            String javaExe = resolveJavaExecutable();
            cmd.add(javaExe);
            String forcedJavaHome = deriveJavaHome(javaExe);

            String javaVersionOutput = readJavaVersion(javaExe);
            int major = parseJavaMajorVersion(javaVersionOutput);
            if (viewer != null) {
                String vv = javaVersionOutput == null ? "" : javaVersionOutput.strip();
                if (vv.length() > 800) vv = vv.substring(0, 800) + "\n...<truncated>...";
                viewer.accept("[EvoSuite] java -version:\n" + vv);
                viewer.accept("[EvoSuite] detected java major=" + major);
            }

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

            if (major == 8) {
                String toolsJar = null;
                try {
                    if (forcedJavaHome != null && !forcedJavaHome.isBlank()) {
                        File tj = new File(forcedJavaHome, "lib" + File.separator + "tools.jar");
                        if (tj.exists()) toolsJar = tj.getAbsolutePath();
                    }
                } catch (Exception e) {
                    logBestEffortFailure("resolve Java 8 tools.jar", e);
                }

                String cpLaunch;
                if (toolsJar != null && !toolsJar.isBlank()) {
                    cpLaunch = toolsJar + File.pathSeparator + evosuiteJar.getAbsolutePath();
                } else {
                    cpLaunch = evosuiteJar.getAbsolutePath();
                    if (viewer != null) {
                        viewer.accept("[EvoSuite] WARN: tools.jar not found under JAVA_HOME; agent attach may fail on Java 8.");
                    }
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
            cmd.add("-Djunit_check=FALSE");
            cmd.add("-Dtestability_transformation=false");
            cmd.add("-DTT=false");
            if (lastDisableEvoSuiteStaticReset) {
                cmd.add("-Dreset_static_fields=false");
                if (viewer != null) {
                    viewer.accept("[EvoSuite] Static reset disabled by request.");
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(base);
            pb.redirectErrorStream(true);

            if (forcedJavaHome != null) {
                if (viewer != null) viewer.accept("[EvoSuite] Enforcing JAVA_HOME=" + forcedJavaHome);
                pb.environment().put("JAVA_HOME", forcedJavaHome);
            }

            if (viewer != null) viewer.accept("[EvoSuite] Running: " + String.join(" ", cmd));

            ProcessRun pr = runProcessStreaming(pb);
            String evOut = pr == null ? "" : (pr.output == null ? "" : pr.output);
            int exit = pr == null ? -1 : pr.exitCode;

            boolean hasFailureMarker = containsAnyIgnoreCase(
                    evOut,
                    "problem for ",
                    "failed to generate",
                    "no statistics has been saved",
                    "error while initializing target class",
                    "no converter available",
                    "conversionexception",
                    "inaccessibleobjectexception",
                    "fatal",
                    "exception in thread"
            );

            boolean hasGeneratedTests = false;
            try {
                if (testDir.exists()) {
                    hasGeneratedTests = Files.walk(testDir.toPath())
                            .anyMatch(p -> p != null && p.toString().endsWith(".java"));
                }
            } catch (Exception e) {
                logBestEffortFailure("detect EvoSuite generated tests", e);
            }

            String nativeTestFqn = "";
            if (hasGeneratedTests) {
                String simpleName;
                try {
                    int idx = targetClass.lastIndexOf('.');
                    simpleName = idx >= 0 ? targetClass.substring(idx + 1) : targetClass;
                } catch (RuntimeException e) {
                    logBestEffortFailure("resolve EvoSuite target simple name", e);
                    simpleName = targetClass;
                }
                if (simpleName == null) simpleName = "";

                String estestName = simpleName + "_ESTest.java";
                Path estestPath = findFileRecursivelyByName(testDir.toPath(), estestName);
                if (estestPath == null) {
                    estestPath = findFirstFileBySuffix(testDir.toPath(), "_ESTest.java");
                }
                if (estestPath == null) {
                    return "[EVOSUITE]\n"
                            + "exitCode=" + exit + "\n"
                            + "status=tests_failed\n"
                            + "reason=generated tests folder exists, but no *_ESTest.java found\n";
                }

                Path scaffPath = null;
                try {
                    String scaffName = simpleName + "_ESTest_scaffolding.java";
                    Path candidate = estestPath.getParent().resolve(scaffName);
                    if (Files.exists(candidate)) scaffPath = candidate;
                } catch (Exception e) {
                    logBestEffortFailure("resolve EvoSuite scaffolding path", e);
                }
                if (scaffPath == null) {
                    scaffPath = findFirstFileBySuffix(testDir.toPath(), "_ESTest_scaffolding.java");
                }

                String rawTest = Files.readString(estestPath, StandardCharsets.UTF_8);
                if (rawTest == null) rawTest = "";
                if (looksLikeLiteralNNewlineCorruption(rawTest)) {
                    rawTest = repairLiteralNNewlineCorruption(rawTest);
                }
                rawTest = normalizeBrokenNewlines(rawTest);

                nativeTestFqn = resolvePrimaryClassFqn(rawTest, estestName);
                lastConvertedTestFqn = nativeTestFqn;

                File srcTestJava = new File(base, "test");
                if (!srcTestJava.exists() && !srcTestJava.mkdirs()) {
                    return "[EVOSUITE]\n"
                            + "exitCode=" + exit + "\n"
                            + "status=tests_failed\n"
                            + "reason=failed to create test\n";
                }

                Path rel = testDir.toPath().relativize(estestPath);
                Path targetEst = srcTestJava.toPath().resolve(rel);
                Files.createDirectories(targetEst.getParent());

                try {
                    String convertedName = simpleName + "_EvoSuiteJUnit4Test.java";
                    Path oldConverted = targetEst.getParent().resolve(convertedName);
                    if (Files.exists(oldConverted)) {
                        Files.delete(oldConverted);
                    }
                } catch (Exception e) {
                    logBestEffortFailure("delete stale converted EvoSuite test", e);
                }

                Files.copy(estestPath, targetEst, StandardCopyOption.REPLACE_EXISTING);

                if (scaffPath != null && Files.exists(scaffPath)) {
                    Path scaffRel = testDir.toPath().relativize(scaffPath);
                    Path targetScaff = srcTestJava.toPath().resolve(scaffRel);
                    Files.createDirectories(targetScaff.getParent());
                    if (lastDisableEvoSuiteStaticReset) {
                        String scaffolding = Files.readString(scaffPath, StandardCharsets.UTF_8);
                        scaffolding = disableEvoSuiteResetClasses(scaffolding);
                        Files.writeString(targetScaff, scaffolding, StandardCharsets.UTF_8);
                    } else {
                        Files.copy(scaffPath, targetScaff, StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                if (viewer != null) {
                    viewer.accept("[EvoSuite] Native test written: " + targetEst);
                    if (scaffPath != null) {
                        viewer.accept("[EvoSuite] Native scaffolding written: " + srcTestJava.toPath().resolve(testDir.toPath().relativize(scaffPath)));
                    }
                    if (nativeTestFqn != null && !nativeTestFqn.isBlank()) {
                        viewer.accept("[EvoSuite] Native test FQN: " + nativeTestFqn);
                    }
                }
            }

            String testOutput = runProjectTests(base, evosuiteJar);
            boolean testSuccess = testOutput != null && testOutput.contains("BUILD SUCCESS");
            String finalStatus = testSuccess ? "tests_passed" : "tests_failed";

            String excerpt = evOut;
            if (excerpt.length() > 6000) excerpt = excerpt.substring(0, 6000) + "\n...<truncated>...";
            if (hasFailureMarker && viewer != null && !hasGeneratedTests) {
                viewer.accept("[EvoSuite] generation reported failure markers without producing tests.");
            }

            return "[EVOSUITE]\n"
                    + "exitCode=" + exit + "\n"
                    + "status=" + finalStatus + "\n"
                    + "generatedTests=" + hasGeneratedTests + "\n"
                    + "outputDir=" + outRoot.getAbsolutePath() + "\n"
                    + "---output---\n"
                    + testOutput;
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

    private void ensureTestDependencies(File baseDir, File evosuiteJar) {
        try {
            if (project == null || project.isDisposed()) return;

            File junit = materializeResourceToProjectLib(baseDir, "tools/junit-4.12.jar", "junit-4.12.jar");
            File hamcrest = materializeResourceToProjectLib(baseDir, "tools/hamcrest-core-1.3.jar", "hamcrest-core-1.3.jar");

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

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            Module[] modules = ModuleManager.getInstance(project).getModules();
                            if (modules == null || modules.length == 0) return;
                            Module module = modules[0];

                            ModifiableRootModel model = ModuleRootManager.getInstance(module).getModifiableModel();

                            try {
                                if (baseDir != null) {
                                    File srcTestJava = new File(baseDir, "test");
                                    if (srcTestJava.exists()) {
                                        String url = VfsUtil.pathToUrl(srcTestJava.getAbsolutePath());
                                        for (ContentEntry ce : model.getContentEntries()) {
                                            if (ce == null) continue;

                                            boolean already = false;
                                            for (SourceFolder sf : ce.getSourceFolders()) {
                                                if (sf != null && url.equals(sf.getUrl()) && sf.isTestSource()) {
                                                    already = true;
                                                    break;
                                                }
                                            }
                                            if (!already) {
                                                try {
                                                    VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(srcTestJava);
                                                    if (vf != null) {
                                                        VirtualFile root = ce.getFile();
                                                        if (root != null && VfsUtilCore.isAncestor(root, vf, true)) {
                                                            ce.addSourceFolder(url, true);
                                                            break;
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    logBestEffortFailure("mark generated test source root", e);
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logBestEffortFailure("configure generated test source root", e);
                            }

                            final String libName = "AntiCopyPaster-TestLib";
                            LibraryTable libraryTable = model.getModuleLibraryTable();
                            Library existing = libraryTable.getLibraryByName(libName);

                            if (existing == null) {
                                Library lib = libraryTable.createLibrary(libName);
                                Library.ModifiableModel lm = lib.getModifiableModel();

                                try {
                                    if (finalJunit != null && finalJunit.exists()) {
                                        lm.addRoot(VfsUtil.getUrlForLibraryRoot(finalJunit), OrderRootType.CLASSES);
                                    }
                                    if (finalHamcrest != null && finalHamcrest.exists()) {
                                        lm.addRoot(VfsUtil.getUrlForLibraryRoot(finalHamcrest), OrderRootType.CLASSES);
                                    }
                                    if (finalEvo != null && finalEvo.exists()) {
                                        lm.addRoot(VfsUtil.getUrlForLibraryRoot(finalEvo), OrderRootType.CLASSES);
                                    }
                                } catch (Exception e) {
                                    logBestEffortFailure("add test library roots", e);
                                }

                                lm.commit();

                                LibraryOrderEntry entry = model.addLibraryEntry(lib);
                                entry.setScope(DependencyScope.TEST);
                            } else {
                                try {
                                    for (OrderEntry oe : model.getOrderEntries()) {
                                        if (oe instanceof LibraryOrderEntry loe) {
                                            if (loe.getLibraryName() != null && libName.equals(loe.getLibraryName())) {
                                                loe.setScope(DependencyScope.TEST);
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    logBestEffortFailure("set test library scope", e);
                                }

                                try {
                                    Library.ModifiableModel lm = existing.getModifiableModel();
                                    for (String url : lm.getUrls(OrderRootType.CLASSES)) {
                                        try {
                                            VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
                                            if (vf == null || !vf.exists()) {
                                                lm.removeRoot(url, OrderRootType.CLASSES);
                                            }
                                        } catch (Exception e) {
                                            logBestEffortFailure("remove stale test library root", e);
                                        }
                                    }
                                    if (finalJunit != null && finalJunit.exists()) {
                                        lm.addRoot(VfsUtil.getUrlForLibraryRoot(finalJunit), OrderRootType.CLASSES);
                                    }
                                    if (finalHamcrest != null && finalHamcrest.exists()) {
                                        lm.addRoot(VfsUtil.getUrlForLibraryRoot(finalHamcrest), OrderRootType.CLASSES);
                                    }
                                    if (finalEvo != null && finalEvo.exists()) {
                                        lm.addRoot(VfsUtil.getUrlForLibraryRoot(finalEvo), OrderRootType.CLASSES);
                                    }
                                    lm.commit();
                                } catch (Exception e) {
                                    logBestEffortFailure("refresh test library roots", e);
                                }
                            }

                            model.commit();
                        } catch (Exception e) {
                            logBestEffortFailure("commit test library configuration", e);
                        }
                    });
                } catch (Exception e) {
                    logBestEffortFailure("schedule test library configuration", e);
                }
            }, ModalityState.any());
        } catch (Exception e) {
            logBestEffortFailure("configure test library", e);
        }
    }

    private String runProjectTests(File baseDir, File evosuiteJar) {
        try {
            File pom = new File(baseDir, "pom.xml");
            File gradle = new File(baseDir, "build.gradle");
            File gradleKts = new File(baseDir, "build.gradle.kts");

            if ((pom.exists() || gradle.exists() || gradleKts.exists())
                    && (lastConvertedTestFqn == null || lastConvertedTestFqn.isBlank())) {
                ProcessBuilder pb;
                if (pom.exists()) {
                    pb = new ProcessBuilder("mvn", "-q", "test");
                } else {
                    boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                    String exe = new File(baseDir, isWin ? "gradlew.bat" : "gradlew").exists()
                            ? (isWin ? ".\\gradlew.bat" : "./gradlew")
                            : "gradle";
                    pb = new ProcessBuilder(exe, "test");
                }
                pb.directory(baseDir);
                pb.redirectErrorStream(true);

                if (isCancelled()) return "[CANCELLED]\n";
                if (viewer != null) viewer.accept("[TEST] Running build tool tests...");
                ProcessRun pr = runProcessStreaming(pb);
                return pr == null ? "" : (pr.output == null ? "" : pr.output);
            }

            if (viewer != null) viewer.accept("[TEST] No build tool found. Running via JUnitCore...");

            if (project == null || project.isDisposed()) {
                return "Error: IDE project is unavailable.";
            }

            String testFqn = lastConvertedTestFqn;
            File testFile = null;

            if (testFqn != null) {
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

            String cp = buildProjectClasspathFromIde();
            if (lastPatchedClassesDir != null && !lastPatchedClassesDir.isBlank()) {
                cp = lastPatchedClassesDir + File.pathSeparator + (cp == null ? "" : cp);
            }
            if (lastEvoSuiteFallbackClassesDir != null && !lastEvoSuiteFallbackClassesDir.isBlank()) {
                cp = lastEvoSuiteFallbackClassesDir + File.pathSeparator + (cp == null ? "" : cp);
            }

            File junit = materializeResourceToProjectLib(baseDir, "tools/junit-4.12.jar", "junit-4.12.jar");
            File hamcrest = materializeResourceToProjectLib(baseDir, "tools/hamcrest-core-1.3.jar", "hamcrest-core-1.3.jar");
            File evoRuntime = evosuiteJar;

            if (hamcrest == null || !hamcrest.exists()) {
                File libHamcrest = new File(baseDir, "libs" + File.separator + "hamcrest-core-1.3.jar");
                if (libHamcrest.exists()) {
                    hamcrest = libHamcrest;
                } else {
                    File m2Hamcrest = new File(
                            System.getProperty("user.home"),
                            ".m2" + File.separator + "repository" + File.separator
                                    + "org" + File.separator + "hamcrest" + File.separator
                                    + "hamcrest-core" + File.separator + "1.3" + File.separator
                                    + "hamcrest-core-1.3.jar"
                    );
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

            if (viewer != null) viewer.accept("[TEST] Compiling generated test (native EvoSuite)...");
            File compiledClassesDir;
            try {
                List<File> sources = new ArrayList<>();
                sources.add(testFile);
                File bootstrapRunner = writeEvoSuiteBootstrapRunner(baseDir);
                if (bootstrapRunner != null && bootstrapRunner.exists()) {
                    sources.add(bootstrapRunner);
                }

                try {
                    String tf = testFile.getName();
                    if (tf.endsWith("_ESTest.java")) {
                        String base = tf.substring(0, tf.length() - "_ESTest.java".length());
                        File scaff = new File(testFile.getParentFile(), base + "_ESTest_scaffolding.java");
                        if (scaff.exists()) sources.add(scaff);
                    }
                } catch (Exception e) {
                    logBestEffortFailure("add EvoSuite scaffolding source for compilation", e);
                }

                compiledClassesDir = compileFiles(sources, runCp);
            } catch (Exception e) {
                return "Compilation Error: " + e.getMessage();
            }

            runCp = compiledClassesDir.getAbsolutePath() + File.pathSeparator + runCp;

            String javaExe = resolveJavaExecutable();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(runCp);
            if (lastTestBootstrapClass != null && !lastTestBootstrapClass.isBlank()) {
                cmd.add("-Dacp.test.bootstrap.class=" + lastTestBootstrapClass);
                if (lastTestBootstrapMethod != null && !lastTestBootstrapMethod.isBlank()) {
                    cmd.add("-Dacp.test.bootstrap.method=" + lastTestBootstrapMethod);
                }
                if (viewer != null) {
                    viewer.accept("[TEST] Using requested test bootstrap: "
                            + lastTestBootstrapClass
                            + "."
                            + ((lastTestBootstrapMethod == null || lastTestBootstrapMethod.isBlank())
                            ? "bootstrap"
                            : lastTestBootstrapMethod));
                }
            }
            cmd.add("org.jetbrains.research.anticopypaster.generated.AcpEvoSuiteJUnit4Runner");
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

            ProcessRun pr = runProcessStreaming(jpb);
            String out = pr == null || pr.output == null ? "" : pr.output;

            if (out.contains("OK (") && !containsAnyIgnoreCase(out, "FAILURES!!!")) {
                return out + "\nBUILD SUCCESS\n";
            }
            if (!lastDisableEvoSuiteStaticReset && shouldRetryWithoutEvoSuiteReset(out)) {
                if (viewer != null) {
                    viewer.accept("[TEST] EvoSuite reset/sandbox failure detected; retrying once with static reset disabled.");
                }
                lastDisableEvoSuiteStaticReset = true;
                patchEvoSuiteScaffoldingForTest(testFile);
                return runProjectTests(baseDir, evosuiteJar);
            }
            return out + "\nBUILD FAILED\n";
        } catch (Exception e) {
            return "Execution Error: " + e.getMessage();
        }
    }

    private File writeEvoSuiteBootstrapRunner(File baseDir) throws IOException {
        File bootstrapDir = new File(
                baseDir,
                ".anticopypaster" + File.separator + "evosuite-tests" + File.separator
                        + "bootstrap" + File.separator + "org" + File.separator + "jetbrains"
                        + File.separator + "research" + File.separator + "anticopypaster"
                        + File.separator + "generated"
        );
        if (!bootstrapDir.exists() && !bootstrapDir.mkdirs()) {
            throw new IOException("Failed to create EvoSuite bootstrap dir: " + bootstrapDir.getAbsolutePath());
        }

        File runner = new File(bootstrapDir, "AcpEvoSuiteJUnit4Runner.java");
        String source = """
                package org.jetbrains.research.anticopypaster.generated;

                import java.lang.reflect.Method;
                import java.util.List;
                import org.junit.runner.Description;
                import org.junit.runner.JUnitCore;
                import org.junit.runner.Result;
                import org.junit.runner.notification.Failure;
                import org.junit.runner.notification.RunListener;

                public final class AcpEvoSuiteJUnit4Runner {
                    private AcpEvoSuiteJUnit4Runner() {
                    }

                    public static void main(String[] args) throws Exception {
                        if (args == null || args.length == 0 || args[0] == null || args[0].trim().isEmpty()) {
                            System.out.println("Error: missing test class FQN");
                            System.exit(2);
                        }

                        runBootstrap();

                        Class<?> testClass = Class.forName(args[0]);
                        long start = System.currentTimeMillis();
                        JUnitCore core = new JUnitCore();
                        core.addListener(new RunListener() {
                            @Override
                            public void testStarted(Description description) {
                                runBootstrap();
                            }
                        });
                        Result result = core.run(testClass);
                        double seconds = (System.currentTimeMillis() - start) / 1000.0d;

                        System.out.println("JUnit version 4");
                        System.out.printf("Time: %.3f%n", seconds);
                        List<Failure> failures = result.getFailures();
                        if (failures.isEmpty()) {
                            System.out.println();
                            System.out.println("OK (" + result.getRunCount() + " tests)");
                            return;
                        }

                        System.out.println("There were " + failures.size() + " failures:");
                        for (int i = 0; i < failures.size(); i++) {
                            Failure failure = failures.get(i);
                            System.out.println((i + 1) + ") " + failure.getTestHeader());
                            Throwable exception = failure.getException();
                            if (exception != null) {
                                exception.printStackTrace(System.out);
                            } else {
                                System.out.println(failure.toString());
                            }
                        }
                        System.out.println();
                        System.out.println("FAILURES!!!");
                        System.out.println("Tests run: " + result.getRunCount() + ",  Failures: " + failures.size());
                        System.exit(1);
                    }

                    private static void runBootstrap() {
                        String className = System.getProperty("acp.test.bootstrap.class", "").trim();
                        if (className.isEmpty()) {
                            return;
                        }
                        String methodName = System.getProperty("acp.test.bootstrap.method", "bootstrap").trim();
                        if (methodName.isEmpty()) {
                            methodName = "bootstrap";
                        }
                        try {
                            Class<?> bootstrapClass = Class.forName(className);
                            Method method = bootstrapClass.getDeclaredMethod(methodName);
                            method.setAccessible(true);
                            method.invoke(null);
                        } catch (Exception t) {
                            System.out.println("[TEST] WARN: bootstrap failed: " + t);
                        }
                    }
                }
                """;
        Files.writeString(runner.toPath(), source, StandardCharsets.UTF_8);
        return runner;
    }

    private String disableEvoSuiteResetClasses(String scaffolding) {
        if (scaffolding == null || scaffolding.isBlank()) {
            return scaffolding == null ? "" : scaffolding;
        }
        return scaffolding.replaceAll(
                "(?m)^(\\s*)resetClasses\\(\\);\\s*$",
                "$1// resetClasses() disabled by AntiCopyPaster request"
        );
    }

    private boolean shouldRetryWithoutEvoSuiteReset(String output) {
        return containsAnyIgnoreCase(
                output,
                "org.evosuite.runtime.classhandling.classresetter.reset",
                "_estest_scaffolding.resetclasses",
                "trying to set up the sandbox while executing a test case"
        );
    }

    private void patchEvoSuiteScaffoldingForTest(File testFile) {
        try {
            if (testFile == null) {
                logEvoSuiteScaffoldingPatchWarning(null, "test file is null");
                return;
            }
            if (!testFile.exists()) {
                logEvoSuiteScaffoldingPatchWarning(testFile, "test file does not exist");
                return;
            }
            String name = testFile.getName();
            if (!name.endsWith("_ESTest.java")) {
                logEvoSuiteScaffoldingPatchWarning(testFile, "unexpected test file name: " + name);
                return;
            }
            String base = name.substring(0, name.length() - "_ESTest.java".length());
            File parent = testFile.getParentFile();
            if (parent == null) {
                logEvoSuiteScaffoldingPatchWarning(testFile, "test file has no parent directory");
                return;
            }
            File scaffoldingFile = new File(parent, base + "_ESTest_scaffolding.java");
            if (!scaffoldingFile.exists()) {
                logEvoSuiteScaffoldingPatchWarning(testFile, "scaffolding file not found: " + scaffoldingFile.getAbsolutePath());
                return;
            }
            String scaffolding = Files.readString(scaffoldingFile.toPath(), StandardCharsets.UTF_8);
            String patched = disableEvoSuiteResetClasses(scaffolding);
            if (!patched.equals(scaffolding)) {
                Files.writeString(scaffoldingFile.toPath(), patched, StandardCharsets.UTF_8);
            } else if (!scaffolding.contains("resetClasses() disabled by AntiCopyPaster request")) {
                logEvoSuiteScaffoldingPatchWarning(testFile, "resetClasses() call not found in " + scaffoldingFile.getAbsolutePath());
            }
        } catch (IOException | SecurityException e) {
            logEvoSuiteScaffoldingPatchWarning(testFile, e.getMessage());
        }
    }

    private void logEvoSuiteScaffoldingPatchWarning(File testFile, String reason) {
        String testPath = testFile == null ? "<unknown>" : testFile.getAbsolutePath();
        String message = "[TEST] WARN: failed to patch EvoSuite scaffolding for "
                + testPath + ": " + (reason == null || reason.isBlank() ? "unknown error" : reason);
        if (viewer != null) {
            viewer.accept(message);
        }
        WorkflowUiSupport.logStage("TEST", message);
    }

    private static void logBestEffortFailure(String action, Exception e) {
        String detail = e == null || e.getMessage() == null || e.getMessage().isBlank()
                ? ""
                : ": " + e.getMessage();
        WorkflowUiSupport.logStage("WORKFLOW", "[WORKFLOW] WARN: " + action + " failed" + detail);
    }

    private String ensureTargetClassOnClasspath(String targetClass, String classpath, File outRoot) {
        try {
            if (targetClass == null || targetClass.isBlank()) {
                return null;
            }
            if (classExistsOnClasspath(targetClass, classpath)) {
                return null;
            }

            File sourceFile = findSourceFileForClass(targetClass);
            if (sourceFile == null || !sourceFile.exists()) {
                if (viewer != null) {
                    viewer.accept("[TEST] WARN: target class not found on classpath and source file could not be located: " + targetClass);
                }
                return null;
            }

            if (viewer != null) {
                viewer.accept("[TEST] target class missing from classpath; compiling source before EvoSuite: " + sourceFile.getAbsolutePath());
            }

            File outputDir = new File(outRoot, "target-classes");
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                if (viewer != null) {
                    viewer.accept("[TEST] WARN: failed to create fallback classes dir: " + outputDir.getAbsolutePath());
                }
                return null;
            }

            String compileOut = compileSourceFileToDir(sourceFile, classpath, outputDir);
            if (classExistsOnClasspath(targetClass, outputDir.getAbsolutePath())) {
                return outputDir.getAbsolutePath();
            }

            if (viewer != null) {
                String excerpt = compileOut == null ? "" : compileOut;
                if (excerpt.length() > 1600) excerpt = excerpt.substring(0, 1600) + "\n...<truncated>...";
                viewer.accept("[TEST] WARN: fallback compile finished but target class is still missing: " + targetClass
                        + (excerpt.isBlank() ? "" : "\n" + excerpt));
            }
        } catch (Exception t) {
            if (viewer != null) {
                viewer.accept("[TEST] WARN: failed to compile missing target class for EvoSuite: " + t.getMessage());
            }
        }
        return null;
    }

    private boolean classExistsOnClasspath(String classFqn, String classpath) {
        if (classFqn == null || classFqn.isBlank() || classpath == null || classpath.isBlank()) {
            return false;
        }
        String rel = classFqn.replace('.', '/') + ".class";
        String[] entries = classpath.split(Pattern.quote(File.pathSeparator));
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            File file = new File(entry);
            if (!file.exists()) {
                continue;
            }
            try {
                if (file.isDirectory()) {
                    if (new File(file, rel.replace('/', File.separatorChar)).exists()) {
                        return true;
                    }
                } else if (file.isFile() && entry.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    try (JarFile jar = new JarFile(file)) {
                        if (jar.getEntry(rel) != null) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                logBestEffortFailure("inspect classpath entry for class", e);
            }
        }
        return false;
    }

    private File findSourceFileForClass(String classFqn) {
        if (classFqn == null || classFqn.isBlank()) {
            return null;
        }
        String rel = classFqn.replace('.', File.separatorChar) + ".java";
        String sourcepath = buildProjectSourcepathFromIde();
        if (sourcepath == null || sourcepath.isBlank()) {
            return null;
        }
        String[] roots = sourcepath.split(Pattern.quote(File.pathSeparator));
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            File candidate = new File(root, rel);
            if (candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private String compileSourceFileToDir(File sourceFile, String classpath, File outputDir) throws Exception {
        if (project == null || project.isDisposed()) {
            throw new RuntimeException("Cannot compile target class: project is null or disposed");
        }

        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null || sdk.getHomePath() == null || sdk.getHomePath().isBlank()) {
            throw new RuntimeException("Cannot resolve Project SDK for target class compilation");
        }

        File javacFile = new File(
                sdk.getHomePath(),
                "bin" + File.separator + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javac.exe" : "javac")
        );
        if (!javacFile.exists()) {
            throw new RuntimeException("javac not found under Project SDK: " + javacFile.getAbsolutePath());
        }

        String sourcepath = buildProjectSourcepathFromIde();
        List<String> cmd = new ArrayList<>();
        cmd.add(javacFile.getAbsolutePath());
        cmd.add("-encoding");
        cmd.add("UTF-8");
        addJavacTargetFlags(cmd, sdk.getHomePath(), resolveProjectTargetMajor());
        cmd.add("-cp");
        cmd.add(classpath == null ? "" : classpath);
        if (sourcepath != null && !sourcepath.isBlank()) {
            cmd.add("-sourcepath");
            cmd.add(sourcepath);
        }
        cmd.add("-d");
        cmd.add(outputDir.getAbsolutePath());
        cmd.add(sourceFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String out = readProcessOutput(process);
        int code;
        try {
            code = process.exitValue();
        } catch (IllegalThreadStateException t) {
            logBestEffortFailure("read generated test javac process exit code", t);
            code = -1;
        }
        if (code != 0 && out != null && out.contains("unmappable character")) {
            List<String> retryCmd = new ArrayList<>(cmd);
            for (int i = 0; i < retryCmd.size() - 1; i++) {
                if ("-encoding".equals(retryCmd.get(i))) {
                    retryCmd.set(i + 1, "ISO-8859-1");
                    break;
                }
            }
            ProcessBuilder retryBuilder = new ProcessBuilder(retryCmd);
            retryBuilder.redirectErrorStream(true);
            Process retryProcess = retryBuilder.start();
            out = readProcessOutput(retryProcess);
            try {
                code = retryProcess.exitValue();
            } catch (IllegalThreadStateException t) {
                logBestEffortFailure("read generated test javac retry process exit code", t);
                code = -1;
            }
        }
        if (code != 0) {
            throw new RuntimeException("Compilation failed:\n" + out);
        }
        return out == null ? "" : out;
    }

    private File compileFiles(List<File> sourceFiles, String classpath) throws Exception {
        File outputDir = Files.createTempDirectory("temp_test_classes").toFile();
        outputDir.deleteOnExit();

        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new RuntimeException("Compilation failed: no source files provided");
        }
        for (File file : sourceFiles) {
            if (file == null || !file.exists()) {
                throw new RuntimeException("Compilation failed: missing source file: " + (file == null ? "null" : file.getAbsolutePath()));
            }
        }

        if (project == null || project.isDisposed()) {
            throw new RuntimeException("Cannot compile tests: project is null or disposed");
        }

        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null || sdk.getHomePath() == null || sdk.getHomePath().isBlank()) {
            throw new RuntimeException("Cannot resolve Project SDK for test compilation");
        }

        File javacFile = new File(
                sdk.getHomePath(),
                "bin" + File.separator + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "javac.exe" : "javac")
        );
        if (!javacFile.exists()) {
            throw new RuntimeException("javac not found under Project SDK: " + javacFile.getAbsolutePath());
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javacFile.getAbsolutePath());
        cmd.add("-encoding");
        cmd.add("UTF-8");
        addJavacTargetFlags(cmd, sdk.getHomePath(), resolveProjectTargetMajor());
        cmd.add("-cp");
        cmd.add(classpath == null ? "" : classpath);
        cmd.add("-d");
        cmd.add(outputDir.getAbsolutePath());
        for (File file : sourceFiles) {
            cmd.add(file.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String out = readProcessOutput(process);
        int code;
        try {
            code = process.exitValue();
        } catch (IllegalThreadStateException t) {
            logBestEffortFailure("read single-file javac process exit code", t);
            code = -1;
        }
        if (code != 0) {
            throw new RuntimeException("Compilation failed:\n" + out);
        }
        return outputDir;
    }

    private ProcessRun runProcessStreaming(ProcessBuilder pb) throws Exception {
        Process process = null;
        StringBuilder sb = new StringBuilder();
        Thread killer = null;

        try {
            process = pb.start();
            currentProcessRef.set(process);
            final Process proc = process;

            killer = new Thread(() -> {
                try {
                    while (proc.isAlive()) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) {
                            try {
                                if (viewer != null) viewer.accept("[WORKFLOW] Cancel requested; killing process...");
                            } catch (Exception e) {
                                logBestEffortFailure("log process cancellation", e);
                            }
                            try {
                                proc.destroy();
                            } catch (Exception e) {
                                logBestEffortFailure("destroy cancelled process", e);
                            }
                            try {
                                if (proc.isAlive()) proc.destroyForcibly();
                            } catch (Exception e) {
                                logBestEffortFailure("forcibly destroy cancelled process", e);
                            }
                            break;
                        }
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    logBestEffortFailure("monitor process cancellation", e);
                }
            }, "acp-cancel-killer");
            killer.setDaemon(true);
            killer.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
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
            } catch (Exception e) {
                logBestEffortFailure("read process output", e);
            }

            int exit;
            try {
                exit = proc.waitFor();
            } catch (InterruptedException e) {
                try {
                    proc.destroy();
                } catch (Exception destroyException) {
                    logBestEffortFailure("destroy interrupted process", destroyException);
                }
                try {
                    if (proc.isAlive()) proc.destroyForcibly();
                } catch (Exception destroyException) {
                    logBestEffortFailure("forcibly destroy interrupted process", destroyException);
                }
                Thread.currentThread().interrupt();
                throw e;
            } catch (RuntimeException t) {
                logBestEffortFailure("wait for process", t);
                exit = -1;
            }

            if (isCancelled() || Thread.currentThread().isInterrupted()) {
                try {
                    proc.destroy();
                } catch (Exception destroyException) {
                    logBestEffortFailure("destroy cancelled process after output read", destroyException);
                }
                try {
                    if (proc.isAlive()) proc.destroyForcibly();
                } catch (Exception destroyException) {
                    logBestEffortFailure("forcibly destroy cancelled process after output read", destroyException);
                }
                return new ProcessRun(-1, sb + "\n[CANCELLED]\n");
            }

            return new ProcessRun(exit, sb.toString());
        } finally {
            if (killer != null) {
                try {
                    killer.interrupt();
                } catch (Exception e) {
                    logBestEffortFailure("interrupt process cancellation monitor", e);
                }
            }
            if (process != null) {
                currentProcessRef.compareAndSet(process, null);
            }
        }
    }

    private String resolveJavaExecutable() {
        String java8 = System.getenv("JAVA_8_HOME");
        if (java8 != null && !java8.isBlank()) {
            File f = new File(java8, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
            File fExe = new File(java8, "bin" + File.separator + "java.exe");
            if (fExe.exists()) return fExe.getAbsolutePath();
        }

        try {
            if (project != null && !project.isDisposed()) {
                Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
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
        } catch (Exception e) {
            logBestEffortFailure("resolve Java executable from project SDK", e);
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            File f = new File(javaHome, "bin" + File.separator + "java");
            if (f.exists()) return f.getAbsolutePath();
            File fExe = new File(javaHome, "bin" + File.separator + "java.exe");
            if (fExe.exists()) return fExe.getAbsolutePath();
        }

        return "java";
    }

    private int resolveProjectTargetMajor() {
        try {
            if (project == null || project.isDisposed()) return 8;
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null) {
                try {
                    String vs = sdk.getVersionString();
                    int m = parseMajorFromText(vs);
                    if (m > 0) return m;
                } catch (Exception e) {
                    logBestEffortFailure("parse project SDK version", e);
                }

                try {
                    String home = sdk.getHomePath();
                    String javaExe = javaExecutableFromSdkHome(home);
                    if (javaExe != null && !javaExe.isBlank()) {
                        String out = readJavaVersion(javaExe);
                        int m = parseJavaMajorVersion(out);
                        if (m > 0) return m;
                    }
                } catch (Exception e) {
                    logBestEffortFailure("read project SDK Java version", e);
                }
            }
        } catch (Exception e) {
            logBestEffortFailure("resolve project target Java major", e);
        }
        return 8;
    }

    private boolean isCancelled() {
        return (isCancelled != null && isCancelled.getAsBoolean()) || Thread.currentThread().isInterrupted();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class ProcessRun {
        final int exitCode;
        final String output;

        ProcessRun(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static File materializeResourceToTempFile(String resourcePath, String prefix, String suffix) throws IOException {
        InputStream in = WorkflowJavaBuildSupport.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) return null;
        File tmp = File.createTempFile(prefix, suffix);
        tmp.deleteOnExit();
        try (in; OutputStream out = new FileOutputStream(tmp)) {
            in.transferTo(out);
        }
        return tmp;
    }

    private static File materializeResourceToProjectLib(File baseDir, String resourcePath, String fileName) {
        try {
            if (baseDir == null) {
                return materializeResourceToTempFile(resourcePath, "acp-lib", ".jar");
            }
            File libDir = new File(baseDir, ".anticopypaster" + File.separator + "ide-libs");
            if (!libDir.exists()) libDir.mkdirs();
            File out = new File(libDir, fileName);
            if (out.exists() && out.length() > 0) return out;

            try (InputStream in = WorkflowJavaBuildSupport.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) return null;
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        os.write(buf, 0, r);
                    }
                }
            }
            return out.exists() ? out : null;
        } catch (Exception e) {
            logBestEffortFailure("materialize bundled library resource", e);
            return null;
        }
    }

    private static String getReqString(Object req, String... names) {
        if (req == null || names == null) return null;
        Class<?> cls = req.getClass();

        for (String n : names) {
            if (n == null || n.isBlank()) continue;

            try {
                java.lang.reflect.Field f = cls.getField(n);
                Object v = f.get(req);
                if (v != null) return String.valueOf(v);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next accessor shape.
            }

            try {
                java.lang.reflect.Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(req);
                if (v != null) return String.valueOf(v);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next accessor shape.
            }

            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(req);
                if (v != null) return String.valueOf(v);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next accessor shape.
            }

            try {
                String mname = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getDeclaredMethod(mname);
                m.setAccessible(true);
                Object v = m.invoke(req);
                if (v != null) return String.valueOf(v);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next accessor shape.
            }

            try {
                String mname = "is" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                java.lang.reflect.Method m = cls.getMethod(mname);
                Object v = m.invoke(req);
                if (v != null) return String.valueOf(v);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Try the next accessor shape.
            }
        }
        return null;
    }

    private static boolean getReqBoolean(Object req, boolean defaultValue, String... names) {
        String value = getReqString(req, names);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static String deriveJavaHome(String javaExe) {
        if (javaExe == null || javaExe.isBlank()) return null;
        File f = new File(javaExe);
        File bin = f.getParentFile();
        if (bin != null && "bin".equalsIgnoreCase(bin.getName())) {
            File home = bin.getParentFile();
            if (home != null && home.exists()) {
                return home.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean containsAnyIgnoreCase(String haystack, String... needles) {
        if (haystack == null) return false;
        String h = haystack.toLowerCase(Locale.ROOT);
        if (needles == null) return false;
        for (String n : needles) {
            if (n == null) continue;
            if (h.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String readProcessOutput(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        p.waitFor();
        return sb.toString();
    }

    private static String readJavaVersion(String javaExe) {
        if (javaExe == null || javaExe.isBlank()) return "";
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return readProcessOutput(p);
        } catch (Exception t) {
            logBestEffortFailure("read Java version", t);
            return "";
        }
    }

    private static int parseJavaMajorVersion(String javaVersionOutput) {
        if (javaVersionOutput == null || javaVersionOutput.isBlank()) return -1;

        Matcher m = Pattern.compile("version\\s+\\\"([^\\\"]+)\\\"").matcher(javaVersionOutput);
        if (!m.find()) return -1;

        String ver = m.group(1);
        if (ver == null) return -1;
        ver = ver.trim();

        try {
            if (ver.startsWith("1.")) {
                String[] parts = ver.split("\\.");
                if (parts.length >= 2) return Integer.parseInt(parts[1]);
                return -1;
            }
            String[] parts = ver.split("\\.");
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseMajorFromText(String s) {
        if (s == null || s.isBlank()) return -1;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("1.8")) return 8;
        Matcher m = Pattern.compile("(\\d{1,2})").matcher(t);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 8 && v <= 99) return v;
            } catch (NumberFormatException e) {
                logBestEffortFailure("parse Java major from text", e);
            }
        }
        return -1;
    }

    private static String javaExecutableFromSdkHome(String home) {
        try {
            if (home == null || home.isBlank()) return "";
            boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            File f = new File(home, "bin" + File.separator + (isWin ? "java.exe" : "java"));
            return f.exists() ? f.getAbsolutePath() : "";
        } catch (RuntimeException t) {
            logBestEffortFailure("resolve Java executable from SDK home", t);
            return "";
        }
    }

    private static void addJavacTargetFlags(List<String> cmd, String sdkHome, int targetMajor) {
        if (cmd == null) return;
        int target = targetMajor > 0 ? targetMajor : 8;

        int javacMajor = -1;
        try {
            String javaExe = javaExecutableFromSdkHome(sdkHome);
            if (javaExe != null && !javaExe.isBlank()) {
                String out = readJavaVersion(javaExe);
                javacMajor = parseJavaMajorVersion(out);
            }
        } catch (Exception e) {
            logBestEffortFailure("read javac Java version", e);
        }
        if (javacMajor <= 0) javacMajor = target;

        if (javacMajor >= 9) {
            cmd.add("--release");
            cmd.add(String.valueOf(target));
        } else {
            cmd.add("-source");
            cmd.add(target == 8 ? "1.8" : String.valueOf(target));
            cmd.add("-target");
            cmd.add(target == 8 ? "1.8" : String.valueOf(target));
        }
    }

    private static Path findFileRecursivelyByName(Path root, String fileName) {
        if (root == null || fileName == null || fileName.isBlank()) return null;
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p != null && p.getFileName() != null && fileName.equals(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException | RuntimeException t) {
            logBestEffortFailure("find file by name", t);
            return null;
        }
    }

    private static Path findFirstFileBySuffix(Path root, String suffix) {
        if (root == null || suffix == null || suffix.isBlank()) return null;
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p != null && p.toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        } catch (IOException | RuntimeException t) {
            logBestEffortFailure("find file by suffix", t);
            return null;
        }
    }

    private static boolean looksLikeLiteralNNewlineCorruption(String code) {
        if (code == null) return false;
        return code.contains("npackage ")
                || code.contains(";n")
                || code.contains("{n")
                || code.contains("}n")
                || code.contains(")n");
    }

    private static String repairLiteralNNewlineCorruption(String code) {
        if (code == null) return "";

        String s = code;
        s = s.replace(";n", ";\n");
        s = s.replace(")n", ")\n");
        s = s.replace("{n", "{\n");
        s = s.replace("}n", "}\n");
        s = s.replaceAll("(?m)^[\\t ]*n(?=package\\s)", "");
        s = s.replaceAll("(?m)^[\\t ]*n(?=import\\s)", "");
        s = s.replaceAll("(?m)^[\\t ]*n(?=(public|private|protected)\\b)", "");
        s = s.replaceAll("(?m)^[\\t ]*n(?=(class|interface|enum|record)\\b)", "");
        s = s.replace("npackage ", "\npackage ");
        s = s.replace("nimport ", "\nimport ");
        return s;
    }

    private static String normalizeBrokenNewlines(String code) {
        if (code == null) return "";
        String out = code.replace("\r\n", "\n").replace("\r", "\n");
        out = out.replaceAll(";\\s*n+", ";\n");
        out = out.replaceAll("\\{\\s*n+", "{\n");
        out = out.replaceAll("\\}\\s*n+", "}\n");
        return out;
    }

    private static boolean containsJavaFilesUnder(Path dir, int maxDepth) {
        if (dir == null || maxDepth < 0) return false;
        try (Stream<Path> s = Files.walk(dir, maxDepth)) {
            return s.anyMatch(p -> {
                try {
                    return p != null
                            && Files.isRegularFile(p)
                            && p.getFileName() != null
                            && p.getFileName().toString().endsWith(".java");
                } catch (RuntimeException e) {
                    logBestEffortFailure("inspect Java source candidate", e);
                    return false;
                }
            });
        } catch (IOException | RuntimeException t) {
            logBestEffortFailure("scan for Java files", t);
            return false;
        }
    }
}
