package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import org.mockito.Mock;

import static org.mockito.Mockito.*;

public class ComplexityMetricsTest {

    /**
     * Testing variant of ComplexityMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    private class TestingComplexityMetrics extends ComplexityMetrics {

        public TestingComplexityMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        public int getSensitivity() {
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
            when(mockFeaturesVector.getFeatureValue(any(Feature.class)))
                    .thenAnswer(invocation -> (double) metricsArray[((Feature) invocation.getArgument(0)).getId()]);
        }
        
        public FeaturesVector getMock() {
            return mockFeaturesVector;
        }
    }

    private TestingComplexityMetrics complexityMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        //Zero out everything
        this.complexityMetrics = null;
        this.fvList = new ArrayList<>();
    }
    
    @Test
    public void testIsTriggeredSensitivityZero(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 100;
        assertFalse(complexityMetrics.isFlagTriggered(null));
    }

    @Test
    public void testIsTriggeredSensitivityOneTrue(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
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


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 25;


        float[] passedInArray = new float[78];
        passedInArray[11] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityOneFalse(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[11] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 25;


        float[] passedInArray = new float[78];
        passedInArray[11] = (float)1;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);



        assertFalse(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityTwoTrue(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[11] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 50;

        float[] passedInArray = new float[78];
        passedInArray[11] = (float)4;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityTwoFalse(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[3] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 50;

        float[] passedInArray = new float[78];
        passedInArray[11] = (float)2;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityThreeTrue(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[11] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[11] = (float)5;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredSensitivityThreeFalse(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        // This category only uses metric 4, which would be index 3 here
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[11] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        
        //Adding these values gives:
        // Q1 = 2
        // Q2 = 3
        // Q3 = 4
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 75;

        float[] passedInArray = new float[78];
        passedInArray[11] = (float)3;
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testIsTriggeredMultiMetricSensitivityOne(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[11] = 1;
        fvArrayValue1[12] = 12;
        fvArrayValue1[13] = 12;
        fvArrayValue1[14] = 1;
        fvArrayValue1[15] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;
        fvArrayValue2[12] = 5;
        fvArrayValue2[13] = 5;
        fvArrayValue2[14] = 2;
        fvArrayValue2[15] = 1;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;
        fvArrayValue3[12] = 2;
        fvArrayValue3[13] = 2;
        fvArrayValue3[14] = 3;
        fvArrayValue3[15] = 1;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;
        fvArrayValue4[12] = 0;
        fvArrayValue4[13] = 7;
        fvArrayValue4[14] = 4;
        fvArrayValue4[15] = 1;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        fvArrayValue5[12] = 4;
        fvArrayValue5[13] = 24;
        fvArrayValue5[14] = 5;
        fvArrayValue5[15] = 1;

        //Adding these values gives:
        // Q1 = 2, 2, 5, 2, 1
        // Q2 = 3, 4, 7, 3, 1
        // Q3 = 4, 5,12, 4, 1
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 25;


        float[] passedInArray = new float[78];
        passedInArray[11] = 3;
        passedInArray[12] = 1;
        passedInArray[13] = 5;
        passedInArray[14] = 10;
        passedInArray[15] = 0;

        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }
    @Test
    public void testIsNotTriggeredMultiMetricSensitivityOne(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        float[] fvArrayValue1 = new float[78];
        fvArrayValue1[11] = 1;
        fvArrayValue1[12] = 12;
        fvArrayValue1[13] = 12;
        fvArrayValue1[14] = 1;
        fvArrayValue1[15] = 1;

        float[] fvArrayValue2 = new float[78];
        fvArrayValue2[11] = 2;
        fvArrayValue2[12] = 5;
        fvArrayValue2[13] = 5;
        fvArrayValue2[14] = 2;
        fvArrayValue2[15] = 1;

        float[] fvArrayValue3 = new float[78];
        fvArrayValue3[11] = 3;
        fvArrayValue3[12] = 2;
        fvArrayValue3[13] = 2;
        fvArrayValue3[14] = 3;
        fvArrayValue3[15] = 1;

        float[] fvArrayValue4 = new float[78];
        fvArrayValue4[11] = 4;
        fvArrayValue4[12] = 0;
        fvArrayValue4[13] = 7;
        fvArrayValue4[14] = 4;
        fvArrayValue4[15] = 1;

        float[] fvArrayValue5 = new float[78];
        fvArrayValue5[11] = 5;
        fvArrayValue5[12] = 4;
        fvArrayValue5[13] = 24;
        fvArrayValue5[14] = 5;
        fvArrayValue5[15] = 1;

        //Adding these values gives:
        // Q1 = 2, 2, 5, 2, 1
        // Q2 = 3, 4, 7, 3, 1
        // Q3 = 4, 5,12, 4, 1
        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.complexityMetrics = new TestingComplexityMetrics(fvList);
        settings.complexitySensitivity = 25;

        float[] passedInArray = new float[78];
        passedInArray[11] = 1;
        passedInArray[12] = 1;
        passedInArray[13] = 4;
        passedInArray[14] = 1;
        passedInArray[15] = 0;

        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(complexityMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    
}