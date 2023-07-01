package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CouplingMetricsTest {

    /**
     * Testing variant of CouplingMetrics.
     * Stores project settings locally rather than through IntelliJ systems.
     */
    private static class TestingCouplingMetrics extends CouplingMetrics {

        // Stores a ProjectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;

        public TestingCouplingMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        protected ProjectSettingsState retrieveCurrentSettings() {
            if (settings == null)
                settings = new ProjectSettingsState();
            return settings;
        }
    }

    private FeaturesVector generateFVMForCouplingByValue(float value) {
        float[] floatArr = new float[78];
        for (int i = 5; i <= 10; i++ ) {
            floatArr[i] = value;
        }
        return new FeaturesVectorMock(floatArr).getMock();
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

    @Test
    public void testNullInputFalse(){
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(null);
        couplingMetrics.settings.couplingSensitivity = 1;
        assertFalse(couplingMetrics.isFlagTriggered(null));
    }

    @Test
    public void testMinimumSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 1;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(3)));
    }

    @Test
    public void testMinimumSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 1;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(1)));
    }

    @Test
    public void testLowerSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 25;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(3)));
    }

    @Test
    public void testLowerSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 25;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(2)));
    }

    @Test
    public void testModerateSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 50;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(4)));
    }

    @Test
    public void testModerateSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity= 50;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(2)));
    }

    @Test
    public void testHighSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 75;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(5)));
    }

    @Test
    public void testHighSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 75;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(4)));
    }

    @Test
    public void testMaximumSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 100;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(6)));
    }

    @Test
    public void testMaximumSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForCouplingByValue(1),
                generateFVMForCouplingByValue(2),
                generateFVMForCouplingByValue(3),
                generateFVMForCouplingByValue(4),
                generateFVMForCouplingByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.settings.couplingSensitivity = 100;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForCouplingByValue(5)));
    }
}
