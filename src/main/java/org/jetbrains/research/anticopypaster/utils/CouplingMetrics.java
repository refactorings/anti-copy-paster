package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CouplingMetrics extends Flag{

    public CouplingMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
    }

    /**
    This is a function that will get the coupling metric out of
    the FeaturesVector that is passed in
    Coupling uses one of Metrics 6 through 11, according to this scheme:
        * Metric index 5: Total connectivity
        * Metric index 6: Total connectivity per line
        * Metric index 7: Field connectivity
        * Metric index 8: Field connectivity per line
        * Metric index 9: Method connectivity
        * Metric index 10: Method connectivity per line
     */
    @Override
    protected float getMetric(FeaturesVector fv){
        if(fv != null){
            lastCalculatedMetric = fv.buildArray()[6];
            return lastCalculatedMetric;
        } else {
            return 0;
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
        return settings.couplingSensitivity;
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Coupling");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Coupling");
    }
}