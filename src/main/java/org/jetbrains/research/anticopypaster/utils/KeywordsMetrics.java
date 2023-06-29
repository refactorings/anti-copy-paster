package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;
import org.jetbrains.research.anticopypaster.config.advanced.AdvancedProjectSettingsComponent.JavaKeywords;

public class KeywordsMetrics extends Flag{

    public KeywordsMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
     * Keywords uses features index 16 to 77, according to this scheme:
         * Even indices: Total keywords
         * (DEFAULT) Odd indices: Keyword density
         * Each even-odd pair of indices corresponds to one Java keyword.
     */
    protected void setSelectedMetrics(){
        ProjectSettingsState settings = retrieveCurrentSettings();
        int metricNum = 16;
        for(JavaKeywords keyword : JavaKeywords.values()) {
            if (settings.activeKeywords.get(keyword)) {
                if (settings.measureKeywordsTotal[0]) {
                    selectedMetrics.add(Feature.fromId(metricNum));
                    if (settings.measureKeywordsTotal[1])
                        requiredMetrics.add(Feature.fromId(metricNum));
                }
                metricNum++;
                if (settings.measureKeywordsDensity[0]) {
                    selectedMetrics.add(Feature.fromId(metricNum));
                    if (settings.measureKeywordsDensity[1])
                        requiredMetrics.add(Feature.fromId(metricNum));
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
        //Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = retrieveCurrentSettings();
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