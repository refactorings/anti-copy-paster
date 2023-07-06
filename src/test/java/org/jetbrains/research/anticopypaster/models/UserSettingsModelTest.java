package org.jetbrains.research.anticopypaster.models;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.utils.*;
import org.junit.jupiter.api.BeforeEach;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;


public class UserSettingsModelTest {

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

    private float[] generateAndFillArray(int value) {
        float[] array = new float[78]; // generate array for metrics
        Arrays.fill(array, value); // set every value in the array to the passed in value
        return array;
    }

    private UserSettingsModel model;
    private ProjectSettingsState settings;

    @BeforeEach
    public void beforeEach() {
        settings = new ProjectSettingsState();
        MetricsGatherer mockedMetricsGatherer = new MetricsGathererMock(null).getMock();
        Project mockedProject = new ProjectMock(settings).getMock();
        model = new UserSettingsModel(mockedMetricsGatherer, mockedProject);
    }

    /**
    This is a test to make sure that if the model has a null metrics
    gatherer that it will always return 0 (do not pop up)
     */
    @Test
    public void testPredictEverythingNull() {
        assertEquals(model.predict(null), 0, 0);
    }

    @Test
    public void testPredictEverythingOff() {
        List<FeaturesVector> fvList = new ArrayList<>();

        float[] fvArrayValue1 = new float[78];
        float[] fvArrayValue2 = new float[78];
        float[] fvArrayValue3 = new float[78];
        float[] fvArrayValue4 = new float[78];
        float[] fvArrayValue5 = new float[78];

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        // Make a FeaturesVector that would trip the flag, but should not
        float[] passedInArray = generateAndFillArray(1);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    /*
    These tests will retest each of the cases from the flag tests
    to show that they are still valid when the flags are created
    via the metrics gatherer. If any of these tests fail, but the 
    flag tests are passing, it is an issue within the model.
     */

    @Test
    public void testPredictOnlySizeOnSensOneTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.complexityEnabled = false;
        settings.keywordsEnabled = false;
        settings.sizeEnabled = true;
        settings.couplingEnabled = false;

        settings.sizeSensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlySizeOnSensOneFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.complexityEnabled = false;

        settings.sizeSensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlySizeOnSensTwoTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.sizeSensitivity = 50;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 4;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlySizeOnSensTwoFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.sizeSensitivity = 50;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 2;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlySizeOnSensThreeTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.sizeSensitivity = 75;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlySizeOnSensThreeFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.sizeSensitivity = 75;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlyComplexityOnSensOneTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // By default, the complexity metric uses only Metric 5 (index 4).
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[4] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlyComplexityOnSensOneFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // By default, the complexity metric uses only Metric 5 (index 4).
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.sizeSensitivity = 100;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[4] = (float)1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        //System.out.println(model.predict(passedInFv.getMock()));
        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlyComplexityOnSensTwoTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // By default, the complexity metric uses only Metric 5 (index 4).
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.complexitySensitivity = 50;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[4] = (float)4;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlyComplexityOnSensTwoFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // By default, the complexity metric uses only Metric 5 (index 4).
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.complexitySensitivity = 50;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[4] = (float)2;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlyComplexityOnSensThreeTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.complexitySensitivity = 75;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[4] = (float)5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlyComplexityOnSensThreeFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

         // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.complexitySensitivity = 75;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[4] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlyKeywordsOnSensOneTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlyKeywordsOnSensOneFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlyKeywordsOnSensTwoTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 50;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(4);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlyKeywordsOnSensTwoFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 50;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(2);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictOnlyKeywordsOnSensThreeTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 75;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(5);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictOnlyKeywordsOnSensThreeFalse() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // This category uses the odd metrics between 17-77, filled by helper method
        float[] fvArrayValue1 = generateAndFillArray(1);
        float[] fvArrayValue2 = generateAndFillArray(2);
        float[] fvArrayValue3 = generateAndFillArray(3);
        float[] fvArrayValue4 = generateAndFillArray(4);
        float[] fvArrayValue5 = generateAndFillArray(5);

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 75;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(3);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictSizeComplexityTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // The size category uses metric 1, so we set those
        // Complexity uses metric 5, so that will also be set
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        passedInArray[4] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictSizeComplexityFalseOneValue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // The size category uses metric 1, so we set those
        // Complexity uses metric 5, so that will also be set
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 3;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictSizeComplexityFalseBothValues() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // The size category uses metric 1, so we set those
        // Complexity uses metric 5, so that will also be set
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = false;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = new float[78];
        passedInArray[0] = 1;
        passedInArray[4] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictSizeKeywordsTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictSizeKeywordsFalseOneValue() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[0] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictSizeKeywordsFalseBothValues() {
        List<FeaturesVector> fvList = new ArrayList<>();

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = false;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[0] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictComplexityKeywordsTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77 by default, so we set those with the helper method
        // The complexity category uses metric 5 by default, so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[4] = 3;
    
        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[4] = 4;
        
        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[4] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictComplexityKeywordsFalseOneValue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77 by default, so we set those with the helper method
        // The complexity category uses metric 5 by default, so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[4] = 3;
    
        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[4] = 4;
        
        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[4] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictComplexityKeywordsFalseBothValues() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77 by default, so we set those with the helper method
        // The complexity category uses metric 5 by default, so we set those after the array is filled
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[4] = 3;
    
        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[4] = 4;
        
        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = false;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[4] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictAllFlagsTrue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metric 1, so we set those after the array is filled
        // The complexity category uses metric 5, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;
        
        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 3;
        passedInArray[4] = 3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 1, 0);
    }

    @Test
    public void testPredictAllFlagsFalseOneValue() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metric 1, so we set those after the array is filled
        // The complexity category uses metric 5, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;
        
        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 3;
        passedInArray[4] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictAllFlagsFalseTwoValues() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metrics 12, so we set those after the array is filled
        // The complexity category uses metric 5, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(3);
        passedInArray[0] = 1;
        passedInArray[3] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }

    @Test
    public void testPredictAllFlagsFalseThreeValues() {
        List<FeaturesVector> fvList = new ArrayList<>();

        // Keywords uses every odd metric from 17-77, so we set those with the helper method
        // The size category uses metrics 12, so we set those after the array is filled
        // The complexity category uses metric 5, so we set that afterwards as well
        float[] fvArrayValue1 = generateAndFillArray(1);
        fvArrayValue1[0] = 1;
        fvArrayValue1[4] = 1;

        float[] fvArrayValue2 = generateAndFillArray(2);
        fvArrayValue2[0] = 2;
        fvArrayValue2[4] = 2;

        float[] fvArrayValue3 = generateAndFillArray(3);
        fvArrayValue3[0] = 3;
        fvArrayValue3[4] = 3;

        float[] fvArrayValue4 = generateAndFillArray(4);
        fvArrayValue4[0] = 4;
        fvArrayValue4[4] = 4;

        float[] fvArrayValue5 = generateAndFillArray(5);
        fvArrayValue5[0] = 5;
        fvArrayValue5[4] = 5;

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

        settings.keywordsEnabled = true;
        settings.couplingEnabled = false;
        settings.sizeEnabled = true;
        settings.complexityEnabled = true;

        settings.keywordsSensitivity = 25;
        settings.sizeSensitivity = 25;
        settings.complexitySensitivity = 25;

        // Make a FeaturesVector that will NOT trip the flag
        float[] passedInArray = generateAndFillArray(1);
        passedInArray[0] = 1;
        passedInArray[4] = 1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertEquals(model.predict(passedInFv.getMock()), 0, 0);
    }
}