package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SizeMetrics extends Flag{
    private  ArrayList<Integer> selectedMetrics = new ArrayList<>(List.of(0 , 1));
    //TODO: create method to retrieve/change these values from frontend

    public SizeMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList, 2);

    }
    @Override
    protected void setSelectedMetrics(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);

        if(settings.measureSizeByLines[0]){
            selectedMetrics.add(0);
        }
        if(settings.measureSizeBySymbols[0]){
            selectedMetrics.add(1);
        }
        numFeatures = selectedMetrics.size();
    }

    /**
    Size can be defined as either the number of lines or number of symbols in a code body.
     getMetric() returns the relevant metric depending on the user's settings.
     */
    @Override
    protected float[] getMetric(FeaturesVector fv){
        if (fv != null) {
            float[] fvArr = fv.buildArray();
            for (int i = 0; i < selectedMetrics.size(); i++) {
                int metricIndex = selectedMetrics.get(i);
                lastCalculatedMetric[i] = fvArr[metricIndex];
            }
            return lastCalculatedMetric;
        } else {
            // Initialize lastCalculatedMetric array with zeros
            for (int i = 0; i < selectedMetrics.size(); i++) {
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
        return settings.sizeSensitivity;
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Size");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Size");
    }
}