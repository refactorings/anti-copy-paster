package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ComplexityMetrics extends Flag{
    private final int[] selectedMetrics = {11, 12, 13, 14, 15};
    //TODO: replace with actual function to retrieve these numbers once advanced settings are made/integrated
    public ComplexityMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
        calculateAverageComplexityMetrics();
    }

    /**
    This is a function that will get the complexity metric out of 
    the FeaturesVector that is passed in
    Complexity only uses Metric #4, so getting the value at index 3
    from the fv array gives us the right value
     */
    private float getComplexityMetricFromFV(FeaturesVector fv, int index){
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
    private void calculateAverageComplexityMetrics(){
        for(int metricNum: selectedMetrics){
            ArrayList<Float> sizeMetricsValues = new ArrayList<>();

            for(FeaturesVector f: featuresVectorList){
                sizeMetricsValues.add(getComplexityMetricFromFV(f, metricNum));
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
        return settings.complexitySensitivity;
    }

    /**
    Required override function from Flag. This just compares the complexity
    of the passed in FeaturesVector against the correct quartile value 
    based on the box plot depending on whatever the sensitivity is.
     */
    @Override
    public boolean isFlagTriggered(FeaturesVector featuresVector){
        ArrayList<Boolean> metricsPassed = new ArrayList<>();
        for(int i = 0; i < selectedMetrics.length; i++){
            float fvSizeValue = getComplexityMetricFromFV(featuresVector, selectedMetrics[i]);
            int quartile = (int) Math.ceil(sensitivity / 25.0);
            switch(quartile) {
                case 1:
                    metricsPassed.add(true);
                case 2:
                    if(fvSizeValue >= metricQ1.get(i)){
                        metricsPassed.add(true);
                    }
                    else{metricsPassed.add(false);}

                case 3:
                    if(fvSizeValue >= metricQ2.get(i)){
                        metricsPassed.add(true);
                    }
                    else{metricsPassed.add(false);}
                case 4:
                    if(fvSizeValue >= metricQ3.get(i)){
                        metricsPassed.add(true);
                    }
                    else{metricsPassed.add(false);}
                default:
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
        logMetric(filepath, "Complexity");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    public void logThresholds(String filepath){
        logThresholds(filepath, "Complexity");
    }
}