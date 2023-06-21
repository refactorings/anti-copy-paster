package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.List;

public class CouplingMetrics extends Flag{

    public CouplingMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);
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
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);

        if(settings.measureCouplingTotal[0]){
            if(settings.measureTotalConnectivity[0]){
                selectedMetrics.add(5);
                if(settings.measureTotalConnectivity[1]){
                    requiredMetrics.add(5);
                }
            }
            if(settings.measureFieldConnectivity[0]){
                selectedMetrics.add(7);
                if(settings.measureFieldConnectivity[1]){
                    requiredMetrics.add(7);
                }
            }
            if(settings.measureMethodConnectivity[0]){
                selectedMetrics.add(9);
                if(settings.measureMethodConnectivity[1]){
                    requiredMetrics.add(9);
                }
            }
        }
        if(settings.measureCouplingDensity[0]){
            if(settings.measureTotalConnectivity[0]){
                selectedMetrics.add(6);
                if(settings.measureTotalConnectivity[1]){
                    requiredMetrics.add(6);
                }
            }
            if(settings.measureFieldConnectivity[0]){
                selectedMetrics.add(8);
                if(settings.measureFieldConnectivity[1]){
                    requiredMetrics.add(8);
                }
            }
            if(settings.measureMethodConnectivity[0]){
                selectedMetrics.add(10);
                if(settings.measureMethodConnectivity[1]){
                    selectedMetrics.add(10);
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
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);
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