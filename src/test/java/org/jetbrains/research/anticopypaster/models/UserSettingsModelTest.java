package org.jetbrains.research.anticopypaster.models;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.utils.*;
import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.io.File;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.mockito.Mock;

import static org.mockito.Mockito.*;


public class UserSettingsModelTest extends LightJavaCodeInsightFixtureTestCase {
    /**
     * Overridden from LightJavaCodeInsightFixtureTestCase. Setup now also ensures
     * the project is initialized fully and adds the testdata to the project.
     * @throws Exception
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        while (!getProject().isInitialized());
        model = new UserSettingsModel(null, getProject());
        settings = ProjectSettingsState.getInstance(getProject());
    }


    /**
    Mock metrics gatherer needs to go here
    */ 
    public static class MetricsGathererMock {
        @Mock
        private MetricsGatherer mockMetricsGatherer;
        
        List<FeaturesVector> fvArray;

        public MetricsGathererMock(List<FeaturesVector> fvArray) {
            mockMetricsGatherer = mock(MetricsGatherer.class);
            
            this.fvArray = fvArray;

            // mock methods for the MetricsGatherer class, should only need to mock getMethodMetrics() here
            when(mockMetricsGatherer.getMethodsMetrics())
                .thenReturn(this.fvArray);
            
        }
        
        public MetricsGatherer getMock() {
            return mockMetricsGatherer;
        }
    }    

    private float[] generateAndFillArray(int value){
        float[] array = new float[78]; // generate array for metrics
        Arrays.fill(array, value); // set every value in the array to the passed in value
        return array;
    }

    private UserSettingsModel model;
    private ProjectSettingsState settings;

    /**
    This is a test to make sure that if the model has a null metrics
    gatherer that it will always return 0 (do not pop-up)
     */
    public void testPredictEverythingNull(){
        assertEquals(model.predict(null), 0, 0);
    }

//    public void testReadSensitivityFromFrontend(){
//        //Mock the file and the scanner to make sure that each flag sensitivity will be set to low or 1
//        File mockedFile = Mockito.mock(File.class);
//        Scanner mockedScanner = Mockito.mock(Scanner.class);
//        Mockito.when(mockedFile.exists()).thenReturn(true);
//        Mockito.when(mockedScanner.nextLine()).thenReturn("low");
//
//        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();
//
//        float[] fvArrayValue1 = new float[78];
//
//        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
//
//        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
//        //This method will make a call to readSensitivityFromFrontend which we are testing
//        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());
//
//        assertEquals(1, model.getComplexitySensitivity());
//        assertEquals(1, model.getSizeSensitivity());
//        assertEquals(1, model.getKeywordsSensitivity() );
//
//    }

    public void testPredictEverythingOff(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        float[] fvArrayValue1 = new float[78];
        float[] fvArrayValue2 = new float[78];
        float[] fvArrayValue3 = new float[78];
        float[] fvArrayValue4 = new float[78];
        float[] fvArrayValue5 = new float[78];
        
        //Adding these values gives:
        // Q1 = 0
        // Q2 = 0
        // Q3 = 0
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        // turn everything off
        settings.keywordsRequired = true;
        settings.couplingRequired = true;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that would trip the flag, but should not
        float[] passedInArray = generateAndFillArray(1);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);

    }

    /**
    
    These tests will retest each of the cases from the flag tests
    to show that they are still valid when the flags are created
    via the metrics gatherer. If any of these tests fail, but the 
    flag tests are passing, it is an issue within the model.
    
     */
    public void testPredictOnlySizeOnSensOneTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

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

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.complexitySensitivity = 100;
        settings.keywordsSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.couplingSensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlySizeOnSensOneFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

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

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlySizeOnSensTwoTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

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

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 50;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 4;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlySizeOnSensTwoFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

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

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 50;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 2;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlySizeOnSensThreeTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

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

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 75;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlySizeOnSensThreeFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

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

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 75;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlyComplexityOnSensOneTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[3] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[3] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlyComplexityOnSensOneFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[3] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[3] = (float)1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        //System.out.println(model.predict(passedInFv.getMock()));
        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlyComplexityOnSensTwoTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[3] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 50;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[3] = (float)4;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlyComplexityOnSensTwoFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[3] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 50;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[3] = (float)2;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlyComplexityOnSensThreeTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[3] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 75;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[3] = (float)5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlyComplexityOnSensThreeFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[3] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 75;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[3] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlyKeywordsOnSensOneTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);
        
        //Adding these values gives:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlyKeywordsOnSensOneFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);
        
        //Adding these values gives:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlyKeywordsOnSensTwoTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);
        
        //Adding these values gives:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 50;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(4);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlyKeywordsOnSensTwoFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);
        
        //Adding these values gives:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 50;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(2);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictOnlyKeywordsOnSensThreeTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);
        
        //Adding these values gives:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 75;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(5);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictOnlyKeywordsOnSensThreeFalse(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);
        
        //Adding these values gives:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 75;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(3);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictSizeComplexityTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // The size category uses metric 1, so we set those
        // Complexity uses metric 4, so that will also be set
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        passedInArray[3] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictSizeComplexityFalseOneValue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // The size category uses metric 1, so we set those
        // Complexity uses metric 4, so that will also be set
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictSizeComplexityFalseBothValues(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // The size category uses metric 1, so we set those
        // Complexity uses metric 4, so that will also be set
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = false;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 100;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 1;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictSizeKeywordsTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metric 1, so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictSizeKeywordsFalseOneValue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metric 1, so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictSizeKeywordsFalseBothValues(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metrics 12, so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = false;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 100;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[0] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictComplexityKeywordsTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The complexity category uses metric 4 so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[3] = 3;
    
        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[3] = 4;
        
        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[3] = 5;
        
        //Adding these values gives complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[3] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictComplexityKeywordsFalseOneValue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The complexity category uses metric 4 so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[3] = 3;
    
        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[3] = 4;
        
        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[3] = 5;
        
        //Adding these values gives complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[3] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictComplexityKeywordsFalseBothValues(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The complexity category uses metric 4 so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[3] = 3;
    
        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[3] = 4;
        
        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[3] = 5;
        
        //Adding these values gives complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = false;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictAllFlagsTrue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metric 1, so we set those after the array is filled
        // The complexity category uses metric 4, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 3;
        passedInArray[3] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    public void testPredictAllFlagsFalseOneValue(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metric 1, so we set those after the array is filled
        // The complexity category uses metric 4, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 3;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictAllFlagsFalseTwoValues(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metrics 12, so we set those after the array is filled
        // The complexity category uses metric 4, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 1;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    public void testPredictAllFlagsFalseThreeValues(){
        List<FeaturesVector> fvList = new ArrayList<FeaturesVector>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metrics 12, so we set those after the array is filled
        // The complexity category uses metric 4, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[3] = 1;
        

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[3] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[3] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[3] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[3] = 5;
        
        //Adding these values gives size metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        //And keywords metrics of:
        // Q1 = 60
        // Q2 = 90
        // Q3 = 120
        //And complexity metrics of:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());

        MetricsGathererMock mockMg = new MetricsGathererMock(fvList);
        this.model.initMetricsGathererAndMetricsFlags(mockMg.getMock());

        settings.keywordsRequired = true;
        settings.couplingRequired = false;
        settings.sizeRequired = true;
        settings.complexityRequired = true;

        settings.keywordsSensitivity = 25;
        settings.couplingSensitivity = 100;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[0] = 1;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }
}