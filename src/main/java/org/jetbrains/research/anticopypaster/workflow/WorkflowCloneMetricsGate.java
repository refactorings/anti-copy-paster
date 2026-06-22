package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import org.jetbrains.research.anticopypaster.models.UserSettingsModel;
import org.jetbrains.research.anticopypaster.utils.MetricsGatherer;

final class WorkflowCloneMetricsGate {
    @FunctionalInterface
    interface CloneCodeExtractor {
        List<String> getCloneCodes(detection.DetectedClone clone);
    }

    @FunctionalInterface
    interface FeatureCalculator {
        FeaturesVector calculate(detection.CloneRange range, String code);
    }

    @FunctionalInterface
    interface RangeSummarizer {
        String summarize(List<detection.CloneRange> ranges);
    }

    static final class Result {
        final List<detection.DetectedClone> passedClones;
        final int evaluatedCloneCount;
        final float bestPrediction;
        final float threshold;
        final String details;

        Result(List<detection.DetectedClone> passedClones,
               int evaluatedCloneCount,
               float bestPrediction,
               float threshold,
               String details) {
            this.passedClones = passedClones == null ? java.util.Collections.emptyList() : passedClones;
            this.evaluatedCloneCount = evaluatedCloneCount;
            this.bestPrediction = bestPrediction;
            this.threshold = threshold;
            this.details = details == null ? "" : details;
        }

        boolean hasPassedClones() {
            return !passedClones.isEmpty();
        }

        boolean hasEvaluatedClones() {
            return evaluatedCloneCount > 0 && Float.isFinite(bestPrediction);
        }
    }

    private static final class Decision {
        final boolean passed;
        final boolean evaluated;
        final float bestPrediction;
        final String details;

        Decision(boolean passed, boolean evaluated, float bestPrediction, String details) {
            this.passed = passed;
            this.evaluated = evaluated;
            this.bestPrediction = bestPrediction;
            this.details = details == null ? "" : details;
        }
    }

    private WorkflowCloneMetricsGate() {}

    static Result filter(Project project,
                         List<detection.DetectedClone> clones,
                         Consumer<String> viewer,
                         CloneCodeExtractor cloneCodeExtractor,
                         FeatureCalculator featureCalculator,
                         RangeSummarizer rangeSummarizer) {
        if (clones == null || clones.isEmpty()) {
            return new Result(java.util.Collections.emptyList(), 0, Float.NaN, 0.3f, "no clone groups available");
        }
        if (project == null || project.isDisposed()) {
            return new Result(java.util.Collections.emptyList(), 0, Float.NaN, 0.3f, "project unavailable");
        }

        UserSettingsModel metricsModel;
        float threshold = 0.3f;
        try {
            ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
            if (settings != null) {
                threshold = settings.modelSensitivity;
            }
            if (!Float.isFinite(threshold)) {
                threshold = 0.3f;
            }
            metricsModel = new UserSettingsModel(new MetricsGatherer(project), project);
        } catch (Throwable t) {
            logStage(viewer, "METRICS", "metrics gate unavailable: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new Result(
                    java.util.Collections.emptyList(),
                    0,
                    Float.NaN,
                    threshold,
                    "metrics gate unavailable: " + t.getClass().getSimpleName()
            );
        }

        java.util.ArrayList<detection.DetectedClone> passed = new java.util.ArrayList<>();
        int evaluatedCloneCount = 0;
        float bestPrediction = Float.NEGATIVE_INFINITY;
        String bestDetails = "";
        for (detection.DetectedClone clone : clones) {
            if (clone == null) continue;

            Decision decision = evaluateClone(
                    clone,
                    metricsModel,
                    threshold,
                    cloneCodeExtractor,
                    featureCalculator
            );

            if (decision.evaluated) {
                evaluatedCloneCount++;
                if (decision.bestPrediction > bestPrediction) {
                    bestPrediction = decision.bestPrediction;
                    bestDetails = decision.details;
                }
            }

            String cloneId = (clone.id == null || clone.id.isBlank()) ? "<unknown>" : clone.id;
            String rangeSummary = rangeSummarizer == null ? "" : rangeSummarizer.summarize(clone.ranges);
            if (decision.passed) {
                passed.add(clone);
                logStage(viewer, "METRICS", "accepted clone " + cloneId +
                        " [" + rangeSummary + "]: prediction=" + formatMetricScore(decision.bestPrediction) +
                        ", threshold=" + formatMetricScore(threshold) +
                        (decision.details.isBlank() ? "" : ", " + decision.details));
            } else {
                logStage(viewer, "METRICS", "filtered clone " + cloneId +
                        " [" + rangeSummary + "]: bestPrediction=" + formatMetricScore(decision.bestPrediction) +
                        ", threshold=" + formatMetricScore(threshold) +
                        (decision.details.isBlank() ? "" : ", " + decision.details));
            }
        }

        if (passed.size() != clones.size()) {
            logStage(viewer, "METRICS", "metrics-filtered clone groups: " + clones.size() + " -> " + passed.size());
        } else {
            logStage(viewer, "METRICS", "all clone groups passed metrics gate: " + passed.size());
        }
        return new Result(
                passed,
                evaluatedCloneCount,
                bestPrediction == Float.NEGATIVE_INFINITY ? Float.NaN : bestPrediction,
                threshold,
                bestDetails
        );
    }

