package org.jetbrains.research.anticopypaster.utils;

import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public abstract class Flag{

    protected int sensitivity;

    protected boolean required;

    protected List<FeaturesVector> featuresVectorList;

    protected ArrayList<Float> metricQ1;
    protected ArrayList<Float> metricQ2;
    protected ArrayList<Float> metricQ3;
    protected float lastCalculatedMetric;


    public abstract boolean isFlagTriggered(FeaturesVector featuresVector);

    public Flag(List<FeaturesVector> featuresVectorList){
        this.featuresVectorList = featuresVectorList;
        metricQ1 = new ArrayList<>();
        metricQ2 = new ArrayList<>();
        metricQ3 = new ArrayList<>();
        this.lastCalculatedMetric = -1;
    }

    /**
     Takes a SORTED list and generates/sets the Q1-3 values based on a box plot
     of those metric values
     */
    protected void boxPlotCalculations(ArrayList<Float> data){

        if(data == null || data.size() == 0){
            metricQ1.add(0f);
            metricQ2.add(0f);
            metricQ3.add(0f);
            return;
        }

        float q1;
        float q2;
        float q3;

        // Box plot logic, for even length lists get the average between middle values
        // For odd length lists just get the middle index
        if (data.size() % 2 == 0) {
            q1 = (data.get(data.size()/4 - 1) + data.get(data.size()/4)) / 2;
            q2 = (data.get(data.size()/2 - 1) + data.get(data.size()/2)) / 2;
            q3 = (data.get(data.size()*3/4 - 1) + data.get(data.size()*3/4)) / 2;
        } else {
            q1 = data.get(data.size()/4);
            q2 = data.get(data.size()/2);
            q3 = data.get(data.size()*3/4);
        }

        metricQ1.add(q1);
        metricQ2.add(q2);
        metricQ3.add(q3);
    }

    public ArrayList<Float> getMetricQ1(){
        return this.metricQ1;
    }

    public ArrayList<Float> getMetricQ2(){
        return this.metricQ2;
    }

    public ArrayList<Float> getMetricQ3(){
        return this.metricQ3;
    }

    public int getSensitivity(){ return this.sensitivity;}


    /**
     Change the sensitivity of the flag.
     Any sensitivities apart from 0, 1, 2, or 3 will be set to 0 (off)
     */
    public int changeSensitivity(int sensitivity){
        if(sensitivity > 100 || sensitivity < 0){
            this.sensitivity = 0;
        } else {
            this.sensitivity = sensitivity;
        }
        return this.sensitivity;
    }

    public boolean changeRequired(boolean required) {
        this.required = required;
        return required;
    }

    /**
     * This function logs the last known metric and the current threshold
     * @param filepath path to the log file
     * @param metricName name of the metric
     */
    protected void logMetric(String filepath, String metricName){
        int quartile = (int) Math.ceil(getSensitivity() / 25.0);
        String threshold = switch (quartile) {
            case (1) -> Float.toString(0);
            case (2) -> metricQ1.toString();
            case (3) -> metricQ2.toString();
            case (4) -> metricQ3.toString();
            default -> "INVALID SENSITIVITY";
        };

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
            fr.write(metricName + " Thresholds: " + metricQ1 + ", " +
                    metricQ2 + ", " + metricQ3 + "\n");
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