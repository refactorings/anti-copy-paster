package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;

public class detection {

    public static class CloneRange {
        public int startLine;
        public int endLine;
    }

    public static class DetectedClone {
        public String id;
        public List<CloneRange> ranges;
        public String refactorType;
        public String reason;
    }

    public static class DetectionResult {
        public String status;
        public String file;
        public List<DetectedClone> clones;
    }

    public DetectionResult detect(String fileName, String fileSource, String selectedSnippet, Function<String, String> llmCaller) {
        String prompt = buildDetectionPrompt(fileSource, selectedSnippet, fileName);
        String rawOutput = llmCaller.apply(prompt);
        DetectionResult result = parseDetectionResult(rawOutput, fileName);
        if (result == null || result.clones == null || result.clones.isEmpty()) {
            DetectionResult noClonesResult = new DetectionResult();
            noClonesResult.status = "no_clones";
            noClonesResult.file = fileName;
            noClonesResult.clones = Collections.emptyList();
            return noClonesResult;
        }
        return result;
    }

    private String buildDetectionPrompt(String fileSource, String selectedSnippet, String fileName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert code clone detection assistant.\n");
        prompt.append("Analyze the following Java source code from a single file named '").append(fileName).append("'.\n");
        prompt.append("Your task is to detect meaningful code clones within this same file only.\n");
        prompt.append("Ignore trivial one-line repetitions and focus on substantial code duplication.\n");
        prompt.append("If a selected snippet is provided, consider it as a hint but analyze the entire file.\n\n");
        prompt.append("File source:\n");
        prompt.append("'''\n");
        prompt.append(fileSource).append("\n");
        prompt.append("'''\n\n");
        if (selectedSnippet != null && !selectedSnippet.isEmpty()) {
            prompt.append("Selected snippet:\n");
            prompt.append("'''\n");
            prompt.append(selectedSnippet).append("\n");
            prompt.append("'''\n\n");
        }
        prompt.append("Output ONLY a valid JSON object with the following structure:\n");
        prompt.append("{\n");
        prompt.append("  \"status\": \"found_clones\" or \"no_clones\",\n");
        prompt.append("  \"file\": \"").append(fileName).append("\",\n");
        prompt.append("  \"clones\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"unique_clone_id\",\n");
        prompt.append("      \"ranges\": [ { \"startLine\": int, \"endLine\": int }, ... ],\n");
        prompt.append("      \"refactorType\": \"extracted_method\" or \"extracted_class\" or other string,\n");
        prompt.append("      \"reason\": \"explanation of why this clone was detected\"\n");
        prompt.append("    },\n");
        prompt.append("    ...\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("Do not include any text outside the JSON object.");
        return prompt.toString();
    }

    private DetectionResult parseDetectionResult(String raw, String fallbackFileName) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        try {
            Gson gson = new Gson();
            DetectionResult result = gson.fromJson(json, DetectionResult.class);
            if (result == null) return null;
            if (result.file == null || result.file.isEmpty()) {
                result.file = fallbackFileName;
            }
            if (result.clones == null) {
                result.clones = Collections.emptyList();
            }
            return result;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
