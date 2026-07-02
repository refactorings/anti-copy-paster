package org.jetbrains.research.anticopypaster.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkflowCloneMetricsGateTest {
    @Test
    void buildsBelowThresholdRecommendationNotification() {
        WorkflowCloneMetricsGate.Result metricsGate = new WorkflowCloneMetricsGate.Result(
                java.util.Collections.emptyList(),
                1,
                0.0f,
                0.3f,
                ""
        );

        assertEquals(
                "CLONE found duplicated code in FloatStats.java, but the detected clone groups do not meet the confidence threshold for an Extract Method recommendation." +
                        "\n\nConfidence: 0% (required: 30%)",
                WorkflowCloneMetricsGate.buildExtractMethodRecommendationNotification(
                        "FloatStats.java",
                        metricsGate,
                        false
                )
        );
    }

    @Test
    void buildsAboveThresholdRecommendationNotification() {
        WorkflowCloneMetricsGate.Result metricsGate = new WorkflowCloneMetricsGate.Result(
                java.util.Collections.emptyList(),
                1,
                0.42f,
                0.3f,
                ""
        );

        assertEquals(
                "CLONE found duplicated code in FloatStats.java, and the detected clone groups meet the confidence threshold for an Extract Method recommendation." +
                        "\n\nConfidence: 42% (required: 30%)",
                WorkflowCloneMetricsGate.buildExtractMethodRecommendationNotification(
                        "FloatStats.java",
                        metricsGate,
                        true
                )
        );
    }

    @Test
    void buildsSelectedCloneRecommendationNotificationWithCloneConfidence() {
        org.jetbrains.research.anticopypaster.agents.detection.DetectedClone selectedClone =
                new org.jetbrains.research.anticopypaster.agents.detection.DetectedClone();
        java.util.IdentityHashMap<org.jetbrains.research.anticopypaster.agents.detection.DetectedClone, Float> predictions =
                new java.util.IdentityHashMap<>();
        predictions.put(selectedClone, 0.1f);
        WorkflowCloneMetricsGate.Result metricsGate = new WorkflowCloneMetricsGate.Result(
                java.util.Collections.emptyList(),
                2,
                0.8f,
                0.3f,
                "",
                predictions
        );

        assertEquals(
                "CLONE found duplicated code in FloatStats.java, but the selected clone group does not meet the confidence threshold for an Extract Method recommendation." +
                        "\n\nConfidence: 10% (required: 30%)",
                WorkflowCloneMetricsGate.buildExtractMethodRecommendationNotification(
                        "FloatStats.java",
                        metricsGate,
                        selectedClone,
                        false
                )
        );
    }
}
