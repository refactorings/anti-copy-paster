package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class Flag{

    protected List<FeaturesVector> featuresVectorList;

    protected float[] thresholds;

    protected float[] lastCalculatedMetric;
    protected Project project;
    protected int cachedSensitivity;

    protected ArrayList<Feature> selectedMetrics = new ArrayList<>();
    protected ArrayList<Feature> requiredMetrics = new ArrayList<>();
    protected int numFeatures;

    protected abstract int getSensitivity();
    protected abstract void setSelectedMetrics();

    protected float[] getMetric(FeaturesVector fv){
        lastCalculatedMetric = new float[selectedMetrics.size()];
        if (fv != null) {
            for (int i = 0; i < selectedMetrics.size(); i++) {
                Feature feature = selectedMetrics.get(i);
                lastCalculatedMetric[i] = (float) fv.getFeatureValue(feature);
            }
        } else {
            // Initialize lastCalculatedMetric array with zeros
            for (int i = 0; i < selectedMetrics.size(); i++) {
                lastCalculatedMetric[i] = 0;
            }
        }
        return lastCalculatedMetric;
    }
    //TODO: even here when a test object is created the getInstance() still returns null
    public Flag(List<FeaturesVector> featuresVectorList, Project project){
        this.featuresVectorList = featuresVectorList;
        this.lastCalculatedMetric = null;
        this.project = project;
        setSelectedMetrics();
        calculateThreshold();
    }

    /**
     * Recalculates this Flag's threshold from its current sensitivity value.
     * This is done by calculating its relevant metrics for each of its FVs,
     * sorting the resulting list, and grabbing the element of the list at
     * the same relative position in the list as the sensitivity value is
     * within the range of 0 to 100.
     */
    public void calculateThreshold() {
        thresholds = new float[numFeatures];
        if (featuresVectorList != null && featuresVectorList.size() != 0) {
            // FeaturesVector list exists and has at least one element.
            if (featuresVectorList.size() == 1) {
                thresholds = getMetric(featuresVectorList.get(0));
            } else {
                float[][] metricValues = new float[numFeatures][featuresVectorList.size()];
                for (int i = 0; i < featuresVectorList.size(); i++) {
                    float[] metric = getMetric(featuresVectorList.get(i));
                    for (int j = 0; j < numFeatures; j++) {
                        metricValues[j][i] = metric[j];
                    }
                }
                for (float[] metricValue : metricValues)
                    Arrays.sort(metricValue);

                if (getSensitivity() == 100) {
                    for (int k = 0; k < numFeatures; k++)
                        thresholds[k] = metricValues[k][metricValues[k].length - 1];
                } else {
                    double position = (double) getSensitivity() * (featuresVectorList.size() - 1) / 100;
                    int lowerIndex = (int) Math.floor(position);
                    float proportion = (float) position % 1;
                    for (int l = 0; l < numFeatures; l++)
                        thresholds[l] = (1 - proportion) * metricValues[l][lowerIndex]
                                + proportion * metricValues[l][lowerIndex + 1];
                }
            }
        }
    }

    /**
     * Returns whether the given feature vector should 'trigger' this flag
     * based on whether the metric calculated from this feature vector
     * exceeds the given threshold.
     * (Recalculates the threshold value if the sensitivity has changed.)
     */
    public boolean isFlagTriggered(FeaturesVector featuresVector) {
        int sensitivity = getSensitivity();
        if (sensitivity != cachedSensitivity) {
            cachedSensitivity = sensitivity;
            calculateThreshold();
        }
        lastCalculatedMetric = getMetric(featuresVector);
        boolean flagTripped = false;
        for (int i = 0; i < numFeatures; i++) {
            if (lastCalculatedMetric[i] > thresholds[i]) {
                flagTripped = true;
            } else {
                for (Feature requiredMetric : requiredMetrics) {
                    if (requiredMetric == selectedMetrics.get(i)) {
                        // Required metric does not pass.
                        return false;
                    }
                }
            }
        }
        // Return true only if at least one metric has passed.
        return flagTripped;
    }

    /**
     * This function logs the last known metric and the current threshold
     * @param filepath path to the log file
     * @param metricName name of the metric
     */
    protected void logMetric(String filepath, String metricName){
        try(FileWriter fr = new FileWriter(filepath, true)){
            fr.write("Current " + metricName +
                    " Threshold, Last Calculated Metric: " +
                    Arrays.toString(thresholds) + ", " + Arrays.toString(lastCalculatedMetric) + "\n");
        } catch(IOException ioe) {ioe.printStackTrace();}
    }

    /**
     * Abstract logMetric method which is required to be implemented.
     * This allows descendants to call the above logMetric with the name
     * of their metric, and have outside classes only require filepath
     * @param filepath path to the log file
     */
    public abstract void logMetric(String filepath);

    /**
     * This function logs the thresholds of the metrics
     * @param filepath path to the log file
     * @param metricName the name of the metric category
     */
    protected void logThresholds(String filepath, String metricName){
        try(FileWriter fr = new FileWriter(filepath, true)){
            fr.write(metricName + " Threshold: " + Arrays.toString(thresholds) + "\n");
        } catch(IOException ioe) { ioe.printStackTrace(); }
    }

    /**
     * Abstract logThresholds method which is required to be implemented.
     * This allows descendants to call the above logThresholds with the name
     * of their metrics, and have outside classes only require filepath
     * @param filepath path to the log file
     */
    public abstract void logThresholds(String filepath);

    public void setProject(Project project) {
        this.project = project;
    }
}