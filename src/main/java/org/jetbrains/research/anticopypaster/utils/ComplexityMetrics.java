package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.List;

public class ComplexityMetrics extends Flag{

    public ComplexityMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
     * Complexity uses four features, according to this scheme:
         * Feature index 3: Area
         * (DEFAULT) Feature index 4: Area per line
         * Feature index 14: Method declaration area
         * Feature index 15: Method declaration depth per line
     */

    @Override
    protected void setSelectedMetrics(){
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

        if (settings.measureComplexityTotal[0]) {
            selectedMetrics.add(Feature.Area);
            if (settings.measureComplexityTotal[1]) {
                requiredMetrics.add(Feature.Area);
            }
        }

        if (settings.measureComplexityDensity[0]) {
            selectedMetrics.add(Feature.AreaPerLine);
            if (settings.measureComplexityDensity[1]) {
                requiredMetrics.add(Feature.AreaPerLine);
            }
        }

        if (settings.measureMethodDeclarationArea[0]) {
            selectedMetrics.add(Feature.MethodDeclarationArea);
            if (settings.measureMethodDeclarationArea[1]) {
                requiredMetrics.add(Feature.MethodDeclarationArea);
            }
        }

        if (settings.measureMethodDeclarationDepthPerLine[0]) {
            selectedMetrics.add(Feature.MethodDeclarationAreaPerLine);
            if (settings.measureMethodDeclarationDepthPerLine[1]) {
                requiredMetrics.add(Feature.MethodDeclarationAreaPerLine);
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
        return ProjectSettingsState.getInstance(project).complexitySensitivity;
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Complexity");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Complexity");
    }
}