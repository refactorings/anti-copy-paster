package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

public class KeywordsMetrics extends Flag{
    private final int[] selectedMetrics;
    public KeywordsMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList, 61);
    }

    /**
    This is a function that will get the keywords metric out of 
    the FeaturesVector that is passed in
     */
    @Override
    protected float[] getMetric(FeaturesVector fv){ // TODO: Reconcile changed Flag definitions
        if (fv != null) {
            float[] fvArr = fv.buildArray();
            for (int i = 0; i < selectedMetrics.length; i++) {
                int metricIndex = selectedMetrics[i];
                lastCalculatedMetric[i] = fvArr[metricIndex];
            }
            return lastCalculatedMetric;
        } else {
            // Initialize lastCalculatedMetric array with zeros
            for (int i = 0; i < selectedMetrics.length; i++) {
                int metricIndex = selectedMetrics[i];
                lastCalculatedMetric[i] = 0;
            }
            return lastCalculatedMetric;
        }
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
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Keywords");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Keywords");
    }
}