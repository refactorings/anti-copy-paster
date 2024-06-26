package org.jetbrains.research.anticopypaster.models;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import org.jetbrains.research.anticopypaster.utils.*;

import java.util.List;


public class UserSettingsModel extends PredictionModel{

    private Flag keywordsMetrics;
    private Flag sizeMetrics;
    private Flag complexityMetrics;
    private Flag couplingMetrics;
    private Project project;

    public UserSettingsModel(MetricsGatherer mg, Project project) {
        this.project = project;
        initMetricsGathererAndMetricsFlags(mg);
    }

    /**
     * Helper initialization method for the metrics gatherer.
     * This is a separate method so that if we ever wanted to have the metrics
     * gatherer regather metrics and update the values in the sensitivity
     * thresholds
     */
    public void initMetricsGathererAndMetricsFlags(MetricsGatherer mg) {
        mg.setProject(project);

        List<FeaturesVector> methodMetrics = mg.getMethodsMetrics();
        this.keywordsMetrics = new KeywordsMetrics(methodMetrics, project);
        this.complexityMetrics = new ComplexityMetrics(methodMetrics, project);
        this.sizeMetrics = new SizeMetrics(methodMetrics, project);
        this.couplingMetrics = new CouplingMetrics(methodMetrics, project);
    }

    /**
     * Returns a value higher than 0.5 if the task satisfied the requirements
     * to be extracted, lower than 0.5 means the notification will not appear.
     * This is currently hardcoded to return 1 until the metrics category logic
     * has been implemented.
     */
    @Override
    public float predict(FeaturesVector featuresVector) {
        if (sizeMetrics == null || complexityMetrics == null || keywordsMetrics == null || couplingMetrics == null)
            return 0;

        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

        boolean sizeTriggered = settings.sizeEnabled && sizeMetrics.isFlagTriggered(featuresVector);
        boolean complexityTriggered = settings.complexityEnabled && complexityMetrics.isFlagTriggered(featuresVector);
        boolean keywordsTriggered = settings.keywordsEnabled && keywordsMetrics.isFlagTriggered(featuresVector);
        boolean couplingTriggered = settings.couplingEnabled && couplingMetrics.isFlagTriggered(featuresVector);

        boolean shouldNotify = sizeTriggered || complexityTriggered || keywordsTriggered || couplingTriggered;
        if (shouldNotify) {
            if (!sizeTriggered && settings.sizeRequired)
                shouldNotify = false;
            else if (!complexityTriggered && settings.complexityRequired)
                shouldNotify = false;
            else if (!keywordsTriggered && settings.keywordsRequired)
                shouldNotify = false;
            else if (!couplingTriggered && settings.couplingRequired)
                shouldNotify = false;
        }

        return shouldNotify ? 1 : 0;
    }

    /**
     * This function logs all the pertinent metrics info for
     * a copy/paste event
     * @param filepath the filepath to the log file
     */
    public void logMetrics(String filepath) {
        this.complexityMetrics.logMetric(filepath);
        this.keywordsMetrics.logMetric(filepath);
        this.sizeMetrics.logMetric(filepath);
        this.couplingMetrics.logMetric(filepath);
    }

    /**
     * This function logs all the metrics thresholds
     * @param filepath the filepath to the log file
     */
    public void logThresholds(String filepath) {
        this.complexityMetrics.logThresholds(filepath);
        this.keywordsMetrics.logThresholds(filepath);
        this.sizeMetrics.logThresholds(filepath);
        this.couplingMetrics.logMetric(filepath);
    }

    public void setProject(Project project) {
        this.project = project;
    }
}
