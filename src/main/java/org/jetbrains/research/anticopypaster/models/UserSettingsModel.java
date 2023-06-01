package org.jetbrains.research.anticopypaster.models;

import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.research.anticopypaster.controller.CustomModelController;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;
import org.jetbrains.research.anticopypaster.utils.*;

import java.io.*;
import java.util.List;
import java.util.Scanner;


public class UserSettingsModel extends PredictionModel{

    private static final String FILE_PATH = ProjectManager.getInstance().getOpenProjects()[0]
            .getBasePath() + "/.idea/custom_metrics.txt";

    private final int DEFAULT_SENSITIVITY = 50;
    private MetricsGatherer metricsGatherer;

    private CustomModelController customModelController = CustomModelController.getInstance();

    private Flag keywordsMetrics;
    private Flag sizeMetrics;
    private Flag complexityMetrics;
    private Flag couplingMetrics;
    
    private int sizeSensitivity = 0;
    private boolean sizeRequired = true;
    private int complexitySensitivity = 0;
    private boolean complexityRequired = true;
    private int keywordsSensitivity = 0;
    private boolean keywordsRequired = true;
    private int couplingSensitivity = 0;
    private boolean couplingRequired = true;

    public UserSettingsModel(MetricsGatherer mg){
        //The metricsGatherer instantiation calls a function that can't be used
        //outside the context of an installed plugin, so in order to unit test
        //our model, the metrics gatherer is passed in from the constructor
        if(mg != null){
            initMetricsGathererAndMetricsFlags(mg);
        }
        customModelController.setUserSettingsModel(this);
    }

    public int getSizeSensitivity(){
        return this.sizeSensitivity;
    }

    public int getComplexitySensitivity(){
        return this.complexitySensitivity;
    }

    public int getKeywordsSensitivity(){
        return this.keywordsSensitivity;
    }

    public int getCouplingSensitivity(){
        return this.couplingSensitivity;
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
        this.keywordsMetrics = new KeywordsMetrics(methodMetrics);
        this.complexityMetrics = new ComplexityMetrics(methodMetrics);
        this.sizeMetrics = new SizeMetrics(methodMetrics);
        this.couplingMetrics = new CouplingMetrics(methodMetrics);

        readSensitivitiesFromFrontend();
    }

    public void setKeywordsSensitivity(int sensitivity){
        this.keywordsSensitivity = sensitivity;
        this.keywordsMetrics.changeSensitivity(sensitivity);
    }

    public void setComplexitySensitivity(int sensitivity){
        this.complexitySensitivity = sensitivity;
        this.complexityMetrics.changeSensitivity(sensitivity);
    }

    public void setSizeSensitivity(int sensitivity){
        this.sizeSensitivity = sensitivity;
        this.sizeMetrics.changeSensitivity(sensitivity);
    }

    public void setCouplingSensitivity(int sensitivity){
        this.couplingSensitivity = sensitivity;
        this.couplingMetrics.changeSensitivity(sensitivity);
    }

    public void setKeywordsRequired(boolean keywordsRequired) {
        this.keywordsRequired = keywordsRequired;
        this.keywordsMetrics.changeRequired(keywordsRequired);
    }

    public void setComplexityRequired(boolean complexityRequired) {
        this.complexityRequired = complexityRequired;
        this.complexityMetrics.changeRequired(complexityRequired);
    }

    public void setSizeRequired(boolean sizeRequired) {
        this.sizeRequired = sizeRequired;
        this.sizeMetrics.changeRequired(sizeRequired);
    }

    public void setCouplingRequired(boolean couplingRequired) {
        this.couplingRequired = couplingRequired;
        this.couplingMetrics.changeRequired(couplingRequired);
    }

    /**
    Defaulted to medium if the user has not set up flag values,reads in
     the sensitivities from the frontend file if the user has set values
    */
    private void readSensitivitiesFromFrontend(){
        //Default values if the user has not yet specified flag values
        int keywordsSensFromFrontend = DEFAULT_SENSITIVITY;
        int sizeSensFromFrontend = DEFAULT_SENSITIVITY;
        int complexitySensFromFrontend = DEFAULT_SENSITIVITY;
        int couplingSensFromFrontend = DEFAULT_SENSITIVITY;

        ProjectSettingsState savedSettings = (new ProjectSettingsState()).getInstance(ProjectManager.getInstance().getOpenProjects()[0]);

        setKeywordsSensitivity(savedSettings.keywordsSensitivity);
        setSizeSensitivity(savedSettings.sizeSensitivity);
        setComplexitySensitivity(savedSettings.complexitySensitivity);
        setCouplingSensitivity(savedSettings.couplingSensitivity);

        setKeywordsRequired(savedSettings.keywordsRequired);
        setSizeRequired(savedSettings.sizeRequired);
        setComplexityRequired(savedSettings.complexityRequired);
        setCouplingRequired(savedSettings.couplingRequired);
    }

    /**
    Returns a value higher than 0.5 if the task satisfied the requirements
    to be extracted, lower than 0.5 means the notification will not appear.
    This is currently hardcoded to return 1 until the metrics category logic
    has been implemented.
     */
    @Override
    public float predict(FeaturesVector featuresVector){

        if(sizeMetrics == null || complexityMetrics == null || keywordsMetrics == null || couplingMetrics == null){
            return 0;
        }

        boolean sizeTriggered = this.sizeMetrics.isFlagTriggered(featuresVector);
        boolean complexityTriggered = this.complexityMetrics.isFlagTriggered(featuresVector);
        boolean keywordsTriggered = this.keywordsMetrics.isFlagTriggered(featuresVector);
        boolean couplingTriggered = this.couplingMetrics.isFlagTriggered(featuresVector);

        boolean shouldNotify = true;

        if (!sizeTriggered && sizeRequired)
            shouldNotify = false;
        else if (!complexityTriggered && complexityRequired)
            shouldNotify = false;
        else if (!keywordsTriggered && keywordsRequired)
            shouldNotify = false;
        else if (!couplingTriggered && couplingRequired)
            shouldNotify = false;


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
}
