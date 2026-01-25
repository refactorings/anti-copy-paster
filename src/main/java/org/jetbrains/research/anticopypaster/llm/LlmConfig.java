package org.jetbrains.research.anticopypaster.llm;

public final class LlmConfig {
    public final String provider;   // OpenAI / Azure / Gemini / Anthropic / DeepSeek / xAI / Ollama
    public final String model;      // model name OR azure deployment name
    public final String apiKey;
    public final String apiBase;    // for Azure endpoint or OpenAI-compatible base
    public final String apiVersion; // for Azure
    public final String ollamaModel;

    public LlmConfig(String provider, String model, String apiKey, String apiBase, String apiVersion, String ollamaModel) {
        this.provider = safe(provider);
        this.model = safe(model);
        this.apiKey = safe(apiKey);
        this.apiBase = safe(apiBase);
        this.apiVersion = safe(apiVersion);
        this.ollamaModel = safe(ollamaModel);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}