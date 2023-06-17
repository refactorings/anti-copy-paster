package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ComplexityMetrics extends Flag{
    private final int[] selectedMetrics = {3, 4};
    public ComplexityMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList, 2);
    }

    /**
    This is a function that will get the complexity metric out of 
    the FeaturesVector that is passed in
    Complexity only uses Metrics #4 and #5, so getting the value at index 3 or 4 (depending on user settings)
    from the fv array gives us the right value
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
        return settings.complexitySensitivity;
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Complexity");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Complexity");
    }
}