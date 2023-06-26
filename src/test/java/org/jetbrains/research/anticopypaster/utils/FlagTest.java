package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import org.mockito.Mock;

import static org.mockito.Mockito.*;

public class FlagTest {

    /**
    Inner, nonspecific testing implementation for this class to test the shared utilities
     */
    class TestingFlag extends Flag{

        public TestingFlag(List<FeaturesVector> featuresVectorList){
            super(featuresVectorList, null);
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
    private int sensitivity;
    public class FeaturesVectorMock {
        @Mock
        private FeaturesVector mockFeaturesVector;

        private float[] metricsArray;

        public FeaturesVectorMock(float[] metricsArray) {
            mockFeaturesVector = mock(FeaturesVector.class);
            this.metricsArray = metricsArray;

            // mock methods for the FeaturesVector class
            when(mockFeaturesVector.buildArray()).thenReturn(this.metricsArray);

            /*
            Mock the behavior of the getFeatureValue method, the feature values are the numbers in the metricsArray,
            and the id numbers of the selectedMetrics are the indexes
             */
            when(mockFeaturesVector.getFeatureValue(any(Feature.class))).thenAnswer(invocation -> {
                Feature feature = invocation.getArgument(0);
                int featureId = feature.getId();
                return (double) metricsArray[featureId];
            });

        }
        public FeaturesVector getMock() {
            return mockFeaturesVector;
        }
    }
    private List<FeaturesVector> fvList;

    // Zero everything out
    @BeforeEach
    public void beforeTest(){
        this.fvList = new ArrayList<>();
        this.testFlag = null;
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
}