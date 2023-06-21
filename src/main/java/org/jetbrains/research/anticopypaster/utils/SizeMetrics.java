package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.ArrayList;
import java.util.List;

public class SizeMetrics extends Flag{

    public SizeMetrics(List<FeaturesVector> featuresVectorList){
        super(featuresVectorList);

    }
    @Override
    protected void setSelectedMetrics(){
        Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);

        if (settings.measureSizeByLines[0]) {
            if (settings.measureTotalSize[0]) {
                selectedMetrics.add(0);
                if (settings.measureSizeByLines[1] && settings.measureTotalSize[1])
                    requiredMetrics.add(0);
            }
            if (settings.measureMethodDeclarationSize[0]) {
                selectedMetrics.add(11);
                if (settings.measureSizeByLines[1] && settings.measureMethodDeclarationSize[1])
                    requiredMetrics.add(11);
            }
        }
        if (settings.measureSizeBySymbols[0]) {
            if (settings.measureTotalSize[0]) {
                selectedMetrics.add(1);
                if (settings.measureSizeBySymbols[1] && settings.measureTotalSize[1])
                    requiredMetrics.add(1);
            }
            if (settings.measureMethodDeclarationSize[0]) {
                selectedMetrics.add(12);
                if (settings.measureSizeBySymbols[1] && settings.measureMethodDeclarationSize[1])
                    requiredMetrics.add(12);
            }
        }
        if (settings.measureSizeBySymbolsPerLine[0]) {
            if (settings.measureTotalSize[0]) {
                selectedMetrics.add(2);
                if (settings.measureSizeBySymbolsPerLine[1] && settings.measureTotalSize[1])
                    requiredMetrics.add(2);
            }
            if (settings.measureMethodDeclarationSize[0]) {
                selectedMetrics.add(13);
                if (settings.measureSizeBySymbolsPerLine[1] && settings.measureMethodDeclarationSize[1])
                    requiredMetrics.add(13);
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
        return settings.sizeSensitivity;
    }

    /**
     * Easier to use logMetric
     * @param filepath path to the log file
     */
    @Override
    public void logMetric(String filepath){
        logMetric(filepath, "Size");
    }

    /**
     * Easier to use logThresholds
     * @param filepath path to the log file
     */
    @Override
    public void logThresholds(String filepath){
        logThresholds(filepath, "Size");
    }
}