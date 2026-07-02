package org.jetbrains.research.anticopypaster.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class ProjectSettingsConfigurable implements Configurable {
    private static final Logger LOG = Logger.getInstance(ProjectSettingsConfigurable.class);

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
        if (settingsComponent == null) {
            return null;
        }
        return settingsComponent.getPreferredFocusedComponent();
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        try {
            settingsComponent = new ProjectSettingsComponent(this.project);
            JComponent panel = settingsComponent.getPanel();
            enlargeSettingsDialogWhenShown(panel);
            return panel;
        } catch (Throwable t) {
            LOG.error("Failed to create AntiCopyPaster settings UI", t);
            settingsComponent = null;

            JPanel panel = new JPanel(new BorderLayout());
            JLabel label = new JLabel("<html><body>AntiCopyPaster settings failed to load. "
                    + "Please check idea.log for details.<br/>"
                    + escapeHtml(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()))
                    + "</body></html>");
            panel.add(label, BorderLayout.NORTH);
            return panel;
        }
    }

    @Override
    public boolean isModified() {
        if (settingsComponent == null) {
            return false;
        }
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        boolean modified = settingsComponent.getMinimumDuplicateMethods() != settings.minimumDuplicateMethods;
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
        modified |= !Objects.equals(settingsComponent.getNameModel(), settings.useNameRec);
        modified |= settingsComponent.getNumOfPreds() != settings.numOfPreds;
        modified |= settingsComponent.getJudgementModel() != settings.judgementModel;
        modified |= settings.judgementModel == ProjectSettingsState.JudgementModel.AIDER &&
                settingsComponent.getCloneMode() != settings.getCloneMode();
        modified |= settingsComponent.getExtractionType() != settings.extractionType;
        modified |= settingsComponent.getModelSensitivity() != settings.modelSensitivity;
        modified |= settingsComponent.getMaxParams() != settings.maxParams;
        modified |= !Objects.equals(settingsComponent.getAiderApiKey(), settings.getAiderApiKey());
        modified |= !Objects.equals(settingsComponent.getAiderPath(), settings.getAiderPath());
        modified |= !Objects.equals(settingsComponent.getSelectedAiderModel(), settings.getAiderModel());
        modified |= !Objects.equals(
                ProjectSettingsComponent.normalizeLlmProviderName(settingsComponent.getLlmProvider()),
                ProjectSettingsComponent.normalizeLlmProviderName(settings.getLlmprovider())
        );
        modified |= !Objects.equals(settingsComponent.getApiBase(), settings.getApiBase());
        modified |= !Objects.equals(settingsComponent.getApiVersion(), settings.getApiVersion());
        modified |= !Objects.equals(settingsComponent.getCopilotCliPath(), settings.getCopilotCliPath());
        modified |= !Objects.equals(settingsComponent.getFilesPath(), settings.getFilesPath());
        modified |= !Objects.equals(settingsComponent.getAllFilesCheckboxes(), settings.getAllFilesCheckboxes());
        modified |= !Objects.equals(settingsComponent.getSelectedAnalysisButton(), settings.getSelectedAnalysisButton());
        modified |= !Objects.equals(settingsComponent.getOllamaModel(), settings.getOllamaModelName());
        modified |= settingsComponent.getMaxAttempts() != settings.getMaxAttempts();
        return modified;
    }

    @Override
    public void apply() throws ConfigurationException {
        if (settingsComponent == null) {
            return;
        }
        // API key prefix validation is intentionally disabled.
//        String apiKeyPrefixValidationError = settingsComponent.getApiKeyPrefixValidationError();
//        if (apiKeyPrefixValidationError != null) {
//            throw new ConfigurationException(apiKeyPrefixValidationError);
//        }

        if ("Azure".equalsIgnoreCase(settingsComponent.getLlmProvider())) {
            if (settingsComponent.getApiBase() == null || settingsComponent.getApiBase().trim().isEmpty()) {
                throw new ConfigurationException("API Base must be provided when using Azure.");
            }
            if (settingsComponent.getApiVersion() == null || settingsComponent.getApiVersion().trim().isEmpty()) {
                throw new ConfigurationException("API Version must be provided when using Azure.");
            }
        }

        if ("Ollama".equalsIgnoreCase(settingsComponent.getLlmProvider())) {
            if (settingsComponent.getApiBase() == null || settingsComponent.getApiBase().trim().isEmpty()) {
                throw new ConfigurationException("API Base must be provided when using Ollama.");
            }
            if (settingsComponent.getOllamaModel() == null || settingsComponent.getOllamaModel().trim().isEmpty()) {
                throw new ConfigurationException("Ollama Model Name must be provided when using Ollama.");
            }
        }

        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
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
        settings.useNameRec = settingsComponent.getNameModel();
        settings.numOfPreds = settingsComponent.getNumOfPreds();
        settings.judgementModel = settingsComponent.getJudgementModel();
        settings.setCloneMode(settingsComponent.getCloneMode());
        settings.extractionType = settingsComponent.getExtractionType();
        settings.modelSensitivity = settingsComponent.getModelSensitivity();
        settings.maxParams = settingsComponent.getMaxParams();
        settings.setMaxAttempts(settingsComponent.getMaxAttempts());
        settings.setLlmprovider(ProjectSettingsComponent.normalizeLlmProviderName(settingsComponent.getLlmProvider()));
        settings.setAiderModel(settingsComponent.getSelectedAiderModel());
        settings.setAiderApiKey(settingsComponent.getAiderApiKey());
        settings.setAiderPath(settingsComponent.getAiderPath());
        settings.setApiBase(settingsComponent.getApiBase());
        settings.setApiVersion(settingsComponent.getApiVersion());
        settings.setCopilotCliPath(settingsComponent.getCopilotCliPath());
        settings.setFilesPath(settingsComponent.getFilesPath());
        settings.setAllFilesCheckboxes(settingsComponent.getAllFilesCheckboxes());
        settings.setSelectedAnalysisButton(settingsComponent.getSelectedAnalysisButton());
        settings.setOllamaName(settingsComponent.getOllamaModel());

        // Apply model changes and close Aider windows if needed
        settingsComponent.applyModelChanges();
    }

    @Override
    public void reset() {
        if (settingsComponent == null) {
            return;
        }
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
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
        settingsComponent.setJudgementModel(settings.judgementModel);
        if (settings.judgementModel == ProjectSettingsState.JudgementModel.AIDER) {
            settingsComponent.setCloneMode(settings.getCloneMode());
        }
        settingsComponent.setNameModel(settings.useNameRec);
        settingsComponent.setNumOfPreds(settings.numOfPreds);
        settingsComponent.setExtractionType(settings.extractionType);
        settingsComponent.setModelSensitivity(settings.modelSensitivity);
        settingsComponent.setMaxParams(settings.maxParams);
        settingsComponent.setMaxAttempts(settings.getMaxAttempts());
        settingsComponent.setLlmProvider(ProjectSettingsComponent.normalizeLlmProviderName(settings.getLlmprovider()));
        settingsComponent.setSelectedAiderModel(settings.getAiderModel());
        settingsComponent.setAiderApiKey(settings.getAiderApiKey());
        settingsComponent.setAiderPath(settings.getAiderPath());
        settingsComponent.setFilesPath(settings.getFilesPath());
        settingsComponent.setApiBase(settings.getApiBase());
        settingsComponent.setApiVersion(settings.getApiVersion());
        settingsComponent.setCopilotCliPath(settings.getCopilotCliPath());
        settingsComponent.setAllFilesCheckboxes(settings.getAllFilesCheckboxes());
        settingsComponent.setSelectedAnalysisButton(settings.getSelectedAnalysisButton());
        settingsComponent.setOllamaModel(settings.getOllamaModelName());

        // Cancel any pending model changes
        settingsComponent.cancelModelChanges();
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void enlargeSettingsDialogWhenShown(JComponent panel) {
        if (panel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> resizeSettingsWindow(panel)));
        Timer retry = new Timer(250, event -> resizeSettingsWindow(panel));
        retry.setRepeats(false);
        retry.start();
    }

    private static void resizeSettingsWindow(JComponent panel) {
        try {
            Window window = SwingUtilities.getWindowAncestor(panel);
            if (window == null) {
                return;
            }
            Rectangle screen = usableScreenBounds(window);
            int targetWidth = Math.min(Math.max(window.getWidth(), (int) (screen.width * 0.92)), screen.width);
            int targetHeight = Math.min(Math.max(window.getHeight(), (int) (screen.height * 0.92)), screen.height);
            int x = screen.x + Math.max(0, (screen.width - targetWidth) / 2);
            int y = screen.y + Math.max(0, (screen.height - targetHeight) / 2);
            window.setBounds(x, y, targetWidth, targetHeight);
            window.validate();
        } catch (Throwable t) {
            LOG.warn("Failed to resize AntiCopyPaster settings dialog", t);
        }
    }

    private static Rectangle usableScreenBounds(Window window) {
        GraphicsConfiguration graphicsConfiguration = window.getGraphicsConfiguration();
        if (graphicsConfiguration == null) {
            return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        Rectangle bounds = graphicsConfiguration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom
        );
    }
}
