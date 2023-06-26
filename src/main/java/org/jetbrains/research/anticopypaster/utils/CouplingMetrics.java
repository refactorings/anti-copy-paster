package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CouplingMetrics extends Flag{

    public CouplingMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
     * Coupling uses one of Metrics 6 through 11, according to this scheme:
     * Metric index 5: Total connectivity
     * Metric index 6: Total connectivity per line
     * Metric index 7: Field connectivity
     * Metric index 8: Field connectivity per line
     * Metric index 9: Method connectivity
     * Metric index 10: Method connectivity per line
     */
    @Override
    protected void setSelectedMetrics(){
        //Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = retrieveCurrentSettings();

        if(settings.measureCouplingTotal[0]){
            if(settings.measureTotalConnectivity[0]){
                selectedMetrics.add(Feature.TotalConnectivity);
                if(settings.measureTotalConnectivity[1]){
                    requiredMetrics.add(Feature.TotalConnectivity);
                }
            }
            if(settings.measureFieldConnectivity[0]){
                selectedMetrics.add(Feature.FieldConnectivity);
                if(settings.measureFieldConnectivity[1]){
                    requiredMetrics.add(Feature.FieldConnectivity);
                }
            }
            if(settings.measureMethodConnectivity[0]){
                selectedMetrics.add(Feature.MethodConnectivity);
                if(settings.measureMethodConnectivity[1]){
                    requiredMetrics.add(Feature.MethodConnectivity);
                }
            }
        }
        if(settings.measureCouplingDensity[0]){
            if(settings.measureTotalConnectivity[0]){
                selectedMetrics.add(Feature.TotalConnectivityPerLine);
                if(settings.measureTotalConnectivity[1]){
                    requiredMetrics.add(Feature.TotalConnectivityPerLine);
                }
            }
            if(settings.measureFieldConnectivity[0]){
                selectedMetrics.add(Feature.FieldConnectivityPerLine);
                if(settings.measureFieldConnectivity[1]){
                    requiredMetrics.add(Feature.FieldConnectivityPerLine);
                }
            }
            if(settings.measureMethodConnectivity[0]){
                selectedMetrics.add(Feature.MethodConnectivityPerLine);
                if(settings.measureMethodConnectivity[1]){
                    selectedMetrics.add(Feature.MethodConnectivityPerLine);
                }
            }
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
        return settings.couplingSensitivity;
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Coupling");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Coupling");
    }
}