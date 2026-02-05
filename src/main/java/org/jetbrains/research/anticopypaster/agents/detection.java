package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import org.jetbrains.research.anticopypaster.rag.RagService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class detection {

    // ---- RAG / few-shot settings for clone detection ----
    // Path is resolved relative to project root by RagService (it also supports classpath resources).
    private static final String CLONE_DB_PATH = "combined_clone_database_cleaned.csv";
    private static final int DETECTION_FEWSHOT_K = 8;
    private static final int DETECTION_MAX_CHARS = 400;

    // ---- Snippet-centered detection helpers ----
    private static int[] findSnippetLineRange(String fileSource, String selectedSnippet) {
        if (fileSource == null || selectedSnippet == null) return null;
        if (selectedSnippet.isEmpty()) return null;

        // Normalize newlines (CRLF/CR -> LF) to reduce false negatives.
        String fs = fileSource.replace("\r\n", "\n").replace("\r", "\n");
        String sn = selectedSnippet.replace("\r\n", "\n").replace("\r", "\n");

        // 1) Exact match (fast path)
        int idx = fs.indexOf(sn);
        if (idx >= 0) {
            return toLineRangeByIndex(fs, idx, idx + sn.length());
        }

        // 2) Trimmed-line match (ignore indentation differences; ignore empty snippet lines)
        String[] snLines = sn.split("\n", -1);
        List<String> need = new ArrayList<>();
        for (String l : snLines) {
            if (l == null) continue;
            String t = l.trim();
            if (!t.isEmpty()) need.add(t);
        }
        if (need.isEmpty()) return null;

        String[] fsLines = fs.split("\n", -1);
        for (int start = 0; start < fsLines.length; start++) {
            int iFile = start;
            int iNeed = 0;

            // Skip leading blank lines in file for a match start
            while (iFile < fsLines.length && (fsLines[iFile] == null || fsLines[iFile].trim().isEmpty())) iFile++;

            int firstMatched = -1;
            int lastMatched = -1;

            while (iFile < fsLines.length && iNeed < need.size()) {
                String f = fsLines[iFile] == null ? "" : fsLines[iFile].trim();
                if (f.isEmpty()) {
                    // Allow blank lines in file during matching
                    iFile++;
                    continue;
                }
                String want = need.get(iNeed);
                if (f.equals(want)) {
                    if (firstMatched < 0) firstMatched = iFile;
                    lastMatched = iFile;
                    iNeed++;
                    iFile++;
                } else {
                    break; // mismatch: abandon this start
                }
            }

            if (iNeed == need.size() && firstMatched >= 0 && lastMatched >= firstMatched) {
                return new int[]{firstMatched + 1, lastMatched + 1}; // 1-based inclusive
            }
        }

        // 3) Whitespace-collapsed match (last resort): collapse all whitespace to single spaces
        // This helps when snippet lines wrap differently but token order is identical.
        String fsW = collapseWhitespace(fs);
        String snW = collapseWhitespace(sn);
        int idxW = fsW.indexOf(snW);
        if (idxW >= 0) {
            // We cannot reliably map idxW back to original lines; give up on grounding.
            return null;
        }

        return null;
    }

    private static int[] toLineRangeByIndex(String text, int startIdx, int endIdxExclusive) {
        int startLine = 1;
        for (int i = 0; i < startIdx && i < text.length(); i++) {
            if (text.charAt(i) == '\n') startLine++;
        }
        int endLine = startLine;
        for (int i = startIdx; i < endIdxExclusive && i < text.length(); i++) {
            if (text.charAt(i) == '\n') endLine++;
        }
        return new int[]{startLine, endLine};
    }

    private static String collapseWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean overlaps(CloneRange r, int snippetStart, int snippetEnd) {
        if (r == null) return false;
        return r.startLine <= snippetEnd && r.endLine >= snippetStart;
    }

    private static boolean cloneOverlapsSnippet(DetectedClone c, int snippetStart, int snippetEnd) {
        if (c == null || c.ranges == null) return false;
        for (CloneRange r : c.ranges) {
            if (overlaps(r, snippetStart, snippetEnd)) return true;
        }
        return false;
    }

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
        // Snippet-centered: only detect clones of the user's pasted snippet inside this file.
        if (selectedSnippet == null || selectedSnippet.isEmpty()) {
            DetectionResult noClonesResult = new DetectionResult();
            noClonesResult.status = "no_clones";
            noClonesResult.file = fileName;
            noClonesResult.clones = Collections.emptyList();
            return noClonesResult;
        }

        int[] snippetRange = findSnippetLineRange(fileSource, selectedSnippet);
        System.out.println("Snippet range: " + Arrays.toString(snippetRange));

        int snippetStartLine = (snippetRange == null) ? -1 : snippetRange[0];
        int snippetEndLine = (snippetRange == null) ? -1 : snippetRange[1];

        String prompt = buildDetectionPrompt(project, fileSource, selectedSnippet, fileName, snippetStartLine, snippetEndLine);
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

        // Keep only clones that overlap the pasted snippet's location in the file (when grounded).
        if (snippetRange != null) {
            int snippetStart = snippetRange[0];
            int snippetEnd = snippetRange[1];
            List<DetectedClone> filtered = new ArrayList<>();
            for (DetectedClone c : result.clones) {
                if (cloneOverlapsSnippet(c, snippetStart, snippetEnd)) {
                    filtered.add(c);
                }
            }

            if (filtered.isEmpty()) {
                DetectionResult noClonesResult = new DetectionResult();
                noClonesResult.status = "no_clones";
                noClonesResult.file = fileName;
                noClonesResult.clones = Collections.emptyList();
                return noClonesResult;
            }

            result.status = "found_clones";
            result.file = fileName;
            result.clones = filtered;
            return result;
        }

        // If snippet location is unknown, return the LLM result as-is (best-effort).
        result.status = "found_clones";
        result.file = fileName;
        return result;
    }

    private String buildDetectionPrompt(Project project, String fileSource, String selectedSnippet, String fileName, int snippetStartLine, int snippetEndLine) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert code clone detection assistant.\n");
        prompt.append("Analyze the following Java source code from a single file named '").append(fileName).append("'.\n");
        prompt.append("Your task is to check whether the user's pasted snippet has clones elsewhere within this same file only.\n");
        prompt.append("You MUST focus on the pasted snippet and find other code regions in the file that are clones of it.\n");
        prompt.append("Do NOT report clones that do not involve the pasted snippet.\n");
        prompt.append("Ignore trivial one-line repetitions and focus on substantial clones.\n");
        if (snippetStartLine > 0 && snippetEndLine > 0) {
            prompt.append("The pasted snippet is located in this file around lines ")
                  .append(snippetStartLine).append(" to ").append(snippetEndLine).append(".\n");
        } else {
            prompt.append("The pasted snippet's exact line location is UNKNOWN (whitespace/format differences may prevent exact matching). ")
                  .append("You must locate the snippet occurrence in the file yourself.\n");
        }

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
            prompt.append("IMPORTANT CONSTRAINTS:\n");
            prompt.append("1) Every clone you report MUST include the pasted snippet as one of the clone regions.\n");
            if (snippetStartLine > 0 && snippetEndLine > 0) {
                prompt.append("2) In \"ranges\", include EXACTLY two ranges: the first range MUST be the pasted snippet's range (lines ")
                      .append(snippetStartLine).append(" to ").append(snippetEndLine).append("), and the second range MUST be the matching clone region elsewhere in the file.\n");
            } else {
                prompt.append("2) In \"ranges\", include EXACTLY two ranges: one range MUST correspond to the pasted snippet occurrence in the file, and the other range MUST be the matching clone region elsewhere in the file.\n");
            }
            prompt.append("3) Set cloneCodeA to be EXACTLY the pasted snippet text (verbatim, no edits). Set cloneCodeB to be the matching region copied verbatim from fileSource.\n");
            prompt.append("4) If you cannot find any clone of the pasted snippet in this file, output status \"no_clones\" with an empty clones list.\n\n");
        }
        prompt.append("IMPORTANT: For each detected clone, you MUST include cloneCodeA and cloneCodeB as verbatim copies from the provided fileSource.\n");
        prompt.append("- Do NOT rewrite, reformat, rename variables, or add missing context. Copy the exact characters from fileSource.\n");
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
