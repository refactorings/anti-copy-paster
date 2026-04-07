package org.jetbrains.research.anticopypaster.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class GeminiGenerateContentClient implements LlmClient {
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newHttpClient();

    public GeminiGenerateContentClient(String apiKey, String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public String complete(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        body.add("contents", contents);

        String m = model.startsWith("models/") ? model : "models/" + model;
        String url = "https://generativelanguage.googleapis.com/v1beta/" + m + ":generateContent?key=" + apiKey;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Gemini error " + resp.statusCode() + ": " + resp.body());

        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray candidates = json.getAsJsonArray("candidates");
        if (candidates == null || candidates.size() == 0) return "";
        JsonObject c0 = candidates.get(0).getAsJsonObject();
        JsonObject cContent = c0.getAsJsonObject("content");
        if (cContent == null) return "";
        JsonArray cParts = cContent.getAsJsonArray("parts");
        if (cParts == null || cParts.size() == 0) return "";
        JsonObject p0 = cParts.get(0).getAsJsonObject();
        return p0.has("text") ? p0.get("text").getAsString() : "";
    }
}