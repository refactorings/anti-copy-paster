package org.jetbrains.research.anticopypaster.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class OpenAICompatibleChatClient implements LlmClient {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    // Large refactoring outputs can be truncated if max_tokens is too small.
    // We keep this fairly high and also support automatic continuation when the API returns finish_reason="length".
    private final int maxTokens;
    private final int maxContinueTurns;
    private final HttpClient http = HttpClient.newHttpClient();

    public OpenAICompatibleChatClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, 8192, 4);
    }

    public OpenAICompatibleChatClient(String baseUrl, String apiKey, String model, int maxTokens, int maxContinueTurns) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.maxTokens = Math.max(256, maxTokens);
        this.maxContinueTurns = Math.max(0, maxContinueTurns);
    }

    @Override
    public String complete(String prompt) throws Exception {
        // We keep a minimal conversation so we can request a continuation if the model hits a length limit.
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        StringBuilder full = new StringBuilder();
        String lastAssistantChunk = "";

        for (int turn = 0; turn <= maxContinueTurns; turn++) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", messages);

            // GPT-5 only supports the default temperature (1). Do not override it.
            if (!model.startsWith("gpt-5")) {
                body.addProperty("temperature", 0.2);
            }

            // Most OpenAI-compatible providers support max_tokens on /v1/chat/completions.
            // This helps for long Java files.
            if (model.startsWith("gpt-5")) {
                // Newer OpenAI models require max_completion_tokens instead of max_tokens
                body.addProperty("max_completion_tokens", maxTokens);
            } else {
                body.addProperty("max_tokens", maxTokens);
            }

            JsonObject choice = sendOnce(body);
            String chunk = choice.getAsJsonObject("message").get("content").getAsString();
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                    ? choice.get("finish_reason").getAsString()
                    : "";

            if (full.length() == 0) {
                full.append(chunk);
            } else {
                full.append(mergeWithOverlap(lastAssistantChunk, chunk));
            }
            lastAssistantChunk = chunk;

            // If we didn't hit a length limit, we're done.
            if (!"length".equalsIgnoreCase(finishReason)) {
                return full.toString();
            }

            // Ask the model to continue exactly where it stopped.
            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            assistantMsg.addProperty("content", chunk);
            messages.add(assistantMsg);

            JsonObject continueMsg = new JsonObject();
            continueMsg.addProperty("role", "user");
            continueMsg.addProperty("content",
                    "Continue from exactly where you stopped. Output ONLY the remaining text. Do not repeat any earlier lines.");
            messages.add(continueMsg);
        }

        return full.toString();
    }

    private JsonObject sendOnce(JsonObject body) throws Exception {
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        url = url + "/v1/chat/completions";

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);

        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("LLM API error " + resp.statusCode() + ": " + resp.body());
        }

        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        return json.getAsJsonArray("choices").get(0).getAsJsonObject();
    }

    /**
     * Avoid repeated text when the model restarts a few tokens before the cutoff.
     * We trim the largest suffix of 'prev' that is also a prefix of 'next'.
     */
    private static String mergeWithOverlap(String prev, String next) {
        if (prev == null || prev.isEmpty()) return next == null ? "" : next;
        if (next == null || next.isEmpty()) return "";

        int max = Math.min(prev.length(), next.length());
        int best = 0;
        for (int i = 1; i <= max; i++) {
            if (prev.regionMatches(prev.length() - i, next, 0, i)) {
                best = i;
            }
        }
        return next.substring(best);
    }
}