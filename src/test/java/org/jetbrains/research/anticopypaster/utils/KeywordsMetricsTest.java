package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

public class KeywordsMetricsTest {

    /**
     * Testing variant of KeywordsMetrics.
     * Stores sensitivity setting locally rather than through IntelliJ project settings.
     */
    private class TestingKeywordsMetrics extends KeywordsMetrics {
        //Stores a projectSettingsState variable locally to adjust settings for testing
        private ProjectSettingsState settings;

        public TestingKeywordsMetrics(List<FeaturesVector> featuresVectorList) {
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

    private float[] generateArrayForKeywordsPopulatedByValue(float value){
        float[] floatArr = new float[78];
        for (int i = 16; i <= 77; i++ ) {
            floatArr[i] = value;
        }
        return floatArr;
    }

    private TestingKeywordsMetrics keywordsMetrics;
    private List<FeaturesVector> fvList;

    @BeforeEach
    public void beforeTest(){
        this.keywordsMetrics = null;
        this.fvList = new ArrayList<>();
    }
    
    @Test
    public void testSetSelectedMetrics_DefaultSettings() {
        keywordsMetrics = new TestingKeywordsMetrics(fvList);

        assertEquals(31, keywordsMetrics.selectedMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(17 + 2 * i), keywordsMetrics.selectedMetrics.get(i));
        assertEquals(31, keywordsMetrics.requiredMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(17 + 2 * i), keywordsMetrics.requiredMetrics.get(i));
    }

    @Test
    public void testSetSelectedMetrics_ChangeSettings(){
        keywordsMetrics = new TestingKeywordsMetrics(fvList);

        keywordsMetrics.settings.measureKeywordsTotal[0] = true;
        keywordsMetrics.settings.measureKeywordsTotal[1] = true;
        keywordsMetrics.settings.measureKeywordsDensity[0] = true;
        keywordsMetrics.settings.measureKeywordsDensity[1] = false;

        keywordsMetrics.selectedMetrics.clear();
        keywordsMetrics.requiredMetrics.clear();
        keywordsMetrics.setSelectedMetrics();

        assertEquals(62, keywordsMetrics.selectedMetrics.size());
        for (int i = 0; i < 62; i++)
            assertEquals(Feature.fromId(16 + i), keywordsMetrics.selectedMetrics.get(i));
        assertEquals(31, keywordsMetrics.requiredMetrics.size());
        for (int i = 0; i < 31; i++)
            assertEquals(Feature.fromId(16 + 2 * i), keywordsMetrics.requiredMetrics.get(i));

    }

    @Test
    public void testNullInputFalse(){
        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 1;
        assertFalse(keywordsMetrics.isFlagTriggered(null));
    }


    @Test
    public void testMinimumThresholdTrue(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 1;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(3);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testMinimumThresholdFalse(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 1;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(1);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testModerateThresholdTrue(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 50;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(4);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testModerateThresholdFalse(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 50;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(2);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testHighThresholdTrue(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 75;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(5);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testHighThresholdFalse(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 75;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(3);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testMaximumThresholdTrue(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 100;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(6);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertTrue(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

    @Test
    public void testMaximumThresholdFalse(){

        float[] fvArrayValue1 = generateArrayForKeywordsPopulatedByValue(1);
        float[] fvArrayValue2 = generateArrayForKeywordsPopulatedByValue(2);
        float[] fvArrayValue3 = generateArrayForKeywordsPopulatedByValue(3);
        float[] fvArrayValue4 = generateArrayForKeywordsPopulatedByValue(4);
        float[] fvArrayValue5 = generateArrayForKeywordsPopulatedByValue(5);

        fvList.add(new FeaturesVectorMock(fvArrayValue1).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue2).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue3).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue4).getMock());
        fvList.add(new FeaturesVectorMock(fvArrayValue5).getMock());


        this.keywordsMetrics = new TestingKeywordsMetrics(fvList);
        sensitivity = 100;

        float[] passedInArray = generateArrayForKeywordsPopulatedByValue(5);
        FeaturesVectorMock passedInFv = new FeaturesVectorMock(passedInArray);

        assertFalse(keywordsMetrics.isFlagTriggered(passedInFv.getMock()));
    }

}