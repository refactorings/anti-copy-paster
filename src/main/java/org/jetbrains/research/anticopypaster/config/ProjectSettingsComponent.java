package org.jetbrains.research.anticopypaster.config;

import javax.swing.*;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import org.jetbrains.research.anticopypaster.config.advanced.AdvancedProjectSettingsDialogWrapper;
import org.jetbrains.research.anticopypaster.config.credentials.CredentialsDialogWrapper;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;


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
    private JButton reset;
    private JPanel filesPanel;
    private ButtonGroup analysisSelectionButtonGroup;
    private ArrayList<JRadioButton> analysisSelectionButtonList;
    private JRadioButton currentFileButton;
    private JRadioButton allFilesButton;
    private JRadioButton multipleFilesButton;
    private ActionListener analysisSelectionButtonListener;
    private JLabel filesToAnalyzeLabel;
    private JLabel filesDirLabel;
    private JTextField filesPath;
    private JButton findFilesInDirButton;
    private JLabel filesToAnalyzeSelectionLabel;
    private JPanel filesCheckboxesPanel;
    private JPanel multFilesPanel;
    private JScrollPane filesCheckboxesScrollPane;
    private JPanel azureApiVersion;
    private JTextField apiVersion;
    private JPanel azureApiBase;
    private JTextField apiBase;
    private JLabel aiderHelpLabel;
    private JLabel apiBaseHelp;
    private JPanel ollamaModelPanel;
    private JTextField ollamaModel;
    private JPanel fileSelectionPanel;
    private JSlider iterationSlider;
    private JPanel iterationNumberPanel;
    private JLabel iterationHelp;
    private ArrayList<JCheckBox> allFilesCheckboxes;
    private final Project projectRef;
    private Integer pendingMainModelIndex = null;
    private Integer pendingNameModelIndex = null;

    private static final Logger LOG = Logger.getInstance(ProjectSettingsComponent.class);
    private JButton toggleApiKeyVisibilityButton;
    private JPanel passwordWithEyePanel;
    private boolean apiKeyVisible = false;
    private char defaultApiKeyEchoChar;

    // Suppress auto-close of Aider windows during initial render
    private boolean suppressAutoCloseOnInit = true;
    private boolean isLoadingSettings = false;
    private Object lastMainModel = null;
    private Object lastNameModel = null;
    private JLabel apiBaseWarningLabel;
    private JLabel ollamaModelWarningLabel;
    /**
     * Builds and wires the Project Settings UI for AntiCopyPaster, including provider/model pickers,
     * Aider fields, validation, and dynamic panel visibility.
     *
     * @param project IntelliJ project used for dialogs and helper calls
     */
    // Simple warning icons like API key for Ollama-required field
    public ProjectSettingsComponent(Project project) {
        this.projectRef = project;
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

        // Add tooltips for Aider-related fields
        llmProviderComboBox.setToolTipText("Select the LLM provider, such as OpenAI, Gemini, Anthropic, DeepSeek or Azure.");
        aidermodelComboBox.setToolTipText("Select the specific model you want to use from the provider.");
        aiderApiKey.setToolTipText("Enter your API key for the selected LLM provider.");
        filesPath.setToolTipText("Specify the path to the directory with the files you would like to search for clones in.");

        // Add warning icon and tooltip for empty API key
        Icon warningIcon = AllIcons.General.Error;
        JLabel apiKeyWarningLabel = new JLabel(warningIcon);
        apiKeyWarningLabel.setToolTipText("API key not found for selected provider");
        apiKeyWarningLabel.setVisible(false);


        // Set layout and add aiderApiKey and warning label with proper constraints
        apiKeyPanel.setLayout(new GridBagLayout());
        defaultApiKeyEchoChar = aiderApiKey.getEchoChar();

        // Wrap the password field with an eye icon drawn "inside" the field at the far right
        passwordWithEyePanel = new JPanel();
        passwordWithEyePanel.setLayout(new OverlayLayout(passwordWithEyePanel));
        passwordWithEyePanel.setOpaque(false);
        // Ensure the wrapper uses the same height as the field (prevents icon from floating vertically)
        Dimension fieldSize = aiderApiKey.getPreferredSize();
        passwordWithEyePanel.setPreferredSize(new Dimension(fieldSize.width, fieldSize.height));
        passwordWithEyePanel.setMinimumSize(new Dimension(0, fieldSize.height));
        passwordWithEyePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldSize.height));

        Icon eyeIcon = AllIcons.General.InspectionsEye;
        toggleApiKeyVisibilityButton = new JButton(eyeIcon); // IntelliJ eye icon
        toggleApiKeyVisibilityButton.setFocusable(false);
        toggleApiKeyVisibilityButton.setBorderPainted(false);
        toggleApiKeyVisibilityButton.setContentAreaFilled(false);
        toggleApiKeyVisibilityButton.setOpaque(false);
        toggleApiKeyVisibilityButton.setMargin(new Insets(0, 0, 0, 0));
        toggleApiKeyVisibilityButton.setBorder(BorderFactory.createEmptyBorder());
        // Tight preferred size around the icon to avoid extra blank space
        toggleApiKeyVisibilityButton.setPreferredSize(new Dimension(eyeIcon.getIconWidth() + 2, eyeIcon.getIconHeight() + 2));
        toggleApiKeyVisibilityButton.setToolTipText("Show/Hide the API key");

        // Align eye icon horizontally with combo-box arrows by adding a right inset
        int eyeRightInset = 6; // tweak this to nudge left/right as desired
        int eyePad = eyeIcon.getIconWidth() + eyeRightInset + 4; // keep a small gap between text and icon
        aiderApiKey.setBorder(BorderFactory.createCompoundBorder(
                aiderApiKey.getBorder(),
                BorderFactory.createEmptyBorder(0, 0, 0, eyePad)
        ));

        // A transparent right-aligned holder laid over the password field, vertically centered
        int vpad = Math.max(0, (fieldSize.height - eyeIcon.getIconHeight()) / 2 - 1); // -1 to visually nudge to true center
        JPanel eyeOverlay = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        eyeOverlay.setOpaque(false);
        eyeOverlay.setBorder(BorderFactory.createEmptyBorder(vpad, 0, vpad, eyeRightInset));
        eyeOverlay.add(toggleApiKeyVisibilityButton);

        // Ensure both components fill the same bounds for proper overlay
        aiderApiKey.setAlignmentX(0.0f);
        aiderApiKey.setAlignmentY(0.5f);
        eyeOverlay.setAlignmentX(0.0f);
        eyeOverlay.setAlignmentY(0.5f);

        passwordWithEyePanel.add(eyeOverlay);
        passwordWithEyePanel.add(aiderApiKey);

        // Add aiderApiKey field (now wrapped) with constraints
        GridBagConstraints apiKeyGbc = new GridBagConstraints();
        apiKeyGbc.gridx = 1;
        apiKeyGbc.gridy = 0;
        apiKeyGbc.weightx = 1.0;
        apiKeyGbc.fill = GridBagConstraints.HORIZONTAL;
        apiKeyGbc.anchor = GridBagConstraints.WEST;
        apiKeyGbc.insets = new Insets(0, 0, 0, 0);
        apiKeyPanel.add(passwordWithEyePanel, apiKeyGbc);
        scrollApiKeyToStart();

        // Add warning icon with constraints
        GridBagConstraints warningGbc = new GridBagConstraints();
        warningGbc.gridx = 2;
        warningGbc.gridy = 0;
        warningGbc.anchor = GridBagConstraints.WEST;
        warningGbc.insets = new Insets(0, 5, 0, 0);
        apiKeyPanel.add(apiKeyWarningLabel, warningGbc);

        // Warning labels for Ollama-required fields (simple and consistent with API key)
        apiBaseWarningLabel = new JLabel(AllIcons.General.Error);
        apiBaseWarningLabel.setToolTipText("API Base is required when provider is Ollama");
        apiBaseWarningLabel.setVisible(false);

        ollamaModelWarningLabel = new JLabel(AllIcons.General.Error);
        ollamaModelWarningLabel.setToolTipText("Ollama Model Name is required when provider is Ollama");
        ollamaModelWarningLabel.setVisible(false);

        // Safely add warning icons without altering layout or reparenting existing fields
        try {
            if (apiBaseWarningLabel.getParent() == null) {
                azureApiBase.add(apiBaseWarningLabel);
            }
        } catch (Exception ignored) {}
        try {
            if (ollamaModelWarningLabel.getParent() == null) {
                ollamaModelPanel.add(ollamaModelWarningLabel);
            }
        } catch (Exception ignored) {}

        // Toggle API key visibility via the eye button
        toggleApiKeyVisibilityButton.addActionListener(e2 -> {
            apiKeyVisible = !apiKeyVisible;
            if (apiKeyVisible) {
                aiderApiKey.setEchoChar((char) 0); // show characters
                toggleApiKeyVisibilityButton.setIcon(AllIcons.General.InspectionsEye); // fallback: use eye icon for both states
                toggleApiKeyVisibilityButton.setToolTipText("Hide the API key");
                scrollApiKeyToStart();
            } else {
                aiderApiKey.setEchoChar(defaultApiKeyEchoChar); // restore default echo char
                toggleApiKeyVisibilityButton.setIcon(AllIcons.General.InspectionsEye); // eye icon
                toggleApiKeyVisibilityButton.setToolTipText("Show the API key");
                scrollApiKeyToStart();
            }
        });

        // Set layout for filesPanel and filesCheckboxesPanel; initialize ArrayList to keep track of checkboxes
        filesPanel.setLayout(new GridBagLayout());
        multFilesPanel.setLayout(new GridBagLayout());
        filesCheckboxesPanel.setLayout(new GridBagLayout());
        allFilesCheckboxes = new ArrayList<>();

        // Add elements (filesToAnalyzeLabel and JRadioButtons) with constraints to filesPanel
        GridBagConstraints filesPanelRadioButtonsGbc = new GridBagConstraints();
        filesPanelRadioButtonsGbc.gridx = 0;
        filesPanelRadioButtonsGbc.gridy = 0;
        filesPanelRadioButtonsGbc.weightx = 1.0;
        filesPanelRadioButtonsGbc.fill = GridBagConstraints.BOTH;
        filesPanelRadioButtonsGbc.anchor = GridBagConstraints.WEST;
        filesPanelRadioButtonsGbc.insets = new Insets(0, 0, 0, 0);
        filesPanel.add(filesToAnalyzeLabel, filesPanelRadioButtonsGbc);
        filesPanelRadioButtonsGbc.gridx = 1;
        filesPanel.add(currentFileButton, filesPanelRadioButtonsGbc);
        filesPanelRadioButtonsGbc.gridx = 2;
        filesPanel.add(allFilesButton, filesPanelRadioButtonsGbc);
        filesPanelRadioButtonsGbc.gridx = 3;
        filesPanel.add(multipleFilesButton, filesPanelRadioButtonsGbc);

        // Add filesPath field with constraints to multFilesPathGbc
        GridBagConstraints multFilesPathGbc = new GridBagConstraints();
        multFilesPathGbc.gridx = 1;
        multFilesPathGbc.gridy = 0;
        multFilesPathGbc.weightx = 1.0;
        multFilesPathGbc.fill = GridBagConstraints.BOTH;
        multFilesPathGbc.anchor = GridBagConstraints.EAST;
        multFilesPathGbc.insets = new Insets(0, 0, 0, 0);
        multFilesPanel.add(filesPath, multFilesPathGbc);

        // Add findFilesInDir button to multFilesPanel
        multFilesPanel.add(findFilesInDirButton);

        // Set default visibility for filesPanel and elements
        filesPanel.setVisible(true);
        multFilesPanel.setVisible(false);
        filesCheckboxesScrollPane.setVisible(false);

        // Add warning icon and tooltip for invalid directory path
        JLabel dirPathWarningLabel = new JLabel(warningIcon);
        dirPathWarningLabel.setToolTipText("Invalid directory path");
        dirPathWarningLabel.setVisible(false);

        // Add warning icon with constraints
        GridBagConstraints dirPathWarningGbc = new GridBagConstraints();
        dirPathWarningGbc.gridx = 3;
        dirPathWarningGbc.gridy = 0;
        dirPathWarningGbc.anchor = GridBagConstraints.WEST;
        dirPathWarningGbc.insets = new Insets(0, 5, 0, 0);
        multFilesPanel.add(dirPathWarningLabel, dirPathWarningGbc);

        // Add warning icon and tooltip for empty directory path
        JLabel emptyDirPathWarningLabel = new JLabel(warningIcon);
        emptyDirPathWarningLabel.setToolTipText("Empty directory path");
        emptyDirPathWarningLabel.setVisible(false);

        // Add warning icon with constraints
        GridBagConstraints emptyDirPathWarningGbc = new GridBagConstraints();
        emptyDirPathWarningGbc.gridx = 3;
        emptyDirPathWarningGbc.gridy = 0;
        emptyDirPathWarningGbc.anchor = GridBagConstraints.WEST;
        emptyDirPathWarningGbc.insets = new Insets(0, 5, 0, 0);
        multFilesPanel.add(emptyDirPathWarningLabel, emptyDirPathWarningGbc);

        // Create an ActionListener for the currentFileButton, allFilesButton, and multipleFilesButton
        // (If user selects the "Multiple Files" option, make extra fields visible)
        // (If user selects either of the other buttons, resort to default visibility)
        analysisSelectionButtonListener = e -> {
            JRadioButton selectedButton = (JRadioButton) e.getSource();
            if((selectedButton.getText()).equals("Current File") ||
                    (selectedButton.getText()).equals("All Files in Current Directory")) {
                multFilesPanel.setVisible(false);
                filesCheckboxesScrollPane.setVisible(false);
            } else if(selectedButton.getText().equals("Multiple Files")) {
                multFilesPanel.setVisible(true);
            }
        };

        // Watch for actions in relation to currentFileButton, allFilesButton, and multipleFilesButton
        currentFileButton.addActionListener(analysisSelectionButtonListener);
        allFilesButton.addActionListener(analysisSelectionButtonListener);
        multipleFilesButton.addActionListener(analysisSelectionButtonListener);

        // Watch for action in relation to Find Files button (if user clicks the Find Files button)
        findFilesInDirButton.addActionListener(e -> {
            dirPathWarningLabel.setVisible(false);
            emptyDirPathWarningLabel.setVisible(false);
            filesCheckboxesPanel.removeAll(); // Clear checkboxes panel of any previous files' checkboxes
            allFilesCheckboxes.clear(); // Clear ArrayList of any previous files' checkboxes

            // Establishing GBC for filesToAnalyzeSelectionLabel and checkboxes to be added to filesCheckboxesPanel
            GridBagConstraints filesCheckboxesPanelGbc = new GridBagConstraints();
            filesCheckboxesPanelGbc.gridx = 0;
            filesCheckboxesPanelGbc.gridy = 0;
            filesCheckboxesPanelGbc.weightx = 1.0;
            filesCheckboxesPanelGbc.fill = GridBagConstraints.BOTH;
            filesCheckboxesPanelGbc.anchor = GridBagConstraints.WEST;
            filesCheckboxesPanelGbc.insets = new Insets(0, 8, 8, 0);

            filesCheckboxesPanel.add(filesToAnalyzeSelectionLabel, filesCheckboxesPanelGbc); // Add filesToAnalyzeSelectionLabel to filesCheckboxesPanel again
            String filesPathStr = filesPath.getText();
            // Check if a path was provided, and if it leads to a valid directory
            if(!(filesPathStr.equals(""))) {
                File filesDir = new File(filesPathStr);
                if(filesDir.isDirectory()) {
                    File[] allFiles = filesDir.listFiles();
                    // If files exist in the directory:
                    // Create a checkbox for each file and add them to the checkbox panel + ArrayList
                    if(allFiles.length > 0) {
                        // Initializing rowNum and colNum for future use in filesCheckboxesPanelGbc
                        int rowNum = 0;
                        int colNum = 1;

                        for(File file : allFiles) {
                            // Moving to column 0 of next row if colNum > last column of current row
                            if(colNum > 3) {
                                colNum = 0;
                                rowNum++;
                            }

                            filesCheckboxesPanelGbc.gridx = colNum;
                            filesCheckboxesPanelGbc.gridy = rowNum;

                            JCheckBox fileCheckBox = new JCheckBox(file.getName());
                            filesCheckboxesPanel.add(fileCheckBox, filesCheckboxesPanelGbc);
                            allFilesCheckboxes.add(fileCheckBox);

                            colNum++;
                        }
                        filesCheckboxesScrollPane.setVisible(true);
                        filesToAnalyzeSelectionLabel.setVisible(true);
                    } else {
                        // If an empty directory path was provided:
                        emptyDirPathWarningLabel.setVisible(true);
                    }
                } else {
                    // If an invalid directory path was provided:
                    dirPathWarningLabel.setVisible(true);
                }
            } else {
                // If no directory path was provided:
                dirPathWarningLabel.setVisible(true);
            }
        });

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

        modelComboBox.addActionListener(e -> {
            if (!suppressAutoCloseOnInit && !isLoadingSettings) {
                pendingMainModelIndex = modelComboBox.getSelectedIndex();
            }
            updatePanelVisibilities();
        });

        nameModel.addActionListener(e -> {
            if (!suppressAutoCloseOnInit && !isLoadingSettings) {
                pendingNameModelIndex = nameModel.getSelectedIndex();
            }
            updatePanelVisibilities();
        });

        updatePanelVisibilities();

        apiBase.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
        });
        ollamaModel.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
        });
        String initProvider = (String) llmProviderComboBox.getSelectedItem();
        updateProviderSpecificPanels(initProvider);
        // Initialize provider and model dropdowns if empty
        if (llmProviderComboBox.getSelectedItem() == null) {
            llmProviderComboBox.setSelectedItem("OpenAI");
        }

        // Manually trigger action listener to populate models
        if (llmProviderComboBox.getActionListeners().length > 0) {
            llmProviderComboBox.getActionListeners()[0].actionPerformed(null);
        }

        // Watch for changes in the model selection combo box
        aidermodelComboBox.addActionListener(e -> notifySettingsChanged());
        // Update model list when provider changes. You can add different providers and their models here.
        llmProviderComboBox.addActionListener(e -> {
            String selectedProvider = (String) llmProviderComboBox.getSelectedItem();
            if (selectedProvider != null) {
                switch (selectedProvider) {
                    case "OpenAI" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                            "gpt-3.5-turbo", "gpt-4", "gpt-4-turbo", "gpt-4.1",
                            "gpt-4o", "gpt-4o-mini", "gpt-5", "gpt-5-chat", "gpt-5-nano", "gpt-5-mini", "o1", "o1-mini", "o3", "o3-mini", "o4-mini"
                    }));
                    case "Gemini" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                            "gemini-1.5-pro", "gemini-1.5-flash", "gemini-2.5-pro", "gemini-2.5-flash", "gemini-3-pro-preview", "gemini-3-flash-preview"
                    }));
                    case "Anthropic" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                           "claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-3-7-sonnet-20250219",
                            "claude-3-7-sonnet-latest", "claude-3-opus-latest", "claude-3-sonnet-20240229", "claude-4-opus-20250514", "claude-4-sonnet-20250514",
                            "claude-opus-4-1", "claude-opus-4-1-20250805", "claude-haiku-4-5", "claude-sonnet-4-5", "claude-sonnet-4-5-20250929",
                    }));
                    case "DeepSeek" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                            "deepseek-chat", "deepseek-coder", "deepseek-reasoner", "deepseek-v3"
                    }));
                    case "Azure" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                            "gpt-3.5-turbo", "gpt-4", "gpt-4-turbo", "gpt-4.1", "gpt-4o", "gpt-5", "o1", "o1-mini", "o3", "o3-mini", "o4-mini", "DeepSeek-V3-0324", "DeepSeek-V3.1", "grok-3"
                    }));
                    case "xAI" -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                           "grok-2", "grok-2-latest", "grok-3", "grok-3-beta", "grok-3-fast-beta", "grok-3-latest", "grok-3-mini", "grok-3-mini-beta", "grok-3-mini-fast-beta", "grok-4", "grok-4-0709", "grok-4-fast-non-reasoning", "grok-4-latest", "grok-beta", "grok-code-fast", "grok-code-fast-1", "grok-code-fast-1-0825"
                    }));

                    default -> aidermodelComboBox.setModel(new DefaultComboBoxModel<>(new String[] {
                            "gpt-5" // fallback
                    }));
                }
                // Toggle Azure-specific fields on provider change
                updateProviderSpecificPanels(selectedProvider);
                // Refresh layout to reflect visibility changes
                azureApiVersion.revalidate();
                azureApiVersion.repaint();
                azureApiBase.revalidate();
                azureApiBase.repaint();
                mainPanel.revalidate();
                mainPanel.repaint();
            }
        });

        // Ensure warning icons are up-to-date at startup
        updateOllamaWarnings();

        timeBufferSelector.setModel(new SpinnerNumberModel(10, 0, Integer.MAX_VALUE, 1));
        minimumMethodSelector.setModel(new SpinnerNumberModel(2, 2, Integer.MAX_VALUE, 1));
        maxParamsSpinner.setModel(new SpinnerNumberModel(10, 0, 255, 1));
        createUIComponents();

        // Make iteration slider move in discrete steps and show ticks
        // Show ONLY min/max labels (not every tick)
        if (iterationSlider != null) {
            iterationSlider.setPaintTicks(true);
            iterationSlider.setSnapToTicks(true);

            if (iterationSlider.getMajorTickSpacing() <= 0) {
                iterationSlider.setMajorTickSpacing(1);
            }
            // Minor ticks are optional; keep them only if already configured.
            // If you want minor ticks, you can set them in the .form; we won't force them here.

            // Only show labels at the two ends
            int min = iterationSlider.getMinimum();
            int max = iterationSlider.getMaximum();
            Hashtable<Integer, JLabel> labelTable = new Hashtable<>();
            labelTable.put(min, new JLabel(String.valueOf(min)));
            labelTable.put(max, new JLabel(String.valueOf(max)));
            iterationSlider.setLabelTable(labelTable);
            iterationSlider.setPaintLabels(true);
        }

        // Record initial model state
        lastMainModel = modelComboBox.getSelectedItem();
        lastNameModel = nameModel.getSelectedItem();

        // Mark initialization complete; subsequent visibility updates may close Aider windows
        suppressAutoCloseOnInit = false;
    }

    /**
     * Applies pending model combo-box changes.
     */
    public void applyModelChanges() {
        boolean mainModelChanged = (pendingMainModelIndex != null);
        boolean nameModelChanged = (pendingNameModelIndex != null);

        if (mainModelChanged || nameModelChanged) {
            lastMainModel = modelComboBox.getSelectedItem();
            lastNameModel = nameModel.getSelectedItem();
            pendingMainModelIndex = null;
            pendingNameModelIndex = null;
        }
    }

    /**
     * Discards pending model selection changes without altering the UI state.
     */
    public void cancelModelChanges() {
        pendingMainModelIndex = null;
        pendingNameModelIndex = null;
    }


    /**
     * Toggles visibility of manual/AI/Aider settings panels based on current model selections
     * and synchronizes the available options in the name model dropdown.
     */
    private void updatePanelVisibilities() {
        boolean isMainModelAider = (modelComboBox.getSelectedIndex() == 2);
        boolean isNameModelAider = (nameModel.getSelectedIndex() == 2);
        boolean isMainModelCopilot = (modelComboBox.getSelectedIndex() == 3);

        manualHeuristicsPanel.setVisible(modelComboBox.getSelectedIndex() == 1);
        aiSettingsPanel.setVisible(modelComboBox.getSelectedIndex() == 0);

        // Show Aider settings if either model selection is Aider, but hide if main model is Copilot
        boolean showAiderSettings = (isMainModelAider || isNameModelAider) && !isMainModelCopilot;
        aiderSettingsPanel.setVisible(showAiderSettings);
        aiderSettingsPanel.revalidate();
        aiderSettingsPanel.repaint();
        aiderSettingsPanel.setMinimumSize(new Dimension(200, 100));
        // Keep Aider help label visibility in sync with the Aider settings panel.
        if (aiderHelpLabel != null) {
            aiderHelpLabel.setVisible(showAiderSettings);
            aiderHelpLabel.revalidate();
            aiderHelpLabel.repaint();
        }

        // Show file-selection scope controls only for Aider or Copilot as main model
        boolean showFileSelection = isMainModelAider || isMainModelCopilot;
        if (fileSelectionPanel != null) {
            fileSelectionPanel.setVisible(showFileSelection);
            fileSelectionPanel.revalidate();
            fileSelectionPanel.repaint();
        }

        // Filter nameModel options based on whether main model is Aider or Copilot, preserving selection if possible
        Object currentSelection = nameModel.getSelectedItem();
        if (isMainModelAider) {
            // When Aider is selected as the main model, only allow "Aider" in name model
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"Clone"});
            nameModel.setModel(model);
            nameModel.setSelectedItem("Clone");
        }
        else if (isMainModelCopilot) {
            // When Copilot is selected as the main model, only allow "Copilot" in name model
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"Copilot"});
            nameModel.setModel(model);
            nameModel.setSelectedItem("Copilot");
        }
        else {
            // When other main models are selected, restore all options
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"code2vec", "built-in", "Clone"});
            nameModel.setModel(model);
            if (currentSelection != null && model.getIndexOf(currentSelection) != -1) {
                nameModel.setSelectedItem(currentSelection);
            } else {
                nameModel.setSelectedIndex(0); // fallback to first item if previous selection no longer valid
            }
        }

    }

    /**
     * Links a metric's enable checkbox to its slider and "required" checkbox so they enable/disable together.
     */
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

    /**
     * Returns the root settings panel for embedding into dialogs.
     */
    public JPanel getPanel() {
        return mainPanel;
    }

    /**
     * Returns the component that should receive initial focus when the settings are shown.
     */
    public JComponent getPreferredFocusedComponent() {
        return minimumMethodSelector;
    }

    /**
     * Gets the minimum number of duplicate methods required before suggestions are shown.
     */
    public int getMinimumDuplicateMethods() { return (int) minimumMethodSelector.getValue(); }

    /**
     * Sets the minimum number of duplicate methods required before suggestions are shown.
     */
    public void setMinimumDuplicateMethods(int minimumMethods) { minimumMethodSelector.setValue(minimumMethods); }

    /**
     * Gets the time buffer (in seconds) used to delay or throttle analyses.
     */
    public int getTimeBuffer() { return (int) timeBufferSelector.getValue(); }

    /**
     * Sets the time buffer (in seconds) used to delay or throttle analyses.
     */
    public void setTimeBuffer(int timeBuffer) { timeBufferSelector.setValue(timeBuffer); }

    /**
     * Returns which judgement model to use (ML, manual heuristics, or Aider).
     */
    public ProjectSettingsState.JudgementModel getJudgementModel() {
        return switch (modelComboBox.getSelectedIndex()) {
            case 0 -> ProjectSettingsState.JudgementModel.TENSORFLOW;
            case 1 -> ProjectSettingsState.JudgementModel.USER_SETTINGS;
            case 2 -> ProjectSettingsState.JudgementModel.AIDER;
            case 3 -> ProjectSettingsState.JudgementModel.COPILOT;
            default -> throw new IllegalStateException("Unknown option selected.");
        };
    }

    /**
     * Updates the judgement model and refreshes panel visibility accordingly.
     */
    public void setJudgementModel(ProjectSettingsState.JudgementModel model) {
        isLoadingSettings = true;
        try {
            modelComboBox.setSelectedIndex(model.getIdx());
            lastMainModel = modelComboBox.getSelectedItem();
            updatePanelVisibilities();
        } finally {
            isLoadingSettings = false;
        }
    }

    /**
     * Returns the selected extraction type (e.g., Type-1 or Type-2).
     */
    public ProjectSettingsState.ExtractionType getExtractionType() {
        return switch (cloneTypeComboBox.getSelectedIndex()) {
            case 0 -> ProjectSettingsState.ExtractionType.TYPE_ONE;
            case 1 -> ProjectSettingsState.ExtractionType.TYPE_TWO;
            default -> throw new IllegalStateException("Unknown option selected.");
        };
    }

    /**
     * Sets the extraction type in the UI.
     */
    public void setExtractionType(ProjectSettingsState.ExtractionType cloneType) { cloneTypeComboBox.setSelectedIndex(cloneType.getIdx()); }

    /**
     * Gets the maximum allowed number of parameters for extracted methods.
     */
    public int getMaxParams() {
        return (int) maxParamsSpinner.getValue();
    }

    /**
     * Sets the maximum allowed number of parameters for extracted methods.
     */
    public void setMaxParams(int maxParams) {
        maxParamsSpinner.setValue(maxParams);
    }

    /**
     * Gets the model sensitivity slider value (0.0–1.0).
     */
    public float getModelSensitivity() {
        return ((float)modelSensitivitySlider.getValue()) / 100.0f;
    }

    /**
     * Sets the model sensitivity slider value (0.0–1.0).
     */
    public void setModelSensitivity(float sensitivity) {
        modelSensitivitySlider.setValue((int)(sensitivity * 100));
    }

    /**
     * Gets the keywords sensitivity value from the slider.
     */
    public int getKeywordsSensitivity() {
        return keywordsSlider.getValue();
    }

    /**
     * Sets the keywords sensitivity slider value.
     */
    public void setKeywordsSensitivity(int sensitivity) {
        keywordsSlider.setValue(sensitivity);
    }

    /**
     * Returns whether the keywords heuristic is enabled.
     */
    public boolean getKeywordsEnabled() {
        return keywordsEnabledCheckBox.isSelected();
    }

    /**
     * Enables or disables the keywords heuristic and its controls.
     */
    public void setKeywordsEnabled(boolean enabled) {
        keywordsEnabledCheckBox.setSelected(enabled);
    }

    /**
     * Returns whether keywords are required for a positive judgement.
     */
    public boolean getKeywordsRequired() {
        return keywordsRequiredCheckBox.isSelected();
    }

    /**
     * Sets whether keywords are required for a positive judgement.
     */
    public void setKeywordsRequired(boolean required) {
        keywordsRequiredCheckBox.setSelected(required);
    }

    /**
     * Gets the coupling sensitivity slider value.
     */
    public int getCouplingSensitivity() {
        return couplingSlider.getValue();
    }

    /**
     * Sets the coupling sensitivity slider value.
     */
    public void setCouplingSensitivity(int sensitivity) {
        couplingSlider.setValue(sensitivity);
    }

    /**
     * Returns whether the coupling heuristic is enabled.
     */
    public boolean getCouplingEnabled() {
        return couplingEnabledCheckBox.isSelected();
    }

    /**
     * Enables or disables the coupling heuristic and its controls.
     */
    public void setCouplingEnabled(boolean enabled) {
        couplingEnabledCheckBox.setSelected(enabled);
    }

    /**
     * Returns whether coupling is required for a positive judgement.
     */
    public boolean getCouplingRequired() {
        return couplingRequiredCheckBox.isSelected();
    }

    /**
     * Sets whether coupling is required for a positive judgement.
     */
    public void setCouplingRequired(boolean required) {
        couplingRequiredCheckBox.setSelected(required);
    }

    /**
     * Gets the size sensitivity slider value.
     */
    public int getSizeSensitivity() {
        return sizeSlider.getValue();
    }

    /**
     * Sets the size sensitivity slider value.
     */
    public void setSizeSensitivity(int sensitivity) {
        sizeSlider.setValue(sensitivity);
    }

    /**
     * Returns whether the size heuristic is enabled.
     */
    public boolean getSizeEnabled() {
        return sizeEnabledCheckBox.isSelected();
    }

    /**
     * Enables or disables the size heuristic and its controls.
     */
    public void setSizeEnabled(boolean enabled) {
        sizeEnabledCheckBox.setSelected(enabled);
    }

    /**
     * Returns whether size is required for a positive judgement.
     */
    public boolean getSizeRequired() {
        return sizeRequiredCheckBox.isSelected();
    }

    /**
     * Sets whether size is required for a positive judgement.
     */
    public void setSizeRequired(boolean required) {
        sizeRequiredCheckBox.setSelected(required);
    }

    /**
     * Gets the complexity sensitivity slider value.
     */
    public int getComplexitySensitivity() {
        return complexitySlider.getValue();
    }

    /**
     * Sets the complexity sensitivity slider value.
     */
    public void setComplexitySensitivity(int sensitivity) {
        complexitySlider.setValue(sensitivity);
    }

    /**
     * Returns whether the complexity heuristic is enabled.
     */
    public boolean getComplexityEnabled() {
        return complexityEnabledCheckBox.isSelected();
    }

    /**
     * Enables or disables the complexity heuristic and its controls.
     */
    public void setComplexityEnabled(boolean enabled) {
        complexityEnabledCheckBox.setSelected(enabled);
    }

    /**
     * Returns whether complexity is required for a positive judgement.
     */
    public boolean getComplexityRequired() {
        return complexityRequiredCheckBox.isSelected();
    }

    /**
     * Returns the Aider API key from the password field.
     */
    public String getAiderApiKey() {
        return new String(aiderApiKey.getPassword());
    }

    /**
     * Returns the selected Aider model name.
     */
    public String getSelectedAiderModel() {
        return (String) aidermodelComboBox.getSelectedItem();
    }

    /**
     * Sets the Aider API key and scrolls the field to the start.
     */
    public void setAiderApiKey(String apiKey) {
        aiderApiKey.setText(apiKey);
        scrollApiKeyToStart();
    }

    /**
     * Selects the Aider model in the combo box.
     */
    public void setSelectedAiderModel(String model) {
        aidermodelComboBox.setSelectedItem(model);
    }

    /**
     * Returns the selected LLM provider.
     */
    public String getLlmProvider() {
        return (String) llmProviderComboBox.getSelectedItem();
    }

    /**
     * Selects the LLM provider in the combo box.
     */
    public void setLlmProvider(String provider) {
        llmProviderComboBox.setSelectedItem(provider);
    }

    /**
     * Returns the Azure/OpenAI API base URL text.
     */
    public String getApiBase() {return apiBase.getText(); }
    public void setOllamaModel(String model) {
        ollamaModel.setText(model);
    }

    public String getOllamaModel() {
        return ollamaModel.getText();
    }

    /**
     * Sets the Azure/OpenAI API base URL text.
     */
    public void setApiBase(String base) { apiBase.setText(base); }

    /**
     * Returns the Azure API version text.
     */
    public String getApiVersion() {return apiVersion.getText(); }

    /**
     * Sets the Azure API version text.
     */
    public void setApiVersion(String version) { apiVersion.setText(version); }

    /**
     * Returns the list backing the dynamically generated file checkboxes.
     */
    public ArrayList<JCheckBox> getAllFilesCheckboxes() {
        return allFilesCheckboxes;
    }

    /**
     * Replaces the tracked file checkboxes list with the given items.
     */
    public void setAllFilesCheckboxes(ArrayList<JCheckBox> filesCheckboxes) {
        allFilesCheckboxes.clear();
        allFilesCheckboxes.addAll(filesCheckboxes);
    }

    /**
     * Returns the label of the currently selected analysis scope radio button.
     */
    public String getSelectedAnalysisButton() {
        String selectedButton = "";
        Enumeration<AbstractButton> analysisButtons = analysisSelectionButtonGroup.getElements();
        while (analysisButtons.hasMoreElements()) {
            AbstractButton currButton = analysisButtons.nextElement();
            if (currButton.isSelected()) {
                selectedButton = currButton.getText();
                break;
            }
        }
        return selectedButton;
    }

    /**
     * Selects an analysis scope radio button by its display text and updates visibility accordingly.
     */
    public void setSelectedAnalysisButton(String analysisButtonText) {
        switch (analysisButtonText) {
            case "Current File":
                currentFileButton.setSelected(true);
                break;
            case "All Files in Current Directory":
                allFilesButton.setSelected(true);
                break;
            case "Multiple Files":
                multipleFilesButton.setSelected(true);
                multFilesPanel.setVisible(true);
                break;
        }
    }

    /**
     * Sets whether complexity is required for a positive judgement.
     */
    public void setComplexityRequired(boolean required) {
        complexityRequiredCheckBox.setSelected(required);
    }
    /**
     * Sets the name-model selection by index while suppressing change side-effects during load.
     */
    public void setNameModel(int selectedIndex) {
        isLoadingSettings = true;
        try {
            nameModel.setSelectedIndex(selectedIndex);
            lastNameModel = nameModel.getSelectedItem();
        } finally {
            isLoadingSettings = false;
        }
    }
    /**
     * Returns the index of the selected name-model option.
     */
    public int getNameModel() { return (nameModel.getSelectedIndex()); }
    /**
     * Returns the number of predicted names to request for suggestions.
     */
    public int getNumOfPreds() {
        return numOfPred.getValue();
    }

    /**
     * Sets the number of predicted names to request for suggestions.
     */
    public void setNumOfPreds(int preds) {
        numOfPred.setValue(preds);
    }

    /**
     * Returns the directory path used for multi-file analysis.
     */
    public String getFilesPath() {
        return filesPath.getText();
    }

    /**
     * Sets the directory path used for multi-file analysis.
     */
    public void setFilesPath(String path) {
        filesPath.setText(path);
    }

    /**
     * Gets the maximum number of refactoring attempts from the iteration slider.
     */
    public int getMaxAttempts() {
        return iterationSlider.getValue();
    }

    /**
     * Sets the maximum number of refactoring attempts on the iteration slider.
     */
    public void setMaxAttempts(int maxAttempts) {
        iterationSlider.setValue(maxAttempts);
    }

    /**
     * Initializes custom UI components, icons, and clickable help links.
     */
    private void createUIComponents() {
        // Set link and icons for help features
        helpLabel = new JLabel();
        createLinkListener(helpLabel, "https://se4airesearch.github.io/AntiCopyPaster-Website/");
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
        aiderHelpLabel = new JLabel();
        createLinkListener(aiderHelpLabel, "https://github.com/refactorings/anti-copy-paster");
        aiderHelpLabel.setIcon(AllIcons.Ide.External_link_arrow);
        apiBaseHelp = new JLabel();
        apiBaseHelp.setIcon(AllIcons.General.ContextHelp);
        iterationHelp = new JLabel();
        iterationHelp.setIcon(AllIcons.General.ContextHelp);
    }

    /**
     * Makes a label or component behave like a link that opens the given URL in the system browser.
     */
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

    /**
     * No-op used to trigger IntelliJ's modified-state tracking when fields change.
     */
    private void notifySettingsChanged() {
        // This method exists solely to trigger IntelliJ's internal modified state tracking
    }
    /**
     * Warns the user if the entered API key's prefix does not match the selected provider.
     */
    void validateApiKeyPrefix() {
        if (!apiKeyPanel.isVisible()) return;

        String apiKey = new String(aiderApiKey.getPassword()).trim();
        String provider = (String) llmProviderComboBox.getSelectedItem();
        boolean mismatch = false;

        if (provider != null && !apiKey.isEmpty()) {
            switch (provider) {
                case "OpenAI":
                    mismatch = !apiKey.startsWith("sk-proj-");
                    break;
                case "Gemini":
                    mismatch = !apiKey.startsWith("AIzaSy");
                    break;
                case "DeepSeek":
                    mismatch = !apiKey.startsWith("sk-");
                    break;
                case "Anthropic":
                    mismatch = !apiKey.startsWith("sk-ant-");
                    break;
            }
        }

        if (mismatch) {
            JOptionPane.showMessageDialog(
                    mainPanel,
                    "The API key prefix does not match the selected provider.\nPlease verify your key.",
                    "API Key Provider Mismatch",
                    JOptionPane.WARNING_MESSAGE
            );
        }
    }

    /**
     * Shows or hides Azure-specific fields based on the selected provider.
     */
    private void updateProviderSpecificPanels(String provider) {
        boolean isAzure = provider != null && "Azure".equalsIgnoreCase(provider);
        boolean isOllama = provider != null && "Ollama".equalsIgnoreCase(provider);

        // Hide API key column when using Ollama (no API key needed)
        if (apiKeyPanel != null) {
            apiKeyPanel.setVisible(!isOllama);
        }

        // Azure-specific fields only for Azure
        azureApiVersion.setVisible(isAzure);
        azureApiBase.setVisible(isAzure || isOllama);

        if (ollamaModelPanel != null) {
            ollamaModelPanel.setVisible(isOllama);
        }
        if (modelPanel != null) {
            modelPanel.setVisible(!isOllama);
        }

        // Refresh layouts so UI updates immediately
        if (apiKeyPanel != null) { apiKeyPanel.revalidate(); apiKeyPanel.repaint(); }
        if (mainPanel != null) { mainPanel.revalidate(); mainPanel.repaint(); }

        updateOllamaWarnings();
    }

    /**
     * Scrolls the API key field to the beginning so the prefix is visible.
     */
    private void scrollApiKeyToStart() {
        SwingUtilities.invokeLater(() -> {
            aiderApiKey.setCaretPosition(0);
            try {
                aiderApiKey.setScrollOffset(0);
            } catch (Exception ignored) {
                // setScrollOffset may behave differently across LAFs; caret is enough
            }
        });
    }

    private void updateOllamaWarnings() {
        boolean isOllama = (llmProviderComboBox.getSelectedItem() != null)
                && "Ollama".equalsIgnoreCase((String) llmProviderComboBox.getSelectedItem());

        if (apiBaseWarningLabel != null) {
            boolean showApiBase = isOllama && (apiBase.getText() == null || apiBase.getText().trim().isEmpty());
            apiBaseWarningLabel.setVisible(showApiBase);
        }
        if (ollamaModelWarningLabel != null) {
            boolean showModel = isOllama && (ollamaModel.getText() == null || ollamaModel.getText().trim().isEmpty());
            ollamaModelWarningLabel.setVisible(showModel);
        }

        if (azureApiBase != null) { azureApiBase.revalidate(); azureApiBase.repaint(); }
        if (ollamaModelPanel != null) { ollamaModelPanel.revalidate(); ollamaModelPanel.repaint(); }
    }
}