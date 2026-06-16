package org.jetbrains.research.anticopypaster.config;

import org.jetbrains.annotations.Nullable;

final class ApiKeyPrefixValidator {

    private ApiKeyPrefixValidator() {
    }

    @Nullable
    static String validate(@Nullable String provider, @Nullable String apiKey) {
        // API key prefix validation is intentionally disabled.
//        String normalizedKey = apiKey == null ? "" : apiKey.trim();
//        if (normalizedKey.isEmpty()) {
//            return null;
//        }
//
//        String expectedPrefix = expectedPrefix(provider);
//        if (expectedPrefix == null || normalizedKey.startsWith(expectedPrefix)) {
//            return null;
//        }
//
//        return "API key for " + provider + " must start with '" + expectedPrefix + "'.";
        return null;
    }

//    @Nullable
//    private static String expectedPrefix(@Nullable String provider) {
//        if (provider == null) {
//            return null;
//        }
//
//        return switch (provider) {
//            case "OpenAI" -> "sk-proj-";
//            case "Google", "Gemini" -> "AIzaSy";
//            case "DeepSeek" -> "sk-";
//            case "Anthropic" -> "sk-ant-";
//            default -> null;
//        };
//    }
}
