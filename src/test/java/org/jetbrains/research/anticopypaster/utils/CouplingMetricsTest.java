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
import static org.junit.Assert.assertFalse;

import org.mockito.Mock;

import static org.mockito.Mockito.*;
public class CouplingMetricsTest {
    /**
     * Testing variant of CouplingMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    private class TestingCouplingMetrics extends CouplingMetrics {

        public TestingCouplingMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList);
        }

        @Override
        public int getSensitivity() {
            return sensitivity;
        }
    }

    public class FeaturesVectorMock {
        @Mock
        private FeaturesVector mockFeaturesVector;

        private float[] metricsArray;

        public FeaturesVectorMock(float[] metricsArray) {
            mockFeaturesVector = mock(FeaturesVector.class);
            this.metricsArray = metricsArray;

            // mock methods for the FeaturesVector class
            when(mockFeaturesVector.buildArray())
                    .thenReturn(this.metricsArray);

        }

        public FeaturesVector getMock() {
            return mockFeaturesVector;
        }
    }

    private TestingCouplingMetrics couplingMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        //Zero out everything
        this.couplingMetrics = null;
        this.fvList = new ArrayList<FeaturesVector>();
    }

    @Test
    public void testIsTriggeredSensitivityZero(){
        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 100;
        assertFalse(couplingMetrics.isFlagTriggered(null));
    }

    @Test
    public void testIsTriggeredSensitivityOneTrue() {

        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;

        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 25;

        float[] passedInArray = new float[78];
        passedInArray[5] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsTriggeredSensitivityOneFalse() {

        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;

        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 25;

        float[] passedInArray = new float[78];
        passedInArray[5] = (float)1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsTriggeredSensitivityTwoTrue() {

        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;

        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 50;

        float[] passedInArray = new float[78];
        passedInArray[5] = (float)5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsTriggeredSensitivityTwoFalse() {

        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;

        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 50;

        float[] passedInArray = new float[78];
        passedInArray[5] = (float)1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsTriggeredSensitivityThreeTrue() {

        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;

        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[5] = (float)5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsTriggeredSensitivityThreeFalse() {

        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;

        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[5] = (float)5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredMultiMetricSensitivityOne() {

        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;
        fvArrayValue1[6] = 12;
        fvArrayValue1[7] = 12;
        fvArrayValue1[8] = 1;
        fvArrayValue1[9] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;
        fvArrayValue2[6] = 5;
        fvArrayValue2[7] = 5;
        fvArrayValue2[8] = 2;
        fvArrayValue2[9] = 1;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;
        fvArrayValue3[6] = 2;
        fvArrayValue3[7] = 2;
        fvArrayValue3[8] = 3;
        fvArrayValue3[9] = 1;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;
        fvArrayValue4[6] = 0;
        fvArrayValue4[7] = 7;
        fvArrayValue4[8] = 4;
        fvArrayValue4[9] = 1;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;
        fvArrayValue5[6] = 4;
        fvArrayValue5[7] = 24;
        fvArrayValue5[8] = 5;
        fvArrayValue5[9] = 1;

        //Adding these values gives:
        // Q1 = 2, 2, 5, 2, 1
        // Q2 = 3, 4, 7, 3, 1
        // Q3 = 4, 5,12, 4, 1
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[5] = 3;
        passedInArray[6] = 1;
        passedInArray[7] = 5;
        passedInArray[8] = 10;
        passedInArray[9] = 0;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsNotTriggeredMultiMetricSensitivityOne() {

        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[5] = 1;
        fvArrayValue1[6] = 12;
        fvArrayValue1[7] = 12;
        fvArrayValue1[8] = 1;
        fvArrayValue1[9] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[5] = 2;
        fvArrayValue2[6] = 5;
        fvArrayValue2[7] = 5;
        fvArrayValue2[8] = 2;
        fvArrayValue2[9] = 1;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[5] = 3;
        fvArrayValue3[6] = 2;
        fvArrayValue3[7] = 2;
        fvArrayValue3[8] = 3;
        fvArrayValue3[9] = 1;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[5] = 4;
        fvArrayValue4[6] = 0;
        fvArrayValue4[7] = 7;
        fvArrayValue4[8] = 4;
        fvArrayValue4[9] = 1;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[5] = 5;
        fvArrayValue5[6] = 4;
        fvArrayValue5[7] = 24;
        fvArrayValue5[8] = 5;
        fvArrayValue5[9] = 1;

        //Adding these values gives:
        // Q1 = 2, 2, 5, 2, 1
        // Q2 = 3, 4, 7, 3, 1
        // Q3 = 4, 5,12, 4, 1
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        this.couplingMetrics = new TestingCouplingMetrics(fvList);
        this.couplingMetrics.sensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[5] = 1;
        passedInArray[6] = 1;
        passedInArray[7] = 4;
        passedInArray[8] = 1;
        passedInArray[9] = 0;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(couplingMetrics.isFlagTriggered(passedInFv.getMock()));
    }
}