package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SizeMetrics extends Flag{
    private final int[] selectedMetrics;
    //TODO: replace with actual function to retrieve these numbers once advanced settings are made/integrated

    public SizeMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
        selectedMetrics = new int[]{0, 1};
        calculateAverageSizeMetrics();
    }

    private void calculateAverageSizeMetrics(){

        for(int metricNum: selectedMetrics){
            ArrayList<Float> sizeMetricsValues = new ArrayList<>();

            for(FeaturesVector f: featuresVectorList){
                sizeMetricsValues.add(getSizeMetricFromFV(f, metricNum));
            }
            Collections.sort(sizeMetricsValues);
            boxPlotCalculations(sizeMetricsValues);
        }

    }

    /**
     This takes a specific from the array and gets size
     of the enclosing method
     */
    private float getSizeMetricFromFV(FeaturesVector fv, int index){
        if(fv != null){
            float[] fvArr = fv.buildArray();
            lastCalculatedMetric = fvArr[index];
            return lastCalculatedMetric;
        }
        lastCalculatedMetric = 0;
        return lastCalculatedMetric;
    }

    /**
     Required override function from Flag. This just compares the size (M1/M12)
     of the passed in FeaturesVector against the correct quartile value
     based on the box plot depending on whatever the sensitivity is.
     */
    @Override
    public boolean isFlagTriggered(FeaturesVector featuresVector){
        if(featuresVector != null) {
            ArrayList<Boolean> metricsPassed = new ArrayList<>();
            Project project = ProjectManager.getInstance().getOpenProjects()[0];
            ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
            for (int i = 0; i < selectedMetrics.length; i++) {
                float fvSizeValue = getSizeMetricFromFV(featuresVector, selectedMetrics[i]);
                int quartile = (int) Math.ceil((sensitivity + 1) / 25.0);

                switch (quartile) {
                    case 1 -> {
                        metricsPassed.add(true);
                    }
                    case 2 -> {
                        if (fvSizeValue > metricQ1.get(i)) {
                            metricsPassed.add(true);

                        } else {
                            if(selectedMetrics[i] == 0){
                                if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByTotal
                                    return false;
                                }
                            }else{
                                if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByLines
                                    return false;
                                }
                            }
                            metricsPassed.add(false);

                        }
                    }
                    case 3 -> {
                        if (fvSizeValue > metricQ2.get(i)) {
                            metricsPassed.add(true);

                        } else {
                            if(selectedMetrics[i] == 0){
                                if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByTotal
                                    return false;
                                }
                            }else{
                                if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByLines
                                    return false;
                                }
                            }
                            metricsPassed.add(false);

                        }
                    }
                    case 4 -> {
                        if (fvSizeValue > metricQ3.get(i)) {
                            metricsPassed.add(true);

                        } else {
                            if(selectedMetrics[i] == 0){
                                if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByTotal
                                    return false;
                                }
                            }else{
                                if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByLines
                                    return false;
                                }
                            }
                            metricsPassed.add(false);

                        }
                    }
                    default -> {
                        if(selectedMetrics[i] == 0){
                            if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByTotal
                                return false;
                            }
                        }else{
                            if(settings.sizeRequired) { //TODO: change sizeRequired to measureSizeByLines
                                return false;
                            }
                        }
                        metricsPassed.add(false);
                    }
                }
            }
            for (boolean passed : metricsPassed) {
                if (passed) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    public void logMetric(String filepath){
        logMetric(filepath, "Size");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    public void logThresholds(String filepath){
        logThresholds(filepath, "Size");
    }
}