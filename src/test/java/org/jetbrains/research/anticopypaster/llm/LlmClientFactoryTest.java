package org.jetbrains.research.anticopypaster.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientFactoryTest {

    @Test
    void normalizeProviderNameMapsLegacyGeminiToGoogle() {
        assertEquals("Google", LlmClientFactory.normalizeProviderName("Gemini"));
        assertEquals("Google", LlmClientFactory.normalizeProviderName(" google "));
    }

    @Test
    void resolveProviderBaseUrlIgnoresStoredBaseForDeepSeek() {
        assertEquals(
                "https://api.deepseek.com",
                LlmClientFactory.resolveProviderBaseUrl("DeepSeek", "https://old-endpoint.example.com/v1")
        );
    }

    @Test
    void resolveProviderBaseUrlIgnoresStoredBaseForXai() {
        assertEquals(
                "https://api.x.ai",
                LlmClientFactory.resolveProviderBaseUrl("xAI", "https://stale-endpoint.example.com")
        );
    }

    @Test
    void resolveProviderBaseUrlPreservesConfiguredBaseForAzureAndOllama() {
        assertEquals(
                "https://azure.example.com",
                LlmClientFactory.resolveProviderBaseUrl("Azure", "https://azure.example.com")
        );
        assertEquals(
                "http://localhost:11434",
                LlmClientFactory.resolveProviderBaseUrl("Ollama", "")
        );
        assertEquals(
                "http://custom-ollama:11434",
                LlmClientFactory.resolveProviderBaseUrl("Ollama", "http://custom-ollama:11434")
        );
    }

    @Test
    void shouldLogApiBaseForProvidersThatUseOrPinOne() {
        assertTrue(LlmClientFactory.shouldLogApiBase("Azure"));
        assertTrue(LlmClientFactory.shouldLogApiBase("Ollama"));
        assertTrue(LlmClientFactory.shouldLogApiBase("DeepSeek"));
        assertTrue(LlmClientFactory.shouldLogApiBase("xAI"));
        assertFalse(LlmClientFactory.shouldLogApiBase("OpenAI"));
    }
}
