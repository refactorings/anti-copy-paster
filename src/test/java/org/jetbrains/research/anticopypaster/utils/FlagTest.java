package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

public class FlagTest {

    /**
    Inner, nonspecific testing implementation for this class to test the shared utilities
     */
    class TestingFlag extends Flag{

        public TestingFlag(List<FeaturesVector> featuresVectorList){
            super(featuresVectorList, new ProjectMock(new ProjectSettingsState()).getMock());
        }
        @Override
        public int getSensitivity() {
            return sensitivity;
        }
        @Override
        public void setSelectedMetrics(){
            selectedMetrics.add(Feature.TotalLinesOfCode);
            selectedMetrics.add(Feature.TotalSymbols);
            selectedMetrics.add(Feature.SymbolsPerLine);
            selectedMetrics.add(Feature.Area);
            numFeatures = selectedMetrics.size();
        }
        @Override
        public void logMetric(String filepath){
            // Do nothing just lets tests go
        }
        @Override
        public void logThresholds(String filepath){
            // Do nothing just lets tests go
        }
    }
    private TestingFlag testFlag;
    private TestingFlag testFlag2;
    private int sensitivity;

    private List<FeaturesVector> fvList;

    // Zero everything out
    @BeforeEach
    public void beforeTest(){
        this.fvList = new ArrayList<>();
        this.testFlag = null;
        this.testFlag2 = null;
    }
    @Test
    public void testGetMetric_NullFV(){

        // Create a Flag instance
        testFlag = new TestingFlag(fvList);

        // Call the getMetric method with a null FeaturesVector
        float[] result = testFlag.getMetric(null);

        // Verify that the lastCalculatedMetric array is initialized with zeros
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, result, 0.0f);
    }
    @Test
    public void testGetMetric(){
        FeaturesVectorMock featuresVectorMock = new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f});

        // Create a Flag instance
        testFlag = new TestingFlag(fvList);
        // Call the getMetric method with the mock FeaturesVector
        float[] result = testFlag.getMetric(featuresVectorMock.getMock());

        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, result, 0f);

    }

    @Test
    public void testCalculateThreshold_NullFV(){
        sensitivity = 50;
        testFlag = new TestingFlag(fvList);

        testFlag.calculateThreshold();

        assertNotNull(testFlag.thresholds);
        assertEquals(4, testFlag.thresholds.length);
    }

    @Test
    public void testCalculateThreshold_OneFV(){
        FeaturesVectorMock featuresVectorMock1 = new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f});
        fvList.add(featuresVectorMock1.getMock());
        // Create a Flag instance
        testFlag = new TestingFlag(fvList);

        assertEquals(4, testFlag.thresholds.length);
        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, testFlag.thresholds, 0f);
    }
    @Test
    public void testCalculateThreshold_MultipleFV(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());

        testFlag = new TestingFlag(fvList);
        assertEquals(4, testFlag.thresholds.length);
        assertArrayEquals(new float[]{1f, 1f, 3f, 4f}, testFlag.thresholds, 0f);
    }
    @Test
    public void testCalculateThreshold_10Sens(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());

        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 10;
        this.sensitivity = 10;
        testFlag.calculateThreshold();

        assertEquals(4, testFlag.thresholds.length);
        assertArrayEquals(new float[]{1f, 1.2f, 3f, 4f}, testFlag.thresholds, 0f);
    }
    @Test
    public void testCalculateThreshold_75Sens(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());

        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 75;
        this.sensitivity = 75;
        testFlag.calculateThreshold();

        assertEquals(4, testFlag.thresholds.length);
        assertArrayEquals(new float[]{2.5f, 2.0f, 5.0f, 4.5f}, testFlag.thresholds, 0f);
    }
    @Test
    public void testCalculateThreshold_100Sens(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());

        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 100;
        this.sensitivity = 100;
        testFlag.calculateThreshold();

        assertEquals(4, testFlag.thresholds.length);
        assertArrayEquals(new float[]{4f, 2f, 7f, 5f}, testFlag.thresholds, 0f);
    }
    @Test
    public void testIsFlagTriggered_OneFV(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());

        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 50;
        this.sensitivity = 50;

        assertFalse(testFlag.isFlagTriggered(fvList.get(0)));

    }
    @Test
    public void testIsFlagTriggered_MultipleFV(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());

        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 50;
        this.sensitivity = 50;

        assertTrue(testFlag.isFlagTriggered(fvList.get(2)));
    }
    @Test
    public void testIsFlagTriggered_RequiredMetrics(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());

        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 50;
        this.sensitivity = 50;

        testFlag.requiredMetrics.add(Feature.TotalSymbols);
        assertFalse(testFlag.isFlagTriggered(fvList.get(2)));
    }

    @Test
    public void testGetMetric_MultipleProjects(){
        FeaturesVectorMock featuresVectorMock1 = new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f});
        FeaturesVectorMock featuresVectorMock2 = new FeaturesVectorMock(new float[]{2f, 4f, 6f, 8f});

        // Create Flag instances
        testFlag = new TestingFlag(fvList);
        testFlag2 = new TestingFlag(fvList);
        // Call the getMetric method with the mock FeaturesVector
        float[] result1 = testFlag.getMetric(featuresVectorMock1.getMock());
        float[] result2 = testFlag2.getMetric(featuresVectorMock2.getMock());

        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, result1, 0f);
        assertArrayEquals(new float[]{2f, 4f, 6f, 8f}, result2, 0f);
    }

    @Test
    public void testCalculateThreshold_OneFV_MultipleProjects(){
        FeaturesVectorMock featuresVectorMock1 = new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f});
        fvList.add(featuresVectorMock1.getMock());
        testFlag = new TestingFlag(fvList);

        fvList.clear();

        FeaturesVectorMock featuresVectorMock2 = new FeaturesVectorMock(new float[]{2f, 4f, 6f, 8f});
        fvList.add(featuresVectorMock2.getMock());
        testFlag2 = new TestingFlag(fvList);

        assertEquals(4, testFlag.thresholds.length);
        assertEquals(4, testFlag2.thresholds.length);
        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, testFlag.thresholds, 0f);
        assertArrayEquals(new float[]{2f, 4f, 6f, 8f}, testFlag2.thresholds, 0f);
    }

    @Test
    public void testCalculateThreshold_MultipleFV_MultipleProjects(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());
        testFlag = new TestingFlag(fvList);

        fvList.clear();

        fvList.add(new FeaturesVectorMock(new float[]{2f, 4f, 6f, 8f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{5f, 10f, 5f, 10f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{2f, 3f, 7f, 18f}).getMock());
        testFlag2 = new TestingFlag(fvList);

        assertEquals(4, testFlag.thresholds.length);
        assertEquals(4, testFlag2.thresholds.length);
        assertArrayEquals(new float[]{1f, 1f, 3f, 4f}, testFlag.thresholds, 0f);
        assertArrayEquals(new float[]{2f, 3f, 5f, 8f}, testFlag2.thresholds, 0f);
    }

    @Test
    public void testCalculateThreshold_MultipleProjects_SameSens(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());
        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 10;
        this.sensitivity = 10;
        testFlag.calculateThreshold();

        this.sensitivity = 0;
        fvList.clear();

        fvList.add(new FeaturesVectorMock(new float[]{2f, 4f, 2f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{3f, 7f, 8f, 9f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 9f, 10f}).getMock());
        testFlag2 = new TestingFlag(fvList);
        testFlag2.cachedSensitivity = 10;
        this.sensitivity = 10;
        testFlag2.calculateThreshold();

        assertEquals(4, testFlag.thresholds.length);
        assertEquals(4, testFlag2.thresholds.length);
        assertArrayEquals(new float[]{1f, 1.2f, 3f, 4f}, testFlag.thresholds, 0f);
        assertArrayEquals(new float[]{1.2f, 2.4f, 3.2f, 5f}, testFlag2.thresholds, 0f);
    }

    @Test
    public void testCalculateThreshold_MultipleProjects_DiffSens(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());
        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 10;
        this.sensitivity = 10;
        testFlag.calculateThreshold();

        this.sensitivity = 0;
        fvList.clear();

        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());
        testFlag2 = new TestingFlag(fvList);
        testFlag2.cachedSensitivity = 75;
        this.sensitivity = 75;
        testFlag2.calculateThreshold();

        assertEquals(4, testFlag.thresholds.length);
        assertEquals(4, testFlag2.thresholds.length);
        assertArrayEquals(new float[]{1f, 1.2f, 3f, 4f}, testFlag.thresholds, 0f);
        assertArrayEquals(new float[]{2.5f, 2.0f, 5.0f, 4.5f}, testFlag2.thresholds, 0f);
    }

    @Test
    public void testIsFlagTriggered_MultipleProjects(){
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        testFlag = new TestingFlag(fvList);
        testFlag.cachedSensitivity = 50;
        FeaturesVector fvL01 = fvList.get(0);

        this.sensitivity = 50;
        fvList.clear();

        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{1f, 2f, 3f, 4f}).getMock());
        fvList.add(new FeaturesVectorMock(new float[]{4f, 1f, 7f, 5f}).getMock());
        testFlag2 = new TestingFlag(fvList);
        testFlag2.cachedSensitivity = 50;
        FeaturesVector fvL22 = fvList.get(2);

        assertFalse(testFlag.isFlagTriggered(fvL01));
        assertTrue(testFlag2.isFlagTriggered(fvL22));
    }
}