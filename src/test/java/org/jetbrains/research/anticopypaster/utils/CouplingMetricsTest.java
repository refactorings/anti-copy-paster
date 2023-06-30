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
    private FeaturesVector generateFVMForKeywordsByValue(float value){
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
        couplingMetrics.sensitivity = 1;
        assertFalse(couplingMetrics.isFlagTriggered(null));
    }
    @Test
    public void testMinimumSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 1;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
    }
    @Test
    public void testMinimumSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 1;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(1)));
    }
    @Test
    public void testLowerSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 25;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(3)));
    }
    @Test
    public void testLowerSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 25;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(2)));
    }
    @Test
    public void testModerateSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 50;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(4)));
    }
    @Test
    public void testModerateSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 50;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(2)));
    }
    @Test
    public void testHighSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 75;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }
    @Test
    public void testHighSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 75;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(4)));
    }
    @Test
    public void testMaximumSensitivityTrue(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 100;
        assertTrue(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(6)));
    }
    @Test
    public void testMaximumSensitivityFalse(){
        List<FeaturesVector> fvList = List.of(
                generateFVMForKeywordsByValue(1),
                generateFVMForKeywordsByValue(2),
                generateFVMForKeywordsByValue(3),
                generateFVMForKeywordsByValue(4),
                generateFVMForKeywordsByValue(5)
        );
        TestingCouplingMetrics couplingMetrics = new TestingCouplingMetrics(fvList);
        couplingMetrics.sensitivity = 100;
        assertFalse(couplingMetrics.isFlagTriggered(generateFVMForKeywordsByValue(5)));
    }
}
