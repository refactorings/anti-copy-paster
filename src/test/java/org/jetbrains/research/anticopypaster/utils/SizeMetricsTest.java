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

        public TestingSizeMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        protected ProjectSettingsState retrieveCurrentSettings() {
            if (settings == null)
                settings = new ProjectSettingsState();
            return settings;
        }
    }
    private FeaturesVector generateFVMForKeywordsByValue(float value){
        float[] floatArr = new float[78];
        for (int i = 0; i < 4; i++ ) {
            floatArr[i] = value;
        }
        for(int i = 11; i < 14; i++){
            floatArr[i] = value;
        }
        return new FeaturesVectorMock(floatArr).getMock();
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
    @Test
    public void testNullInputFalse(){
        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(null);
        sizeMetrics.sensitivity = 1;
        assertFalse(sizeMetrics.isFlagTriggered(null));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 1;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 1;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(1)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 50;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 50;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(2)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 75;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 75;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 100;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(6)));
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

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.sensitivity = 100;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }
}

