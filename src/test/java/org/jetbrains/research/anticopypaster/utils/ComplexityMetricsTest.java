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
     * Uses a ProjectMock instead of a project and exposes a reference to its settings.
     */
    private static class TestingComplexityMetrics extends ComplexityMetrics {

        public final ProjectSettingsState settings;

        public TestingComplexityMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, new ProjectMock(new ProjectSettingsState()).getMock());
            settings = ProjectSettingsState.getInstance(project);
        }
    }
    private FeaturesVector generateFVMForComplexityByValue(float value) {
        float[] floatArr = new float[78];
        floatArr[3] = value;
        floatArr[4] = value;
        floatArr[14] = value;
        floatArr[15] = value;
        return new FeaturesVectorMock(floatArr).getMock();
    }

    @Test
    public void testSetSelectedMetrics_SelectedMetrics() {
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(null);

        assertEquals(1, complexityMetrics.selectedMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.selectedMetrics.get(0));
    }

    @Test
    public void testSetSelectedMetrics_RequiredMetrics() {
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(null);

        assertEquals(1, complexityMetrics.requiredMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.requiredMetrics.get(0));
    }

    @Test
    public void testSetSelectedMetrics_ChangeSettings() {
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
    public void testNullInputFalse() {
        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(  null);
        complexityMetrics.settings.complexitySensitivity = 1;
        assertFalse(complexityMetrics.isFlagTriggered(null));
    }

    @Test
    public void testMinimumThresholdTrue() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 1;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(3)));
    }

    @Test
    public void testMinimumThresholdFalse() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 1;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(1)));
    }

    @Test
    public void testModerateThresholdTrue() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 50;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(5)));
    }

    @Test
    public void testModerateThresholdFalse() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 50;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(2)));
    }

    @Test
    public void testHighThresholdTrue() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 75;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(5)));
    }

    @Test
    public void testHighThresholdFalse() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 75;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(3)));
    }

    @Test
    public void testMaximumThresholdTrue() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 100;

        assertTrue(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(6)));
    }

    @Test
    public void testMaximumThresholdFalse() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics = new TestingComplexityMetrics(fvList);
        complexityMetrics.settings.complexitySensitivity = 100;

        assertFalse(complexityMetrics.isFlagTriggered(generateFVMForComplexityByValue(5)));
    }

    @Test
    public void test_SetSelectedMetrics_ChangeSettings_MultipleProjects() {
        TestingComplexityMetrics complexityMetrics1 = new TestingComplexityMetrics(null);
        TestingComplexityMetrics complexityMetrics2 = new TestingComplexityMetrics(null);

        complexityMetrics1.settings.measureComplexityTotal[0] = true;
        complexityMetrics1.settings.measureComplexityTotal[1] = true;

        complexityMetrics1.selectedMetrics.clear();
        complexityMetrics1.requiredMetrics.clear();
        complexityMetrics1.setSelectedMetrics();

        assertEquals(2, complexityMetrics1.selectedMetrics.size());
        assertEquals(2, complexityMetrics1.requiredMetrics.size());
        assertEquals(1, complexityMetrics2.selectedMetrics.size());
        assertEquals(1, complexityMetrics2.requiredMetrics.size());

        assertEquals(Feature.Area, complexityMetrics1.selectedMetrics.get(0));
        assertNotEquals(Feature.Area, complexityMetrics2.selectedMetrics.get(0));

        assertEquals(Feature.Area, complexityMetrics1.requiredMetrics.get(0));
        assertNotEquals(Feature.Area, complexityMetrics2.requiredMetrics.get(0));
    }

    @Test
    public void testMinimumThresholds_MultipleProjects() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics1 = new TestingComplexityMetrics(fvList);
        complexityMetrics1.settings.complexitySensitivity = 1;
        TestingComplexityMetrics complexityMetrics2 = new TestingComplexityMetrics(fvList);
        complexityMetrics2.settings.complexitySensitivity = 1;

        assertTrue(complexityMetrics1.isFlagTriggered(generateFVMForComplexityByValue(3)));
        assertFalse(complexityMetrics1.isFlagTriggered(generateFVMForComplexityByValue(1)));
    }

    @Test
    public void testMaximumThresholds_MultipleProjects() {
        List<FeaturesVector> fvList = List.of(
                generateFVMForComplexityByValue(1),
                generateFVMForComplexityByValue(2),
                generateFVMForComplexityByValue(3),
                generateFVMForComplexityByValue(4),
                generateFVMForComplexityByValue(5)
        );

        TestingComplexityMetrics complexityMetrics1 = new TestingComplexityMetrics(fvList);
        complexityMetrics1.settings.complexitySensitivity = 100;
        TestingComplexityMetrics complexityMetrics2 = new TestingComplexityMetrics(fvList);
        complexityMetrics2.settings.complexitySensitivity = 100;

        assertTrue(complexityMetrics1.isFlagTriggered(generateFVMForComplexityByValue(6)));
        assertFalse(complexityMetrics2.isFlagTriggered(generateFVMForComplexityByValue(5)));

    }
}