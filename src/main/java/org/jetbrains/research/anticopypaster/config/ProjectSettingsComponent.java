package org.jetbrains.research.anticopypaster.config;

import javax.swing.*;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import com.intellij.ui.JBIntSpinner;
import org.jetbrains.research.anticopypaster.config.advanced.AdvancedProjectSettingsDialogWrapper;
import org.jetbrains.research.anticopypaster.config.credentials.CredentialsDialogWrapper;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class ProjectSettingsComponent {

    private JPanel mainPanel;
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
    private JSpinner timeBufferSelector;
    private JButton statisticsCollectionButton;
    private JLabel helpLabel;
    private JLabel duplicateMethodsHelp;
    private JLabel waitTimeHelp;
    private JLabel statisticsButtonHelp;
    private JLabel advancedButtonHelp;
    private JComboBox nameModel;
    private JSlider numOfPred;
    private JComboBox modelComboBox;
    private JComboBox cloneTypeComboBox;
    private JSlider modelSensitivitySlider;
    private JLabel modelSensitivityHelp;

    private static final Logger LOG = Logger.getInstance(ProjectSettingsComponent.class);

    public ProjectSettingsComponent(Project project) {
        advancedSettingsButton.addActionListener(e -> {
            AdvancedProjectSettingsDialogWrapper advancedDialog = new AdvancedProjectSettingsDialogWrapper(project);
            boolean displayAndResolveAdvanced = advancedDialog.showAndGet();
            advancedDialog.saveSettings(displayAndResolveAdvanced);
        });
        statisticsCollectionButton.addActionListener(e -> {
            CredentialsDialogWrapper credentialsDialog = new CredentialsDialogWrapper(project);
            boolean displayAndResolveCredentials = credentialsDialog.showAndGet();
            credentialsDialog.saveSettings(displayAndResolveCredentials);
        });
        addConditionallyEnabledMetricGroup(keywordsEnabledCheckBox,keywordsSlider,keywordsRequiredCheckBox);
        addConditionallyEnabledMetricGroup(couplingEnabledCheckBox,couplingSlider,couplingRequiredCheckBox);
        addConditionallyEnabledMetricGroup(complexityEnabledCheckBox, complexitySlider, complexityRequiredCheckBox);
        addConditionallyEnabledMetricGroup(sizeEnabledCheckBox, sizeSlider, sizeRequiredCheckBox);
        createUIComponents();
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
        return minimumMethodSelector;
    }

    public int getMinimumDuplicateMethods() { return (int) minimumMethodSelector.getValue(); }

    public void setMinimumDuplicateMethods(int minimumMethods) { minimumMethodSelector.setValue(minimumMethods); }

    public int getTimeBuffer() { return (int) timeBufferSelector.getValue(); }

    public void setTimeBuffer(int timeBuffer) { timeBufferSelector.setValue(timeBuffer); }

    public ProjectSettingsState.JudgementModel getJudgementModel() {
        return switch (modelComboBox.getSelectedIndex()) {
            case 0 -> ProjectSettingsState.JudgementModel.TENSORFLOW;
            case 1 -> ProjectSettingsState.JudgementModel.USER_SETTINGS;
            default -> throw new IllegalStateException("Unknown option selected.");
        };
    }

    public void setJudgementModel(ProjectSettingsState.JudgementModel model) { modelComboBox.setSelectedIndex(model.getIdx()); }

    public ProjectSettingsState.ExtractionType getExtractionType() {
        return switch (cloneTypeComboBox.getSelectedIndex()) {
            case 0 -> ProjectSettingsState.ExtractionType.TYPE_ONE;
            case 1 -> ProjectSettingsState.ExtractionType.TYPE_TWO;
            default -> throw new IllegalStateException("Unknown option selected.");
        };
    }

    public void setExtractionType(ProjectSettingsState.ExtractionType cloneType) { cloneTypeComboBox.setSelectedIndex(cloneType.getIdx()); }

    public int getModelSensitivity() {
        return modelSensitivitySlider.getValue();
    }

    public void setModelSensitivity(int sensitivity) {
        modelSensitivitySlider.setValue(sensitivity);
    }

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
    public void setNameModel(int selectedIndex) { nameModel.setSelectedIndex(selectedIndex); }
    public int getNameModel() { return (nameModel.getSelectedIndex()); }
    public int getNumOfPreds() {
        return numOfPred.getValue();
    }

    public void setNumOfPreds(int preds) {
        numOfPred.setValue(preds);
    }

    private void createUIComponents() {
        minimumMethodSelector = new JBIntSpinner(2, 0, Integer.MAX_VALUE);
        timeBufferSelector = new JBIntSpinner(10, 1, 300);
        // Set link and icons for help features
        helpLabel = new JLabel();
        createLinkListener(helpLabel, "https://se4airesearch.github.io/AntiCopyPaster_Summer2023/index.html");
        helpLabel.setIcon(AllIcons.Ide.External_link_arrow);
        duplicateMethodsHelp = new JLabel();
        duplicateMethodsHelp.setIcon(AllIcons.General.ContextHelp);
        waitTimeHelp = new JLabel();
        waitTimeHelp.setIcon(AllIcons.General.ContextHelp);
        advancedButtonHelp = new JLabel();
        advancedButtonHelp.setIcon(AllIcons.General.ContextHelp);
        statisticsButtonHelp = new JLabel();
        statisticsButtonHelp.setIcon(AllIcons.General.ContextHelp);
        modelSensitivityHelp = new JLabel();
        modelSensitivityHelp.setIcon(AllIcons.General.ContextHelp);
    }

    public static void createLinkListener(JComponent component, String url) {
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    URI uri = new URI(url);
                    Desktop.getDesktop().browse(uri);
                } catch (IOException | URISyntaxException ex) {
                    LOG.error("Failed to open link", ex);
                }
            }
        });
    }
}
