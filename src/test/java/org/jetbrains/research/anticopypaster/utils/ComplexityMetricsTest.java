package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

public class ComplexityMetricsTest {

    /**
     * Testing variant of ComplexityMetrics.
     * Stores project settings locally rather than through IntelliJ systems.
     */
    private static class TestingComplexityMetrics extends ComplexityMetrics {

        // Stores a ProjectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;
        private int sensitivity;

        public TestingComplexityMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        public int getSensitivity() {
            return sensitivity;
        }

        @Override
        protected ProjectSettingsState retrieveCurrentSettings(){
            if (settings == null)
                settings = new ProjectSettingsState();
            return settings;
        }
    }

    private TestingComplexityMetrics complexityMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        this.complexityMetrics = null;
        this.fvList = new ArrayList<>();
    }
    @Test
    public void testSetSelectedMetrics_SelectedMetrics(){
        complexityMetrics = new TestingComplexityMetrics(fvList);

        assertEquals(1, complexityMetrics.selectedMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.selectedMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_RequiredMetrics(){
        complexityMetrics = new TestingComplexityMetrics(fvList);

        assertEquals(1, complexityMetrics.requiredMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.requiredMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_ChangeSettings(){
        complexityMetrics = new TestingComplexityMetrics(fvList);

        complexityMetrics.settings.measureComplexityTotal[0] = true;
        complexityMetrics.settings.measureComplexityTotal[1] = true;

        complexityMetrics.selectedMetrics.clear();
        complexityMetrics.requiredMetrics.clear();
        complexityMetrics.setSelectedMetrics();

        assertEquals(2, complexityMetrics.selectedMetrics.size());
        assertEquals(2, complexityMetrics.requiredMetrics.size());

        assertEquals(Feature.Area, complexityMetrics.selectedMetrics.get(0));
        assertEquals(Feature.AreaPerLine, complexityMetrics.selectedMetrics.get(1));

        assertEquals(Feature.Area, complexityMetrics.requiredMetrics.get(0));
        assertEquals(Feature.AreaPerLine, complexityMetrics.requiredMetrics.get(1));
    }
}