package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.mockito.Mock;

import static org.mockito.Mockito.*;

public class FlagTest {

    /**
    Inner, nonspecific testing implementation for this class to test the shared utilities
     */
    class TestingFlag extends Flag{

        public TestingFlag(List<FeaturesVector> featuresVectorList){
            super(featuresVectorList);
        }

        @Override
        protected int getSensitivity() {
            return sensitivity;
        }

        @Override
        protected float getMetric(FeaturesVector featuresVector) { return 0; }

        @Override
        public boolean isFlagTriggered(FeaturesVector featuresVector){
            return false;
        }

        @Override
        protected void calculateAverageMetrics() {
            // Do nothing just lets tests go
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

    @BeforeEach
    public void beforeTest(){
        this.testFlag = new TestingFlag(null);
    }

    @Test
    public void testSensitivityZero(){
        sensitivity = 0;
        assertEquals(testFlag.getSensitivity(), 0);
    }

    @Test
    public void testSensitivityOne(){
        sensitivity = 1;
        assertEquals(testFlag.getSensitivity(), 1);
    }

    @Test
    public void testSensitivityTwo(){
        sensitivity = 2;
        assertEquals(testFlag.getSensitivity(), 2);
    }

    @Test
    public void testSensitivityThree(){
        sensitivity = 3;
        assertEquals(testFlag.getSensitivity(), 3);
    }

    @Test 
    public void testBoxPlotNullList(){
        testFlag.boxPlotCalculations(null);
        assertEquals(0, testFlag.getMetricQ1(), 0);
        assertEquals(0, testFlag.getMetricQ2(), 0);
        assertEquals(0, testFlag.getMetricQ3(), 0);
    }

    @Test 
    public void testBoxPlotEmptyList(){
        ArrayList emptyList = new ArrayList<Float>();
        testFlag.boxPlotCalculations(emptyList);
        assertEquals(0, testFlag.getMetricQ1(), 0);
        assertEquals(0, testFlag.getMetricQ2(), 0);
        assertEquals(0, testFlag.getMetricQ3(), 0);
    }

    @Test 
    public void testBoxPlotSameNumberFourValuesList(){
        ArrayList fourValueSameNumberFloatList = new ArrayList<Float>();
        
        fourValueSameNumberFloatList.add((float)1.0);
        fourValueSameNumberFloatList.add((float)1.0);
        fourValueSameNumberFloatList.add((float)1.0);
        fourValueSameNumberFloatList.add((float)1.0);

        Collections.sort(fourValueSameNumberFloatList);

        testFlag.boxPlotCalculations(fourValueSameNumberFloatList);
        assertEquals(1, testFlag.getMetricQ1(), 0);
        assertEquals(1, testFlag.getMetricQ2(), 0);
        assertEquals(1, testFlag.getMetricQ3(), 0);
    }

    @Test 
    public void testBoxPlotSameNumberFiveValuesList(){
        ArrayList fiveValueSameNumberFloatList = new ArrayList<Float>();
        
        fiveValueSameNumberFloatList.add((float)1.0);
        fiveValueSameNumberFloatList.add((float)1.0);
        fiveValueSameNumberFloatList.add((float)1.0);
        fiveValueSameNumberFloatList.add((float)1.0);
        fiveValueSameNumberFloatList.add((float)1.0);

        Collections.sort(fiveValueSameNumberFloatList);

        testFlag.boxPlotCalculations(fiveValueSameNumberFloatList);
        assertEquals(1, testFlag.getMetricQ1(), 0);
        assertEquals(1, testFlag.getMetricQ2(), 0);
        assertEquals(1, testFlag.getMetricQ3(), 0);
    }

    @Test 
    public void testBoxPlotDifferentNumberFourValuesList(){
        ArrayList fourValueDifferentNumberFloatList = new ArrayList<Float>();
        
        fourValueDifferentNumberFloatList.add((float)1.0);
        fourValueDifferentNumberFloatList.add((float)2.0);
        fourValueDifferentNumberFloatList.add((float)3.0);
        fourValueDifferentNumberFloatList.add((float)4.0);

        Collections.sort(fourValueDifferentNumberFloatList);

        testFlag.boxPlotCalculations(fourValueDifferentNumberFloatList);
        assertEquals(1.5, testFlag.getMetricQ1(), 0);
        assertEquals(2.5, testFlag.getMetricQ2(), 0);
        assertEquals(3.5, testFlag.getMetricQ3(), 0);
    }

    @Test 
    public void testBoxPlotDifferentNumberFiveValuesList(){
        ArrayList fiveValueDifferentNumberFloatList = new ArrayList<Float>();
        
        fiveValueDifferentNumberFloatList.add((float)1.0);
        fiveValueDifferentNumberFloatList.add((float)2.0);
        fiveValueDifferentNumberFloatList.add((float)3.0);
        fiveValueDifferentNumberFloatList.add((float)4.0);
        fiveValueDifferentNumberFloatList.add((float)5.0);

        Collections.sort(fiveValueDifferentNumberFloatList);

        testFlag.boxPlotCalculations(fiveValueDifferentNumberFloatList);
        assertEquals(2, testFlag.getMetricQ1(), 0);
        assertEquals(3, testFlag.getMetricQ2(), 0);
        assertEquals(4, testFlag.getMetricQ3(), 0);
    }

    @Test 
    public void testBoxPlotDifferentNumberFiveDecimalValuesList(){
        ArrayList fiveValueDifferentNumberDecimalFloatList = new ArrayList<Float>();
        
        fiveValueDifferentNumberDecimalFloatList.add((float)0);
        fiveValueDifferentNumberDecimalFloatList.add((float)0.25);
        fiveValueDifferentNumberDecimalFloatList.add((float)0.5);
        fiveValueDifferentNumberDecimalFloatList.add((float)0.75);
        fiveValueDifferentNumberDecimalFloatList.add((float)1);

        Collections.sort(fiveValueDifferentNumberDecimalFloatList);

        testFlag.boxPlotCalculations(fiveValueDifferentNumberDecimalFloatList);
        assertEquals(0.25, testFlag.getMetricQ1(), 0);
        assertEquals(0.5, testFlag.getMetricQ2(), 0);
        assertEquals(0.75, testFlag.getMetricQ3(), 0);
    }

}