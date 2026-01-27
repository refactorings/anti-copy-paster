package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.rag.RagService;

public class refactor {

    // RAG defaults (can be overridden by passing a pre-built ragExamples string)
    private static final String DEFAULT_REFACTOR_DB_PATH = "refactor_database.csv";
    private static final int DEFAULT_RAG_TOP_K = 2;
    private static final int DEFAULT_RAG_MAX_CHARS = 700;

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

        /**
         * Representative clone code/snippet (preferred over line ranges; used as the RAG query).
         * This should be provided by the detection agent.
         */
        public String cloneCode;

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason) {
            this(id, ranges, refactorType, reason, "");
        }

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason, String cloneCode) {
            this.id = id;
            this.ranges = ranges;
            this.refactorType = refactorType;
            this.reason = reason;
            this.cloneCode = (cloneCode == null ? "" : cloneCode);
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
        // Backward-compatible entrypoint when Project is not available.
        return refactorFile(null, fileName, fileSource, clone, ragExamples, llmCaller);
    }

    /**
     * Refactor with optional in-agent RAG.
     * If `ragExamples` is empty, we will build a few-shot bundle using RagService and `clone.cloneCode` as the query.
     * IMPORTANT: We do NOT use line numbers/ranges as the RAG query.
     */
    public RefactorResult refactorFile(Project project, String fileName, String fileSource, DetectedClone clone, String ragExamples, Function<String, String> llmCaller) {
        if (clone == null) {
            return fail(fileName, "DetectedClone is null");
        }

        // If caller didn't provide RAG examples, build them here using clone code as the query.
        String rag = (ragExamples == null ? "" : ragExamples.trim());
        if (rag.isEmpty()) {
            String query = (clone.cloneCode == null ? "" : clone.cloneCode.trim());
            // As a last resort (if detection didn't provide clone code), use a truncated file source.
            if (query.isEmpty()) {
                query = safeTruncate(fileSource, DEFAULT_RAG_MAX_CHARS);
            }
            try {
                rag = RagService.buildRefactorRagGuidance(project, DEFAULT_REFACTOR_DB_PATH, query, DEFAULT_RAG_TOP_K, DEFAULT_RAG_MAX_CHARS);
            } catch (Throwable t) {
                // RAG is optional; continue without it.
                rag = "";
            }
        }

        String prompt = buildRefactorPrompt(fileName, fileSource, clone, rag);
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

        // Prefer clone code for grounding (line ranges are optional and may be unstable).
        if (clone.cloneCode != null && !clone.cloneCode.trim().isEmpty()) {
            sb.append("Representative clone code (use this as the main target to de-duplicate):\n");
            sb.append("```\n").append(clone.cloneCode).append("\n```\n\n");
        }

        if (clone.ranges != null && !clone.ranges.isEmpty()) {
            sb.append("Approximate clone ranges (may be imprecise):\n");
            for (CloneRange range : clone.ranges) {
                sb.append("- from line ").append(range.startLine).append(" to line ").append(range.endLine).append("\n");
            }
        }

        // Force a single refactoring strategy to keep the workflow stable and reproducible.
        // We only allow Extract Method (and helper method extraction) as the clone-removal technique.
        sb.append("Refactor type: Extract Method\n");
        if (clone.reason != null && !clone.reason.trim().isEmpty()) {
            sb.append("Reason: ").append(clone.reason).append("\n");
        }

        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
            sb.append("\nHere are some few-shot RAG examples to guide you:\n");
            sb.append(ragExamples).append("\n");
        }

        sb.append("\nInstructions:\n");
        sb.append("- Use ONLY Extract Method (and creating private helper methods) to remove clones.\n");
        sb.append("- Do NOT use any other refactoring type (e.g., Rename, Move Method, Introduce Parameter Object, etc.).\n");
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

    private static String safeTruncate(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (maxChars <= 0) return t;
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars) + "\n...<truncated>...";
    }

    private RefactorResult fail(String fileName, String message) {
        return new RefactorResult("failed", fileName, "", message);
    }
}
