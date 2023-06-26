package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ComplexityMetrics extends Flag{

    public ComplexityMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
     This is a function that will get the complexity metric out of
     the FeaturesVector that is passed in
     Complexity only uses Metrics #4 and #5, so getting the value at index 3 or 4 (depending on user settings)
     from the fv array gives us the right value
     */

    @Override
    protected void setSelectedMetrics(){
        //Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = retrieveCurrentSettings();

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
        //Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = retrieveCurrentSettings();
        return settings.complexitySensitivity;
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