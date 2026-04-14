package org.jetbrains.research.anticopypaster.llm;

import com.intellij.openapi.project.Project;

import java.util.Locale;
import java.util.function.Consumer;

public final class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient fromProjectSettings(Project project, Consumer<String> viewer) {
        try {
            LlmConfig cfg = ProjectSettingsReader.read(project);

            String provider = cfg == null ? "" : cfg.provider;
            String model = cfg == null ? "" : cfg.model;
            String apiKey = cfg == null ? "" : cfg.apiKey;
            String apiBase = cfg == null ? "" : cfg.apiBase;
            String apiVersion = cfg == null ? "" : cfg.apiVersion;
            String ollamaModel = cfg == null ? "" : cfg.ollamaModel;

            // fallback: allow old env-based OpenAI usage
            if (provider.isBlank()) {
                String envKey = System.getenv("OPENAI_API_KEY");
                if (envKey != null && !envKey.isBlank()) {
                    provider = "OpenAI";
                    apiKey = envKey;
                    if (model.isBlank()) model = "gpt-4o-mini";
                }
            }

            if (provider.equalsIgnoreCase("Ollama")) {
                String base = resolveProviderBaseUrl(provider, apiBase);
                String m = !ollamaModel.isBlank() ? ollamaModel : model;
                if (m.isBlank()) m = "llama3";
                log(viewer, "Ollama", m, base, "", "");
                return new OpenAICompatibleChatClient(base, "", m);
            }

            if (provider.equalsIgnoreCase("Azure")) {
                if (!apiBase.isBlank() && !apiVersion.isBlank() && !model.isBlank() && !apiKey.isBlank()) {
                    log(viewer, "Azure", model, apiBase, apiVersion, apiKey);
                    return new AzureOpenAIChatClient(apiBase, apiVersion, apiKey, model); // model=deployment
                }
                log(viewer, "Azure", model, apiBase, apiVersion, apiKey);
                if (viewer != null) viewer.accept("[LLM_SETTINGS] Azure config incomplete -> Noop");
                return new NoopLlmClient();
            }

            if (provider.equalsIgnoreCase("Gemini")) {
                String m = model.isBlank() ? "gemini-2.5-pro" : model;
                log(viewer, "Gemini", m, "", "", apiKey);
                return apiKey.isBlank() ? new NoopLlmClient() : new GeminiGenerateContentClient(apiKey, m);
            }

            if (provider.equalsIgnoreCase("Anthropic")) {
                String m = model.isBlank() ? "claude-3-5-sonnet-latest" : model;
                log(viewer, "Anthropic", m, "", "", apiKey);
                return apiKey.isBlank() ? new NoopLlmClient() : new AnthropicMessagesClient(apiKey, m);
            }

            if (provider.equalsIgnoreCase("OpenAI")) {
                String m = model.isBlank() ? "gpt-4o-mini" : model;
                log(viewer, "OpenAI", m, "https://api.openai.com", "", apiKey);
                return apiKey.isBlank() ? new NoopLlmClient() : new OpenAICompatibleChatClient("https://api.openai.com", apiKey, m);
            }

            if (provider.equalsIgnoreCase("DeepSeek")) {
                String m = model.isBlank() ? "deepseek-chat" : model;
                String base = resolveProviderBaseUrl(provider, apiBase);
                logIgnoredStoredBase(viewer, "DeepSeek", apiBase, base);
                log(viewer, "DeepSeek", m, base, "", apiKey);
                return apiKey.isBlank() ? new NoopLlmClient() : new OpenAICompatibleChatClient(base, apiKey, m);
            }

            if (provider.equalsIgnoreCase("xAI")) {
                String m = model.isBlank() ? "grok-3" : model;
                String base = resolveProviderBaseUrl(provider, apiBase);
                logIgnoredStoredBase(viewer, "xAI", apiBase, base);
                log(viewer, "xAI", m, base, "", apiKey);
                return apiKey.isBlank() ? new NoopLlmClient() : new OpenAICompatibleChatClient(base, apiKey, m);
            }

            if (viewer != null) viewer.accept("[LLM_SETTINGS] Unknown provider '" + provider + "' -> Noop");
            return new NoopLlmClient();

        } catch (Throwable t) {
            if (viewer != null) viewer.accept("[LLM_SETTINGS] factory failed: " + t.getMessage());
            return new NoopLlmClient();
        }
    }

    static String resolveProviderBaseUrl(String provider, String configuredBase) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String trimmedBase = configuredBase == null ? "" : configuredBase.trim();

        return switch (normalizedProvider) {
            case "ollama" -> trimmedBase.isBlank() ? "http://localhost:11434" : trimmedBase;
            // DeepSeek/xAI API base is not editable in the UI, so stale stored values from
            // Azure/Ollama should never leak into these providers.
            case "deepseek" -> "https://api.deepseek.com";
            case "xai" -> "https://api.x.ai";
            default -> trimmedBase;
        };
    }

    static boolean shouldLogApiBase(String provider) {
        if (provider == null) return false;
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "ollama", "azure", "deepseek", "xai" -> true;
            default -> false;
        };
    }

    private static void log(Consumer<String> viewer, String provider, String model, String base, String version, String key) {
        if (viewer == null) return;

        String keyPreview;
        if (provider != null && provider.equalsIgnoreCase("Ollama")) {
            keyPreview = "<unused>";
        } else {
            keyPreview = (key == null || key.isBlank()) ? "<empty>" :
                    (key.length() <= 6 ? "<set>" : key.substring(0, 3) + "..." + key.substring(key.length() - 3));
        }

        StringBuilder msg = new StringBuilder();
        msg.append("[LLM_SETTINGS] provider=").append(provider)
                .append(", model=").append(model);

        if (shouldLogApiBase(provider)) {
            msg.append(", apiBase=").append(base);
            if (provider.equalsIgnoreCase("Azure")) {
                msg.append(", apiVersion=").append(version);
            }
        }

        msg.append(", apiKey=").append(keyPreview);

        viewer.accept(msg.toString());
    }

    private static void logIgnoredStoredBase(Consumer<String> viewer, String provider, String configuredBase, String resolvedBase) {
        if (viewer == null) return;
        if (configuredBase == null || configuredBase.isBlank()) return;
        if (sameBase(configuredBase, resolvedBase)) return;

        viewer.accept("[LLM_SETTINGS] ignoring stored apiBase for " + provider + "; using " + resolvedBase);
    }

    private static boolean sameBase(String left, String right) {
        return normalizeBase(left).equals(normalizeBase(right));
    }

    private static String normalizeBase(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
