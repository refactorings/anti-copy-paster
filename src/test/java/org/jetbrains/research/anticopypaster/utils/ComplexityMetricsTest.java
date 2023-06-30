package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

import org.junit.jupiter.api.Test;
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
    private FeaturesVector generateFVMForKeywordsByValue(float value){
        float[] floatArr = new float[78];
        floatArr[3] = value;
        floatArr[4] = value;
        floatArr[14] = value;
        floatArr[15] = value;
        return new FeaturesVectorMock(floatArr).getMock();
    }

    @Test
    public void testSetSelectedMetrics_SelectedMetrics(){
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(null);

        assertEquals(1, complexityMetrics.selectedMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.selectedMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_RequiredMetrics(){
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(null);

        assertEquals(1, complexityMetrics.requiredMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.requiredMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_ChangeSettings(){
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(null);

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
    @Test
    public void testNullInputFalse(){
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(  null);
        complexityMetrics.sensitivity = 1;
        assertFalse(complexityMetrics.isFlagTriggered(null));
    }

    @Test
    public void testMinimumThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 1;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
    }

    @Test
    public void testMinimumThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 1;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(1)));
    }

    @Test
    public void testModerateThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 50;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }

    @Test
    public void testModerateThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 50;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(2)));
    }

    @Test
    public void testHighThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 75;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }

    @Test
    public void testHighThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 75;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
    }

    @Test
    public void testMaximumThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 100;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(6)));
    }

    @Test
    public void testMaximumThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.sensitivity = 100;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }
}