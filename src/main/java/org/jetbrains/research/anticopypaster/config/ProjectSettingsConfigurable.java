package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

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
        modified |= settingsComponent.getMinimumDuplicateMethods() != settings.minimumDuplicateMethods;
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
        return modified;
    }

    // Save dialog inputs to ProjectSettingsState saved state
    @Override
    public void apply() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        settings.useMLModel = settingsComponent.getUseMLModel();
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
    }

    // Pull from saved state to preset dialog state upon opening
    @Override
    public void reset() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        settingsComponent.setUseMLModel(settings.useMLModel);
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
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}