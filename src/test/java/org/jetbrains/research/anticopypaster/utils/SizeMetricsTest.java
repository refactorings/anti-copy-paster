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

        public final ProjectSettingsState settings;

        public TestingSizeMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, new ProjectMock(new ProjectSettingsState()).getMock());
            settings = ProjectSettingsState.getInstance(project);
        }
    }

    private FeaturesVector generateFVMForSizeByValue(float value){
        float[] floatArr = new float[78];
        for (int i = 0; i < 4; i++)
            floatArr[i] = value;
        for(int i = 11; i < 14; i++)
            floatArr[i] = value;
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
        sizeMetrics.settings.sizeSensitivity = 1;
        assertFalse(sizeMetrics.isFlagTriggered(null));
    }

    @Test
    public void testMinimumThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 1;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(3)));
    }

    @Test
    public void testMinimumThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 1;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(1)));
    }

    @Test
    public void testModerateThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 50;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(5)));
    }

    @Test
    public void testModerateThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 50;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(2)));
    }

    @Test
    public void testHighThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 75;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(5)));
    }

    @Test
    public void testHighThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 75;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(3)));
    }

    @Test
    public void testMaximumThresholdTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 100;

        assertTrue(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(6)));
    }

    @Test
    public void testMaximumThresholdFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics = new TestingSizeMetrics(fvList);
        sizeMetrics.settings.sizeSensitivity = 100;

        assertFalse(sizeMetrics.isFlagTriggered(generateFVMForSizeByValue(5)));
    }

    @Test
    public void testSetSelectedMetrics_ChangedSettings_MultipleProjects() {
        TestingSizeMetrics sizeMetrics1 = new TestingSizeMetrics(null);
        TestingSizeMetrics sizeMetrics2 = new TestingSizeMetrics(null);

        sizeMetrics1.settings.measureSizeBySymbols[0] = true;
        sizeMetrics1.settings.measureSizeBySymbols[1] = true;
        sizeMetrics1.settings.measureSizeBySymbolsPerLine[0] = true;

        sizeMetrics1.selectedMetrics.clear();
        sizeMetrics1.requiredMetrics.clear();
        sizeMetrics1.setSelectedMetrics();

        assertEquals(3, sizeMetrics1.selectedMetrics.size());
        assertEquals(2, sizeMetrics1.requiredMetrics.size());
        assertEquals(1, sizeMetrics2.selectedMetrics.size());
        assertEquals(1, sizeMetrics2.requiredMetrics.size());

        assertEquals(Feature.TotalLinesOfCode, sizeMetrics1.selectedMetrics.get(0));
        assertEquals(Feature.TotalSymbols, sizeMetrics1.selectedMetrics.get(1));
        assertEquals(Feature.SymbolsPerLine, sizeMetrics1.selectedMetrics.get(2));

        assertEquals(Feature.TotalLinesOfCode, sizeMetrics1.requiredMetrics.get(0));
        assertEquals(Feature.TotalSymbols, sizeMetrics1.requiredMetrics.get(1));
    }

    @Test
    public void testMinimumThresholds_MultipleProjects() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics1 = new TestingSizeMetrics(fvList);
        sizeMetrics1.settings.sizeSensitivity = 1;
        TestingSizeMetrics sizeMetrics2 = new TestingSizeMetrics(fvList);
        sizeMetrics2.settings.sizeSensitivity = 1;

        assertTrue(sizeMetrics1.isFlagTriggered(generateFVMForSizeByValue(3)));
        assertFalse(sizeMetrics1.isFlagTriggered(generateFVMForSizeByValue(1)));
    }

    @Test
    public void testMaximumThresholds_MultipleProjects() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForSizeByValue(1),
                generateFVMForSizeByValue(2),
                generateFVMForSizeByValue(3),
                generateFVMForSizeByValue(4),
                generateFVMForSizeByValue(5)
        );

        TestingSizeMetrics sizeMetrics1 = new TestingSizeMetrics(fvList);
        sizeMetrics1.settings.sizeSensitivity = 100;
        TestingSizeMetrics sizeMetrics2 = new TestingSizeMetrics(fvList);
        sizeMetrics2.settings.sizeSensitivity = 100;

        assertTrue(sizeMetrics1.isFlagTriggered(generateFVMForSizeByValue(6)));
        assertFalse(sizeMetrics2.isFlagTriggered(generateFVMForSizeByValue(5)));

    }
}

