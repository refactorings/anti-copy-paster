package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.research.anticopypaster.config.advanced.NewAdvancedProjectSettingsComponent.JavaKeywords;
public class KeywordsMetrics extends Flag{
    private ArrayList<Integer> selectedMetrics = new ArrayList<Integer>();
    public KeywordsMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList, 61);
    }

    /**
    This is a function that will get the keywords metric out of 
    the FeaturesVector that is passed in
     */
    protected void setSelectedMetrics(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        int count = 16;
        for(JavaKeywords keyword : JavaKeywords.values()) {
            if(settings.measureKeywordsTotal[0]){
                if (settings.activeKeywords.get(keyword)) {
                    selectedMetrics.add(count);
                }
            }count++;
            if(settings.measureKeywordsDensity[0]){
                if (settings.activeKeywords.get(keyword)) {
                    selectedMetrics.add(count);
                }
            }

            count++;
        }
        numFeatures = selectedMetrics.size();
    }
    @Override
    protected float[] getMetric(FeaturesVector fv){ // TODO: Reconcile changed Flag definitions
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
                int metricIndex = selectedMetrics.get(i);
                lastCalculatedMetric[i] = 0;
            }
            return lastCalculatedMetric;
        }
    }
    /**
     * Returns whether the given feature vector should 'trigger' this flag
     * based on whether the metric calculated from this feature vector
     * exceeds the given threshold.
     * (Recalculates the threshold value if the sensitivity has changed.)
     */
    @Override
    public boolean isFlagTriggered(FeaturesVector featuresVector) {
        int sensitivity = getSensitivity();
        if (sensitivity != cachedSensitivity) {
            cachedSensitivity = sensitivity;
            calculateThreshold();
        }
        lastCalculatedMetric = getMetric(featuresVector);

        ArrayList<Boolean> metricsPassed = new ArrayList<>();
        for (int i = 0; i < numFeatures; i++) {
            if (lastCalculatedMetric[i] > thresholds[i]) {
                metricsPassed.add(true);
            } else {
                metricsPassed.add(false);
            }
        }
        // Check if there is at least one 'true' in metricsPassed
        return (metricsPassed.contains(true));
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