    static boolean confirmContinue(Project project, String fileName, Result metricsGate, String subject) {
        String normalizedSubject = subject == null || subject.isBlank() ? "the detected clone" : subject;
        String confidenceLine;
        if (metricsGate != null && metricsGate.hasEvaluatedClones()) {
            confidenceLine = "Best confidence: " + formatMetricPercent(metricsGate.bestPrediction)
                    + ". Required: " + formatMetricPercent(metricsGate.threshold) + ".";
        } else {
            confidenceLine = "AntiCopyPaster could not compute a reliable confidence score for this clone.";
            if (metricsGate != null && Float.isFinite(metricsGate.threshold)) {
                confidenceLine += " Required: " + formatMetricPercent(metricsGate.threshold) + ".";
            }
        }

        String message = "AntiCopyPaster found duplicated code in " +
                (fileName == null || fileName.isBlank() ? "this file" : fileName) +
                ", but " + normalizedSubject +
                " does not look like a strong Extract Method candidate under the current Model Sensitivity.\n\n" +
                confidenceLine +
                "\n\nContinuing may produce a weak or noisy refactoring." +
                "\n\nMove Model Sensitivity toward More sensitive to lower the required confidence." +
                "\n\nDo you want to continue anyway?";

        final java.util.concurrent.atomic.AtomicInteger out = new java.util.concurrent.atomic.AtomicInteger(Messages.NO);
        Runnable ui = () -> {
            try {
                int choice = Messages.showYesNoDialog(
                        project,
                        message,
                        "Continue Refactoring?",
                        "Continue Anyway",
                        "Stop",
                        Messages.getQuestionIcon()
                );
                out.set(choice);
            } catch (Throwable ignored) {
                out.set(Messages.NO);
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }
        return out.get() == Messages.YES;
    }

    private static Decision evaluateClone(detection.DetectedClone clone,
                                          UserSettingsModel metricsModel,
                                          float threshold,
                                          CloneCodeExtractor cloneCodeExtractor,
                                          FeatureCalculator featureCalculator) {
        if (clone == null || metricsModel == null || cloneCodeExtractor == null || featureCalculator == null) {
            return new Decision(false, false, 0f, "metrics model unavailable");
        }

        List<String> cloneCodes = cloneCodeExtractor.getCloneCodes(clone);
        int rangeCount = clone.ranges == null ? 0 : clone.ranges.size();
        int occurrenceCount = Math.max(rangeCount, cloneCodes == null ? 0 : cloneCodes.size());
        if (occurrenceCount <= 0) {
            return new Decision(false, false, 0f, "no clone occurrences available");
        }

        boolean evaluated = false;
        float bestPrediction = Float.NEGATIVE_INFINITY;
        String bestDetails = "";

        for (int i = 0; i < occurrenceCount; i++) {
            detection.CloneRange range = (clone.ranges == null || i >= clone.ranges.size()) ? null : clone.ranges.get(i);
            String code = cloneCodes != null && i < cloneCodes.size() ? cloneCodes.get(i) : "";
            FeaturesVector featuresVector = featureCalculator.calculate(range, code);
            if (featuresVector == null) continue;

            evaluated = true;
            float prediction;
            try {
                prediction = metricsModel.predict(featuresVector);
            } catch (Throwable t) {
                continue;
            }

            String occurrenceDetails = "occurrence=" + (i + 1) +
                    (range == null ? "" : " lines=" + range.startLine + "-" + range.endLine);
            if (prediction > bestPrediction) {
                bestPrediction = prediction;
                bestDetails = occurrenceDetails;
            }
            if (prediction > threshold) {
                return new Decision(true, true, prediction, occurrenceDetails);
            }
        }

        if (!evaluated) {
            return new Decision(false, false, 0f, "no evaluable occurrence metrics");
        }
        return new Decision(false, true, bestPrediction, bestDetails);
    }

    private static String formatMetricScore(float value) {
        if (!Float.isFinite(value)) return String.valueOf(value);
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatMetricPercent(float value) {
        if (!Float.isFinite(value)) return "unknown";
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0f);
    }
}
