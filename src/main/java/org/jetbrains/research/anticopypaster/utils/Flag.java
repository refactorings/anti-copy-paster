package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public abstract class Flag{

    protected List<FeaturesVector> featuresVectorList;

    protected float threshold;

    protected float lastCalculatedMetric;

    protected int cachedSensitivity;

    protected abstract int getSensitivity();

    protected abstract float getMetric(FeaturesVector featuresVector);

    public Flag(List<FeaturesVector> featuresVectorList) {
        this.featuresVectorList = featuresVectorList;
        this.threshold = 0;
        this.lastCalculatedMetric = -1;
        calculateThreshold();
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
        return lastCalculatedMetric >= threshold;
    }

    /**
     * Recalculates this Flag's threshold from its current sensitivity value.
     * This is done by calculating its relevant metric for each of its FVs,
     * sorting the resulting list, and grabbing the element of the list at
     * the same relative position in the list as the sensitivity value is
     * within the range of 0 to 100.
     */
    public void calculateThreshold() {
        if (featuresVectorList == null || featuresVectorList.size() == 0) {
            threshold = 0;
        } else if (featuresVectorList.size() == 1) {
            threshold = getMetric(featuresVectorList.get(0));
        } else {
            float[] metricValues = new float[featuresVectorList.size()];
            for (int i = 0; i < metricValues.length; i++)
                metricValues[i] = getMetric(featuresVectorList.get(i));
            Arrays.sort(metricValues);

            if (getSensitivity() == 100) {
                threshold = metricValues[metricValues.length - 1];
            } else {
                double position = (double) getSensitivity() * featuresVectorList.size() / 100;
                int lowerIndex = (int) Math.floor(position);
                float proportion = (float) position % 1;

                threshold = proportion * metricValues[lowerIndex] + (1 - proportion) * metricValues[lowerIndex + 1];
            }
        }
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
                    threshold + ", " + lastCalculatedMetric + "\n");
        }catch(IOException ioe){

        }
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
            fr.write(metricName + " Threshold: " + threshold + "\n");
        }catch(IOException ioe){

        }
    }

    /**
     * Abstract logThresholds method which is required to be implemented.
     * This allows descendants to call the above logThresholds with the name
     * of their metrics, and have outside classes only require filepath
     * @param filepath path to the log file
     */
    public abstract void logThresholds(String filepath);
}