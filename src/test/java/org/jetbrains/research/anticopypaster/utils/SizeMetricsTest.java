package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

public class SizeMetricsTest {

    /**
     * Testing variant of SizeMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */

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
        protected ProjectSettingsState retrieveCurrentSettings() {
            if (settings == null){
                this.settings = new ProjectSettingsState();
            }
            return this.settings;
        }
    }

    private int sensitivity;


    private TestingSizeMetrics sizeMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        this.sizeMetrics = null;
        this.fvList = new ArrayList<>();
    }
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

