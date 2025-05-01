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
        modified |= settingsComponent.getExtractionType() != settings.extractionType;
        modified |= settingsComponent.getModelSensitivity() != settings.modelSensitivity;
        modified |= settingsComponent.getMaxParams() != settings.maxParams;
        modified |= !Objects.equals(settingsComponent.getAiderApiKey(), settings.getAiderApiKey());
        modified |= !Objects.equals(settingsComponent.getSelectedAiderModel(), settings.getAiderModel());
        modified |= !Objects.equals(settingsComponent.getLlmProvider(), settings.getLlmprovider());
        modified |= !Objects.equals(settingsComponent.getAiderPath(), settings.getAiderPath());
        return modified;
    }

    // Save dialog inputs to ProjectSettingsState saved state
    @Override
    public void apply() throws ConfigurationException {
        if ((settingsComponent.getJudgementModel() == ProjectSettingsState.JudgementModel.AIDER ||
             settingsComponent.getNameModel() == 2) &&
            (settingsComponent.getAiderApiKey() == null || settingsComponent.getAiderApiKey().trim().isEmpty())) {
            throw new ConfigurationException("API Key must be provided when using Aider.");
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
        settings.extractionType = settingsComponent.getExtractionType();
        settings.modelSensitivity = settingsComponent.getModelSensitivity();
        settings.maxParams = settingsComponent.getMaxParams();
        settings.setAiderApiKey(settingsComponent.getAiderApiKey());
        settings.setAiderModel(settingsComponent.getSelectedAiderModel());
        settings.setLlmprovider(settingsComponent.getLlmProvider());
        settings.setAiderPath(settingsComponent.getAiderPath());
    }

    // Pull from saved state to preset dialog state upon opening
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
        settingsComponent.setExtractionType(settings.extractionType);
        settingsComponent.setModelSensitivity(settings.modelSensitivity);
        settingsComponent.setMaxParams(settings.maxParams);
        settingsComponent.setAiderApiKey(settings.getAiderApiKey());
        settingsComponent.setSelectedAiderModel(settings.getAiderModel());
        settingsComponent.setLlmProvider(settings.getLlmprovider());
        settingsComponent.setAiderPath(settings.getAiderPath());
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}