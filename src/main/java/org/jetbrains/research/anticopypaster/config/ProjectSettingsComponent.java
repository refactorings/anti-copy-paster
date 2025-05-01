package org.jetbrains.research.anticopypaster.config;

import javax.swing.*;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

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
    private JLabel upToLabel;
    private JPanel manualHeuristicsPanel;
    private JPanel aiSettingsPanel;
    private JPanel aiderSettingsPanel;
    private JSpinner timeBufferSelector;
    private JSpinner minimumMethodSelector;
    private JSpinner maxParamsSpinner;
    private JPasswordField aiderApiKey;
    private JComboBox aidermodelComboBox;
    private JComboBox llmProviderComboBox;
    private JPanel providerPanel;
    private JPanel apiKeyPanel;
    private JPanel modelPanel;
    private JTextField aiderPath;
    private JPanel pathPanel;
    private JButton reset;

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
        reset.addActionListener(e -> {
            aiderPath.setText("aider");
            notifySettingsChanged();
        });
        // Add tooltips for Aider-related fields
        aiderPath.setToolTipText("Specify the path to the aider executable (The path to where you installed Aider). Default is 'aider'.");
        llmProviderComboBox.setToolTipText("Select the LLM provider, such as OpenAI, Gemini, Anthropic, or DeepSeek.");
        aidermodelComboBox.setToolTipText("Select the specific model you want to use from the provider.");
        aiderApiKey.setToolTipText("Enter your API key for the selected LLM provider.");

        // Add warning icon and tooltip for empty API key
        Icon warningIcon = AllIcons.General.Error;
        JLabel apiKeyWarningLabel = new JLabel(warningIcon);
        apiKeyWarningLabel.setToolTipText("API key not found for selected provider");
        apiKeyWarningLabel.setVisible(false);

        // Set layout and add aiderApiKey and warning label with proper constraints
        apiKeyPanel.setLayout(new GridBagLayout());

        // Add aiderApiKey field with constraints
        GridBagConstraints apiKeyGbc = new GridBagConstraints();
        apiKeyGbc.gridx = 1;
        apiKeyGbc.gridy = 0;
        apiKeyGbc.weightx = 1.0;
        apiKeyGbc.fill = GridBagConstraints.HORIZONTAL;
        apiKeyGbc.anchor = GridBagConstraints.WEST;
        apiKeyGbc.insets = new Insets(0, 0, 0, 0);
        apiKeyPanel.add(aiderApiKey, apiKeyGbc);

        // Add warning icon with constraints
        GridBagConstraints warningGbc = new GridBagConstraints();
        warningGbc.gridx = 2;
        warningGbc.gridy = 0;
        warningGbc.anchor = GridBagConstraints.WEST;
        warningGbc.insets = new Insets(0, 5, 0, 0);
        apiKeyPanel.add(apiKeyWarningLabel, warningGbc);

        // Watch for API key input changes and toggle warning visibility
        aiderApiKey.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }

            private void updateWarning() {
                boolean isEmpty = new String(aiderApiKey.getPassword()).trim().isEmpty();
                apiKeyWarningLabel.setVisible(isEmpty);
            }
        });

        // Initialize visibility based on current field state
        boolean initialEmpty = new String(aiderApiKey.getPassword()).trim().isEmpty();
        apiKeyWarningLabel.setVisible(initialEmpty);
        addConditionallyEnabledMetricGroup(keywordsEnabledCheckBox,keywordsSlider,keywordsRequiredCheckBox);
        addConditionallyEnabledMetricGroup(couplingEnabledCheckBox,couplingSlider,couplingRequiredCheckBox);
        addConditionallyEnabledMetricGroup(complexityEnabledCheckBox, complexitySlider, complexityRequiredCheckBox);
        addConditionallyEnabledMetricGroup(sizeEnabledCheckBox, sizeSlider, sizeRequiredCheckBox);
        modelComboBox.addActionListener(e -> updatePanelVisibilities());
        nameModel.addActionListener(e -> updatePanelVisibilities());
        updatePanelVisibilities();
        // Initialize provider and model dropdowns if empty
        if (llmProviderComboBox.getSelectedItem() == null) {
            llmProviderComboBox.setSelectedItem("OpenAI");
        }

        // Manually trigger action listener to populate models
        if (llmProviderComboBox.getActionListeners().length > 0) {
            llmProviderComboBox.getActionListeners()[0].actionPerformed(null);
        }
        // Watch for changes in the Aider API key field
        aiderApiKey.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { notifySettingsChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { notifySettingsChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { notifySettingsChanged(); }
        });

        // Watch for changes in the model selection combo box
        aidermodelComboBox.addActionListener(e -> notifySettingsChanged());
        llmProviderComboBox.addActionListener(e -> {
            String selectedProvider = (String) llmProviderComboBox.getSelectedItem();
            if (selectedProvider != null) {
                switch (selectedProvider) {
                    case "OpenAI" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                        "gpt-3.5-turbo", "gpt-4", "gpt-4-turbo", "gpt-4.1",
                            "gpt-4o", "gpt-4o-mini", "o1", "o1-mini", "o3", "o3-mini", "o4-mini"
                    }));
                    case "Gemini" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                        "gemini-2.5-pro"
                    }));
                    case "Anthropic" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                        "claude-2", "claude-2.1", "claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-3-7-sonnet-20250219",
                            "claude-3-7-sonnet-latest", "claude-3-opus-latest", "claude-3-sonnet-20240229",
                            "claude-instant-1", "claude-instant-1.2"
                    }));
                    case "DeepSeek" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                            "deepseek-chat", "deepseek-coder", "deepseek-reasoner"
                    }));
                    default -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                        "gpt-4" // fallback
                    }));
                }
            }
        });

        timeBufferSelector.setModel(new SpinnerNumberModel(10, 0, Integer.MAX_VALUE, 1));
        minimumMethodSelector.setModel(new SpinnerNumberModel(2, 2, Integer.MAX_VALUE, 1));
        maxParamsSpinner.setModel(new SpinnerNumberModel(10, 0, 255, 1));
        createUIComponents();
    }

    private void updatePanelVisibilities() {
        boolean isMainModelAider = (modelComboBox.getSelectedIndex() == 2);
        boolean isNameModelAider = (nameModel.getSelectedIndex() == 2);

        manualHeuristicsPanel.setVisible(modelComboBox.getSelectedIndex() == 1);
        aiSettingsPanel.setVisible(modelComboBox.getSelectedIndex() == 0);

        // Show Aider settings if either model selection is Aider
        boolean showAiderSettings = isMainModelAider || isNameModelAider;
        aiderSettingsPanel.setVisible(showAiderSettings);
        aiderSettingsPanel.revalidate();
        aiderSettingsPanel.repaint();
        aiderSettingsPanel.setMinimumSize(new Dimension(200, 100));

        // Filter nameModel options based on whether main model is Aider, preserving selection if possible
        Object currentSelection = nameModel.getSelectedItem();
        if (isMainModelAider) {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"Aider"});
            nameModel.setModel(model);
        } else {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"code2vec", "built-in", "Aider"});
            nameModel.setModel(model);
            if (currentSelection != null && model.getIndexOf(currentSelection) != -1) {
                nameModel.setSelectedItem(currentSelection);
            }
        }

        if (providerPanel != null) {
            providerPanel.setVisible(showAiderSettings);
            providerPanel.revalidate();
            providerPanel.repaint();
        }
        if (apiKeyPanel != null) {
            apiKeyPanel.setVisible(showAiderSettings);
            apiKeyPanel.revalidate();
            apiKeyPanel.repaint();
        }
        if (modelPanel != null) {
            modelPanel.setVisible(showAiderSettings);
            modelPanel.revalidate();
            modelPanel.repaint();
        }
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
            case 2 -> ProjectSettingsState.JudgementModel.AIDER;
            default -> throw new IllegalStateException("Unknown option selected.");
        };
    }

    public void setJudgementModel(ProjectSettingsState.JudgementModel model) {
        modelComboBox.setSelectedIndex(model.getIdx());
        updatePanelVisibilities();
    }

    public ProjectSettingsState.ExtractionType getExtractionType() {
        return switch (cloneTypeComboBox.getSelectedIndex()) {
            case 0 -> ProjectSettingsState.ExtractionType.TYPE_ONE;
            case 1 -> ProjectSettingsState.ExtractionType.TYPE_TWO;
            default -> throw new IllegalStateException("Unknown option selected.");
        };
    }

    public void setExtractionType(ProjectSettingsState.ExtractionType cloneType) { cloneTypeComboBox.setSelectedIndex(cloneType.getIdx()); }

    public int getMaxParams() {
        return (int) maxParamsSpinner.getValue();
    }

    public void setMaxParams(int maxParams) {
        maxParamsSpinner.setValue(maxParams);
    }

    public float getModelSensitivity() {
        return ((float)modelSensitivitySlider.getValue()) / 100.0f;
    }

    public void setModelSensitivity(float sensitivity) {
        modelSensitivitySlider.setValue((int)(sensitivity * 100));
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

    public String getAiderApiKey() {
        return new String(aiderApiKey.getPassword());
    }

    public String getSelectedAiderModel() {
        return (String) aidermodelComboBox.getSelectedItem();
    }

    public void setAiderApiKey(String apiKey) {
        aiderApiKey.setText(apiKey);
    }

    public void setSelectedAiderModel(String model) {
        aidermodelComboBox.setSelectedItem(model);
    }

    public String getLlmProvider() {
        return (String) llmProviderComboBox.getSelectedItem();
    }

    public void setLlmProvider(String provider) {
        llmProviderComboBox.setSelectedItem(provider);
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

    public String getAiderPath() {
        return aiderPath.getText();
    }

    public void setAiderPath(String path) {
        aiderPath.setText(path);
    }

    private void createUIComponents() {
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

    private void notifySettingsChanged() {
        // This method exists solely to trigger IntelliJ's internal modified state tracking
    }
}