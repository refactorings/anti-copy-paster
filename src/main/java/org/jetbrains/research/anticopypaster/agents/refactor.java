package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

public class refactor {

    public static class CloneRange {
        public int startLine;
        public int endLine;

        public CloneRange(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    public static class DetectedClone {
        public String id;
        public List<CloneRange> ranges;
        public String refactorType;
        public String reason;

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason) {
            this.id = id;
            this.ranges = ranges;
            this.refactorType = refactorType;
            this.reason = reason;
        }
    }

    public static class RefactorResult {
        public String status;
        public String file;
        public String newSource;
        public String message;

        public RefactorResult(String status, String file, String newSource, String message) {
            this.status = status;
            this.file = file;
            this.newSource = newSource;
            this.message = message;
        }
    }

    public RefactorResult refactorFile(String fileName, String fileSource, DetectedClone clone, String ragExamples, Function<String, String> llmCaller) {
        if (clone == null) {
            return fail(fileName, "DetectedClone is null");
        }
        if (clone.ranges == null || clone.ranges.isEmpty()) {
            return fail(fileName, "DetectedClone ranges are null or empty");
        }

        String prompt = buildRefactorPrompt(fileName, fileSource, clone, ragExamples);
        String rawOutput;
        try {
            rawOutput = llmCaller.apply(prompt);
        } catch (Exception e) {
            return fail(fileName, "LLM caller threw exception: " + e.getMessage());
        }
        if (rawOutput == null || rawOutput.isEmpty()) {
            return fail(fileName, "LLM caller returned empty output");
        }

        String newSource = extractJavaCodeBlock(rawOutput);
        if (newSource == null) {
            // Try JSON parsing fallback
            String jsonStr = extractJsonSubstring(rawOutput);
            if (jsonStr != null) {
                try {
                    JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
                    if (obj.has("new_source")) {
                        newSource = obj.get("new_source").getAsString();
                    } else if (obj.has("newSource")) {
                        newSource = obj.get("newSource").getAsString();
                    } else {
                        return fail(fileName, "JSON output missing 'new_source' or 'newSource' field");
                    }
                } catch (JsonSyntaxException e) {
                    return fail(fileName, "Failed to parse JSON output: " + e.getMessage());
                }
            } else {
                return fail(fileName, "Failed to extract Java code block or JSON from LLM output");
            }
        }

        if (newSource == null || newSource.isEmpty()) {
            return fail(fileName, "Extracted new source is empty");
        }

        return new RefactorResult("refactored", fileName, newSource, "Refactoring successful");
    }

    private String buildRefactorPrompt(String fileName, String fileSource, DetectedClone clone, String ragExamples) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Java refactor agent.\n");
        sb.append("You have a single file to modify: ").append(fileName).append("\n");
        sb.append("The file source is below:\n");
        sb.append("```\n").append(fileSource).append("\n```\n\n");

        sb.append("Detected clone id: ").append(clone.id).append("\n");
        sb.append("Clone ranges (1-based line numbers):\n");
        for (CloneRange range : clone.ranges) {
            sb.append("- from line ").append(range.startLine).append(" to line ").append(range.endLine).append("\n");
        }
        String refactorType = (clone.refactorType == null || clone.refactorType.trim().isEmpty()) ? "Extract Method" : clone.refactorType.trim();
        sb.append("Suggested refactor type: ").append(refactorType).append("\n");
        if (clone.reason != null && !clone.reason.trim().isEmpty()) {
            sb.append("Reason: ").append(clone.reason).append("\n");
        }

        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
            sb.append("\nHere are some few-shot RAG examples to guide you:\n");
            sb.append(ragExamples).append("\n");
        }

        sb.append("\nInstructions:\n");
        sb.append("- Restrict modifications only to this file.\n");
        sb.append("- Preserve package and import statements exactly.\n");
        sb.append("- Keep public API signatures unchanged where possible.\n");
        sb.append("- Minimize edits outside the clone regions.\n");
        sb.append("- Output ONLY ONE Java code block with the full updated file if possible.\n");
        sb.append("- If you cannot output the full file, you may respond with a JSON object containing a 'new_source' field with the full updated source.\n");
        sb.append("- Do not include any explanations or text outside the code block or JSON.\n");

        return sb.toString();
    }

    private String extractJavaCodeBlock(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        int idx = -1;
        int start = -1;
        int end = -1;

        // Try to find ```java fenced code block
        idx = lower.indexOf("```java");
        if (idx >= 0) {
            start = idx + 7;
            end = raw.indexOf("```", start);
            if (end > start) {
                String code = raw.substring(start, end).trim();
                if (!code.isEmpty()) {
                    return code;
                }
            }
        }

        // Try to find any fenced code block ```
        idx = raw.indexOf("```");
        if (idx >= 0) {
            start = idx + 3;
            end = raw.indexOf("```", start);
            if (end > start) {
                String code = raw.substring(start, end).trim();
                if (!code.isEmpty()) {
                    return code;
                }
            }
        }

        return null;
    }

    private String extractJsonSubstring(String raw) {
        if (raw == null) return null;
        int first = raw.indexOf('{');
        int last = raw.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return raw.substring(first, last + 1).trim();
        }
        return null;
    }

    private RefactorResult fail(String fileName, String message) {
        return new RefactorResult("failed", fileName, "", message);
    }
}
