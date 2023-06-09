package org.jetbrains.research.anticopypaster.config;

import javax.swing.*;
import com.intellij.openapi.project.Project;

import org.jetbrains.research.anticopypaster.config.advanced.AdvancedProjectSettingsDialogWrapper;

public class ProjectSettingsComponent {

    private JPanel mainPanel;
    private JRadioButton useModelControlRadioButton;
    private JRadioButton useManualControlRadioButton;
    private JSlider keywordsSlider;
    private JCheckBox keywordsCheckBox;
    private JSlider couplingSlider;
    private JCheckBox couplingCheckBox;
    private JSlider sizeSlider;
    private JCheckBox sizeCheckBox;
    private JSlider complexitySlider;
    private JCheckBox complexityCheckBox;
    private JButton advancedSettingsButton;

    public ProjectSettingsComponent(Project project) {
        ButtonGroup radioButtons = new ButtonGroup();
        radioButtons.add(useModelControlRadioButton);
        radioButtons.add(useManualControlRadioButton);
        advancedSettingsButton.addActionListener( e-> {
                AdvancedProjectSettingsDialogWrapper advancedDialog = new AdvancedProjectSettingsDialogWrapper(project);
                boolean displayAndResolveAdvanced = advancedDialog.showAndGet();
                advancedDialog.saveSettings(displayAndResolveAdvanced);
            }
        );
    }

    public JPanel getPanel() {
        return mainPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return useModelControlRadioButton;
    }

    public boolean getUseMLModel() {
        return useModelControlRadioButton.isSelected();
    }

    public void setUseMLModel(boolean useMLModel) {
        useModelControlRadioButton.setSelected(useMLModel);
        useManualControlRadioButton.setSelected(!useMLModel);
    }

    public int getKeywordsSensitivity() {
        return keywordsSlider.getValue();
    }

    public void setKeywordsSensitivity(int sensitivity) {
        keywordsSlider.setValue(sensitivity);
    }

    public boolean getKeywordsRequired() {
        return keywordsCheckBox.isSelected();
    }

    public void setKeywordsRequired(boolean required) {
        keywordsCheckBox.setSelected(required);
    }

    public int getCouplingSensitivity() {
        return couplingSlider.getValue();
    }

    public void setCouplingSensitivity(int sensitivity) {
        couplingSlider.setValue(sensitivity);
    }

    public boolean getCouplingRequired() {
        return couplingCheckBox.isSelected();
    }

    public void setCouplingRequired(boolean required) {
        couplingCheckBox.setSelected(required);
    }

    public int getSizeSensitivity() {
        return sizeSlider.getValue();
    }

    public void setSizeSensitivity(int sensitivity) {
        sizeSlider.setValue(sensitivity);
    }

    public boolean getSizeRequired() {
        return sizeCheckBox.isSelected();
    }

    public void setSizeRequired(boolean required) {
        sizeCheckBox.setSelected(required);
    }

    public int getComplexitySensitivity() {
        return complexitySlider.getValue();
    }

    public void setComplexitySensitivity(int sensitivity) {
        complexitySlider.setValue(sensitivity);
    }

    public boolean getComplexityRequired() {
        return complexityCheckBox.isSelected();
    }

    public void setComplexityRequired(boolean required) {
        complexityCheckBox.setSelected(required);
    }
}
