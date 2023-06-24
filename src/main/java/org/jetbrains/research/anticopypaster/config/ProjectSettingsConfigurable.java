package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

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
        boolean modified = settingsComponent.getUseMLModel() != settings.useMLModel;
        modified |= settingsComponent.getKeywordsSensitivity() != settings.keywordsSensitivity;
        modified |= settingsComponent.getKeywordsRequired() != settings.keywordsRequired;
        modified |= settingsComponent.getCouplingSensitivity() != settings.couplingSensitivity;
        modified |= settingsComponent.getCouplingRequired() != settings.couplingRequired;
        modified |= settingsComponent.getSizeSensitivity() != settings.sizeSensitivity;
        modified |= settingsComponent.getSizeRequired() != settings.sizeRequired;
        modified |= settingsComponent.getComplexitySensitivity() != settings.complexitySensitivity;
        modified |= settingsComponent.getComplexityRequired() != settings.complexityRequired;
        return modified;
    }

    @Override
    public void apply() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        settings.useMLModel = settingsComponent.getUseMLModel();
        settings.keywordsSensitivity = settingsComponent.getKeywordsSensitivity();
        settings.keywordsRequired = settingsComponent.getKeywordsRequired();
        settings.couplingSensitivity = settingsComponent.getCouplingSensitivity();
        settings.couplingRequired = settingsComponent.getCouplingRequired();
        settings.sizeSensitivity = settingsComponent.getSizeSensitivity();
        settings.sizeRequired = settingsComponent.getSizeRequired();
        settings.complexitySensitivity = settingsComponent.getComplexitySensitivity();
        settings.complexityRequired = settingsComponent.getComplexityRequired();
    }

    @Override
    public void reset() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        settingsComponent.setUseMLModel(settings.useMLModel);
        settingsComponent.setKeywordsSensitivity(settings.keywordsSensitivity);
        settingsComponent.setKeywordsRequired(settings.keywordsRequired);
        settingsComponent.setCouplingSensitivity(settings.couplingSensitivity);
        settingsComponent.setCouplingRequired(settings.couplingRequired);
        settingsComponent.setSizeSensitivity(settings.sizeSensitivity);
        settingsComponent.setSizeRequired(settings.sizeRequired);
        settingsComponent.setComplexitySensitivity(settings.complexitySensitivity);
        settingsComponent.setComplexityRequired(settings.complexityRequired);
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}