package org.jetbrains.research.anticopypaster.models;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import org.jetbrains.research.anticopypaster.utils.*;

import java.io.*;
import java.util.List;
import java.util.Scanner;


public class UserSettingsModel extends PredictionModel{

    /*private static final String FILE_PATH = ProjectManager.getInstance().getOpenProjects()[0]
            .getBasePath() + "/.idea/custom_metrics.txt";*/

    private final int DEFAULT_SENSITIVITY = 50;
    private MetricsGatherer metricsGatherer;

    private Flag keywordsMetrics;
    private Flag sizeMetrics;
    private Flag complexityMetrics;
    private Flag couplingMetrics;
    private Project project;

    public UserSettingsModel(MetricsGatherer mg, Project project){
        //The metricsGatherer instantiation calls a function that can't be used
        //outside the context of an installed plugin, so in order to unit test
        //our model, the metrics gatherer is passed in from the constructor
        this.project = project;
        if(mg != null){
            mg.setProject(project);
            initMetricsGathererAndMetricsFlags(mg);
        }
    }

    /**
    Helper initializaton method for the metrics gatherer.
    This is a separate method so that if we ever wanted to have the metrics 
    gatherer regather metrics and update the values in the sensitivity 
    thresholds
     */
    public void initMetricsGathererAndMetricsFlags(MetricsGatherer mg){
        this.metricsGatherer = mg;

        List<FeaturesVector> methodMetrics = mg.getMethodsMetrics();
        this.keywordsMetrics = new KeywordsMetrics(methodMetrics, project);
        this.complexityMetrics = new ComplexityMetrics(methodMetrics, project);
        this.sizeMetrics = new SizeMetrics(methodMetrics, project);
        this.couplingMetrics = new CouplingMetrics(methodMetrics, project);
    }

    /**
    Returns a value higher than 0.5 if the task satisfied the requirements
    to be extracted, lower than 0.5 means the notification will not appear.
    This is currently hardcoded to return 1 until the metrics category logic
    has been implemented.
     */
    @Override
    public float predict(FeaturesVector featuresVector) {

        if (sizeMetrics == null || complexityMetrics == null || keywordsMetrics == null || couplingMetrics == null){
            return 0;
        }

        boolean sizeTriggered = this.sizeMetrics.isFlagTriggered(featuresVector);
        boolean complexityTriggered = this.complexityMetrics.isFlagTriggered(featuresVector);
        boolean keywordsTriggered = this.keywordsMetrics.isFlagTriggered(featuresVector);
        boolean couplingTriggered = this.couplingMetrics.isFlagTriggered(featuresVector);

        //Project project = ProjectManager.getInstance().getOpenProjects()[0];
        ProjectSettingsState settings = project.getService(ProjectSettingsState.class);

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
    public void logMetrics(String filepath){
        this.complexityMetrics.logMetric(filepath);
        this.keywordsMetrics.logMetric(filepath);
        this.sizeMetrics.logMetric(filepath);
        this.couplingMetrics.logMetric(filepath);
    }

    /**
     * This function logs all the metrics thresholds
     * @param filepath the filepath to the log file
     */
    public void logThresholds(String filepath){
        this.complexityMetrics.logThresholds(filepath);
        this.keywordsMetrics.logThresholds(filepath);
        this.sizeMetrics.logThresholds(filepath);
        this.couplingMetrics.logMetric(filepath);
    }

    public void setProject(Project project) {
        this.project = project;
    }
}
