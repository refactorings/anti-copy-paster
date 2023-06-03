package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CouplingMetrics extends Flag{

    public CouplingMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
        calculateAverageCouplingMetrics();
    }

    /**
    This is a function that will get the coupling metric out of
    the FeaturesVector that is passed in
    Coupling only uses Metric #6, so getting the value at index 5
    from the fv array gives us the right value
     */
    private float getCouplingMetricFromFV(FeaturesVector fv){
        if(fv != null){
            lastCalculatedMetric = fv.buildArray()[5];
            return lastCalculatedMetric;
        } else {
            return 0;
        }
    }

    /**
    This will iterate over all of the FeaturesVectors passed in to the
    class, and then export only the relevant metric values to an array.
    That array will then be sorted and run through the Flag boxplot 
    method to get Q1, Q2, and Q3 for the sensitivities
     */
    private void calculateAverageCouplingMetrics(){
        ArrayList<Float> couplingMetricsValues = new ArrayList<Float>();

        for(FeaturesVector f : featuresVectorList){
            couplingMetricsValues.add(getCouplingMetricFromFV(f));
        }

        Collections.sort(couplingMetricsValues);
        boxPlotCalculations(couplingMetricsValues);
    }

    /**
     * Required override function from Flag. Gets the sensitivity for this metric
     * by grabbing its appropriate settings from this project's ProjectSettingsState.
     */
    @Override
    protected int getSensitivity() {
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
        return settings.couplingSensitivity;
    }

    /**
    Required override function from Flag. This just compares the coupling
    of the passed in FeaturesVector against the correct quartile value 
    based on the box plot depending on whatever the sensitivity is.
     */
    @Override
    public boolean isFlagTriggered(FeaturesVector featuresVector){
        float fvCouplingValue = getCouplingMetricFromFV(featuresVector);

        int quartile = (int) Math.ceil(getSensitivity() / 25.0);
        switch(quartile) {
            case 1:
                return true;
            case 2:
                return fvCouplingValue >= metricQ1;
            case 3:
                return fvCouplingValue >= metricQ2;
            case 4:
                return fvCouplingValue >= metricQ3;
            default:
                return false;
        }

    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    public void logMetric(String filepath){
        logMetric(filepath, "Coupling");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    public void logThresholds(String filepath){
        logThresholds(filepath, "Coupling");
    }
}