package org.jetbrains.research.anticopypaster.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class AzureOpenAIChatClient implements LlmClient {
    private final String apiBase;
    private final String apiVersion;
    private final String apiKey;
    private final String deployment;
    private final HttpClient http = HttpClient.newHttpClient();

    public AzureOpenAIChatClient(String apiBase, String apiVersion, String apiKey, String deployment) {
        this.apiBase = apiBase == null ? "" : apiBase.trim();
        this.apiVersion = apiVersion == null ? "" : apiVersion.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.deployment = deployment == null ? "" : deployment.trim();
    }

    @Override
    public String complete(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);
        body.addProperty("temperature", 0.2);

        String base = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        String url = base + "/openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Azure OpenAI error " + resp.statusCode() + ": " + resp.body());

        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }
}