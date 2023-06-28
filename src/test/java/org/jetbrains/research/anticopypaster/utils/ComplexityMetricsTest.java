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
        //Stores a projectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;

        public TestingComplexityMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        public int getSensitivity() {
            return sensitivity;
        }
        @Override
        protected ProjectSettingsState retrieveCurrentSettings(){
            if(settings == null){
                this.settings = new ProjectSettingsState();
            }
            return this.settings;
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
        this.complexityMetrics = null;
    }
    @Test
    public void testSetSelectedMetrics_SelectedMetrics(){
        complexityMetrics = new TestingComplexityMetrics(fvList);

        assertEquals(2, complexityMetrics.selectedMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.selectedMetrics.get(0));
        assertEquals(Feature.MethodDeclarationAreaPerLine, complexityMetrics.selectedMetrics.get(1));
    }
    @Test
    public void testSetSelectedMetrics_RequiredMetrics(){
        complexityMetrics = new TestingComplexityMetrics(fvList);

        assertEquals(1, complexityMetrics.requiredMetrics.size());
        assertEquals(Feature.AreaPerLine, complexityMetrics.requiredMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_ChangeSettings(){
        complexityMetrics = new TestingComplexityMetrics(fvList);

        complexityMetrics.settings.measureComplexityTotal[0] = true;
        complexityMetrics.settings.measureComplexityTotal[1] = true;

        complexityMetrics.selectedMetrics.clear();
        complexityMetrics.requiredMetrics.clear();
        complexityMetrics.setSelectedMetrics();

        assertEquals(3, complexityMetrics.selectedMetrics.size());
        assertEquals(2, complexityMetrics.requiredMetrics.size());

        assertEquals(Feature.Area, complexityMetrics.selectedMetrics.get(0));
        assertEquals(Feature.AreaPerLine, complexityMetrics.selectedMetrics.get(1));
        assertEquals(Feature.MethodDeclarationAreaPerLine, complexityMetrics.selectedMetrics.get(2));

        assertEquals(Feature.Area, complexityMetrics.requiredMetrics.get(0));
        assertEquals(Feature.AreaPerLine, complexityMetrics.requiredMetrics.get(1));

    }
    
}