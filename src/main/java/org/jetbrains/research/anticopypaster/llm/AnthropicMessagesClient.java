package org.jetbrains.research.anticopypaster.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class AnthropicMessagesClient implements LlmClient {
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newHttpClient();

    public AnthropicMessagesClient(String apiKey, String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public String complete(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 8192);

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Anthropic error " + resp.statusCode() + ": " + resp.body());

        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray content = json.getAsJsonArray("content");
        if (content == null || content.size() == 0) return "";
        JsonObject first = content.get(0).getAsJsonObject();
        return first.has("text") ? first.get("text").getAsString() : "";
    }
}