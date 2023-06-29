package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class KeywordsMetricsTest {

    /**
     * Testing variant of KeywordsMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    private static class TestingKeywordsMetrics extends KeywordsMetrics {

        private ProjectSettingsState settings;
        private int sensitivity;

        public TestingKeywordsMetrics(List<FeaturesVector> featuresVectorList) {
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
            return this.settings;
        }
    }

    private FeaturesVector generateFVMForKeywordsByValue(float value){
        float[] floatArr = new float[78];
        for (int i = 16; i <= 77; i++ ) {
            floatArr[i] = value;
        }
        return new FeaturesVectorMock(floatArr).getMock();
    }

    @Test
    public void testSetSelectedMetrics_DefaultSettings() {
        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(null);

        assertEquals(31, keywordsMetrics.selectedMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(17 + 2 * i), keywordsMetrics.selectedMetrics.get(i));
        assertEquals(31, keywordsMetrics.requiredMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(17 + 2 * i), keywordsMetrics.requiredMetrics.get(i));
    }

    @Test
    public void testSetSelectedMetrics_ChangeSettings(){
        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(null);

        keywordsMetrics.settings.measureKeywordsTotal[0] = true;
        keywordsMetrics.settings.measureKeywordsTotal[1] = true;
        keywordsMetrics.settings.measureKeywordsDensity[0] = true;
        keywordsMetrics.settings.measureKeywordsDensity[1] = false;

        keywordsMetrics.selectedMetrics.clear();
        keywordsMetrics.requiredMetrics.clear();
        keywordsMetrics.setSelectedMetrics();

        assertEquals(62, keywordsMetrics.selectedMetrics.size());
        for (int i = 0; i < 62; i++)
            assertEquals(Feature.fromId(16 + i), keywordsMetrics.selectedMetrics.get(i));
        assertEquals(31, keywordsMetrics.requiredMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(16 + 2 * i), keywordsMetrics.requiredMetrics.get(i));

    }

    @Test
    public void testNullInputFalse(){
        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(null);
        keywordsMetrics.sensitivity = 1;
        assertFalse(keywordsMetrics.isFlagTriggered(null));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 1;

        assertTrue(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 1;

        assertFalse(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(1)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 50;

        assertTrue(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 50;

        assertFalse(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(2)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 75;

        assertTrue(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 75;

        assertFalse(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 100;

        assertTrue(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(6)));
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

        TestingKeywordsMetrics keywordsMetrics = new TestingKeywordsMetrics(fvList);
        keywordsMetrics.sensitivity = 100;

        assertFalse(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }
}