package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
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

public class SizeMetricsTest extends LightJavaCodeInsightFixtureTestCase {

    /**
     * Testing variant of SizeMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    private class TestingSizeMetrics extends SizeMetrics {

        public TestingSizeMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, getProject());
        }

        @Override
        protected int getSensitivity() {
            return sensitivity;
        }
    }

    private int sensitivity;
    
    /**
    Inner class to mock a FeaturesVector
     */
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

    private TestingSizeMetrics sizeMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        //Zero out everything
        this.sizeMetrics = null;
        this.fvList = new ArrayList<FeaturesVector>();
    }

    @Test
    public void testIsTriggeredSensitivityZero(){
        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 100;
        assertFalse(sizeMetrics.isFlagTriggered(null));
    }

    @Test
    public void testIsTriggeredSensitivityOneTrue(){

        // This category uses metric 1, so we set just those
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 25;

        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(sizeMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityOneFalse(){

        // This category uses metric 1, so we set just those
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 25;

        float[] passedInArray = new float[78];
        passedInArray[0] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(sizeMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityTwoTrue(){

        // This category uses metric 1, so we set just those
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 50;

        float[] passedInArray = new float[78];
        passedInArray[0] = 4;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(sizeMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityTwoFalse(){

        // This category uses metric 1, so we set just those
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 50;

        float[] passedInArray = new float[78];
        passedInArray[0] = 2;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(sizeMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityThreeTrue(){

        // This category uses metric 1, so we set just those
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[0] = 5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(sizeMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityThreeFalse(){

        // This category uses metrics 1 and 12, so we set just those
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.sizeMetrics = new TestingSizeMetrics(fvList);
        sensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(sizeMetrics.isFlagTriggered(passedInFv.getMock()));
    }

}