package org.jetbrains.research.anticopypaster.ide;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import org.jetbrains.research.anticopypaster.agents.compile;
import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.agents.refactor;
import org.jetbrains.research.anticopypaster.agents.testing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;

/**
 * AiderHelper (Aider-free version)
 *
 * This class orchestrates the multi-agent workflow:
 *   Detection -> Refactor -> Compile -> Test
 *
 * All LLM calls are done via a direct API stub (callLlmDirect),
 * NOT via Aider or any external CLI tool.
 */
public class AiderHelper {

    /* =========================
     * Entry point (example hook)
     * ========================= */
    public static void runMultiAgentWorkflow(Project project,
                                             String fileName,
                                             String originalFilePath,
                                             detection.DetectionResult det) {

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                File originalFile = new File(originalFilePath);
                String originalSource = Files.readString(originalFile.toPath(), StandardCharsets.UTF_8);

                // temp working copy
                File tempFile = File.createTempFile("anticopypaster_", ".java");
                Files.writeString(tempFile.toPath(), originalSource, StandardCharsets.UTF_8);

                notify(project, "Multi-agent workflow started for " + fileName);

                // === LLM caller (NO AIDER) ===
                Function<String, String> llmCaller = AiderHelper::callLlmDirect;

                // === Agents ===
                refactor refactorAgent = new refactor();
                compile compileAgent = new compile();
                testing testAgent = new testing();

                detection.DetectedClone firstClone =
                        (det != null && det.clones != null && !det.clones.isEmpty())
                                ? det.clones.get(0)
                                : null;

                if (firstClone == null) {
                    notify(project, "No clones detected. Abort workflow.");
                    return;
                }

                String currentSource = originalSource;
                String feedback = null;

                for (int attempt = 1; attempt <= 3; attempt++) {
                    System.out.println("=== [WORKFLOW] Attempt " + attempt + " / 3 ===");

                    // -------- Refactor --------
                    refactor.RefactorResult rr = refactorAgent.refactorFile(
                            fileName,
                            currentSource,
                            convertClone(firstClone),
                            feedback,
                            llmCaller
                    );

                    if (rr == null || rr.newSource == null || rr.newSource.isBlank()) {
                        feedback = "Refactor agent failed to generate a valid file.";
                        continue;
                    }

                    currentSource = rr.newSource;
                    Files.writeString(tempFile.toPath(), currentSource, StandardCharsets.UTF_8);

                    // -------- Compile --------
                    File buildRoot = findBuildRoot(project);
                    BuildTool tool = detectBuildTool(buildRoot);

                    String compileLog = runBuildCapture(buildRoot, tool, BuildPhase.COMPILE);
                    compile.CompileResult cr = compileAgent.analyze(fileName, compileLog);

                    if (cr == null || !"compile_ok".equals(cr.status)) {
                        System.out.println("[COMPILE] FAILED");
                        feedback = cr == null ? "Compile failed." : cr.summary;
                        continue;
                    }

                    System.out.println("[COMPILE] OK");

                    // -------- Test --------
                    testing.TestRunRequest treq =
                            new testing.TestRunRequest(
                                    buildRoot != null ? buildRoot.getAbsolutePath() : ".",
                                    "all",
                                    null,
                                    false
                            );

                    Function<testing.TestRunRequest, String> testRunner =
                            (req) -> runBuildCapture(buildRoot, tool, BuildPhase.TEST);

                    testing.TestResult tr = testAgent.runAndSummarize(
                            treq,
                            testRunner,
                            llmCaller,
                            originalSource,
                            currentSource
                    );

                    if (tr != null && "tests_passed".equals(tr.status)) {
                        notify(project, "Workflow succeeded for " + fileName);
                        return;
                    }

                    feedback = (tr == null)
                            ? "Test agent returned null."
                            : (tr.summary != null ? tr.summary : tr.raw);
                }

                notify(project, "Workflow failed after 3 attempts for " + fileName);

            } catch (Exception e) {
                notify(project, "Workflow crashed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /* =========================
     * LLM API (stub)
     * ========================= */

    /**
     * Direct LLM call WITHOUT Aider.
     *
     * Replace this with OpenAI / Azure / Ollama API calls.
     */
    private static String callLlmDirect(String prompt) {
        // ---- PLACEHOLDER ----
        // For now, return empty output so the system can run end-to-end.
        return "";
    }

    /* =========================
     * Build / Compile / Test
     * ========================= */

    private enum BuildTool { MAVEN, GRADLE, ANT, UNKNOWN }
    private enum BuildPhase { COMPILE, TEST }

    private static File findBuildRoot(Project project) {
        if (project == null || project.getBasePath() == null) return null;
        File cur = new File(project.getBasePath());
        for (int i = 0; i < 8 && cur != null; i++) {
            if (new File(cur, "pom.xml").isFile()
                    || new File(cur, "build.gradle").isFile()
                    || new File(cur, "build.gradle.kts").isFile()
                    || new File(cur, "build.xml").isFile()) {
                return cur;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    private static BuildTool detectBuildTool(File root) {
        if (root == null) return BuildTool.UNKNOWN;
        if (new File(root, "pom.xml").isFile()) return BuildTool.MAVEN;
        if (new File(root, "build.gradle").isFile()
                || new File(root, "build.gradle.kts").isFile()) return BuildTool.GRADLE;
        if (new File(root, "build.xml").isFile()) return BuildTool.ANT;
        return BuildTool.UNKNOWN;
    }

    private static String runBuildCapture(File root, BuildTool tool, BuildPhase phase) {
        if (tool == BuildTool.UNKNOWN || root == null) {
            return "Unknown build system. Skipping.";
        }

        try {
            switch (tool) {
                case MAVEN:
                    return runProcess(root,
                            phase == BuildPhase.COMPILE
                                    ? new String[]{"mvn", "-q", "-DskipTests", "compile"}
                                    : new String[]{"mvn", "-q", "test"});
                case GRADLE:
                    return runProcess(root,
                            phase == BuildPhase.COMPILE
                                    ? new String[]{"gradle", "-q", "classes"}
                                    : new String[]{"gradle", "-q", "test"});
                case ANT:
                    return runProcess(root,
                            phase == BuildPhase.COMPILE
                                    ? new String[]{"ant", "-q", "compile"}
                                    : new String[]{"ant", "-q", "test"});
                default:
                    return "Unsupported build tool.";
            }
        } catch (Exception e) {
            return "[BUILD_EXCEPTION] " + e.getMessage();
        }
    }

    private static String runProcess(File dir, String[] command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append("\n");
            }
        }
        p.waitFor();
        return out.toString();
    }

    /* =========================
     * Utilities
     * ========================= */

    private static refactor.DetectedClone convertClone(detection.DetectedClone c) {
        if (c == null) return null;

        List<refactor.CloneRange> convertedRanges = new ArrayList<>();
        if (c.ranges != null) {
            for (detection.CloneRange r : c.ranges) {
                convertedRanges.add(
                        new refactor.CloneRange(
                                r.startLine,
                                r.endLine
                        )
                );
            }
        }

        return new refactor.DetectedClone(
                c.id,
                convertedRanges,
                c.refactorType,
                c.reason
        );
    }

    private static void notify(Project project, String msg) {
        Notifications.Bus.notify(
                new Notification(
                        "AntiCopyPaster",
                        "AntiCopyPaster",
                        msg,
                        NotificationType.INFORMATION
                ),
                project
        );
    }
}