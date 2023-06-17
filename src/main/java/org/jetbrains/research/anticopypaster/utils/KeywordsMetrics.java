package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

public class KeywordsMetrics extends Flag{

    public KeywordsMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList, 61);
    }

    /**
    This is a function that will get the keywords metric out of 
    the FeaturesVector that is passed in
     */
    @Override
    protected float getMetric(FeaturesVector fv){ // TODO: Reconcile changed Flag definitions
        if(fv != null) {
            float[] fvArray = fv.buildArray();
            int totalKeywords = 0;
            for(int i = 16; i<77; i+=2) {
                totalKeywords += fvArray[i];
            }

            lastCalculatedMetric = totalKeywords;
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