package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SizeMetrics extends Flag{

    public SizeMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
        calculateAverageSizeMetrics();
    }

    private void calculateAverageSizeMetrics(){
        ArrayList<Float> sizeMetricsValues = new ArrayList<Float>();

        for(FeaturesVector f : featuresVectorList){
            sizeMetricsValues.add(getSizeMetricFromFV(f));
        }

        Collections.sort(sizeMetricsValues);
        boxPlotCalculations(sizeMetricsValues);
    }

    /**
    This takes metric 1 from the array and gets size
    of the enclosing method
     */
    private float getSizeMetricFromFV(FeaturesVector fv){
        if(fv != null){
            float[] fvArr = fv.buildArray();
            lastCalculatedMetric = fvArr[0];
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
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        return settings.sizeSensitivity;
    }

    /**
    Required override function from Flag. This just compares the size (M1/M12)
    of the passed in FeaturesVector against the correct quartile value 
    based on the box plot depending on whatever the sensitivity is.
     */
    @Override
    public boolean isFlagTriggered(FeaturesVector featuresVector){
        float fvSizeValue = getSizeMetricFromFV(featuresVector);

        int quartile = (int) Math.ceil(getSensitivity() / 25.0);
        switch(quartile) {
            case 1:
                return true;
            case 2:
                return fvSizeValue >= metricQ1; 
            case 3:
                return fvSizeValue >= metricQ2; 
            case 4:
                return fvSizeValue >= metricQ3; 
            default:
                return false;
        }
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