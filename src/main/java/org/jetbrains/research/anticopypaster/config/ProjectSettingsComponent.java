package org.jetbrains.research.anticopypaster.config;

import javax.swing.*;
import com.intellij.openapi.project.Project;

import com.intellij.ui.JBIntSpinner;
import org.jetbrains.research.anticopypaster.config.advanced.AdvancedProjectSettingsDialogWrapper;

public class ProjectSettingsComponent {

    private JPanel mainPanel;
    private JRadioButton useModelControlRadioButton;
    private JRadioButton useManualControlRadioButton;
    private JSlider keywordsSlider;
    private JCheckBox keywordsEnabledCheckBox;
    private JCheckBox keywordsRequiredCheckBox;
    private JSlider couplingSlider;
    private JCheckBox couplingEnabledCheckBox;
    private JCheckBox couplingRequiredCheckBox;
    private JSlider sizeSlider;
    private JCheckBox sizeEnabledCheckBox;
    private JCheckBox sizeRequiredCheckBox;
    private JSlider complexitySlider;
    private JCheckBox complexityEnabledCheckBox;
    private JCheckBox complexityRequiredCheckBox;
    private JButton advancedSettingsButton;
    private JSpinner minimumMethodSelector;

    public ProjectSettingsComponent(Project project) {
        advancedSettingsButton.addActionListener(e -> {
            AdvancedProjectSettingsDialogWrapper advancedDialog = new AdvancedProjectSettingsDialogWrapper(project);
            boolean displayAndResolveAdvanced = advancedDialog.showAndGet();
            advancedDialog.saveSettings(displayAndResolveAdvanced);
        });
        addConditionallyEnabledMetricGroup(keywordsEnabledCheckBox,keywordsSlider,keywordsRequiredCheckBox);
        addConditionallyEnabledMetricGroup(couplingEnabledCheckBox,couplingSlider,couplingRequiredCheckBox);
        addConditionallyEnabledMetricGroup(complexityEnabledCheckBox, complexitySlider, complexityRequiredCheckBox);
        addConditionallyEnabledMetricGroup(sizeEnabledCheckBox, sizeSlider, sizeRequiredCheckBox);

    }
    private void addConditionallyEnabledMetricGroup(JCheckBox ind, JSlider depslid, JCheckBox dep) {
        ind.addActionListener(e -> {
                    if (ind.isSelected()) {
                        dep.setEnabled(true);
                        depslid.setEnabled(true);
                        dep.setSelected(true);
                    } else {
                        dep.setSelected(false);
                        depslid.setEnabled(false);
                        dep.setEnabled(false);
                    }
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

    public int getMinimumDuplicateMethods() { return (int) minimumMethodSelector.getValue(); }

    public void setMinimumDuplicateMethods(int minimumMethods) { minimumMethodSelector.setValue(minimumMethods); }

    public int getKeywordsSensitivity() {
        return keywordsSlider.getValue();
    }

    public void setKeywordsSensitivity(int sensitivity) {
        keywordsSlider.setValue(sensitivity);
    }

    public boolean getKeywordsEnabled() {
        return keywordsEnabledCheckBox.isSelected();
    }

    public void setKeywordsEnabled(boolean enabled) {
        keywordsEnabledCheckBox.setSelected(enabled);
    }

    public boolean getKeywordsRequired() {
        return keywordsRequiredCheckBox.isSelected();
    }

    public void setKeywordsRequired(boolean required) {
        keywordsRequiredCheckBox.setSelected(required);
    }

    public int getCouplingSensitivity() {
        return couplingSlider.getValue();
    }

    public void setCouplingSensitivity(int sensitivity) {
        couplingSlider.setValue(sensitivity);
    }

    public boolean getCouplingEnabled() {
        return couplingEnabledCheckBox.isSelected();
    }

    public void setCouplingEnabled(boolean enabled) {
        couplingEnabledCheckBox.setSelected(enabled);
    }

    public boolean getCouplingRequired() {
        return couplingRequiredCheckBox.isSelected();
    }

    public void setCouplingRequired(boolean required) {
        couplingRequiredCheckBox.setSelected(required);
    }

    public int getSizeSensitivity() {
        return sizeSlider.getValue();
    }

    public void setSizeSensitivity(int sensitivity) {
        sizeSlider.setValue(sensitivity);
    }

    public boolean getSizeEnabled() {
        return sizeEnabledCheckBox.isSelected();
    }

    public void setSizeEnabled(boolean enabled) {
        sizeEnabledCheckBox.setSelected(enabled);
    }

    public boolean getSizeRequired() {
        return sizeRequiredCheckBox.isSelected();
    }

    public void setSizeRequired(boolean required) {
        sizeRequiredCheckBox.setSelected(required);
    }

    public int getComplexitySensitivity() {
        return complexitySlider.getValue();
    }

    public void setComplexitySensitivity(int sensitivity) {
        complexitySlider.setValue(sensitivity);
    }

    public boolean getComplexityEnabled() {
        return complexityEnabledCheckBox.isSelected();
    }

    public void setComplexityEnabled(boolean enabled) {
        complexityEnabledCheckBox.setSelected(enabled);
    }

    public boolean getComplexityRequired() {
        return complexityRequiredCheckBox.isSelected();
    }

    public void setComplexityRequired(boolean required) {
        complexityRequiredCheckBox.setSelected(required);
    }

    private void createUIComponents() {
        minimumMethodSelector = new JBIntSpinner(2, 0, Integer.MAX_VALUE);
    }
}
