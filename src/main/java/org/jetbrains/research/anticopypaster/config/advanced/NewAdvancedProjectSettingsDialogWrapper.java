package org.jetbrains.research.anticopypaster.config.advanced;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import java.util.EnumMap;
import javax.swing.*;

public class NewAdvancedProjectSettingsDialogWrapper extends DialogWrapper {

    Project project;
    private NewAdvancedProjectSettingsComponent settingsComponent;

    public NewAdvancedProjectSettingsDialogWrapper(Project project) {
        super(true);
        this.project = project;

        setTitle("AntiCopyPaster Advanced Settings");
        setResizable(true);

        init();
    }

    @Nullable
    @Override
    public JComponent createCenterPanel() {
        settingsComponent = new NewAdvancedProjectSettingsComponent();
        setFields();
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

            settings.measureKeywordsTotal = settingsComponent.getKeywordTotalSubmetricInfo();
            settings.measureKeywordsDensity = settingsComponent.getKeywordDensitySubmetricInfo();
            settings.activeKeywords = new EnumMap<>(settingsComponent.getActiveKeywords());
            settings.measureCouplingTotal = settingsComponent.getCouplingTotalSubmetricInfo();
            settings.measureCouplingDensity = settingsComponent.getCouplingDensitySubmetricInfo();
            settings.measureTotalConnectivity = settingsComponent.getTotalConnectivityInfo();
            settings.measureFieldConnectivity = settingsComponent.getFieldConnectivityInfo();
            settings.measureMethodConnectivity = settingsComponent.getMethodConnectivityInfo();
            settings.measureComplexityTotal = settingsComponent.getComplexityTotalSubmetricInfo();
            settings.measureComplexityDensity = settingsComponent.getComplexityDensitySubmetricInfo();
            settings.measureSizeByLines = settingsComponent.getSizeByLinesSubmetricInfo();
            settings.measureSizeBySymbols = settingsComponent.getSizeBySymbolsSubmetricInfo();
        }
    }

    public void setFields() {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);

        settingsComponent.setKeywordTotalSubmetric(settings.measureKeywordsTotal[0], settings.measureKeywordsTotal[1]);
        settingsComponent.setKeywordDensitySubmetric(settings.measureKeywordsDensity[0], settings.measureKeywordsDensity[1]);
        settingsComponent.setActiveKeywords(settings.activeKeywords);

        settingsComponent.setCouplingTotalSubmetric(settings.measureCouplingTotal[0], settings.measureCouplingTotal[1]);
        settingsComponent.setCouplingDensitySubmetric(settings.measureCouplingDensity[0], settings.measureCouplingDensity[1]);
        settingsComponent.setTotalConnectivity(settings.measureTotalConnectivity[0], settings.measureTotalConnectivity[1]);
        settingsComponent.setFieldConnectivity(settings.measureFieldConnectivity[0], settings.measureFieldConnectivity[1]);
        settingsComponent.setMethodConnectivity(settings.measureMethodConnectivity[0], settings.measureMethodConnectivity[1]);

        settingsComponent.setComplexityTotalSubmetric(settings.measureComplexityTotal[0], settings.measureComplexityTotal[1]);
        settingsComponent.setComplexityDensitySubmetric(settings.measureComplexityDensity[0], settings.measureComplexityDensity[1]);

        settingsComponent.setSizeByLinesSubmetric(settings.measureSizeByLines[0], settings.measureSizeByLines[1]);
        settingsComponent.setSizeBySymbolsSubmetric(settings.measureSizeBySymbols[0], settings.measureSizeBySymbols[1]);
    }

}
