package org.jetbrains.research.anticopypaster.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class CrossFileJsonSupport {
    private CrossFileJsonSupport() {}

    static String sanitizeCrossFileName(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    static String safeTruncate(String value, int maxChars) {
        if (value == null) return "";
        String text = value.trim();
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n...<truncated>...";
    }

    static String stripOptionalJavaFence(String block) {
        if (block == null) return "";
        String s = block.replace("\r\n", "\n").replace("\r", "\n").strip();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd >= 0) {
                s = s.substring(firstLineEnd + 1);
            }
        }
        String trimmedRight = s.stripTrailing();
        if (trimmedRight.endsWith("```")) {
            int lastFence = trimmedRight.lastIndexOf("\n```");
            if (lastFence >= 0 && trimmedRight.substring(lastFence).trim().equals("```")) {
                trimmedRight = trimmedRight.substring(0, lastFence);
            } else if ("```".equals(trimmedRight.trim())) {
                trimmedRight = "";
            }
        }
        return trimmedRight.stripTrailing();
    }

    static String getJsonString(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return "";
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                return obj.get(key).getAsString();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    static int getJsonInt(JsonObject obj, int fallback, String... keys) {
        if (obj == null || keys == null) return fallback;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                return obj.get(key).getAsInt();
            } catch (Throwable ignored) {
                try {
                    return Integer.parseInt(obj.get(key).getAsString().trim());
                } catch (Throwable ignoredAgain) {}
            }
        }
        return fallback;
    }

    static boolean getJsonBoolean(JsonObject obj, boolean fallback, String... keys) {
        if (obj == null || keys == null) return fallback;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                return obj.get(key).getAsBoolean();
            } catch (Throwable ignored) {
                try {
                    String value = obj.get(key).getAsString();
                    if (value != null) {
                        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
                        if ("true".equals(normalized) || "yes".equals(normalized) || "useful".equals(normalized)) return true;
                        if ("false".equals(normalized) || "no".equals(normalized) || "not_useful".equals(normalized)) return false;
                    }
                } catch (Throwable ignoredAgain) {}
            }
        }
        return fallback;
    }

    static double getJsonDouble(JsonObject obj, double fallback, String... keys) {
        if (obj == null || keys == null) return fallback;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                return obj.get(key).getAsDouble();
            } catch (Throwable ignored) {
                try {
                    return Double.parseDouble(obj.get(key).getAsString().trim());
                } catch (Throwable ignoredAgain) {}
            }
        }
        return fallback;
    }

    static JsonArray getJsonArray(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                JsonElement element = obj.get(key);
                if (element.isJsonArray()) return element.getAsJsonArray();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static JsonObject getJsonObject(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                JsonElement element = obj.get(key);
                if (element.isJsonObject()) return element.getAsJsonObject();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static java.util.List<String> parseJsonStringArray(JsonArray arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (arr == null) return out;
        for (JsonElement element : arr) {
            if (element == null || element.isJsonNull()) continue;
            try {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) out.add(value);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static String extractJsonObjectSubstring(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
