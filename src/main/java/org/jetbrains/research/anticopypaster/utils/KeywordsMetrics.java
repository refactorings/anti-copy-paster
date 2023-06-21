package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;
import org.jetbrains.research.anticopypaster.config.advanced.NewAdvancedProjectSettingsComponent.JavaKeywords;
public class KeywordsMetrics extends Flag{

    public KeywordsMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
    }

    /**
    This is a function that will get the keywords metric out of 
    the FeaturesVector that is passed in
     */
    protected void setSelectedMetrics(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        int metricNum = 16;
        for(JavaKeywords keyword : JavaKeywords.values()) {
            if (settings.activeKeywords.get(keyword)) {
                if (settings.measureKeywordsTotal[0]) {
                    selectedMetrics.add(metricNum);
                    if (settings.measureKeywordsTotal[1])
                        requiredMetrics.add(metricNum);
                }
                metricNum++;
                if (settings.measureKeywordsDensity[0]) {
                    selectedMetrics.add(metricNum);
                    if (settings.measureKeywordsDensity[1])
                        requiredMetrics.add(metricNum);
                }
                metricNum++;
            } else metricNum += 2;
        }
        numFeatures = selectedMetrics.size();
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