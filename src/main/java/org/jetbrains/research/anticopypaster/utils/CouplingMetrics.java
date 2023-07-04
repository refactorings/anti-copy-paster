package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

public class CouplingMetrics extends Flag{

    public CouplingMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
     * Coupling uses features 6 through 11, according to this scheme:
         * Feature index 5: Total connectivity
         * (DEFAULT) Feature index 6: Total connectivity per line
         * Feature index 7: Field connectivity
         * Feature index 8: Field connectivity per line
         * Feature index 9: Method connectivity
         * Feature index 10: Method connectivity per line
     *
     * Users select between measuring connectivity over all lines and/or per line (2 choices), and
     *  between measuring total, field, and/or method connectivity (3 choices).
     *  2 x 3 = 6 features.
     */
    @Override
    protected void setSelectedMetrics(){
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

        if(settings.measureCouplingTotal[0]){
            if(settings.measureTotalConnectivity[0]){
                selectedMetrics.add(Feature.TotalConnectivity);
                if(settings.measureCouplingTotal[1] && settings.measureTotalConnectivity[1]){
                    requiredMetrics.add(Feature.TotalConnectivity);
                }
            }
            if(settings.measureFieldConnectivity[0]){
                selectedMetrics.add(Feature.FieldConnectivity);
                if(settings.measureCouplingTotal[1] && settings.measureFieldConnectivity[1]){
                    requiredMetrics.add(Feature.FieldConnectivity);
                }
            }
            if(settings.measureMethodConnectivity[0]){
                selectedMetrics.add(Feature.MethodConnectivity);
                if(settings.measureCouplingTotal[1] && settings.measureMethodConnectivity[1]){
                    requiredMetrics.add(Feature.MethodConnectivity);
                }
            }
        }
        if(settings.measureCouplingDensity[0]){
            if(settings.measureTotalConnectivity[0]){
                selectedMetrics.add(Feature.TotalConnectivityPerLine);
                if(settings.measureCouplingDensity[1] && settings.measureTotalConnectivity[1]){
                    requiredMetrics.add(Feature.TotalConnectivityPerLine);
                }
            }
            if(settings.measureFieldConnectivity[0]){
                selectedMetrics.add(Feature.FieldConnectivityPerLine);
                if(settings.measureCouplingDensity[1] && settings.measureFieldConnectivity[1]){
                    requiredMetrics.add(Feature.FieldConnectivityPerLine);
                }
            }
            if(settings.measureMethodConnectivity[0]){
                selectedMetrics.add(Feature.MethodConnectivityPerLine);
                if(settings.measureCouplingDensity[1] && settings.measureMethodConnectivity[1]){
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
        return ProjectSettingsState.getInstance(project).couplingSensitivity;
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