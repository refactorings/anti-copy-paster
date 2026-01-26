package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import org.jetbrains.research.anticopypaster.rag.RagService;

public class detection {

    // ---- RAG / few-shot settings for clone detection ----
    // Path is resolved relative to project root by RagService (it also supports classpath resources).
    private static final String CLONE_DB_PATH = "combined_clone_database_cleaned.csv";
    private static final int DETECTION_FEWSHOT_K = 8;
    private static final int DETECTION_MAX_CHARS = 400;

    public static class CloneRange {
        public int startLine;
        public int endLine;
    }

    public static class DetectedClone {
        public String id;
        public List<CloneRange> ranges;
        public String refactorType;
        public String reason;
        public String cloneCodeA;
        public String cloneCodeB;
    }

    public static class DetectionResult {
        public String status;
        public String file;
        public List<DetectedClone> clones;
    }

    /**
     * Backward-compatible entry point (no RAG few-shot injection).
     * Prefer using the Project-aware overload so RagService can load few-shot examples.
     */
    public DetectionResult detect(String fileName, String fileSource, String selectedSnippet, Function<String, String> llmCaller) {
        return detect(null, fileName, fileSource, selectedSnippet, llmCaller);
    }

    /**
     * Project-aware detection entry point.
     * When project is non-null, injects few-shot examples from CLONE_DB_PATH via RagService.
     */
    public DetectionResult detect(Project project, String fileName, String fileSource, String selectedSnippet, Function<String, String> llmCaller) {
        String prompt = buildDetectionPrompt(project, fileSource, selectedSnippet, fileName);
        // DEBUG: print detection prompt for inspection
        System.out.println("[DETECTION_PROMPT_START]");
        System.out.println(prompt);
        System.out.println("[DETECTION_PROMPT_END]");
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

    private String buildDetectionPrompt(Project project, String fileSource, String selectedSnippet, String fileName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert code clone detection assistant.\n");
        prompt.append("Analyze the following Java source code from a single file named '").append(fileName).append("'.\n");
        prompt.append("Your task is to detect meaningful code clones within this same file only.\n");
        prompt.append("Ignore trivial one-line repetitions and focus on substantial code duplication.\n");

        // Inject few-shot examples (RAG) when we have an IntelliJ Project context.
        // RagService will try classpath first, then fall back to project-relative filesystem paths.
        if (project != null) {
            try {
                String fewShot = RagService.buildDetectionPromptWithFewShot(
                        project,
                        CLONE_DB_PATH,
                        DETECTION_FEWSHOT_K,
                        DETECTION_MAX_CHARS
                );
                if (fewShot != null && !fewShot.isBlank()) {
                    prompt.append("\n");
                    prompt.append("=== Few-shot examples (from clone database) ===\n");
                    prompt.append(fewShot).append("\n");
                    prompt.append("=== End few-shot examples ===\n\n");
                }
            } catch (Throwable t) {
                // If RAG fails, continue with the base prompt.
            }
        }

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
        prompt.append("IMPORTANT: For each detected clone, you MUST include cloneCodeA and cloneCodeB as verbatim copies from the provided fileSource.\n");
        prompt.append("- Do NOT rewrite, reformat, rename variables, or add missing context. Copy the exact characters from fileSource.\n");
        prompt.append("- If you are not confident you can copy verbatim, set cloneCodeA/cloneCodeB to an empty string instead of guessing.\n\n");
        prompt.append("Output ONLY a valid JSON object with the following structure:\n");
        prompt.append("{\n");
        prompt.append("  \"status\": \"found_clones\" or \"no_clones\",\n");
        prompt.append("  \"file\": \"").append(fileName).append("\",\n");
        prompt.append("  \"clones\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"unique_clone_id\",\n");
        prompt.append("      \"ranges\": [ { \"startLine\": int, \"endLine\": int }, ... ],\n");
        prompt.append("      \"refactorType\": \"extracted_method\" or \"extracted_class\" or other string,\n");
        prompt.append("      \"reason\": \"explanation of why this clone was detected\",\n");
        prompt.append("      \"cloneCodeA\": \"EXACT code snippet A copied verbatim from the file (no edits). If you cannot copy exactly, return an empty string\",\n");
        prompt.append("      \"cloneCodeB\": \"EXACT code snippet B copied verbatim from the file (no edits). If you cannot copy exactly, return an empty string\"\n");
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
            } else {
                for (DetectedClone c : result.clones) {
                    if (c == null) continue;
                    if (c.cloneCodeA == null) c.cloneCodeA = "";
                    if (c.cloneCodeB == null) c.cloneCodeB = "";
                }
            }
            return result;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
