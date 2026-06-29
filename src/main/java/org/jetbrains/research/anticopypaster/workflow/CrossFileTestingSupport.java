package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.safeTruncate;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.intellij.openapi.project.Project;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.testing;
import org.jetbrains.research.anticopypaster.llm.LlmClient;

final class CrossFileTestingSupport {
    private CrossFileTestingSupport() {}

    static final class CrossFileTestResult {
        boolean passed;
        String summary = "";
        String feedback = "";
    }

    static CrossFileTestResult runCrossFileTests(WorkflowJavaBuildSupport javaBuildSupport,
                                                 LlmClient llm,
                                                 Project project,
                                                 Consumer<String> viewer,
                                                 CrossFileClone selectedClone,
                                                 CrossFileRefactorResult refactorResult) {
        CrossFileTestResult result = new CrossFileTestResult();
        if (javaBuildSupport == null || selectedClone == null || selectedClone.affectedSources().isEmpty()) {
            result.summary = "No cross-file test targets were available.";
            result.feedback = result.summary;
            return result;
        }

        testing testAgent = new testing();
        java.util.ArrayList<String> failedTargets = new java.util.ArrayList<>();
        java.util.ArrayList<String> skippedTargets = new java.util.ArrayList<>();
        java.util.ArrayList<String> feedbackParts = new java.util.ArrayList<>();
        java.util.LinkedHashSet<CrossFileSource> targets = selectedClone.affectedSources();

        for (CrossFileSource source : targets) {
            if (source == null) continue;
            String beforeSource = source.source == null ? "" : source.source;
            String afterSource = CrossFileCloneRefactorWorkflow.refactoredSourceForUsefulness(source, refactorResult);
            String fileName = sourceFileName(source);
            String targetFqn = resolveTestTargetFqn(javaBuildSupport, source, afterSource, beforeSource, fileName);

            if (targetFqn.isBlank()) {
                String message = "Test skipped: target class FQN could not be resolved for " + fileName + ".";
                logStage(viewer, "TEST", message);
                failedTargets.add(fileName);
                feedbackParts.add(message);
                continue;
            }

            logStage(viewer, "TEST", "targetFqn=" + targetFqn);
            javaBuildSupport.setTargetFqn(targetFqn);

            testing.TestRunRequest request = new testing.TestRunRequest(
                    project == null ? "" : project.getBasePath(),
                    targetFqn,
                    null,
                    false
            );
            testing.TestResult testResult = testAgent.runAndSummarize(
                    request,
                    javaBuildSupport::runTests,
                    prompt -> WorkflowLlmCallSupport.callUsefulness(llm, "TEST", prompt, viewer, project),
                    beforeSource,
                    afterSource
            );

            if (isTestingInfrastructureSkip(testResult)) {
                skippedTargets.add(targetFqn);
                logStage(viewer, "TEST", "skipped: " + targetFqn
                        + " because the Testing Agent/EvoSuite could not generate runnable tests in this environment");
                continue;
            }

            if (viewer != null && testResult != null) {
                viewer.accept("[TEST] raw output for " + targetFqn + ":\n"
                        + safeTruncate(testResult.raw, 4000));
            }

            if (testResult != null && "tests_passed".equals(testResult.status)) {
                logStage(viewer, "TEST", "passed: " + targetFqn);
                continue;
            }

            failedTargets.add(targetFqn);
            String detail = buildTestFailureFeedback(targetFqn, testResult);
            feedbackParts.add(detail);
            logStage(viewer, "TEST", "failed: " + targetFqn);
        }

        if (failedTargets.isEmpty()) {
            result.passed = true;
            if (skippedTargets.isEmpty()) {
                result.summary = "Cross-file tests passed for " + targets.size() + " target class(es).";
            } else {
                result.summary = "Cross-file tests skipped for "
                        + String.join(", ", skippedTargets)
                        + " because the Testing Agent/EvoSuite could not generate runnable tests in this environment.";
            }
            return result;
        }

        result.summary = "Cross-file tests failed for: " + String.join(", ", failedTargets);
        result.feedback = String.join("\n\n", feedbackParts);
        return result;
    }

    static boolean isTestingInfrastructureSkip(testing.TestResult testResult) {
        if (testResult == null || testResult.raw == null) return false;
        String raw = testResult.raw;
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return (raw.contains("[TEST_SKIPPED]")
                && (lower.contains("tests_skipped")
                || lower.contains("java_version_mismatch")
                || lower.contains("evosuite_generation_failed")))
                || lower.contains("unsupported class file major version")
                || lower.contains("unsupportedclassversionerror")
                || (lower.contains("no converter available")
                && (lower.contains("evosuite")
                || lower.contains("xstream")
                || lower.contains("inaccessibleobjectexception")))
                || (lower.contains("class file version")
                && lower.contains("only recognizes class file versions up to"));
    }

    static boolean isJavaVersionMismatchSkip(testing.TestResult testResult) {
        return isTestingInfrastructureSkip(testResult);
    }

    private static String resolveTestTargetFqn(WorkflowJavaBuildSupport javaBuildSupport,
                                               CrossFileSource source,
                                               String afterSource,
                                               String beforeSource,
                                               String fileName) {
        String targetFqn = javaBuildSupport.resolvePrimaryClassFqn(afterSource, fileName);
        if (targetFqn == null) targetFqn = "";
        if (targetFqn.isBlank()) {
            targetFqn = javaBuildSupport.resolvePrimaryClassFqn(beforeSource, fileName);
        }
        if (targetFqn == null || targetFqn.isBlank()) {
            String base = fileName != null && fileName.endsWith(".java")
                    ? fileName.substring(0, fileName.length() - 5)
                    : fileName;
            targetFqn = base == null ? "" : base.trim();
        }
        return targetFqn == null ? "" : targetFqn.trim();
    }

    private static String sourceFileName(CrossFileSource source) {
        if (source == null) return "";
        if (source.ioFile != null && source.ioFile.getName() != null && !source.ioFile.getName().isBlank()) {
            return source.ioFile.getName();
        }
        if (source.vf != null && source.vf.getName() != null && !source.vf.getName().isBlank()) {
            return source.vf.getName();
        }
        String path = source.relativePath == null || source.relativePath.isBlank()
                ? source.absolutePath
                : source.relativePath;
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String buildTestFailureFeedback(String targetFqn, testing.TestResult testResult) {
        if (testResult == null) {
            return "Tests failed for " + targetFqn + ": no test result returned.";
        }
        String detail = testResult.summary != null && !testResult.summary.isBlank()
                ? testResult.summary
                : testResult.raw;
        if (detail == null || detail.isBlank()) {
            detail = testResult.status == null || testResult.status.isBlank()
                    ? "unknown test failure"
                    : testResult.status;
        }
        return "Tests failed for " + targetFqn + ":\n" + safeTruncate(detail, 3000);
    }
}
