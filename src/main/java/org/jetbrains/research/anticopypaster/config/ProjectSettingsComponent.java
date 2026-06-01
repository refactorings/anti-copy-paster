package org.jetbrains.research.anticopypaster.config;

import javax.swing.*;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;

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
    private JPanel multiAgentSettingsPanel;

    private JPanel aiderSettingsPanel;

    // Original (Multi-agent panel) widget references (preserved before we alias to duplicated Aider widgets)
    private JComboBox multiAgentLlmProviderComboBox;
    private JComboBox multiAgentAidermodelComboBox;
    private JPasswordField multiAgentApiKey;

    private JPanel multiAgentProviderPanel;
    private JPanel multiAgentApiKeyPanel;
    private JPanel multiAgentModelPanel;

    private JPanel multiAgentAzureApiVersion;
    private JTextField multiAgentApiVersion;

    private JPanel multiAgentAzureApiBase;
    private JTextField multiAgentApiBase;
    private JLabel multiAgentApiBaseHelp;

    private JPanel multiAgentOllamaModelPanel;
    private JTextField multiAgentOllamaModel;

    private JSlider multiAgentIterationSlider;
    private JPanel multiAgentIterationNumberPanel;
    private JLabel multiAgentIterationHelp;

    // Aider panel bindings (copied from multiAgentSettingsPanel with `aider*` prefixes)
    private JComboBox aiderLlmProviderComboBox;
    private JComboBox aiderAidermodelComboBox;
    private JPasswordField aiderAiderApiKey;

    private JPanel aiderProviderPanel;
    private JPanel aiderApiKeyPanel;
    private JPanel aiderModelPanel;

    private JPanel aiderAzureApiVersion;
    private JTextField aiderApiVersion;

    private JPanel aiderAzureApiBase;
    private JTextField aiderApiBase;
    private JLabel aiderApiBaseHelp;

    private JPanel aiderOllamaModelPanel;
    private JTextField aiderOllamaModel;

    private JSlider aiderIterationSlider;
    private JPanel aiderIterationNumberPanel;
    private JLabel aiderIterationHelp;
    private JSpinner timeBufferSelector;
    private JSpinner minimumMethodSelector;
    private JSpinner maxParamsSpinner;
    private JPasswordField agentApiKey;
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
    private JTextField textField1;
    private JLabel aiderPath;
    private JPanel numberMethodPanel;
    private JPanel waitSecondPanel;
    private JPanel cloneTypePanel;
    private JPanel nameModelPanel;
    private JPanel nameNumberPanel;
    private JPanel parameterNamePanel;
    private JScrollPane scrollpanel;
    private JScrollPane rootSettingsScrollPane;
    private ArrayList<JCheckBox> allFilesCheckboxes;
    private final Project projectRef;
    private Integer pendingMainModelIndex = null;
    private Integer pendingNameModelIndex = null;

    private static final Logger LOG = Logger.getInstance(ProjectSettingsComponent.class);
    static final String GOOGLE_PROVIDER = "Google";
    private static final String LEGACY_GEMINI_PROVIDER = "Gemini";

    static String normalizeLlmProviderName(String provider) {
        if (provider == null) {
            return null;
        }
        String trimmed = provider.trim();
        if (GOOGLE_PROVIDER.equalsIgnoreCase(trimmed) || LEGACY_GEMINI_PROVIDER.equalsIgnoreCase(trimmed)) {
            return GOOGLE_PROVIDER;
        }
        return trimmed;
    }

    private static final class ViewportWidthPanel extends JPanel implements Scrollable {
        private ViewportWidthPanel() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 96;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // Suppress auto-close of Aider windows during initial render
    private boolean suppressAutoCloseOnInit = true;
    private boolean isLoadingSettings = false;
    private Object lastMainModel = null;
    private Object lastNameModel = null;
    private JLabel apiBaseWarningLabel;
    private JLabel ollamaModelWarningLabel;
    private JLabel apiKeyWarningLabel;
    private JLabel multiAgentApiKeyWarningLabel;
    private String lastAzureApiBase = "";
    private static final String OLLAMA_DEFAULT_API_BASE = "http://127.0.0.1:11434";
    /**
     * Builds and wires the Project Settings UI for AntiCopyPaster, including provider/model pickers,
     * Aider fields, validation, and dynamic panel visibility.
     *
     * @param project IntelliJ project used for dialogs and helper calls
     */
    // Simple warning icons like API key for Ollama-required field
    public ProjectSettingsComponent(Project project) {
        this.projectRef = project;
        configureSettingsScrollPane();
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
        llmProviderComboBox.setToolTipText("Select the Model suite, such as OpenAI, Google, Anthropic, DeepSeek or Azure.");
        aidermodelComboBox.setToolTipText("Select the specific model you want to use from the provider.");
        agentApiKey.setToolTipText("Enter your API key for the selected LLM provider.");

        // Preserve the original (multi-agent panel) widgets before we alias to the duplicated Aider panel widgets.
        multiAgentLlmProviderComboBox = llmProviderComboBox;
        multiAgentAidermodelComboBox = aidermodelComboBox;
        multiAgentApiKey = agentApiKey;

        multiAgentProviderPanel = providerPanel;
        multiAgentApiKeyPanel = apiKeyPanel;
        multiAgentModelPanel = modelPanel;

        multiAgentAzureApiVersion = azureApiVersion;
        multiAgentApiVersion = apiVersion;

        multiAgentAzureApiBase = azureApiBase;
        multiAgentApiBase = apiBase;
        multiAgentApiBaseHelp = apiBaseHelp;

        multiAgentOllamaModelPanel = ollamaModelPanel;
        multiAgentOllamaModel = ollamaModel;

        multiAgentIterationSlider = iterationSlider;
        multiAgentIterationNumberPanel = iterationNumberPanel;
        multiAgentIterationHelp = iterationHelp;

        // If the new duplicated Aider panel exists (aiderSettingsPanel + aider* bindings),
        // alias those components into the existing fields so the rest of this class continues to work.
        // This lets us keep all provider/model/api-key logic in one place.
        if (aiderSettingsPanel != null) {
            if (aiderLlmProviderComboBox != null) {
                llmProviderComboBox = aiderLlmProviderComboBox;
            }
            if (aiderAidermodelComboBox != null) {
                aidermodelComboBox = aiderAidermodelComboBox;
            }
            if (aiderAiderApiKey != null) {
                agentApiKey = aiderAiderApiKey;
            }

            if (aiderProviderPanel != null) {
                providerPanel = aiderProviderPanel;
            }
            if (aiderApiKeyPanel != null) {
                apiKeyPanel = aiderApiKeyPanel;
            }
            if (aiderModelPanel != null) {
                modelPanel = aiderModelPanel;
            }

            if (aiderAzureApiVersion != null) {
                azureApiVersion = aiderAzureApiVersion;
            }
            if (aiderApiVersion != null) {
                apiVersion = aiderApiVersion;
            }

            if (aiderAzureApiBase != null) {
                azureApiBase = aiderAzureApiBase;
            }
            if (aiderApiBase != null) {
                apiBase = aiderApiBase;
            }
            if (aiderApiBaseHelp != null) {
                apiBaseHelp = aiderApiBaseHelp;
            }

            if (aiderOllamaModelPanel != null) {
                ollamaModelPanel = aiderOllamaModelPanel;
            }
            if (aiderOllamaModel != null) {
                ollamaModel = aiderOllamaModel;
            }

            if (aiderIterationSlider != null) {
                iterationSlider = aiderIterationSlider;
            }
            if (aiderIterationNumberPanel != null) {
                iterationNumberPanel = aiderIterationNumberPanel;
            }
            if (aiderIterationHelp != null) {
                iterationHelp = aiderIterationHelp;
            }
        }
        filesPath.setToolTipText("Specify the path to the directory with the files you would like to search for clones in.");

        // Add warning icon and tooltip for empty API key
        Icon warningIcon = AllIcons.General.Error;
        apiKeyWarningLabel = new JLabel(warningIcon);
        apiKeyWarningLabel.setToolTipText("API key not found for selected provider");
        apiKeyWarningLabel.setVisible(false);

        multiAgentApiKeyWarningLabel = new JLabel(warningIcon);
        multiAgentApiKeyWarningLabel.setToolTipText("API key not found for selected provider");
        multiAgentApiKeyWarningLabel.setVisible(false);


        // Set layout and add agentApiKey and warning label with proper constraints
        apiKeyPanel.setLayout(new GridBagLayout());
        // Wrap the password field with an eye icon drawn "inside" the field at the far right
        JComponent passwordWithEyePanel = createPasswordFieldWithEye(agentApiKey);
        GridBagConstraints apiKeyGbc = new GridBagConstraints();
        apiKeyGbc.gridx = 1;
        apiKeyGbc.gridy = 0;
        apiKeyGbc.weightx = 1.0;
        apiKeyGbc.fill = GridBagConstraints.HORIZONTAL;
        apiKeyGbc.anchor = GridBagConstraints.WEST;
        apiKeyGbc.insets = new Insets(0, 0, 0, 0);
        apiKeyPanel.add(passwordWithEyePanel, apiKeyGbc);

        // Add warning icon with constraints
        GridBagConstraints warningGbc = new GridBagConstraints();
        warningGbc.gridx = 2;
        warningGbc.gridy = 0;
        warningGbc.anchor = GridBagConstraints.WEST;
        warningGbc.insets = new Insets(0, 5, 0, 0);
        apiKeyPanel.add(apiKeyWarningLabel, warningGbc);

        if (multiAgentApiKeyPanel != null && multiAgentApiKeyPanel != apiKeyPanel) {
            multiAgentApiKeyPanel.setLayout(new GridBagLayout());

            GridBagConstraints multiApiKeyGbc = new GridBagConstraints();
            multiApiKeyGbc.gridx = 1;
            multiApiKeyGbc.gridy = 0;
            multiApiKeyGbc.weightx = 1.0;
            multiApiKeyGbc.fill = GridBagConstraints.HORIZONTAL;
            multiApiKeyGbc.anchor = GridBagConstraints.WEST;
            multiApiKeyGbc.insets = new Insets(0, 0, 0, 0);
            JComponent multiPasswordWithEyePanel = createPasswordFieldWithEye(multiAgentApiKey);
            multiAgentApiKeyPanel.add(multiPasswordWithEyePanel, multiApiKeyGbc);

            GridBagConstraints multiWarningGbc = new GridBagConstraints();
            multiWarningGbc.gridx = 2;
            multiWarningGbc.gridy = 0;
            multiWarningGbc.anchor = GridBagConstraints.WEST;
            multiWarningGbc.insets = new Insets(0, 5, 0, 0);
            multiAgentApiKeyPanel.add(multiAgentApiKeyWarningLabel, multiWarningGbc);
        }

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
        agentApiKey.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }

            private void updateWarning() {
                boolean isClone = isCloneSelected();
                boolean isEmpty = new String(agentApiKey.getPassword()).trim().isEmpty();
                updateApiKeyWarningForPanel(apiKeyPanel, apiKeyWarningLabel, isClone && isEmpty);
            }
        });

        if (multiAgentApiKey != null && multiAgentApiKey != agentApiKey) {
            multiAgentApiKey.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { updateWarning(); }

                private void updateWarning() {
                    boolean isCloneMulti = isCloneMultiAgentSelected();
                    boolean isEmpty = new String(multiAgentApiKey.getPassword()).trim().isEmpty();
                    updateApiKeyWarningForPanel(multiAgentApiKeyPanel, multiAgentApiKeyWarningLabel, isCloneMulti && isEmpty);
                }
            });
        }

        // Initialize visibility based on current field state
        boolean initialIsCloneMulti = isCloneMultiAgentSelected();
        boolean initialIsClone = isCloneSelected();
        boolean initialSingleEmpty = agentApiKey == null || new String(agentApiKey.getPassword()).trim().isEmpty();
        boolean initialMultiEmpty = multiAgentApiKey == null || new String(multiAgentApiKey.getPassword()).trim().isEmpty();
        updateApiKeyWarningForPanel(apiKeyPanel, apiKeyWarningLabel, initialIsClone && initialSingleEmpty);
        updateApiKeyWarningForPanel(multiAgentApiKeyPanel, multiAgentApiKeyWarningLabel, initialIsCloneMulti && initialMultiEmpty);
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
            public void insertUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { onChange(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onChange(); }

            private void onChange() {
                updateOllamaWarnings();
                Object providerObj = llmProviderComboBox.getSelectedItem();
                if (providerObj != null && "Azure".equalsIgnoreCase(providerObj.toString())) {
                    String text = apiBase.getText();
                    if (text != null) {
                        lastAzureApiBase = text.trim();
                    }
                }
            }
        });

        ollamaModel.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateOllamaWarnings(); }
        });
        // Ensure initial provider/model state is applied
        String initProvider = (String) llmProviderComboBox.getSelectedItem();
        setModelOptionsForProvider(initProvider, aidermodelComboBox);
        updateProviderSpecificPanels(initProvider);

        if ("Ollama".equalsIgnoreCase(initProvider)) {
            apiBase.setText(OLLAMA_DEFAULT_API_BASE);
        } else if ("Azure".equalsIgnoreCase(initProvider) && lastAzureApiBase != null && !lastAzureApiBase.isBlank()) {
            apiBase.setText(lastAzureApiBase);
        }

        // Initialize provider and model dropdowns if empty
        if (llmProviderComboBox.getSelectedItem() == null) {
            llmProviderComboBox.setSelectedItem("OpenAI");
        }

        // Watch for changes in the model selection combo box
        aidermodelComboBox.addActionListener(e -> notifySettingsChanged());

        // Unified provider/model wiring for BOTH Clone (Aider panel alias) and Clone_multiagent (original panel)
        wireProviderAndModel(llmProviderComboBox, aidermodelComboBox, apiKeyPanel, modelPanel, azureApiVersion, azureApiBase, ollamaModelPanel);

        if (multiAgentLlmProviderComboBox != null && multiAgentLlmProviderComboBox != llmProviderComboBox) {
            wireProviderAndModel(
                    multiAgentLlmProviderComboBox,
                    multiAgentAidermodelComboBox,
                    multiAgentApiKeyPanel,
                    multiAgentModelPanel,
                    multiAgentAzureApiVersion,
                    multiAgentAzureApiBase,
                    multiAgentOllamaModelPanel
            );
        }

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

    private void configureSettingsScrollPane() {
        if (scrollpanel == null) {
            return;
        }

        scrollpanel.setEnabled(true);
        scrollpanel.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollpanel.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollpanel.setWheelScrollingEnabled(true);
        scrollpanel.setPreferredSize(new Dimension(1100, 900));
        scrollpanel.setMinimumSize(new Dimension(760, 640));

        if (mainPanel != null) {
            mainPanel.setAutoscrolls(true);
            mainPanel.setPreferredSize(null);
        }
    }


    /**
     * Toggles visibility of manual/AI/Aider settings panels based on current model selections
     * and synchronizes the available options in the name model dropdown.
     */
    private void updatePanelVisibilities() {
        String mainModel = (String) modelComboBox.getSelectedItem();
        String nameModelValue = (String) nameModel.getSelectedItem();

        boolean isMainClone = "Clone".equals(mainModel);
        boolean isMainCloneMulti = "Clone_multiagent".equals(mainModel);
        boolean isMainCopilot = "Copilot".equals(mainModel);

        boolean isNameClone = "Clone".equals(nameModelValue);
        boolean isNameCloneMulti = "Clone_multiagent".equals(nameModelValue);

        boolean isMainManual = "my manual heuristics".equals(mainModel);
        boolean isMainAiModel = "the AI model".equals(mainModel);

        manualHeuristicsPanel.setVisible(isMainManual);
        aiSettingsPanel.setVisible(isMainAiModel);

        // Show Aider (single-agent) clone settings when either selection is "Clone",
        // but hide if the main model is Copilot.
        boolean showAiderSettings = (isMainClone || isNameClone) && !isMainCopilot;

        // Show multi-agent clone settings when either selection is "Clone_multiagent",
        // but hide if the main model is Copilot.
        boolean showMultiAgentSettings = (isMainCloneMulti || isNameCloneMulti) && !isMainCopilot;

        if (aiderSettingsPanel != null) {
            aiderSettingsPanel.setVisible(showAiderSettings);
            aiderSettingsPanel.revalidate();
            aiderSettingsPanel.repaint();
            aiderSettingsPanel.setMinimumSize(new Dimension(200, 100));
        }

        if (multiAgentSettingsPanel != null) {
            multiAgentSettingsPanel.setVisible(showMultiAgentSettings);
            multiAgentSettingsPanel.revalidate();
            multiAgentSettingsPanel.repaint();
            if (showMultiAgentSettings) {
                multiAgentSettingsPanel.setMinimumSize(new Dimension(200, 100));
            }
        }

        // Keep Aider help label visibility in sync with the Clone (Aider) panel.
        if (aiderHelpLabel != null) {
            aiderHelpLabel.setVisible(showAiderSettings);
            aiderHelpLabel.revalidate();
            aiderHelpLabel.repaint();
        }

        // Ensure provider-specific rows are correct for the currently visible clone settings panel
        if (showAiderSettings && aiderLlmProviderComboBox != null) {
            String provider = (String) aiderLlmProviderComboBox.getSelectedItem();
            updateProviderSpecificPanelsFor(provider, apiKeyPanel, modelPanel, azureApiVersion, azureApiBase, ollamaModelPanel);
        }
        if (showMultiAgentSettings && multiAgentLlmProviderComboBox != null) {
            String provider = (String) multiAgentLlmProviderComboBox.getSelectedItem();
            updateProviderSpecificPanelsFor(provider, multiAgentApiKeyPanel, multiAgentModelPanel, multiAgentAzureApiVersion, multiAgentAzureApiBase, multiAgentOllamaModelPanel);
        }

        // Show file-selection scope controls for Clone/Clone_multiagent/Copilot as main model
        boolean showFileSelection = isMainClone || isMainCloneMulti || isMainCopilot;
        if (fileSelectionPanel != null) {
            fileSelectionPanel.setVisible(showFileSelection);
            fileSelectionPanel.revalidate();
            fileSelectionPanel.repaint();
        }

        boolean showSingleApiKeyWarning = showAiderSettings
                && (agentApiKey == null || new String(agentApiKey.getPassword()).trim().isEmpty());
        boolean showMultiApiKeyWarning = showMultiAgentSettings
                && (multiAgentApiKey == null || new String(multiAgentApiKey.getPassword()).trim().isEmpty());
        updateApiKeyWarningForPanel(apiKeyPanel, apiKeyWarningLabel, showSingleApiKeyWarning);
        updateApiKeyWarningForPanel(multiAgentApiKeyPanel, multiAgentApiKeyWarningLabel, showMultiApiKeyWarning);

        // Hide method/name/clone configuration panels for Clone and Clone_multiagent in the main model selection.
        boolean hideClonePanelsForClone = isMainClone;
        boolean hideClonePanelsForCloneMulti = isMainCloneMulti;
        boolean hideClonePanels = hideClonePanelsForClone || hideClonePanelsForCloneMulti;

        if (numberMethodPanel != null) {
            numberMethodPanel.setVisible(!hideClonePanelsForClone);
            numberMethodPanel.revalidate();
            numberMethodPanel.repaint();
        }

        if (cloneTypePanel != null) {
            cloneTypePanel.setVisible(!hideClonePanels);
            cloneTypePanel.revalidate();
            cloneTypePanel.repaint();
        }

        if (nameModelPanel != null) {
            nameModelPanel.setVisible(!hideClonePanels);
            nameModelPanel.revalidate();
            nameModelPanel.repaint();
        }

        if (parameterNamePanel != null) {
            parameterNamePanel.setVisible(!hideClonePanels);
            parameterNamePanel.revalidate();
            parameterNamePanel.repaint();
        }

        if (nameNumberPanel != null) {
            nameNumberPanel.setVisible(!hideClonePanels);
            nameNumberPanel.revalidate();
            nameNumberPanel.repaint();
        }

        if (iterationNumberPanel != null) {
            iterationNumberPanel.setVisible(isMainCloneMulti);
            iterationNumberPanel.revalidate();
            iterationNumberPanel.repaint();
        }

        // Filter nameModel options based on whether main model is Clone, Clone_multiagent, or Copilot, preserving selection if possible
        Object currentSelection = nameModel.getSelectedItem();
        if (isMainClone) {
            // When Clone is selected as the main model, only allow "Clone" in name model
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"Clone"});
            nameModel.setModel(model);
            nameModel.setSelectedItem("Clone");
        }
        else if (isMainCloneMulti) {
            // When Clone_multiagent is selected as the main model, only allow "Clone_multiagent" in name model
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"Clone_multiagent"});
            nameModel.setModel(model);
            nameModel.setSelectedItem("Clone_multiagent");
        }
        else if (isMainCopilot) {
            // When Copilot is selected as the main model, only allow "Copilot" in name model
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"Copilot"});
            nameModel.setModel(model);
            nameModel.setSelectedItem("Copilot");
        }
        else {
            // When other main models are selected, restore all options
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[] {"code2vec", "built-in", "Clone", "Clone_multiagent", "Copilot"});
            nameModel.setModel(model);
            if (currentSelection != null && model.getIndexOf(currentSelection) != -1) {
                nameModel.setSelectedItem(currentSelection);
            } else {
                nameModel.setSelectedIndex(0);
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
    public JComponent getPanel() {
        if (mainPanel == null) {
            return scrollpanel;
        }
        if (rootSettingsScrollPane == null) {
            ViewportWidthPanel viewportWidthPanel = new ViewportWidthPanel();
            viewportWidthPanel.add(mainPanel, BorderLayout.NORTH);

            rootSettingsScrollPane = new JBScrollPane(
                    viewportWidthPanel,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            );
            rootSettingsScrollPane.setWheelScrollingEnabled(true);
            rootSettingsScrollPane.getVerticalScrollBar().setUnitIncrement(16);
            rootSettingsScrollPane.getVerticalScrollBar().setBlockIncrement(96);
            rootSettingsScrollPane.setBorder(BorderFactory.createEmptyBorder());
            rootSettingsScrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
            rootSettingsScrollPane.setPreferredSize(new Dimension(1100, 900));
            rootSettingsScrollPane.setMinimumSize(new Dimension(760, 640));
        }
        return rootSettingsScrollPane;
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
        String mainModel = (String) modelComboBox.getSelectedItem();
        if ("the AI model".equals(mainModel)) return ProjectSettingsState.JudgementModel.TENSORFLOW;
        if ("my manual heuristics".equals(mainModel)) return ProjectSettingsState.JudgementModel.USER_SETTINGS;
        if ("Clone".equals(mainModel)) return ProjectSettingsState.JudgementModel.AIDER;
        if ("Clone_multiagent".equals(mainModel)) return ProjectSettingsState.JudgementModel.AIDER; // treat as AIDER for now
        if ("Copilot".equals(mainModel)) return ProjectSettingsState.JudgementModel.COPILOT;
        throw new IllegalStateException("Unknown option selected: " + mainModel);
    }

    /**
     * Returns which clone pipeline mode is selected based on the main model combo-box.
     */
    public ProjectSettingsState.CloneMode getCloneMode() {
        String mainModel = (String) modelComboBox.getSelectedItem();
        if ("Clone_multiagent".equals(mainModel)) {
            return ProjectSettingsState.CloneMode.MULTI_AGENT;
        }
        // Default and "Clone"
        return ProjectSettingsState.CloneMode.SINGLE_AGENT;
    }

    /**
     * Sets the clone pipeline mode by updating the main model combo-box.
     */
    public void setCloneMode(ProjectSettingsState.CloneMode mode) {
        isLoadingSettings = true;
        try {
            if (mode == ProjectSettingsState.CloneMode.MULTI_AGENT) {
                modelComboBox.setSelectedItem("Clone_multiagent");
            } else {
                modelComboBox.setSelectedItem("Clone");
            }
            lastMainModel = modelComboBox.getSelectedItem();
            updatePanelVisibilities();
        } finally {
            isLoadingSettings = false;
        }
    }

    /**
     * Updates the judgement model and refreshes panel visibility accordingly.
     */
    public void setJudgementModel(ProjectSettingsState.JudgementModel model) {
        isLoadingSettings = true;
        try {
            switch (model) {
                case TENSORFLOW -> modelComboBox.setSelectedItem("the AI model");
                case USER_SETTINGS -> modelComboBox.setSelectedItem("my manual heuristics");
                case AIDER -> modelComboBox.setSelectedItem("Clone");
                case COPILOT -> modelComboBox.setSelectedItem("Copilot");
                default -> modelComboBox.setSelectedItem("the AI model");
            }
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


    private boolean isCloneMultiAgentSelected() {
        Object mainSel = modelComboBox != null ? modelComboBox.getSelectedItem() : null;
        Object nameSel = nameModel != null ? nameModel.getSelectedItem() : null;
        return "Clone_multiagent".equals(mainSel) || "Clone_multiagent".equals(nameSel);
    }

    private boolean isCloneSelected() {
        Object mainSel = modelComboBox != null ? modelComboBox.getSelectedItem() : null;
        Object nameSel = nameModel != null ? nameModel.getSelectedItem() : null;
        return "Clone".equals(mainSel) || "Clone".equals(nameSel);
    }

    /**
     * Returns the API key from the password field.
     */
    public String getAiderApiKey() {
        if (isCloneMultiAgentSelected() && multiAgentApiKey != null) {
            return new String(multiAgentApiKey.getPassword());
        }
        return new String(agentApiKey.getPassword());
    }

    /**
     * Returns the selected Aider model name.
     */
    public String getSelectedAiderModel() {
        if (isCloneMultiAgentSelected() && multiAgentAidermodelComboBox != null) {
            return (String) multiAgentAidermodelComboBox.getSelectedItem();
        }
        return (String) aidermodelComboBox.getSelectedItem();
    }

    /**
     * Sets the Aider API key and scrolls the field to the start.
     */
    public void setAiderApiKey(String apiKey) {
        if (isCloneMultiAgentSelected() && multiAgentApiKey != null) {
            multiAgentApiKey.setText(apiKey);
            SwingUtilities.invokeLater(() -> {
                try {
                    multiAgentApiKey.setCaretPosition(0);
                } catch (Exception ignored) {
                }
            });
            return;
        }
        agentApiKey.setText(apiKey);
    }

    /**
     * Selects the Aider model in the combo box.
     */
    public void setSelectedAiderModel(String model) {
        if (isCloneMultiAgentSelected() && multiAgentAidermodelComboBox != null) {
            multiAgentAidermodelComboBox.setSelectedItem(model);
            return;
        }
        aidermodelComboBox.setSelectedItem(model);
    }

    /**
     * Returns the selected LLM provider.
     */
    public String getLlmProvider() {
        if (isCloneMultiAgentSelected() && multiAgentLlmProviderComboBox != null) {
            return normalizeLlmProviderName((String) multiAgentLlmProviderComboBox.getSelectedItem());
        }
        return normalizeLlmProviderName((String) llmProviderComboBox.getSelectedItem());
    }

    /**
     * Selects the LLM provider in the combo box.
     */
    public void setLlmProvider(String provider) {
        String normalizedProvider = normalizeLlmProviderName(provider);
        if (isCloneMultiAgentSelected() && multiAgentLlmProviderComboBox != null) {
            multiAgentLlmProviderComboBox.setSelectedItem(normalizedProvider);
            return;
        }
        llmProviderComboBox.setSelectedItem(normalizedProvider);
    }

    /**
     * Returns the Azure/OpenAI API base URL text.
     */
    public String getApiBase() {
        if (isCloneMultiAgentSelected() && multiAgentApiBase != null) {
            return multiAgentApiBase.getText();
        }
        return apiBase.getText();
    }

    public void setOllamaModel(String model) {
        if (isCloneMultiAgentSelected() && multiAgentOllamaModel != null) {
            multiAgentOllamaModel.setText(model);
            return;
        }
        ollamaModel.setText(model);
    }

    public String getOllamaModel() {
        if (isCloneMultiAgentSelected() && multiAgentOllamaModel != null) {
            return multiAgentOllamaModel.getText();
        }
        return ollamaModel.getText();
    }

    /**
     * Sets the Azure/OpenAI API base URL text.
     */
    public void setApiBase(String base) {
        if (isCloneMultiAgentSelected() && multiAgentApiBase != null) {
            multiAgentApiBase.setText(base);
            String provider = multiAgentLlmProviderComboBox == null ? null : (String) multiAgentLlmProviderComboBox.getSelectedItem();
            if ("Azure".equalsIgnoreCase(provider) && base != null && !base.isBlank()) {
                lastAzureApiBase = base.trim();
            }
            return;
        }
        apiBase.setText(base);
        String provider = llmProviderComboBox == null ? null : (String) llmProviderComboBox.getSelectedItem();
        if ("Azure".equalsIgnoreCase(provider) && base != null && !base.isBlank()) {
            lastAzureApiBase = base.trim();
        }
    }

    /**
     * Returns the Azure API version text.
     */
    public String getApiVersion() {
        if (isCloneMultiAgentSelected() && multiAgentApiVersion != null) {
            return multiAgentApiVersion.getText();
        }
        return apiVersion.getText();
    }

    /**
     * Sets the Azure API version text.
     * Clears placeholder state so saved values are rendered as normal text.
     */
    public void setApiVersion(String version) {
        if (isCloneMultiAgentSelected() && multiAgentApiVersion != null) {
            multiAgentApiVersion.setText(version);
            return;
        }
        apiVersion.setText(version);
    }

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
     * Sets the name-model selection by value while suppressing change side-effects during load.
     */
    public void setNameModel(String selectedValue) {
        isLoadingSettings = true;
        try {
            if (selectedValue == null || selectedValue.isBlank()) {
                selectedValue = "code2vec";
            }

            ComboBoxModel<String> model = nameModel.getModel();
            boolean found = false;
            for (int i = 0; i < model.getSize(); i++) {
                String value = model.getElementAt(i);
                if (selectedValue.equals(value)) {
                    found = true;
                    break;
                }
            }

            if (found) {
                nameModel.setSelectedItem(selectedValue);
            } else {
                nameModel.setSelectedItem("code2vec");
            }

            lastNameModel = nameModel.getSelectedItem();
            pendingNameModelIndex = nameModel.getSelectedIndex();
            updatePanelVisibilities();
        } finally {
            isLoadingSettings = false;
        }
    }
    /**
     * Returns the selected name-model option.
     */
    public String getNameModel() {
        Object selected = nameModel.getSelectedItem();
        return selected == null ? "code2vec" : selected.toString();
    }
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
     * Returns the configured Aider executable path.
     */
    public String getAiderPath() {
        return textField1.getText();
    }

    /**
     * Sets the configured Aider executable path.
     */
    public void setAiderPath(String path) {
        textField1.setText(path == null ? "" : path);
    }

    /**
     * Gets the maximum number of refactoring attempts from the iteration slider.
     */
    public int getMaxAttempts() {
        if (isCloneMultiAgentSelected() && multiAgentIterationSlider != null) {
            return multiAgentIterationSlider.getValue();
        }
        return iterationSlider.getValue();
    }

    /**
     * Sets the maximum number of refactoring attempts on the iteration slider.
     */
    public void setMaxAttempts(int maxAttempts) {
        if (isCloneMultiAgentSelected() && multiAgentIterationSlider != null) {
            multiAgentIterationSlider.setValue(maxAttempts);
            return;
        }
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

        // Help labels for the duplicated Aider settings panel (custom-create bindings)
        aiderApiBaseHelp = new JLabel();
        aiderApiBaseHelp.setIcon(AllIcons.General.ContextHelp);
        aiderIterationHelp = new JLabel();
        aiderIterationHelp.setIcon(AllIcons.General.ContextHelp);
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
     * API key prefix validation is intentionally disabled.
     */
    void validateApiKeyPrefix() {
//        String error = getApiKeyPrefixValidationError();
//        if (error != null) {
//            JOptionPane.showMessageDialog(
//                    mainPanel,
//                    error,
//                    "API Key Provider Mismatch",
//                    JOptionPane.WARNING_MESSAGE
//            );
//        }
    }

    public String getApiKeyPrefixValidationError() {
//        return ApiKeyPrefixValidator.validate(getLlmProvider(), getAiderApiKey());
        return null;
    }

    /**
     * Shows or hides Azure-specific fields based on the selected provider.
     */
    private void updateProviderSpecificPanels(String provider) {
        updateProviderSpecificPanelsFor(provider, apiKeyPanel, modelPanel, azureApiVersion, azureApiBase, ollamaModelPanel);
        updateOllamaWarnings();
    }

    private void setModelOptionsForProvider(String provider, JComboBox modelComboBoxToUpdate) {
        if (modelComboBoxToUpdate == null) return;
        String normalizedProvider = normalizeLlmProviderName(provider);
        if (normalizedProvider == null) {
            modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {"gpt-5.5"}));
            return;
        }

        switch (normalizedProvider) {
            case "OpenAI" -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {
                    "gpt-5.5", "gpt-5.5-pro",
                    "gpt-5.4", "gpt-5.4-pro", "gpt-5.4-mini", "gpt-5.4-nano",
                    "gpt-5.2", "gpt-5.2-pro", "gpt-5.1",
                    "gpt-5", "gpt-5-pro", "gpt-5-mini", "gpt-5-nano",
                    "gpt-4.1", "gpt-4o", "gpt-4o-mini",
                    "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo"
            }));
            case GOOGLE_PROVIDER -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {
                    "gemini-3-pro-preview", "gemini-3-pro-image-preview",
                    "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools",
                    "gemini-3-flash-preview", "gemini-3.1-flash-lite-preview",
                    "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
                    "gemini-1.5-pro", "gemini-1.5-flash"
            }));
            case "Anthropic" -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {
                    "claude-opus-4-7", "claude-sonnet-4-6",
                    "claude-haiku-4-5", "claude-haiku-4-5-20251001",
                    "claude-opus-4-6", "claude-opus-4-5-20251101",
                    "claude-sonnet-4-5-20250929", "claude-opus-4-1-20250805",
                    "claude-opus-4-20250514", "claude-sonnet-4-20250514",
                    "claude-3-7-sonnet-latest", "claude-3-5-sonnet-latest",
                    "claude-3-5-haiku-latest", "claude-3-haiku-20240307"
            }));
            case "DeepSeek" -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {
                    "deepseek-v4-flash", "deepseek-v4-pro",
                    "deepseek-chat",  "deepseek-reasoner"
            }));
            case "Azure" -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {
                    "gpt-5.5", "gpt-5.5-pro", "gpt-5.4", "gpt-5.4-pro", "gpt-5.4-mini", "gpt-5.4-nano",
                    "gpt-5.2", "gpt-5.2-pro", "gpt-5.1", "gpt-5", "gpt-5-pro", "gpt-5-mini", "gpt-5-nano",
                    "gpt-4.1", "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo",
                    "o1", "o1-mini", "o3", "o3-mini", "o4-mini",
                    "DeepSeek-V4-Flash", "DeepSeek-V4-Pro", "DeepSeek-V3-0324", "DeepSeek-V3.1",
                    "grok-4.20", "grok-4", "grok-3"
            }));
            case "xAI" -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {
                    "grok-4.20", "grok-4", "grok-4-0709", "grok-4-fast-non-reasoning", "grok-4-latest",
                    "grok-3", "grok-3-beta", "grok-3-fast-beta", "grok-3-latest",
                    "grok-3-mini", "grok-3-mini-beta", "grok-3-mini-fast-beta",
                    "grok-2", "grok-2-latest", "grok-beta",
                    "grok-code-fast", "grok-code-fast-1", "grok-code-fast-1-0825"
            }));
            default -> modelComboBoxToUpdate.setModel(new DefaultComboBoxModel<>(new String[] {"gpt-5.5"}));
        }
    }

    private void wireProviderAndModel(
            JComboBox providerCombo,
            JComboBox modelCombo,
            JPanel apiKeyPanelX,
            JPanel modelPanelX,
            JPanel azureApiVersionX,
            JPanel azureApiBaseX,
            JPanel ollamaModelPanelX
    ) {
        if (providerCombo == null) return;

        providerCombo.addActionListener(e -> {
            String selectedProvider = (String) providerCombo.getSelectedItem();
            setModelOptionsForProvider(selectedProvider, modelCombo);
            updateProviderSpecificPanelsFor(selectedProvider, apiKeyPanelX, modelPanelX, azureApiVersionX, azureApiBaseX, ollamaModelPanelX);
            syncApiBaseForProvider(selectedProvider, resolveApiBaseField(azureApiBaseX));
        });

        // Apply initial state
        String initProviderLocal = (String) providerCombo.getSelectedItem();
        setModelOptionsForProvider(initProviderLocal, modelCombo);
        updateProviderSpecificPanelsFor(initProviderLocal, apiKeyPanelX, modelPanelX, azureApiVersionX, azureApiBaseX, ollamaModelPanelX);
        syncApiBaseForProvider(initProviderLocal, resolveApiBaseField(azureApiBaseX));
    }

    private JTextField resolveApiBaseField(JPanel azureApiBasePanel) {
        if (azureApiBasePanel == azureApiBase) {
            return apiBase;
        }
        if (azureApiBasePanel == multiAgentAzureApiBase) {
            return multiAgentApiBase;
        }
        return null;
    }

    private void syncApiBaseForProvider(String provider, JTextField apiBaseField) {
        if (apiBaseField == null) {
            return;
        }
        if ("Ollama".equalsIgnoreCase(provider)) {
            apiBaseField.setText(OLLAMA_DEFAULT_API_BASE);
            return;
        }
        if ("Azure".equalsIgnoreCase(provider) && lastAzureApiBase != null && !lastAzureApiBase.isBlank()) {
            apiBaseField.setText(lastAzureApiBase);
        }
    }


    /**
     * Helper to create a password field with an eye icon for toggling visibility.
     */
    private JComponent createPasswordFieldWithEye(JPasswordField passwordField) {
        JPanel passwordWithEyePanel = new JPanel();
        passwordWithEyePanel.setLayout(new OverlayLayout(passwordWithEyePanel));
        passwordWithEyePanel.setOpaque(false);

        Dimension fieldSize = passwordField.getPreferredSize();
        passwordWithEyePanel.setPreferredSize(new Dimension(fieldSize.width, fieldSize.height));
        passwordWithEyePanel.setMinimumSize(new Dimension(0, fieldSize.height));
        passwordWithEyePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldSize.height));

        Icon eyeIcon = AllIcons.General.InspectionsEye;
        JButton toggleButton = new JButton(eyeIcon);
        toggleButton.setFocusable(false);
        toggleButton.setBorderPainted(false);
        toggleButton.setContentAreaFilled(false);
        toggleButton.setOpaque(false);
        toggleButton.setMargin(new Insets(0, 0, 0, 0));
        toggleButton.setBorder(BorderFactory.createEmptyBorder());
        toggleButton.setPreferredSize(new Dimension(eyeIcon.getIconWidth() + 2, eyeIcon.getIconHeight() + 2));
        toggleButton.setToolTipText("Show/Hide the API key");

        int eyeRightInset = 6;
        int eyePad = eyeIcon.getIconWidth() + eyeRightInset + 4;
        passwordField.setBorder(BorderFactory.createCompoundBorder(
                passwordField.getBorder(),
                BorderFactory.createEmptyBorder(0, 0, 0, eyePad)
        ));

        int vpad = Math.max(0, (fieldSize.height - eyeIcon.getIconHeight()) / 2 - 1);
        JPanel eyeOverlay = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        eyeOverlay.setOpaque(false);
        eyeOverlay.setBorder(BorderFactory.createEmptyBorder(vpad, 0, vpad, eyeRightInset));
        eyeOverlay.add(toggleButton);

        passwordField.setAlignmentX(0.0f);
        passwordField.setAlignmentY(0.5f);
        eyeOverlay.setAlignmentX(0.0f);
        eyeOverlay.setAlignmentY(0.5f);

        passwordWithEyePanel.add(eyeOverlay);
        passwordWithEyePanel.add(passwordField);

        char defaultEchoChar = passwordField.getEchoChar();
        toggleButton.addActionListener(ev -> {
            if (passwordField.getEchoChar() == 0) {
                passwordField.setEchoChar(defaultEchoChar);
                toggleButton.setToolTipText("Show the API key");
            } else {
                passwordField.setEchoChar((char) 0);
                toggleButton.setToolTipText("Hide the API key");
            }
        });

        return passwordWithEyePanel;
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

    /**
     * Shows or hides provider-specific fields for an arbitrary set of panels (used for both Aider and Clone_multiagent).
     */
    private void updateProviderSpecificPanelsFor(
            String provider,
            JPanel apiKeyPanelX,
            JPanel modelPanelX,
            JPanel azureApiVersionX,
            JPanel azureApiBaseX,
            JPanel ollamaModelPanelX
    ) {
        boolean isAzure = provider != null && "Azure".equalsIgnoreCase(provider);
        boolean isOllama = provider != null && "Ollama".equalsIgnoreCase(provider);

        if (apiKeyPanelX != null) {
            apiKeyPanelX.setVisible(!isOllama);
        }
        if (azureApiVersionX != null) {
            azureApiVersionX.setVisible(isAzure);
        }
        if (azureApiBaseX != null) {
            azureApiBaseX.setVisible(isAzure || isOllama);
        }
        if (ollamaModelPanelX != null) {
            ollamaModelPanelX.setVisible(isOllama);
        }
        if (modelPanelX != null) {
            modelPanelX.setVisible(!isOllama);
        }

        JLabel helpLabel = null;
        if (azureApiBaseX == azureApiBase) {
            helpLabel = apiBaseHelp;
        } else if (azureApiBaseX == multiAgentAzureApiBase) {
            helpLabel = multiAgentApiBaseHelp;
        }
        updateApiBaseHelpLabel(helpLabel, provider);

        // Refresh layouts so UI updates immediately
        if (apiKeyPanelX != null) { apiKeyPanelX.revalidate(); apiKeyPanelX.repaint(); }
        if (azureApiVersionX != null) { azureApiVersionX.revalidate(); azureApiVersionX.repaint(); }
        if (azureApiBaseX != null) { azureApiBaseX.revalidate(); azureApiBaseX.repaint(); }
        if (ollamaModelPanelX != null) { ollamaModelPanelX.revalidate(); ollamaModelPanelX.repaint(); }
        if (modelPanelX != null) { modelPanelX.revalidate(); modelPanelX.repaint(); }
        if (mainPanel != null) { mainPanel.revalidate(); mainPanel.repaint(); }
    }

    private void updateApiKeyWarningForPanel(JPanel targetApiKeyPanel, JLabel warningLabel, boolean visible) {
        if (warningLabel != null) {
            warningLabel.setVisible(visible);
        } else if (targetApiKeyPanel != null) {
            for (java.awt.Component component : targetApiKeyPanel.getComponents()) {
                if (component instanceof JLabel label && label.getIcon() == AllIcons.General.Error) {
                    label.setVisible(visible);
                }
            }
        }
        if (targetApiKeyPanel != null) {
            targetApiKeyPanel.revalidate();
            targetApiKeyPanel.repaint();
        }
    }

    private void updateApiBaseHelpLabel(JLabel helpLabel, String provider) {
        if (helpLabel == null) {
            return;
        }

        String p = provider == null ? "" : provider.trim();

        if ("Ollama".equalsIgnoreCase(provider)) {
//            helpLabel.setText("API base for Ollama");
            helpLabel.setToolTipText("API Base for the Ollama server, for example http://localhost:11434");
        } else if ("Azure".equalsIgnoreCase(provider)) {
//            helpLabel.setText("API base for Azure");
            helpLabel.setToolTipText("API Base for the Azure OpenAI endpoint, for example https://your-resource.openai.azure.com");
        } else {
//            helpLabel.setText(p + " API base");
            helpLabel.setToolTipText("Base URL used by " + p + " provider.");
        }
    }
}
