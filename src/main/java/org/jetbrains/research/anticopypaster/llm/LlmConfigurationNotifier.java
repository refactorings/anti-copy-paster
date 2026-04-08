package org.jetbrains.research.anticopypaster.llm;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsConfigurable;

public final class LlmConfigurationNotifier {
    private static final String NOTIFICATION_GROUP_ID = "AntiCopyPaster";
    private static final String SETTINGS_ACTION_TEXT = "Open Settings";

    private LlmConfigurationNotifier() {}

    @Nullable
    public static String getConfigurationProblem(Project project, boolean allowOpenAiEnvFallbackForBlankProvider) {
        LlmConfig cfg = ProjectSettingsReader.read(project);
        if (cfg == null) {
            return "LLM settings could not be read. Open Settings and configure your provider and API key.";
        }

        String provider = cfg.provider;
        if (provider.isBlank()) {
            if (allowOpenAiEnvFallbackForBlankProvider) {
                String envKey = System.getenv("OPENAI_API_KEY");
                if (envKey != null && !envKey.isBlank()) {
                    return null;
                }
            }
            return "No LLM provider is configured. Open Settings, choose a provider, and enter your API key.";
        }

        if (!isSupportedProvider(provider)) {
            return "The configured LLM provider '" + provider + "' is not supported. Open Settings and choose a supported provider.";
        }

        if (requiresApiKey(provider) && cfg.apiKey.isBlank()) {
            return "No API key is configured for " + provider + ". Open Settings and enter your API key.";
        }

        if (provider.equalsIgnoreCase("Azure")) {
            if (cfg.apiBase.isBlank()) {
                return "Azure API Base is missing. Open Settings and complete the Azure configuration.";
            }
            if (cfg.apiVersion.isBlank()) {
                return "Azure API Version is missing. Open Settings and complete the Azure configuration.";
            }
        }

        return null;
    }

    public static void notifyConfigurationProblem(Project project, String title, String message) {
        Notification notification = new Notification(
                NOTIFICATION_GROUP_ID,
                title,
                message,
                NotificationType.WARNING
        );
        notification.addAction(NotificationAction.createSimple(SETTINGS_ACTION_TEXT, () -> {
            notification.expire();
            openSettings(project);
        }));
        notification.notify(project);
    }

    public static void openSettings(Project project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectSettingsConfigurable.class);
    }

    private static boolean requiresApiKey(String provider) {
        return !provider.equalsIgnoreCase("Ollama");
    }

    private static boolean isSupportedProvider(String provider) {
        return provider.equalsIgnoreCase("OpenAI")
                || provider.equalsIgnoreCase("Azure")
                || provider.equalsIgnoreCase("Gemini")
                || provider.equalsIgnoreCase("Anthropic")
                || provider.equalsIgnoreCase("DeepSeek")
                || provider.equalsIgnoreCase("xAI")
                || provider.equalsIgnoreCase("Ollama");
    }
}
