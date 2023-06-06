package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KeywordsMetrics extends Flag{

    public KeywordsMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
        calculateAverageKeywordsMetrics();
    }

    /**
    This is a function that will get the keywords metric out of 
    the FeaturesVector that is passed in
    Keywords uses every odd-number value from metrics 17-78 
     */
    private float getKeywordsMetricFromFV(FeaturesVector fv){
        if(fv != null){
            float[] fvArray = fv.buildArray();
            int totalKeywords = 0;
            for(int i = 16; i<77; i+=2){
                totalKeywords += fvArray[i];
            }
            lastCalculatedMetric = totalKeywords;
            return lastCalculatedMetric;
        } else {
            return 0;
        }
    }

    /**
    This will iterate over all of the FeaturesVectors passed in to the
    class, and then export only the relevant metric values to an array.
    That array will then be sorted and run through the Flag boxplot 
    method to get Q1, Q2, and Q3 for the sensitivities
     */
    private void calculateAverageKeywordsMetrics(){
        ArrayList<Float> keywordsMetricsValues = new ArrayList<Float>();

        for(FeaturesVector f : featuresVectorList){
            keywordsMetricsValues.add(getKeywordsMetricFromFV(f));
        }

        Collections.sort(keywordsMetricsValues);
        boxPlotCalculations(keywordsMetricsValues);
    }

    /**
     * Required override function from Flag. Gets the sensitivity for this metric
     * by grabbing its appropriate settings from this project's ProjectSettingsState.
     */
    @Override
    protected int getSensitivity() {
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
        float fvKeywordsValue = getKeywordsMetricFromFV(featuresVector);

        int quartile = (int) Math.ceil((getSensitivity() + 1) / 25.0);
        switch(quartile) {
            case 1:
                return true;
            case 2:
                return fvKeywordsValue >= metricQ1;
            case 3:
                return fvKeywordsValue >= metricQ2;
            case 4:
                return fvKeywordsValue >= metricQ3;
            default:
                return false;
        }
        
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