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
     * Uses a ProjectMock instead of a project and exposes a reference to its settings.
     */
    private static class TestingKeywordsMetrics extends KeywordsMetrics {

        public final ProjectSettingsState settings;

        public TestingKeywordsMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, new ProjectMock(new ProjectSettingsState()).getMock());
            settings = ProjectSettingsState.getInstance(project);
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
        keywordsMetrics.settings.keywordsSensitivity = 1;
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
        keywordsMetrics.settings.keywordsSensitivity = 1;

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
        keywordsMetrics.settings.keywordsSensitivity = 1;

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
        keywordsMetrics.settings.keywordsSensitivity = 50;

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
        keywordsMetrics.settings.keywordsSensitivity = 50;

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
        keywordsMetrics.settings.keywordsSensitivity = 75;

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
        keywordsMetrics.settings.keywordsSensitivity = 75;

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
        keywordsMetrics.settings.keywordsSensitivity = 100;

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
        keywordsMetrics.settings.keywordsSensitivity = 100;

        assertFalse(keywordsMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }

    @Test
    public void testSetSelectedMetrics_ChangeSettings_MultipleProjects(){
        TestingKeywordsMetrics keywordsMetrics1 = new TestingKeywordsMetrics(null);
        TestingKeywordsMetrics keywordsMetrics2 = new TestingKeywordsMetrics(null);

        keywordsMetrics1.settings.measureKeywordsTotal[0] = true;
        keywordsMetrics1.settings.measureKeywordsTotal[1] = true;
        keywordsMetrics1.settings.measureKeywordsDensity[0] = true;
        keywordsMetrics1.settings.measureKeywordsDensity[1] = false;

        keywordsMetrics1.selectedMetrics.clear();
        keywordsMetrics1.requiredMetrics.clear();
        keywordsMetrics1.setSelectedMetrics();

        assertEquals(62, keywordsMetrics1.selectedMetrics.size());
        for (int i = 0; i < 62; i++)
            assertEquals(Feature.fromId(16 + i), keywordsMetrics1.selectedMetrics.get(i));
        assertEquals(31, keywordsMetrics1.requiredMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(16 + 2 * i), keywordsMetrics1.requiredMetrics.get(i));

        assertEquals(31, keywordsMetrics2.selectedMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(17 + 2 * i), keywordsMetrics2.selectedMetrics.get(i));
        assertEquals(31, keywordsMetrics2.requiredMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(17 + 2 * i), keywordsMetrics2.requiredMetrics.get(i));
    }

    @Test
    public void testMinimumThresholds_MultipleProjects() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingKeywordsMetrics keywordsMetrics1 = new TestingKeywordsMetrics(fvList);
        keywordsMetrics1.settings.keywordsSensitivity = 1;
        TestingKeywordsMetrics keywordsMetrics2 = new TestingKeywordsMetrics(fvList);
        keywordsMetrics2.settings.keywordsSensitivity = 1;

        assertTrue(keywordsMetrics1.isFlagTriggered(generateFVMForKeywordsByValue(3)));
        assertFalse(keywordsMetrics2.isFlagTriggered(generateFVMForKeywordsByValue(1)));
    }

    @Test
    public void testMaximumThresholds_MultipleProjects() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );

        TestingKeywordsMetrics keywordsMetrics1 = new TestingKeywordsMetrics(fvList);
        keywordsMetrics1.settings.keywordsSensitivity = 100;
        TestingKeywordsMetrics keywordsMetrics2 = new TestingKeywordsMetrics(fvList);
        keywordsMetrics2.settings.keywordsSensitivity = 100;

        assertTrue(keywordsMetrics1.isFlagTriggered(generateFVMForKeywordsByValue(6)));
        assertFalse(keywordsMetrics2.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }
}