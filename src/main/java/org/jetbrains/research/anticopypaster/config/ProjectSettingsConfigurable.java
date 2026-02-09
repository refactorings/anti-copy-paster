package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

public class ProjectSettingsConfigurable implements Configurable {

    private final Project project;
    private ProjectSettingsComponent settingsComponent;

    public ProjectSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "AntiCopyPaster";
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return settingsComponent.getPreferredFocusedComponent();
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settingsComponent = new ProjectSettingsComponent(this.project);
        return settingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        boolean modified = settingsComponent.getMinimumDuplicateMethods() != settings.minimumDuplicateMethods;
        modified |= settingsComponent.getTimeBuffer() != settings.timeBuffer;
        modified |= settingsComponent.getKeywordsSensitivity() != settings.keywordsSensitivity;
        modified |= settingsComponent.getKeywordsEnabled() != settings.keywordsEnabled;
        modified |= settingsComponent.getKeywordsRequired() != settings.keywordsRequired;
        modified |= settingsComponent.getCouplingSensitivity() != settings.couplingSensitivity;
        modified |= settingsComponent.getCouplingEnabled() != settings.couplingEnabled;
        modified |= settingsComponent.getCouplingRequired() != settings.couplingRequired;
        modified |= settingsComponent.getSizeSensitivity() != settings.sizeSensitivity;
        modified |= settingsComponent.getSizeEnabled() != settings.sizeEnabled;
        modified |= settingsComponent.getSizeRequired() != settings.sizeRequired;
        modified |= settingsComponent.getComplexitySensitivity() != settings.complexitySensitivity;
        modified |= settingsComponent.getComplexityEnabled() != settings.complexityEnabled;
        modified |= settingsComponent.getComplexityRequired() != settings.complexityRequired;
        modified |= settingsComponent.getNameModel() != settings.useNameRec;
        modified |= settingsComponent.getNumOfPreds() != settings.numOfPreds;
        modified |= settingsComponent.getJudgementModel() != settings.judgementModel;
        modified |= settingsComponent.getCloneMode() != settings.getCloneMode();
        modified |= settingsComponent.getExtractionType() != settings.extractionType;
        modified |= settingsComponent.getModelSensitivity() != settings.modelSensitivity;
        modified |= settingsComponent.getMaxParams() != settings.maxParams;
        modified |= !Objects.equals(settingsComponent.getAiderApiKey(), settings.getAiderApiKey());
        modified |= !Objects.equals(settingsComponent.getSelectedAiderModel(), settings.getAiderModel());
        modified |= !Objects.equals(settingsComponent.getLlmProvider(), settings.getLlmprovider());
        modified |= !Objects.equals(settingsComponent.getApiBase(), settings.getApiBase());
        modified |= !Objects.equals(settingsComponent.getApiVersion(), settings.getApiVersion());
        modified |= !Objects.equals(settingsComponent.getFilesPath(), settings.getFilesPath());
        modified |= !Objects.equals(settingsComponent.getAllFilesCheckboxes(), settings.getAllFilesCheckboxes());
        modified |= !Objects.equals(settingsComponent.getSelectedAnalysisButton(), settings.getSelectedAnalysisButton());
        modified |= !Objects.equals(settingsComponent.getOllamaModel(), settings.getOllamaModelName());
        modified |= settingsComponent.getMaxAttempts() != settings.getMaxAttempts();
        return modified;
    }

    @Override
    public void apply() throws ConfigurationException {
        if ((settingsComponent.getJudgementModel() == ProjectSettingsState.JudgementModel.AIDER ||
                settingsComponent.getNameModel() == 2) &&
                (settingsComponent.getAiderApiKey() == null || settingsComponent.getAiderApiKey().trim().isEmpty())) {
            throw new ConfigurationException("API Key must be provided when using Aider.");
        }

        if ("Azure".equalsIgnoreCase(settingsComponent.getLlmProvider())) {
            if (settingsComponent.getApiBase() == null || settingsComponent.getApiBase().trim().isEmpty()) {
                throw new ConfigurationException("API Base must be provided when using Azure.");
            }
            if (settingsComponent.getApiVersion() == null || settingsComponent.getApiVersion().trim().isEmpty()) {
                throw new ConfigurationException("API Version must be provided when using Azure.");
            }
        }

        if ("Ollama".equalsIgnoreCase(settingsComponent.getLlmProvider())) {
            if (settingsComponent.getApiBase() == null || settingsComponent.getApiBase().trim().isEmpty()) {
                throw new ConfigurationException("API Base must be provided when using Ollama.");
            }
            if (settingsComponent.getOllamaModel() == null || settingsComponent.getOllamaModel().trim().isEmpty()) {
                throw new ConfigurationException("Ollama Model Name must be provided when using Ollama.");
            }
        }

        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        settings.minimumDuplicateMethods = settingsComponent.getMinimumDuplicateMethods();
        settings.timeBuffer = settingsComponent.getTimeBuffer();
        settings.keywordsSensitivity = settingsComponent.getKeywordsSensitivity();
        settings.keywordsEnabled = settingsComponent.getKeywordsEnabled();
        settings.keywordsRequired = settingsComponent.getKeywordsRequired();
        settings.couplingSensitivity = settingsComponent.getCouplingSensitivity();
        settings.couplingEnabled = settingsComponent.getCouplingEnabled();
        settings.couplingRequired = settingsComponent.getCouplingRequired();
        settings.sizeSensitivity = settingsComponent.getSizeSensitivity();
        settings.sizeEnabled = settingsComponent.getSizeEnabled();
        settings.sizeRequired = settingsComponent.getSizeRequired();
        settings.complexitySensitivity = settingsComponent.getComplexitySensitivity();
        settings.complexityEnabled = settingsComponent.getComplexityEnabled();
        settings.complexityRequired = settingsComponent.getComplexityRequired();
        settings.useNameRec = settingsComponent.getNameModel();
        settings.numOfPreds = settingsComponent.getNumOfPreds();
        settings.judgementModel = settingsComponent.getJudgementModel();
        settings.setCloneMode(settingsComponent.getCloneMode());
        settings.extractionType = settingsComponent.getExtractionType();
        settings.modelSensitivity = settingsComponent.getModelSensitivity();
        settings.maxParams = settingsComponent.getMaxParams();
        settings.setMaxAttempts(settingsComponent.getMaxAttempts());
        settings.setLlmprovider(settingsComponent.getLlmProvider());
        settings.setAiderModel(settingsComponent.getSelectedAiderModel());
        settings.setAiderApiKey(settingsComponent.getAiderApiKey());
        settings.setApiBase(settingsComponent.getApiBase());
        settings.setApiVersion(settingsComponent.getApiVersion());
        settings.setFilesPath(settingsComponent.getFilesPath());
        settings.setAllFilesCheckboxes(settingsComponent.getAllFilesCheckboxes());
        settings.setSelectedAnalysisButton(settingsComponent.getSelectedAnalysisButton());
        settings.setOllamaName(settingsComponent.getOllamaModel());

        // Apply model changes and close Aider windows if needed
        settingsComponent.applyModelChanges();
    }

    @Override
    public void reset() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        settingsComponent.setMinimumDuplicateMethods(settings.minimumDuplicateMethods);
        settingsComponent.setTimeBuffer(settings.timeBuffer);
        settingsComponent.setKeywordsSensitivity(settings.keywordsSensitivity);
        settingsComponent.setKeywordsEnabled(settings.keywordsEnabled);
        settingsComponent.setKeywordsRequired(settings.keywordsRequired);
        settingsComponent.setCouplingSensitivity(settings.couplingSensitivity);
        settingsComponent.setCouplingEnabled(settings.couplingEnabled);
        settingsComponent.setCouplingRequired(settings.couplingRequired);
        settingsComponent.setSizeSensitivity(settings.sizeSensitivity);
        settingsComponent.setSizeEnabled(settings.sizeEnabled);
        settingsComponent.setSizeRequired(settings.sizeRequired);
        settingsComponent.setComplexitySensitivity(settings.complexitySensitivity);
        settingsComponent.setComplexityEnabled(settings.complexityEnabled);
        settingsComponent.setComplexityRequired(settings.complexityRequired);
        settingsComponent.setNameModel(settings.useNameRec);
        settingsComponent.setNumOfPreds(settings.numOfPreds);
        settingsComponent.setJudgementModel(settings.judgementModel);
        settingsComponent.setCloneMode(settings.getCloneMode());
        settingsComponent.setExtractionType(settings.extractionType);
        settingsComponent.setModelSensitivity(settings.modelSensitivity);
        settingsComponent.setMaxParams(settings.maxParams);
        settingsComponent.setMaxAttempts(settings.getMaxAttempts());
        settingsComponent.setLlmProvider(settings.getLlmprovider());
        settingsComponent.setSelectedAiderModel(settings.getAiderModel());
        settingsComponent.setAiderApiKey(settings.getAiderApiKey());
        settingsComponent.setFilesPath(settings.getFilesPath());
        settingsComponent.setApiBase(settings.getApiBase());
        settingsComponent.setApiVersion(settings.getApiVersion());
        settingsComponent.setAllFilesCheckboxes(settings.getAllFilesCheckboxes());
        settingsComponent.setSelectedAnalysisButton(settings.getSelectedAnalysisButton());
        settingsComponent.setOllamaModel(settings.getOllamaModelName());

        // Cancel any pending model changes
        settingsComponent.cancelModelChanges();
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}