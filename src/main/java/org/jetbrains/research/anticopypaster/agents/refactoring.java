package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.rag.RagService;

public class refactoring {

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

            // Persist full LLM output to a temp file so it won't be lost in the console scroll.
            String dumpPath = "";
            try {
                java.io.File f = java.io.File.createTempFile("acp_llm_output_", ".txt");
                try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                    w.write(rawOutput == null ? "" : rawOutput);
                }
                dumpPath = f.getAbsolutePath();
            } catch (Throwable ignored) {
            }

            System.out.println("========== [LLM_OUTPUT_SAVED] " + dumpPath + " ==========");

            // Still print a short header+tail preview for quick sanity checks.
            try {
                if (rawOutput != null) {
                    int headLen = Math.min(600, rawOutput.length());
                    int tailLen = Math.min(600, rawOutput.length());
                    System.out.println("[LLM_OUTPUT_HEAD]\n" + rawOutput.substring(0, headLen));
                    System.out.println("[LLM_OUTPUT_TAIL]\n" + rawOutput.substring(Math.max(0, rawOutput.length() - tailLen)));
                }
            } catch (Throwable ignored) {
            }
        } catch (Exception e) {
            return fail(fileName, "LLM caller threw exception: " + e.getMessage());
        }

        // DEBUG: log raw LLM output length and a short preview
        try {
            System.out.println("[DEBUG][REFACTOR] raw LLM output length = " + rawOutput.length());
            int previewLen = Math.min(800, rawOutput.length());
            System.out.println("[DEBUG][REFACTOR] raw LLM output preview:\n" +
                    rawOutput.substring(0, previewLen));
        } catch (Throwable t) {
            System.out.println("[DEBUG][REFACTOR] failed to log raw LLM output: " + t.getMessage());
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

    /**
     * Improved refactor prompt (combined ideas from:
     * - RefAgent (structured task, strong constraints, clear output contract)
     * - ChatGPT for Code Refactoring paper (role + working set + context + steps + output format)
     */
    private String buildRefactorPrompt(String fileName, String fileSource, DetectedClone clone, String ragExamples) {
        StringBuilder sb = new StringBuilder();

        // ===== Refactoring Agent prompt (paper listing: Refactoring Agent prompt with RAG context) =====
        sb.append("You are a Java software engineer.\n\n");
        sb.append("Your task is to refactor the given Java file to remove cloned code.\n");
        sb.append("You must apply Extract Method refactoring only.\n\n");

        sb.append("Input:\n");
        sb.append("- A Java source file.\n");
        sb.append("- Detected cloned code snippets copied verbatim from the file.\n\n");

        sb.append("Java source file (FULL FILE):\n");
        sb.append("```\n").append(fileSource).append("\n```\n\n");

        sb.append("Detected cloned code snippets (copied verbatim):\n");
        if (clone != null) {
            if (clone.cloneCode != null && !clone.cloneCode.trim().isEmpty()) {
                sb.append("```\n").append(clone.cloneCode).append("\n```\n\n");
            }
            if (clone.ranges != null && !clone.ranges.isEmpty()) {
                sb.append("(Approximate line ranges, secondary hint)\n");
                for (CloneRange range : clone.ranges) {
                    sb.append("- from line ").append(range.startLine)
                      .append(" to line ").append(range.endLine).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("Retrieved refactoring examples (for guidance only):\n");
        sb.append("Each example includes a BEFORE code fragment, an AFTER code fragment,\n");
        sb.append("and a short note describing the refactoring intent.\n");
        sb.append("Use these examples only as guidance.\n");
        sb.append("Do not copy code directly from them.\n");
        sb.append("Always follow the Extract Method pattern.\n\n");

//        // RAG block (keep placeholder even if empty so the prompt matches the paper listing)
//        sb.append("[RAG_EXAMPLES]\n");
//        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
//            sb.append(ragExamples.trim()).append("\n");
//        }
//        sb.append("\n");

        sb.append("Instructions:\n");
        sb.append("- Use Extract Method to remove clones.\n");
        sb.append("- Do not apply any other refactoring type.\n");
        sb.append("- Do not rename existing classes, methods, or variables unless required by Extract Method.\n");
        sb.append("- Do not modify package or import declarations.\n");
        sb.append("- Restrict all changes to this file only.\n");
        sb.append("- Preserve the original program behavior.\n");
        sb.append("- Keep the refactoring minimal and focused.\n\n");

        sb.append("Output:\n");
        sb.append("- Return the full updated Java file.\n");
        sb.append("- Output exactly one Java code block.\n");
        sb.append("- Do not include any explanation or text outside the code block.\n");
        sb.append("\n");

        // ===== Role =====
//        sb.append("You are an expert Java refactoring agent.\n");
//        sb.append("You specialize in clone removal via Extract Method and improving software quality ");
//        sb.append("(readability, maintainability, cohesion, and low coupling).\n");
//        sb.append("Follow the constraints and output format EXACTLY.\n");
//        sb.append("If anything is unclear, ask at most ONE concise clarification question; otherwise proceed.\n\n");
//
//        // ===== Working Set =====
//        sb.append("=== WORKING SET (Single File Only) ===\n");
//        sb.append("File name: ").append(fileName).append("\n");
//        sb.append("You are given the FULL file source below. You may ONLY modify this file.\n");
//        sb.append("```\n").append(fileSource).append("\n```\n\n");
//
//        // ===== Context =====
//        sb.append("=== CONTEXT ===\n");
//        sb.append("- Language: Java\n");
//        sb.append("- Goal: Remove duplicated code (clone) with minimal behavior change.\n");
//        sb.append("- Constraints: Preserve public API, preserve package/imports, keep semantics.\n\n");
//
//        // ===== Clone Context =====
//        sb.append("=== CLONE CONTEXT ===\n");
//        sb.append("Clone ID: ").append(clone.id).append("\n");
//        if (clone.cloneCode != null && !clone.cloneCode.trim().isEmpty()) {
//            sb.append("Representative clone snippet (PRIMARY ground truth):\n");
//            sb.append("```\n").append(clone.cloneCode).append("\n```\n\n");
//        }
//        if (clone.ranges != null && !clone.ranges.isEmpty()) {
//            sb.append("Approximate clone line ranges (SECONDARY, may be imprecise):\n");
//            for (CloneRange range : clone.ranges) {
//                sb.append("- from line ").append(range.startLine)
//                  .append(" to line ").append(range.endLine).append("\n");
//            }
//            sb.append("\n");
//        }
//        if (clone.reason != null && !clone.reason.trim().isEmpty()) {
//            sb.append("Reason for refactoring: ").append(clone.reason).append("\n\n");
//        }

//        // ===== Optional RAG few-shot guidance =====
//        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
//            sb.append("=== FEW-SHOT GUIDANCE (RAG) ===\n");
//            sb.append("Use these examples only as guidance for structure and style. Do NOT copy verbatim.\n");
//            sb.append(ragExamples).append("\n\n");
//        }

//        // ===== Refactoring Task (pattern + intent) =====
//        sb.append("=== REFACTORING TASK ===\n");
//        sb.append("Refactoring Pattern: Extract Method (ONLY)\n");
//        sb.append("Intent: To improve maintainability and reduce duplication while preserving behavior.\n");
//        sb.append("Requirements:\n");
//        sb.append("1) Identify duplicated logic corresponding to the representative clone snippet.\n");
//        sb.append("2) Extract the duplicated logic into one private helper method.\n");
//        sb.append("3) Replace ALL duplicated occurrences with calls to the extracted method.\n");
//        sb.append("4) Choose the smallest safe extraction boundary (minimal code movement).\n");
//        sb.append("5) Ensure the result compiles as valid Java.\n\n");
//
//        // ===== Steps (structured, but must not be printed) =====
//        sb.append("=== STEPS TO FOLLOW (DO NOT OUTPUT THESE STEPS) ===\n");
//        sb.append("Step 1: Locate all occurrences of the duplicated logic (use snippet first, ranges second).\n");
//        sb.append("Step 2: Create private helper method(s) with clear names.\n");
//        sb.append("Step 3: Replace duplicated blocks with helper call.\n");
//        sb.append("Step 4: Re-check for compilation issues (imports, generics, visibility, checked exceptions).\n\n");
//
//        // ===== Strict Constraints (hard rules) =====
//        sb.append("=== STRICT CONSTRAINTS (HARD RULES) ===\n");
//        sb.append("- Modify ONLY this file. Do NOT reference or require changes in other files.\n");
//        sb.append("- Preserve the package line and ALL import statements EXACTLY (do not add/remove/reorder).\n");
//        sb.append("- Do NOT change public/protected method signatures or class public API.\n");
//        sb.append("- Do NOT introduce new classes or new files.\n");
//        sb.append("- Do NOT change external behavior; keep outputs and side effects the same.\n");
//        sb.append("- Minimize edits outside the clone regions.\n");
//        sb.append("- Do NOT add explanatory prose outside code.\n\n");
//
//        // ===== Output Format (compatible with your extractor) =====
//        sb.append("=== OUTPUT FORMAT ===\n");
//        sb.append("- Output ONLY ONE Java code block containing the full updated file.\n");
//        sb.append("- Do NOT output JSON.\n");
//        sb.append("- Do NOT output multiple alternatives.\n");
//        sb.append("- Do NOT output any text before or after the code block.\n");

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
            } else {
                // Some models omit the closing fence. If we see a Java-looking file, take the rest.
                String tail = raw.substring(start).trim();
                if (!tail.isEmpty() && (tail.contains("package ") || tail.contains("class ") || tail.contains("interface "))) {
                    return tail;
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
            } else {
                // Missing closing fence: take the rest if it looks like Java source.
                String tail = raw.substring(start).trim();
                if (!tail.isEmpty() && (tail.contains("package ") || tail.contains("class ") || tail.contains("interface "))) {
                    return tail;
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
