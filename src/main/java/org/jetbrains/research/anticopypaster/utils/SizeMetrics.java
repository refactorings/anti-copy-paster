package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.Feature;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import java.util.List;

public class SizeMetrics extends Flag{

    public SizeMetrics(List<FeaturesVector> featuresVectorList, Project project){
        super(featuresVectorList, project);
    }

    /**
     * Size uses six features, according to this scheme:
        * (DEFAULT) Feature index 0: Total lines of code
        * Feature index 11: Method declaration lines of code
        * Feature index 1: Total symbols
        * Feature index 12: Method declaration symbols
        * Feature index 2: Symbols per line
        * Feature index 13: Method declaration symbols per line
     *
     * Users select between measuring by lines, symbols, and/or symbols per line (3 choices),
     *  and between measuring the whole code segment or just method declarations (2 choices).
     *  3 x 2 = 6 features.
     */
    @Override
    protected void setSelectedMetrics(){
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

        if (settings.measureSizeByLines[0]) {
            if (settings.measureTotalSize[0]) {
                selectedMetrics.add(Feature.TotalLinesOfCode);
                if (settings.measureSizeByLines[1] && settings.measureTotalSize[1])
                    requiredMetrics.add(Feature.TotalLinesOfCode);
            }
            if (settings.measureMethodDeclarationSize[0]) {
                selectedMetrics.add(Feature.MethodDeclarationLines);
                if (settings.measureSizeByLines[1] && settings.measureMethodDeclarationSize[1])
                    requiredMetrics.add(Feature.MethodDeclarationLines);
            }
        }
        if (settings.measureSizeBySymbols[0]) {
            if (settings.measureTotalSize[0]) {
                selectedMetrics.add(Feature.TotalSymbols);
                if (settings.measureSizeBySymbols[1] && settings.measureTotalSize[1])
                    requiredMetrics.add(Feature.TotalSymbols);
            }
            if (settings.measureMethodDeclarationSize[0]) {
                selectedMetrics.add(Feature.MethodDeclarationSymbols);
                if (settings.measureSizeBySymbols[1] && settings.measureMethodDeclarationSize[1])
                    requiredMetrics.add(Feature.MethodDeclarationSymbols);
            }
        }
        if (settings.measureSizeBySymbolsPerLine[0]) {
            if (settings.measureTotalSize[0]) {
                selectedMetrics.add(Feature.SymbolsPerLine);
                if (settings.measureSizeBySymbolsPerLine[1] && settings.measureTotalSize[1])
                    requiredMetrics.add(Feature.SymbolsPerLine);
            }
            if (settings.measureMethodDeclarationSize[0]) {
                selectedMetrics.add(Feature.MethodDeclarationSymbolsPerLine);
                if (settings.measureSizeBySymbolsPerLine[1] && settings.measureMethodDeclarationSize[1])
                    requiredMetrics.add(Feature.MethodDeclarationSymbolsPerLine);
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
        return ProjectSettingsState.getInstance(project).sizeSensitivity;
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