package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

public class CouplingMetricsTest {
    /**
     * Testing variant of CouplingMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    private static class TestingCouplingMetrics extends CouplingMetrics {
        //Stores a ProjectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;
        private int sensitivity;

        public TestingCouplingMetrics(List<FeaturesVector> featuresVectorList) {
            super(featuresVectorList, null);
        }

        @Override
        public int getSensitivity() {return sensitivity;}
        @Override
        protected ProjectSettingsState retrieveCurrentSettings(){
            if(settings == null){
                this.settings = new ProjectSettingsState();
            }
            return this.settings;
        }
    }

    private TestingCouplingMetrics couplingMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        //Zero out everything
        this.couplingMetrics = null;
        this.fvList = new ArrayList<>();
    }
    @Test
    public void testSetSelectedMetrics_SelectedMetrics(){
        couplingMetrics = new TestingCouplingMetrics(fvList);

        assertEquals(1, couplingMetrics.selectedMetrics.size());
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.selectedMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_RequiredMetrics(){
        couplingMetrics = new TestingCouplingMetrics(fvList);

        assertEquals(1, couplingMetrics.requiredMetrics.size());
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.requiredMetrics.get(0));
    }
    @Test
    public void testSetSelectedMetrics_ChangeSettings(){
        couplingMetrics = new TestingCouplingMetrics(fvList);

        couplingMetrics.settings.measureCouplingTotal[0] = true;
        couplingMetrics.settings.measureCouplingTotal[1] = false;

        couplingMetrics.selectedMetrics.clear();
        couplingMetrics.requiredMetrics.clear();
        couplingMetrics.setSelectedMetrics();

        assertEquals(2, couplingMetrics.selectedMetrics.size());
        assertEquals(1, couplingMetrics.requiredMetrics.size());

        assertEquals(Feature.TotalConnectivity, couplingMetrics.selectedMetrics.get(0));
        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.selectedMetrics.get(1));

        assertEquals(Feature.TotalConnectivityPerLine, couplingMetrics.requiredMetrics.get(0));

    }
}
