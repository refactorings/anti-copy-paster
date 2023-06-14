package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KeywordsMetrics extends Flag{
    private final int[] selectedMetrics;
    //TODO: replace with actual function to retrieve these numbers once advanced settings are made/integrated
    public KeywordsMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
        selectedMetrics = new int[62];
        int num = 16;
        for (int i = 0; i < selectedMetrics.length; i++) {
            selectedMetrics[i] = num;
            num++;
        }
        calculateAverageKeywordsMetrics();
    }

    /**
    This is a function that will get the keywords metric out of 
    the FeaturesVector that is passed in
    Keywords uses every odd-number value from metrics 17-78 
     */
    private float getKeywordsMetricFromFV(FeaturesVector fv, int index){
        if(fv != null){
            float[] fvArr = fv.buildArray();
            lastCalculatedMetric = fvArr[index];
            return lastCalculatedMetric;
        }
        lastCalculatedMetric = 0;
        return lastCalculatedMetric;
    }

    /**
    This will iterate over all of the FeaturesVectors passed in to the
    class, and then export only the relevant metric values to an array.
    That array will then be sorted and run through the Flag boxplot 
    method to get Q1, Q2, and Q3 for the sensitivities
     */
    private void calculateAverageKeywordsMetrics(){

        for(int metricNum: selectedMetrics){
            ArrayList<Float> sizeMetricsValues = new ArrayList<>();

            for(FeaturesVector f: featuresVectorList){
                sizeMetricsValues.add(getKeywordsMetricFromFV(f, metricNum));
            }
            Collections.sort(sizeMetricsValues);
            boxPlotCalculations(sizeMetricsValues);
        }
    }

    /**
     * Required override function from Flag. Gets the sensitivity for this metric
     * by grabbing its appropriate settings from this project's ProjectSettingsState.
     */
    @Override
    public int getSensitivity() {
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        return settings.keywordsSensitivity;
    }

    /**
    Required override function from Flag. This just compares the keywords
    of the passed in FeaturesVector against the correct quartile value 
    based on the box plot depending on whatever the sensitivity is.
     */
    @Override
    public boolean isFlagTriggered(FeaturesVector featuresVector){
        ArrayList<Boolean> metricsPassed = new ArrayList<>();
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        for(int i = 0; i < selectedMetrics.length; i++){
            float fvSizeValue = getKeywordsMetricFromFV(featuresVector, selectedMetrics[i]);
            int quartile = (int) Math.ceil(sensitivity / 25.0);
            switch(quartile) {
                case 1:
                    metricsPassed.add(true);
                case 2:
                    if(fvSizeValue >= metricQ1.get(i)){
                        metricsPassed.add(true);
                    }
                    else{
                        if(selectedMetrics[i] % 2 == 0){
                            if(settings.keywordsRequired) { //TODO: change keywordsRequired to measureKeywordsByTotal
                                return false; //since this field is required no need to go through the other keywords
                            }
                        }else {
                            if(settings.keywordsRequired) { //TODO: change keywordsRequired to measureKeywordsPerLine
                                return false; //since this field is required no need to go through the other keywords
                            }
                        }
                        metricsPassed.add(false);
                    }

                case 3:
                    if(fvSizeValue >= metricQ2.get(i)){
                        metricsPassed.add(true);
                    }
                    else{
                        if(selectedMetrics[i] % 2 == 0){
                            if(settings.keywordsRequired) { //TODO: change keywordsRequired to measureKeywordsByTotal
                                return false; //since this field is required no need to go through the other keywords
                            }
                        }else {
                            if(settings.keywordsRequired) { //TODO: change keywordsRequired to measureKeywordsPerLine
                                return false; //since this field is required no need to go through the other keywords
                            }
                        }
                        metricsPassed.add(false);
                    }
                case 4:
                    if(fvSizeValue >= metricQ3.get(i)){
                        metricsPassed.add(true);
                    }
                    else{
                        if(selectedMetrics[i] % 2 == 0){
                            if(settings.keywordsRequired) { //TODO: change keywordsRequired to measureKeywordsByTotal
                                return false; //since this field is required no need to go through the other keywords
                            }
                        }else {
                            if(settings.keywordsRequired) { //TODO: change keywordsRequired to measureKeywordsPerLine
                                return false; //since this field is required no need to go through the other keywords
                            }
                        }
                        metricsPassed.add(false);
                    }
                default:
                    if(selectedMetrics[i] % 2 == 0){
                        if(settings.keywordsRequired) { //TODO: change this to measureKeywordsByTotal
                            return false; //since this field is required no need to go through the other keywords
                        }
                    }else {
                        if(settings.keywordsRequired) { //TODO: change this to measureKeywordsPerLine
                            return false; //since this field is required no need to go through the other keywords
                        }
                    }
                    metricsPassed.add(false);
            }
        }
        for (boolean passed : metricsPassed) {
            if (passed) {
                return true;
            }
        }
        return false;
        
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    public void logMetric(String filepath){
        logMetric(filepath, "Keywords");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    public void logThresholds(String filepath){
        logThresholds(filepath, "Keywords");
    }
}