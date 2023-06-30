package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SizeMetricsTest {

    /**
     * Testing variant of SizeMetrics.
     * Stores project settings locally rather than through IntelliJ systems.
     */
    private static class TestingSizeMetrics extends SizeMetrics {

        // Stores a ProjectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;
        private int sensitivity;

        public TestingSizeMetrics(List<FeaturesVector> featuresVectorList) {
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
    public void testSetSelectedMetrics_DefaultSettings() {
        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(null);

        assertEquals(1, sizeMetrics.selectedMetrics.size());
        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.selectedMetrics.get(0));
        assertEquals(1, sizeMetrics.requiredMetrics.size());
        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.requiredMetrics.get(0));
    }

    @Test
    public void testSetSelectedMetrics_ChangedSettings() {
        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(null);

        sizeMetrics.settings.measureSizeBySymbols[0] = true;
        sizeMetrics.settings.measureSizeBySymbols[1] = true;
        sizeMetrics.settings.measureSizeBySymbolsPerLine[0] = true;

        sizeMetrics.selectedMetrics.clear();
        sizeMetrics.requiredMetrics.clear();
        sizeMetrics.setSelectedMetrics();

        assertEquals(3, sizeMetrics.selectedMetrics.size());
        assertEquals(2, sizeMetrics.requiredMetrics.size());

        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.selectedMetrics.get(0));
        assertEquals(Feature.TotalSymbols, sizeMetrics.selectedMetrics.get(1));
        assertEquals(Feature.SymbolsPerLine, sizeMetrics.selectedMetrics.get(2));

        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.requiredMetrics.get(0));
        assertEquals(Feature.TotalSymbols, sizeMetrics.requiredMetrics.get(1));
    }
}

