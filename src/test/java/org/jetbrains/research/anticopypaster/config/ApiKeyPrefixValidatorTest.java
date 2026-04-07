package org.jetbrains.research.anticopypaster.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKeyPrefixValidatorTest {

    @Test
    void allowsBlankApiKey() {
        assertNull(ApiKeyPrefixValidator.validate("OpenAI", ""));
        assertNull(ApiKeyPrefixValidator.validate("Gemini", "   "));
        assertNull(ApiKeyPrefixValidator.validate("Anthropic", null));
    }

    @Test
    void acceptsKnownPrefixes() {
        assertNull(ApiKeyPrefixValidator.validate("OpenAI", "sk-proj-demo"));
        assertNull(ApiKeyPrefixValidator.validate("Gemini", "AIzaSyDemo"));
        assertNull(ApiKeyPrefixValidator.validate("DeepSeek", "sk-demo"));
        assertNull(ApiKeyPrefixValidator.validate("Anthropic", "sk-ant-demo"));
    }

    @Test
    void rejectsMismatchedPrefixes() {
        assertEquals(
                "API key for OpenAI must start with 'sk-proj-'.",
                ApiKeyPrefixValidator.validate("OpenAI", "sk-demo")
        );
        assertEquals(
                "API key for Gemini must start with 'AIzaSy'.",
                ApiKeyPrefixValidator.validate("Gemini", "sk-demo")
        );
    }

    @Test
    void skipsProvidersWithoutKnownPrefixRule() {
        assertNull(ApiKeyPrefixValidator.validate("Azure", "anything"));
        assertNull(ApiKeyPrefixValidator.validate("Ollama", "anything"));
        assertNull(ApiKeyPrefixValidator.validate("xAI", "anything"));
    }
}
