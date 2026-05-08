package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.lang.reflect.Type;
import java.util.*;
import java.util.Locale;
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
    private static final List<PanelistSpec> PANELIST_SPECS = List.of(
            new PanelistSpec("P1", "Detection Panelist 1"),
            new PanelistSpec("P2", "Detection Panelist 2"),
            new PanelistSpec("P3", "Detection Panelist 3")
    );

    private static final class PanelistSpec {
        final String id;
        final String title;

        private PanelistSpec(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static final class PanelistResult {
        final String panelistId;
        final boolean parsed;
        final DetectionResult detectionResult;
        final String rawResponse;
        final String error;

        private PanelistResult(String panelistId,
                               boolean parsed,
                               DetectionResult detectionResult,
                               String rawResponse,
                               String error) {
            this.panelistId = safe(panelistId);
            this.parsed = parsed;
            this.detectionResult = detectionResult;
            this.rawResponse = safe(rawResponse);
            this.error = safe(error);
        }
    }

    public void saveAsNiCadXml(DetectionResult result, String filePath, Path outputPath) throws IOException {
        if (outputPath == null) {
            throw new IllegalArgumentException("outputPath must not be null");
        }
        String xml = toNiCadXml(result, filePath);
        Files.writeString(outputPath, xml, StandardCharsets.UTF_8);
    }

    public String toNiCadXml(DetectionResult result, String filePath) {
        String normalizedFilePath = normalizeNiCadSourcePath(filePath);
        StringBuilder xml = new StringBuilder();
        xml.append("<clones>\n");
        xml.append("<systeminfo processor=\"nicad3\" system=\"_\" granularity=\"blocks-consistent\" threshold=\"20%\" minlines=\"5\" maxlines=\"2500\"/>\n");

        List<DetectedClone> clones = (result == null || result.clones == null) ? Collections.emptyList() : result.clones;
        int npairs = 0;
        for (DetectedClone clone : clones) {
            if (clone != null && clone.ranges != null && clone.ranges.size() >= 2) {
                npairs += clone.ranges.size() * (clone.ranges.size() - 1) / 2;
            }
        }
        xml.append("<cloneinfo npcs=\"").append(countCloneSources(clones)).append("\" npairs=\"").append(npairs).append("\"/>\n");
        xml.append("<runinfo ncompares=\"0\" cputime=\"0\"/>\n");
        xml.append("<classinfo nclasses=\"").append(countCloneClasses(clones)).append("\"/>\n\n");

        int classId = 1;
        int pcid = 1;
        for (DetectedClone clone : clones) {
            if (clone == null || clone.ranges == null || clone.ranges.size() < 2) {
                continue;
            }

            int nlines = computeCloneNLines(clone);
            int similarity = estimateSimilarity(clone);
            xml.append("<class classid=\"").append(classId++)
                    .append("\" nclones=\"").append(clone.ranges.size())
                    .append("\" nlines=\"").append(nlines)
                    .append("\" similarity=\"").append(similarity)
                    .append("\">\n");

            for (CloneRange range : clone.ranges) {
                if (range == null) {
                    continue;
                }
                xml.append("<source file=\"").append(escapeXml(normalizedFilePath))
                        .append("\" startline=\"").append(range.startLine)
                        .append("\" endline=\"").append(range.endLine)
                        .append("\" pcid=\"").append(pcid++)
                        .append("\"></source>\n");
            }

            xml.append("</class>\n\n");
        }

        xml.append("</clones>\n");
        return xml.toString();
    }

    private String normalizeNiCadSourcePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "unknown";
        }
        try {
            return Path.of(filePath).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return filePath;
        }
    }

    private int countCloneSources(List<DetectedClone> clones) {
        if (clones == null || clones.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (DetectedClone clone : clones) {
            if (clone != null && clone.ranges != null) {
                count += clone.ranges.size();
            }
        }
        return count;
    }

    private int countCloneClasses(List<DetectedClone> clones) {
        if (clones == null || clones.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (DetectedClone clone : clones) {
            if (clone != null && clone.ranges != null && clone.ranges.size() >= 2) {
                count++;
            }
        }
        return count;
    }

    private int computeCloneNLines(DetectedClone clone) {
        if (clone == null || clone.ranges == null || clone.ranges.isEmpty()) {
            return 0;
        }
        CloneRange first = clone.ranges.get(0);
        if (first == null) {
            return 0;
        }
        return Math.max(0, first.endLine - first.startLine + 1);
    }

    private int estimateSimilarity(DetectedClone clone) {
        String a = clone == null || clone.cloneCodeA == null ? "" : clone.cloneCodeA;
        String b = clone == null || clone.cloneCodeB == null ? "" : clone.cloneCodeB;
        if (a.isBlank() || b.isBlank()) {
            return 100;
        }

        String na = collapseWhitespace(a);
        String nb = collapseWhitespace(b);
        if (na.isEmpty() && nb.isEmpty()) {
            return 100;
        }

        int maxLen = Math.max(na.length(), nb.length());
        if (maxLen == 0) {
            return 100;
        }

        int distance = levenshteinDistance(na, nb);
        int similarity = (int) Math.round((1.0 - ((double) distance / maxLen)) * 100.0);
        return Math.max(0, Math.min(100, similarity));
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

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
        public List<String> cloneCodes;
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
            return buildNoClonesResult(fileName);
        }

        int[] snippetRange = findSnippetLineRange(fileSource, selectedSnippet);
        System.out.println("Snippet range: " + Arrays.toString(snippetRange));

        int snippetStartLine = (snippetRange == null) ? -1 : snippetRange[0];
        int snippetEndLine = (snippetRange == null) ? -1 : snippetRange[1];

        List<PanelistResult> panelistResults = new ArrayList<>();
        for (PanelistSpec spec : PANELIST_SPECS) {
            String prompt = buildPanelistPrompt(spec, project, fileSource, selectedSnippet, fileName, snippetStartLine, snippetEndLine);
            String rawOutput = safeLlmCall(llmCaller, prompt);
            DetectionResult parsed = parseDetectionResult(rawOutput, fileName);
            DetectionResult normalized = normalizeDetectionResult(parsed, fileName, fileSource, selectedSnippet, snippetRange);
            panelistResults.add(new PanelistResult(
                    spec.id,
                    parsed != null,
                    normalized,
                    rawOutput,
                    parsed == null ? "Could not parse panelist JSON" : ""
            ));
        }

        String curatorPrompt = buildCuratorPrompt(fileName, fileSource, selectedSnippet, snippetStartLine, snippetEndLine, panelistResults);
        String curatorRawOutput = safeLlmCall(llmCaller, curatorPrompt);
        DetectionResult curatorParsed = parseDetectionResult(curatorRawOutput, fileName);
        DetectionResult curatorResult = normalizeDetectionResult(curatorParsed, fileName, fileSource, selectedSnippet, snippetRange);
        if (curatorParsed != null) {
            return curatorResult;
        }

        return mergePanelistResults(panelistResults, fileName);
    }

    private String buildPanelistPrompt(PanelistSpec spec,
                                       Project project,
                                       String fileSource,
                                       String selectedSnippet,
                                       String fileName,
                                       int snippetStartLine,
                                       int snippetEndLine) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are ").append(spec.title).append(" (").append(spec.id).append(").\n");
        prompt.append("You are one of three independent clone-detection panelists.\n");
        prompt.append("All panelists review the same file and pasted snippet independently.\n");
        prompt.append("Return ONLY one JSON object and no extra text.\n\n");
        appendDetectionTask(prompt, project, fileSource, selectedSnippet, fileName, snippetStartLine, snippetEndLine, true);
        return prompt.toString();
    }

    private String buildCuratorPrompt(String fileName,
                                      String fileSource,
                                      String selectedSnippet,
                                      int snippetStartLine,
                                      int snippetEndLine,
                                      List<PanelistResult> panelistResults) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the detection curator.\n");
        prompt.append("You must review three panelist outputs and produce the final clone detection result.\n");
        prompt.append("Apply a majority vote across the three panelists.\n");
        prompt.append("- If 2 or more panelists provide credible evidence of a clone, return found_clones.\n");
        prompt.append("- If 2 or more panelists report no clones or provide weak/unparsed evidence, return no_clones.\n");
        prompt.append("- If exactly one panelist finds a clone, only return found_clones if that panelist's evidence is exceptionally strong: precise line ranges and clear structural similarity. Otherwise return no_clones.\n");
        prompt.append("Merge duplicate or overlapping clone groups when they clearly refer to the same pasted-snippet clone class.\n");
        prompt.append("Return ONLY one JSON object and no extra text.\n\n");

        appendDetectionTask(prompt, null, fileSource, selectedSnippet, fileName, snippetStartLine, snippetEndLine, false);

        prompt.append("=== PANELIST OUTPUTS ===\n");
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        for (PanelistResult panelistResult : panelistResults) {
            prompt.append("[").append(panelistResult.panelistId).append("]\n");
            JsonObject obj = new JsonObject();
            obj.addProperty("parsed", panelistResult.parsed);
            obj.addProperty("error", panelistResult.error);
            obj.add("result", gson.toJsonTree(panelistResult.detectionResult == null
                    ? buildNoClonesResult(fileName)
                    : panelistResult.detectionResult));
            prompt.append(gson.toJson(obj)).append("\n\n");
        }

        prompt.append("Rules:\n");
        prompt.append("- The final JSON must follow the exact schema below.\n");
        prompt.append("- Keep only clone groups that involve the pasted snippet occurrence in this file.\n");
        prompt.append("- Preserve all known matching ranges for a clone group when the evidence is strong enough.\n");
        prompt.append("- Use no_clones with an empty clones list when the panelists do not provide convincing clone evidence.\n\n");
        appendDetectionOutputSchema(prompt, fileName);
        return prompt.toString();
    }

    private void appendDetectionTask(StringBuilder prompt,
                                     Project project,
                                     String fileSource,
                                     String selectedSnippet,
                                     String fileName,
                                     int snippetStartLine,
                                     int snippetEndLine,
                                     boolean includeFewShot) {
        prompt.append("Analyze the following Java source code from a single file named '").append(fileName).append("'.\n");
        prompt.append("Your task is to exhaustively find every non-trivial clone of the user's pasted snippet within this same file only.\n");
        prompt.append("You MUST focus on the pasted snippet and enumerate all other code regions in the file that are clones of it.\n");
        prompt.append("Do NOT report clones that do not involve the pasted snippet.\n");
        prompt.append("Ignore only truly trivial single-line repetitions.\n");
        prompt.append("Do not reject short, method-level, symmetric, or small-substitution clones merely because only a few identifiers, literals, casts, or method names differ.\n");
        prompt.append("Do not stop after finding the first match. Keep searching until you have listed all relevant matches involving the pasted snippet.\n");
        if (snippetStartLine > 0 && snippetEndLine > 0) {
            prompt.append("The pasted snippet is located in this file around lines ")
                    .append(snippetStartLine).append(" to ").append(snippetEndLine).append(".\n");
        } else {
            prompt.append("The pasted snippet's exact line location is UNKNOWN (whitespace/format differences may prevent exact matching). ")
                    .append("You must locate the snippet occurrence in the file yourself.\n");
        }

        if (includeFewShot && project != null) {
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
            prompt.append("1) Every clone you report MUST involve the pasted snippet occurrence in this file.\n");
            prompt.append("2) Return ALL non-trivial matching occurrences related to the pasted snippet, not just one example match.\n");
            if (snippetStartLine > 0 && snippetEndLine > 0) {
                prompt.append("3) In \"ranges\", include every matching range for that clone class in this file. One range SHOULD overlap the pasted snippet's approximate location (around lines ")
                        .append(snippetStartLine).append(" to ").append(snippetEndLine).append("), and all other matching ranges elsewhere in the file SHOULD also be included.\n");
            } else {
                prompt.append("3) In \"ranges\", include every matching range for that clone class in this file. One range SHOULD correspond to the pasted snippet occurrence in the file, and all other matching ranges elsewhere in the file SHOULD also be included.\n");
            }
            prompt.append("4) If there are multiple distinct clone classes involving the pasted snippet, return multiple objects in \"clones\".\n");
            prompt.append("5) Focus on structural similarity, even when a few identifiers, literals, casts, method names, or API calls differ.\n");
            prompt.append("6) Return cloneCodes as an array of verbatim code snippets aligned with ranges. cloneCodes[i] must correspond to ranges[i].\n");
            prompt.append("7) Prefer returning the pasted snippet occurrence as cloneCodeA and one representative matching region as cloneCodeB, but still include ALL occurrence snippets in cloneCodes.\n");
            prompt.append("8) cloneCodes entries do not need to be textually identical. Small substitutions still count as a valid clone.\n");
            prompt.append("9) If no reasonably similar code is found, output status \"no_clones\" with an empty clones list.\n\n");
        }
        prompt.append("IMPORTANT: For each detected clone, include cloneCodes for ALL listed ranges as verbatim copies from the provided fileSource when possible.\n");
        prompt.append("- startLine/endLine are 1-based inclusive line numbers in fileSource, and each range must tightly cover its matching cloneCodes entry.\n");
        prompt.append("- If a cloneCodes entry and its line numbers disagree, prioritize making cloneCodes verbatim from fileSource and make the line numbers match that exact text.\n");
        prompt.append("- Do NOT rewrite, reformat, rename variables, or add missing context when copying cloneCodes from fileSource.\n");
        prompt.append("- A clone may still be valid even when the two regions differ slightly in identifiers, literals, casts, or one method call.\n");
        prompt.append("- A single clone object may have more than two ranges, and if so you should include all of them.\n");
        prompt.append("- If you can identify three or more matching occurrences of the same pasted snippet clone class in this file, all of them must appear in \"ranges\".\n\n");
        appendDetectionOutputSchema(prompt, fileName);
    }

    private void appendDetectionOutputSchema(StringBuilder prompt, String fileName) {
        prompt.append("Output ONLY a valid JSON object with the following structure:\n");
        prompt.append("{\n");
        prompt.append("  \"status\": \"found_clones\" or \"no_clones\",\n");
        prompt.append("  \"file\": \"").append(fileName).append("\",\n");
        prompt.append("  \"clones\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"unique_clone_id\",\n");
        prompt.append("      \"ranges\": [ { \"startLine\": int, \"endLine\": int }, ... all matching ranges for this clone class; 1-based inclusive line numbers that match cloneCodes ... ],\n");
        prompt.append("      \"cloneCodes\": [ \"verbatim occurrence code aligned with ranges[0]\", \"verbatim occurrence code aligned with ranges[1]\", ... ],\n");
        prompt.append("      \"refactorType\": \"extracted_method\" or \"extracted_class\" or other string,\n");
        prompt.append("      \"reason\": \"explanation of why this clone was detected and why all listed ranges belong to the same clone class\",\n");
        prompt.append("      \"cloneCodeA\": \"pasted-snippet occurrence copied verbatim from fileSource when possible; may differ slightly from cloneCodeB and still be valid\",\n");
        prompt.append("      \"cloneCodeB\": \"matching clone region copied verbatim from fileSource when possible; may differ slightly from cloneCodeA and still be valid\"\n");
        prompt.append("    },\n");
        prompt.append("    ...\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("Do not include any text outside the JSON object.");
    }

    private DetectionResult normalizeDetectionResult(DetectionResult result,
                                                     String fileName,
                                                     String fileSource,
                                                     String selectedSnippet,
                                                     int[] snippetRange) {
        if (result == null || result.clones == null || result.clones.isEmpty()) {
            return buildNoClonesResult(fileName);
        }

        DetectionResult normalized = deepCopyDetectionResult(result, fileName);
        for (DetectedClone clone : normalized.clones) {
            groundCloneRangesAgainstSource(clone, fileSource, selectedSnippet, snippetRange);
        }
        if (snippetRange != null) {
            int snippetStart = snippetRange[0];
            int snippetEnd = snippetRange[1];
            List<DetectedClone> filtered = new ArrayList<>();
            for (DetectedClone c : normalized.clones) {
                System.out.println("[DETECTION] checking clone=" + (c == null ? "null" : c.id));
                if (c != null && c.ranges != null) {
                    for (CloneRange r : c.ranges) {
                        boolean ov = overlaps(r, snippetStart, snippetEnd);
                        System.out.println("[DETECTION]   range=" + r.startLine + "-" + r.endLine + ", overlapsSnippet=" + ov);
                    }
                }
                if (cloneOverlapsSnippet(c, snippetStart, snippetEnd)) {
                    filtered.add(c);
                }
            }

            if (!filtered.isEmpty()) {
                normalized.clones = filtered;
            } else {
                System.out.println("[DETECTION] LLM returned clones, but none overlapped the grounded pasted-snippet range; returning unfiltered LLM result as fallback.");
            }
        }

        normalized.status = "found_clones";
        normalized.file = fileName;
        return normalized;
    }

    private void groundCloneRangesAgainstSource(DetectedClone clone,
                                                String fileSource,
                                                String selectedSnippet,
                                                int[] snippetRange) {
        if (clone == null || fileSource == null || fileSource.isBlank()) return;

        List<String> cloneCodes = new ArrayList<>();
        if (clone.cloneCodes != null) {
            for (String code : clone.cloneCodes) {
                cloneCodes.add(safe(code));
            }
        }
        if (cloneCodes.isEmpty()) {
            if (!safe(clone.cloneCodeA).isBlank()) cloneCodes.add(clone.cloneCodeA);
            if (!safe(clone.cloneCodeB).isBlank()) cloneCodes.add(clone.cloneCodeB);
        }

        int rawRangeCount = clone.ranges == null ? 0 : clone.ranges.size();
        int occurrenceCount = Math.max(rawRangeCount, cloneCodes.size());
        if (occurrenceCount == 0) return;

        List<CloneRange> groundedRanges = new ArrayList<>();
        Set<String> usedRangeKeys = new HashSet<>();
        for (int i = 0; i < occurrenceCount; i++) {
            CloneRange rawRange = i < rawRangeCount ? clone.ranges.get(i) : null;
            String cloneCode = i < cloneCodes.size() ? cloneCodes.get(i) : "";
            if (cloneCode.isBlank() && i == 0 && selectedSnippet != null) {
                cloneCode = selectedSnippet;
            }

            boolean preferSnippetOverlap = shouldPreferSnippetOverlap(i, cloneCode, selectedSnippet, rawRange, snippetRange);
            CloneRange grounded = chooseBestGroundedRange(fileSource, cloneCode, rawRange, snippetRange, preferSnippetOverlap, usedRangeKeys);
            if (grounded == null) {
                grounded = copyRange(rawRange);
            }
            if (grounded != null) {
                groundedRanges.add(grounded);
                usedRangeKeys.add(rangeKey(grounded));
            }
        }

        clone.ranges = groundedRanges;
        if (clone.cloneCodeA.isBlank() && !cloneCodes.isEmpty()) {
            clone.cloneCodeA = cloneCodes.get(0);
        }
        if (clone.cloneCodeB.isBlank() && cloneCodes.size() > 1) {
            clone.cloneCodeB = cloneCodes.get(1);
        }
    }

    private CloneRange chooseBestGroundedRange(String fileSource,
                                               String cloneCode,
                                               CloneRange rawRange,
                                               int[] snippetRange,
                                               boolean preferSnippetOverlap,
                                               Set<String> usedRangeKeys) {
        if (cloneCode == null || cloneCode.isBlank()) return null;

        List<SourceRangeCandidate> candidates = findCloneCodeRangesInSource(fileSource, cloneCode);
        if (candidates.isEmpty()) return null;

        SourceRangeCandidate best = null;
        for (SourceRangeCandidate candidate : candidates) {
            if (best == null || compareGroundingCandidates(candidate, best, rawRange, snippetRange, preferSnippetOverlap, usedRangeKeys) < 0) {
                best = candidate;
            }
        }
        return best == null ? null : copyRange(best.range);
    }

    private int compareGroundingCandidates(SourceRangeCandidate left,
                                           SourceRangeCandidate right,
                                           CloneRange rawRange,
                                           int[] snippetRange,
                                           boolean preferSnippetOverlap,
                                           Set<String> usedRangeKeys) {
        long leftScore = groundingScore(left, rawRange, snippetRange, preferSnippetOverlap, usedRangeKeys);
        long rightScore = groundingScore(right, rawRange, snippetRange, preferSnippetOverlap, usedRangeKeys);
        if (leftScore != rightScore) {
            return Long.compare(leftScore, rightScore);
        }
        if (left.range.startLine != right.range.startLine) {
            return Integer.compare(left.range.startLine, right.range.startLine);
        }
        return Integer.compare(left.range.endLine, right.range.endLine);
    }

    private long groundingScore(SourceRangeCandidate candidate,
                                CloneRange rawRange,
                                int[] snippetRange,
                                boolean preferSnippetOverlap,
                                Set<String> usedRangeKeys) {
        long score = 0L;
        if (usedRangeKeys != null && usedRangeKeys.contains(rangeKey(candidate.range))) {
            score += 1_000_000L;
        }
        if (!candidate.exactMatch) {
            score += 10_000L;
        }

        if (snippetRange != null) {
            boolean overlapsSnippet = overlaps(candidate.range, snippetRange[0], snippetRange[1]);
            if (preferSnippetOverlap) {
                if (!overlapsSnippet) {
                    score += 100_000L;
                }
            } else if (overlapsSnippet && rawRange != null && !overlaps(rawRange, snippetRange[0], snippetRange[1])) {
                score += 5_000L;
            }
        }

        if (rawRange != null && rawRange.startLine > 0 && rawRange.endLine > 0) {
            score += Math.abs(candidate.range.startLine - rawRange.startLine);
            score += Math.abs(candidate.range.endLine - rawRange.endLine);
        } else {
            score += candidate.range.startLine;
        }

        score += Math.max(0, candidate.range.endLine - candidate.range.startLine);
        return score;
    }

    private boolean shouldPreferSnippetOverlap(int occurrenceIndex,
                                               String cloneCode,
                                               String selectedSnippet,
                                               CloneRange rawRange,
                                               int[] snippetRange) {
        if (snippetRange == null) return false;
        if (occurrenceIndex == 0) return true;
        if (rawRange != null && overlaps(rawRange, snippetRange[0], snippetRange[1])) return true;
        return textLooksEquivalent(cloneCode, selectedSnippet);
    }

    private List<SourceRangeCandidate> findCloneCodeRangesInSource(String fileSource, String cloneCode) {
        List<SourceRangeCandidate> exactMatches = findExactCloneCodeRanges(fileSource, cloneCode);
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        return findTrimmedLineCloneCodeRanges(fileSource, cloneCode);
    }

    private List<SourceRangeCandidate> findExactCloneCodeRanges(String fileSource, String cloneCode) {
        if (fileSource == null || cloneCode == null) return Collections.emptyList();

        String fs = normalizeNewlines(fileSource);
        String code = normalizeNewlines(cloneCode);
        if (code.isBlank()) return Collections.emptyList();

        List<SourceRangeCandidate> matches = new ArrayList<>();
        int fromIndex = 0;
        while (fromIndex <= fs.length()) {
            int idx = fs.indexOf(code, fromIndex);
            if (idx < 0) break;
            CloneRange range = toCloneRangeByIndex(fs, idx, idx + code.length());
            matches.add(new SourceRangeCandidate(range, true));
            fromIndex = idx + 1;
        }
        return dedupeSourceRangeCandidates(matches);
    }

    private List<SourceRangeCandidate> findTrimmedLineCloneCodeRanges(String fileSource, String cloneCode) {
        if (fileSource == null || cloneCode == null) return Collections.emptyList();

        String fs = normalizeNewlines(fileSource);
        String code = normalizeNewlines(cloneCode);
        if (code.isBlank()) return Collections.emptyList();

        String[] codeLines = code.split("\n", -1);
        List<String> need = new ArrayList<>();
        for (String line : codeLines) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isEmpty()) {
                need.add(trimmed);
            }
        }
        if (need.isEmpty()) return Collections.emptyList();

        String[] fsLines = fs.split("\n", -1);
        List<SourceRangeCandidate> matches = new ArrayList<>();
        for (int start = 0; start < fsLines.length; start++) {
            int iFile = start;
            int iNeed = 0;
            int firstMatched = -1;
            int lastMatched = -1;

            while (iFile < fsLines.length && iNeed < need.size()) {
                String fileLine = fsLines[iFile] == null ? "" : fsLines[iFile].trim();
                if (fileLine.isEmpty()) {
                    iFile++;
                    continue;
                }
                if (!fileLine.equals(need.get(iNeed))) {
                    break;
                }
                if (firstMatched < 0) firstMatched = iFile;
                lastMatched = iFile;
                iNeed++;
                iFile++;
            }

            if (iNeed == need.size() && firstMatched >= 0 && lastMatched >= firstMatched) {
                CloneRange range = new CloneRange();
                range.startLine = firstMatched + 1;
                range.endLine = lastMatched + 1;
                matches.add(new SourceRangeCandidate(range, false));
            }
        }
        return dedupeSourceRangeCandidates(matches);
    }

    private List<SourceRangeCandidate> dedupeSourceRangeCandidates(List<SourceRangeCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

        LinkedHashMap<String, SourceRangeCandidate> deduped = new LinkedHashMap<>();
        for (SourceRangeCandidate candidate : candidates) {
            if (candidate == null || candidate.range == null) continue;
            String key = rangeKey(candidate.range);
            SourceRangeCandidate existing = deduped.get(key);
            if (existing == null || (candidate.exactMatch && !existing.exactMatch)) {
                deduped.put(key, candidate);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private CloneRange toCloneRangeByIndex(String text, int startIdx, int endIdxExclusive) {
        int[] lines = toLineRangeByIndex(text, startIdx, endIdxExclusive);
        CloneRange range = new CloneRange();
        range.startLine = lines[0];
        range.endLine = lines[1];
        return range;
    }

    private CloneRange copyRange(CloneRange range) {
        if (range == null) return null;
        CloneRange copy = new CloneRange();
        copy.startLine = range.startLine;
        copy.endLine = range.endLine;
        return copy;
    }

    private String rangeKey(CloneRange range) {
        if (range == null) return "";
        return range.startLine + ":" + range.endLine;
    }

    private boolean textLooksEquivalent(String left, String right) {
        return !collapseWhitespace(left).isEmpty() && collapseWhitespace(left).equals(collapseWhitespace(right));
    }

    private String normalizeNewlines(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static final class SourceRangeCandidate {
        final CloneRange range;
        final boolean exactMatch;

        private SourceRangeCandidate(CloneRange range, boolean exactMatch) {
            this.range = range;
            this.exactMatch = exactMatch;
        }
    }

    private DetectionResult mergePanelistResults(List<PanelistResult> panelistResults, String fileName) {
        LinkedHashMap<String, Integer> signatureVotes = new LinkedHashMap<>();
        LinkedHashMap<String, DetectedClone> signatureToClone = new LinkedHashMap<>();
        for (PanelistResult panelistResult : panelistResults) {
            if (panelistResult == null || panelistResult.detectionResult == null || panelistResult.detectionResult.clones == null) {
                continue;
            }
            HashSet<String> seenInThisPanelist = new HashSet<>();
            for (DetectedClone clone : panelistResult.detectionResult.clones) {
                if (clone == null || clone.ranges == null || clone.ranges.isEmpty()) continue;
                DetectedClone copy = deepCopyClone(clone);
                String signature = buildCloneSignature(copy);
                if (signature.isBlank() || !seenInThisPanelist.add(signature)) continue;
                signatureVotes.merge(signature, 1, Integer::sum);
                signatureToClone.putIfAbsent(signature, copy);
            }
        }

        ArrayList<DetectedClone> majorityClones = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : signatureVotes.entrySet()) {
            if (entry.getValue() >= 2) {
                DetectedClone clone = signatureToClone.get(entry.getKey());
                if (clone != null) majorityClones.add(clone);
            }
        }

        if (majorityClones.isEmpty()) {
            return buildNoClonesResult(fileName);
        }

        DetectionResult merged = new DetectionResult();
        merged.status = "found_clones";
        merged.file = fileName;
        merged.clones = majorityClones;
        return merged;
    }

    private DetectionResult buildNoClonesResult(String fileName) {
        DetectionResult noClonesResult = new DetectionResult();
        noClonesResult.status = "no_clones";
        noClonesResult.file = fileName;
        noClonesResult.clones = Collections.emptyList();
        return noClonesResult;
    }

    private DetectionResult deepCopyDetectionResult(DetectionResult source, String fallbackFileName) {
        DetectionResult copy = new DetectionResult();
        copy.status = source == null || source.status == null || source.status.isBlank() ? "found_clones" : source.status;
        copy.file = source == null || source.file == null || source.file.isBlank() ? fallbackFileName : source.file;
        copy.clones = new ArrayList<>();
        if (source != null && source.clones != null) {
            for (DetectedClone clone : source.clones) {
                if (clone != null) copy.clones.add(deepCopyClone(clone));
            }
        }
        return copy;
    }

    private DetectedClone deepCopyClone(DetectedClone clone) {
        DetectedClone copy = new DetectedClone();
        copy.id = safe(clone == null ? null : clone.id);
        copy.refactorType = safe(clone == null ? null : clone.refactorType);
        copy.reason = safe(clone == null ? null : clone.reason);
        copy.cloneCodeA = safe(clone == null ? null : clone.cloneCodeA);
        copy.cloneCodeB = safe(clone == null ? null : clone.cloneCodeB);
        copy.cloneCodes = new ArrayList<>();
        copy.ranges = new ArrayList<>();
        if (clone != null && clone.cloneCodes != null) {
            for (String code : clone.cloneCodes) {
                copy.cloneCodes.add(safe(code));
            }
        }
        if (clone != null && clone.ranges != null) {
            for (CloneRange range : clone.ranges) {
                if (range == null) continue;
                CloneRange rangeCopy = new CloneRange();
                rangeCopy.startLine = range.startLine;
                rangeCopy.endLine = range.endLine;
                copy.ranges.add(rangeCopy);
            }
        }
        if (copy.id.isBlank()) {
            copy.id = "clone_" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
        }
        if (copy.cloneCodeA.isBlank() && !copy.cloneCodes.isEmpty()) {
            copy.cloneCodeA = copy.cloneCodes.get(0);
        }
        if (copy.cloneCodeB.isBlank() && copy.cloneCodes.size() > 1) {
            copy.cloneCodeB = copy.cloneCodes.get(1);
        }
        return copy;
    }

    private String buildCloneSignature(DetectedClone clone) {
        if (clone == null || clone.ranges == null || clone.ranges.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (CloneRange range : clone.ranges) {
            if (range == null) continue;
            parts.add(range.startLine + ":" + range.endLine);
        }
        Collections.sort(parts);
        return String.join("|", parts);
    }

    private String safeLlmCall(Function<String, String> llmCaller, String prompt) {
        if (llmCaller == null) return "";
        try {
            return safe(llmCaller.apply(prompt));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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
                    if (c.cloneCodes == null) c.cloneCodes = new ArrayList<>();
                    if (c.cloneCodeA == null) c.cloneCodeA = "";
                    if (c.cloneCodeB == null) c.cloneCodeB = "";
                    if (c.cloneCodes.isEmpty()) {
                        if (!c.cloneCodeA.isBlank()) c.cloneCodes.add(c.cloneCodeA);
                        if (!c.cloneCodeB.isBlank()) c.cloneCodes.add(c.cloneCodeB);
                    }
                    if (c.cloneCodeA.isBlank() && !c.cloneCodes.isEmpty()) {
                        c.cloneCodeA = c.cloneCodes.get(0) == null ? "" : c.cloneCodes.get(0);
                    }
                    if (c.cloneCodeB.isBlank() && c.cloneCodes.size() > 1) {
                        c.cloneCodeB = c.cloneCodes.get(1) == null ? "" : c.cloneCodes.get(1);
                    }
                    if (c.id == null || c.id.isBlank()) {
                        c.id = "clone_" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
                    }
                }
            }
            return result;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
