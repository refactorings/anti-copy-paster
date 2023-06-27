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

public class SizeMetricsTest {

    /**
     * Testing variant of SizeMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    @Mock
    private ProjectSettingsState settings;

    private class TestingSizeMetrics extends SizeMetrics {
        //Stores a projectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;

        public TestingSizeMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
            this.settings = new ProjectSettingsState();
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

    private TestingSizeMetrics sizeMetrics;
    private List<FeaturesVector> fvList;
    @Test
    public void testSetSelectedMetrics_SelectedMetrics(){
        sizeMetrics = new TestingSizeMetrics(fvList);

        assertEquals(1, sizeMetrics.selectedMetrics.size());
        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.selectedMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_RequiredMetrics(){
        sizeMetrics = new TestingSizeMetrics(fvList);

        assertEquals(1, sizeMetrics.requiredMetrics.size());
        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.requiredMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_ChangedSettings(){
        sizeMetrics = new TestingSizeMetrics(fvList);

        sizeMetrics.settings.measureSizeBySymbols[0] = true;
        sizeMetrics.settings.measureSizeBySymbols[1] = true;
        sizeMetrics.settings.measureSizeBySymbolsPerLine[0] = true;

        sizeMetrics.selectedMetrics.clear();
        sizeMetrics.requiredMetrics.clear();
        sizeMetrics.setSelectedMetrics();

        assertEquals(3, sizeMetrics.selectedMetrics.size());
        assertEquals(2, sizeMetrics.requiredMetrics.size());

        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.selectedMetrics.get(0));
        assertEquals(Feature.TotalSymbols, sizeMetrics.selectedMetrics.get(1));
        assertEquals(Feature.SymbolsPerLine, sizeMetrics.selectedMetrics.get(2));

        assertEquals(Feature.TotalLinesOfCode, sizeMetrics.requiredMetrics.get(0));
        assertEquals(Feature.TotalSymbols, sizeMetrics.requiredMetrics.get(1));
    }
}

