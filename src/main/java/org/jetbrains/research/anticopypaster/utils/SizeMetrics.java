package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SizeMetrics extends Flag{

    public SizeMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
    Size can be defined as either the number of lines or number of symbols in a code body.
     getMetric() returns the relevant metric depending on the user's settings.
     */
    @Override
    protected float getMetric(FeaturesVector fv){
        if(fv != null){
            //Project project = ProjectManager.getInstance().getOpenProjects()[0];
            ProjectSettingsState settings = project.getService(ProjectSettingsState.class);

            int sizeMetricIndex = 0;
            if (!settings.defineSizeByLines) { sizeMetricIndex = 1; }

            float[] fvArr = fv.buildArray();
            lastCalculatedMetric = fvArr[sizeMetricIndex];
            return lastCalculatedMetric;
        }
        lastCalculatedMetric = 0;
        return lastCalculatedMetric;
    }

    /**
     * Required override function from Flag. Gets the sensitivity for this metric
     * by grabbing its appropriate settings from this project's ProjectSettingsState.
     */
    @Override
    protected int getSensitivity() {
        //Project project = ProjectManager.getInstance().getOpenProjects()[0];
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