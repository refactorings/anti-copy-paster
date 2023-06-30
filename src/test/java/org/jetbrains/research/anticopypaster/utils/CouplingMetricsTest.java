package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

public class CouplingMetricsTest {

    /**
     * Testing variant of CouplingMetrics.
     * Stores project settings locally rather than through IntelliJ systems.
     */
    private static class TestingCouplingMetrics extends CouplingMetrics {

        // Stores a ProjectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;
        private int sensitivity;

        public TestingCouplingMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        public int getSensitivity() {
            return sensitivity;
        }

        @Override
        protected ProjectSettingsState retrieveCurrentSettings() {
            if (settings == null)
                settings = new ProjectSettingsState();
            return settings;
        }
    }

    @Test
    public void testSetSelectedMetrics_SelectedMetrics() {
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(null);

        assertEquals(1, couplingMetrics.selectedMetrics.size());
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.selectedMetrics.get(0));
    }

    @Test
    public void testSetSelectedMetrics_RequiredMetrics() {
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(null);

        assertEquals(1, couplingMetrics.requiredMetrics.size());
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.requiredMetrics.get(0));
    }

    @Test
    public void testSetSelectedMetrics_ChangeSettings() {
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(null);

        couplingMetrics.settings.measureCouplingTotal[0] = true;
        couplingMetrics.settings.measureCouplingTotal[1] = false;

        couplingMetrics.selectedMetrics.clear();
        couplingMetrics.requiredMetrics.clear();
        couplingMetrics.setSelectedMetrics();

        assertEquals(2, couplingMetrics.selectedMetrics.size());
        assertEquals(Feature.TotalConnectivity, couplingMetrics.selectedMetrics.get(0));
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.selectedMetrics.get(1));

        assertEquals(1, couplingMetrics.requiredMetrics.size());
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.requiredMetrics.get(0));
    }
}
