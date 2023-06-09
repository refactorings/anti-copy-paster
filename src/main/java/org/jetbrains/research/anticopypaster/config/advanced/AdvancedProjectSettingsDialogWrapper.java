package org.jetbrains.research.anticopypaster.config.advanced;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import java.util.EnumMap;
import javax.swing.*;

public class AdvancedProjectSettingsDialogWrapper extends DialogWrapper {

    Project project;
    private AdvancedProjectSettingsComponent settingsComponent;

    public AdvancedProjectSettingsDialogWrapper(Project project) {
        super(true);
        this.project = project;

        setTitle("AntiCopyPaster Advanced Settings");
        setResizable(true);

        init();
    }

    @Nullable
    @Override
    public JComponent createCenterPanel() {
        settingsComponent = new AdvancedProjectSettingsComponent();
        setValues();
        return settingsComponent.getPanel();
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        return settingsComponent.getPreferredFocusedComponent();
    }

    public void saveSettings(boolean pressedOK) {
        if (pressedOK) {
            ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

            settings.defineSizeByLines = settingsComponent.getSizeDeterminedByLineCount();
            settings.measureKeywordsByTotal = settingsComponent.getKeywordsDefinedByTotal();
            settings.activeKeywords = new EnumMap<>(settingsComponent.getActiveKeywords());
            settings.measureComplexityByTotal = settingsComponent.getComplexityDefinedByTotal();
            settings.measureCouplingByTotal = settingsComponent.getCouplingDefinedByTotal();
            settings.connectivityType = settingsComponent.getConnectivityType();
        }
    }

    public void setValues() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

        settingsComponent.setDefineSizeByLines(settings.defineSizeByLines);
        settingsComponent.setDefineKeywordsByTotal(settings.measureKeywordsByTotal);
        settingsComponent.setActiveKeywords(settings.activeKeywords);
        settingsComponent.setDefineComplexityByTotal(settings.measureComplexityByTotal);
        settingsComponent.setDefineCouplingByTotal(settings.measureCouplingByTotal);
        settingsComponent.setDefineConnectivityType(settings.connectivityType);
    }

}
