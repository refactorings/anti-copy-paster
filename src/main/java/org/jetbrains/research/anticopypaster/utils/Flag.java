package org.jetbrains.research.anticopypaster.utils;

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

    protected int cachedSensitivity;

    protected int numFeatures;

    protected abstract int getSensitivity();
    protected abstract void setSelectedMetrics();

    protected abstract float[] getMetric(FeaturesVector featuresVector);

    public abstract boolean isFlagTriggered(FeaturesVector featuresVector);

    protected Flag(List<FeaturesVector> featuresVectorList, int numFeatures) {
        this.numFeatures = numFeatures;
        this.featuresVectorList = featuresVectorList;
        this.thresholds = null;
        this.lastCalculatedMetric = null;
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
        if (featuresVectorList == null || featuresVectorList.size() == 0) {
            thresholds = new float[numFeatures];
        } else if (featuresVectorList.size() == 1) {
            thresholds = getMetric(featuresVectorList.get(0));
        } else {
            float[][] metricValues = new float[featuresVectorList.size()][numFeatures];
            for (int i = 0; i < featuresVectorList.size(); i++) {
                float[] metric = getMetric(featuresVectorList.get(i));
                for (int j = 0; j < numFeatures; j++)
                    metricValues[j][i] = metric[j];
            }
            for (float[] metricValue : metricValues)
                Arrays.sort(metricValue);

            if (getSensitivity() == 100) {
                for (int k = 0; k < numFeatures; k++)
                    thresholds[k] = metricValues[k][metricValues.length - 1];
            } else {
                double position = (double) getSensitivity() * (featuresVectorList.size() - 1) / 100;
                int lowerIndex = (int) Math.floor(position);
                float proportion = (float) position % 1;

                thresholds = new float[numFeatures];
                for (int l = 0; l < numFeatures; l++)
                    thresholds[l] = (1 - proportion) * metricValues[l][lowerIndex]
                            + proportion * metricValues[l][lowerIndex + 1];
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
                    thresholds + ", " + lastCalculatedMetric + "\n");
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
            fr.write(metricName + " Threshold: " + thresholds + "\n");
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