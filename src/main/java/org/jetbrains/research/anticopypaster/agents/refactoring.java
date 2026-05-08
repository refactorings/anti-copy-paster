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
    private static final int CURATOR_CANDIDATE_MAX_CHARS = 12000;
    private static final List<PanelistSpec> PANELIST_SPECS = List.of(
            new PanelistSpec("P1", "Refactoring Panelist 1"),
            new PanelistSpec("P2", "Refactoring Panelist 2"),
            new PanelistSpec("P3", "Refactoring Panelist 3")
    );
    private static final String USEFUL_CATEGORY_NAME = "EXTRACT_METHOD_CONFIRMED";
    private static final List<String> NOT_USEFUL_CATEGORY_NAMES = List.of(
            "EXTRACT_METHOD_NOT_FOUND",
            "INCOMPLETE_REFACTORING_DETECTED",
            "EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED",
            "DIRECT_CLONE_REMOVAL_DETECTED",
            "POST_EXTRACTION_CLONE_DELETION_DETECTED",
            "CALL_BASED_CLONE_SUBSTITUTION_DETECTED",
            "CLONE_REMOVAL_BY_DELEGATION_DETECTED",
            "FRAGMENTATION_OF_LOGIC_DETECTED",
            "NON_TARGET_CLONE_REFACTORING_DETECTED",
            "EXCESSIVE_REFACTORING_DETECTED"
    );
    private static final Map<String, String> USEFULNESS_CATEGORY_GUIDANCE = Map.ofEntries(
            Map.entry("EXTRACT_METHOD_CONFIRMED", "The candidate clearly extracts one helper and replaces all target clone occurrences with calls to it."),
            Map.entry("EXTRACT_METHOD_NOT_FOUND", "No valid Extract Method is visible for the target clone."),
            Map.entry("INCOMPLETE_REFACTORING_DETECTED", "Some duplicated logic still remains in the original target methods after refactoring."),
            Map.entry("EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED", "A helper was introduced, but the original duplicate body still remains instead of being replaced."),
            Map.entry("DIRECT_CLONE_REMOVAL_DETECTED", "A clone occurrence was deleted directly instead of extracting shared logic."),
            Map.entry("POST_EXTRACTION_CLONE_DELETION_DETECTED", "A helper was added, but one target clone was deleted afterward."),
            Map.entry("CALL_BASED_CLONE_SUBSTITUTION_DETECTED", "One target clone now calls another existing method instead of using a new shared helper."),
            Map.entry("CLONE_REMOVAL_BY_DELEGATION_DETECTED", "The candidate relies on delegation without clearly extracting one shared helper for all target clones."),
            Map.entry("FRAGMENTATION_OF_LOGIC_DETECTED", "Shared logic is split across multiple helpers instead of one coherent extraction."),
            Map.entry("NON_TARGET_CLONE_REFACTORING_DETECTED", "The candidate seems to refactor some other duplication while the intended target remains unresolved."),
            Map.entry("EXCESSIVE_REFACTORING_DETECTED", "The candidate pulls in code beyond the intended cloned fragment.")
    );

    private static final class PanelistSpec {
        final String id;
        final String title;

        private PanelistSpec(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

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
        public List<String> cloneCodes;
        public String cloneCodeA;
        public String cloneCodeB;

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason) {
            this(id, ranges, refactorType, reason, "");
        }

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason, String cloneCode) {
            this(id, ranges, refactorType, reason, cloneCode, List.of(), "", "");
        }

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason,
                             String cloneCode, String cloneCodeA, String cloneCodeB) {
            this(id, ranges, refactorType, reason, cloneCode, List.of(), cloneCodeA, cloneCodeB);
        }

        public DetectedClone(String id, List<CloneRange> ranges, String refactorType, String reason,
                             String cloneCode, List<String> cloneCodes, String cloneCodeA, String cloneCodeB) {
            this.id = id;
            this.ranges = ranges;
            this.refactorType = refactorType;
            this.reason = reason;
            this.cloneCode = (cloneCode == null ? "" : cloneCode);
            this.cloneCodes = cloneCodes == null ? new ArrayList<>() : new ArrayList<>(cloneCodes);
            this.cloneCodeA = (cloneCodeA == null ? "" : cloneCodeA);
            this.cloneCodeB = (cloneCodeB == null ? "" : cloneCodeB);
            if (this.cloneCodeA.isBlank() && !this.cloneCodes.isEmpty()) {
                this.cloneCodeA = this.cloneCodes.get(0) == null ? "" : this.cloneCodes.get(0);
            }
            if (this.cloneCodeB.isBlank() && this.cloneCodes.size() > 1) {
                this.cloneCodeB = this.cloneCodes.get(1) == null ? "" : this.cloneCodes.get(1);
            }
        }
    }

    private static class OccurrenceSpec {
        public String occurrenceId;
        public String snippet;
        public CloneRange preferredRange;

        public OccurrenceSpec(String occurrenceId, String snippet, CloneRange preferredRange) {
            this.occurrenceId = occurrenceId;
            this.snippet = snippet == null ? "" : snippet;
            this.preferredRange = preferredRange;
        }
    }

    private static class OccurrenceRewrite {
        public String occurrenceId;
        public String replacementCode;
    }

    private static class PartialRefactorPlan {
        public String helperMethod;
        public List<OccurrenceRewrite> occurrenceReplacements;
    }

    private static class StructuredRefactorOutcome {
        public boolean recognized;
        public String newSource;
        public String error;
        public String helperMethod;
        public List<OccurrenceRewrite> occurrenceReplacements;
    }

    private static final class PanelistOutcome {
        public final String panelistId;
        public final String rawResponse;
        public final RefactorResult result;
        public final boolean parsed;
        public final String error;
        public final String helperMethod;
        public final List<OccurrenceRewrite> occurrenceReplacements;

        private PanelistOutcome(String panelistId,
                                String rawResponse,
                                RefactorResult result,
                                boolean parsed,
                                String error,
                                String helperMethod,
                                List<OccurrenceRewrite> occurrenceReplacements) {
            this.panelistId = panelistId == null ? "" : panelistId;
            this.rawResponse = rawResponse == null ? "" : rawResponse;
            this.result = result;
            this.parsed = parsed;
            this.error = error == null ? "" : error;
            this.helperMethod = helperMethod == null ? "" : helperMethod;
            this.occurrenceReplacements = occurrenceReplacements == null ? List.of() : List.copyOf(occurrenceReplacements);
        }
    }

    private static final class CuratorSelectionResult {
        public boolean parsed;
        public String selectedPanelistId;
        public List<String> matchedCategories;
        public String summary;
        public String feedback;
        public double confidence;
        public String rawResponse;
        public String error;

        private CuratorSelectionResult() {
            this.matchedCategories = List.of();
            this.summary = "";
            this.feedback = "";
            this.rawResponse = "";
            this.error = "";
            this.selectedPanelistId = "";
        }
    }

    private static class TextSpan {
        public int start;
        public int end;

        public TextSpan(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static class NormalizedMatchText {
        public String text;
        public List<Integer> offsets;

        public NormalizedMatchText(String text, List<Integer> offsets) {
            this.text = text == null ? "" : text;
            this.offsets = offsets == null ? List.of() : offsets;
        }
    }

    public static class RefactorResult {
        public String status;
        public String file;
        public String newSource;
        public String message;
        public String selectedPanelistId;
        public List<String> curatorMatchedCategories;
        public String curatorSummary;
        public String curatorFeedback;
        public double curatorConfidence;
        public boolean curatorGeneratedPlan;

        public RefactorResult(String status, String file, String newSource, String message) {
            this.status = status;
            this.file = file;
            this.newSource = newSource;
            this.message = message;
            this.selectedPanelistId = "";
            this.curatorMatchedCategories = List.of();
            this.curatorSummary = "";
            this.curatorFeedback = "";
            this.curatorConfidence = 0.0d;
            this.curatorGeneratedPlan = false;
        }
    }

    public RefactorResult refactorFile(String fileName, String fileSource, DetectedClone clone, String ragExamples, Function<String, String> llmCaller) {
        // Backward-compatible entrypoint when Project is not available.
        return refactorFile(null, fileName, fileSource, clone, ragExamples, llmCaller);
    }

    public RefactorResult refactorWithPrompt(String fileName,
                                             String fileSource,
                                             DetectedClone clone,
                                             String prompt,
                                             Function<String, String> llmCaller) {
        if (clone == null) {
            return fail(fileName, "DetectedClone is null");
        }
        if (prompt == null || prompt.isBlank()) {
            return fail(fileName, "Prompt is empty");
        }
        String revisionPrompt = buildFeedbackRevisionPrompt(fileName, fileSource, clone, prompt);
        return executePanelistsAndCurator(fileName, fileSource, clone, revisionPrompt, llmCaller);
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
        return executePanelistsAndCurator(fileName, fileSource, clone, prompt, llmCaller);
    }

    private RefactorResult executeSingleRefactor(String fileName,
                                                 String fileSource,
                                                 DetectedClone clone,
                                                 String prompt,
                                                 Function<String, String> llmCaller) {
        if (llmCaller == null) {
            return fail(fileName, "LLM caller is null");
        }

        PanelistOutcome outcome = executePanelistPrompt(
                new PanelistSpec("REF", "Refactoring"),
                fileName,
                fileSource,
                clone,
                prompt,
                llmCaller
        );
        if (outcome == null || outcome.result == null) {
            return fail(fileName, "Refactoring agent did not return a result");
        }
        return outcome.result;
    }

    private RefactorResult executePanelistsAndCurator(String fileName,
                                                      String fileSource,
                                                      DetectedClone clone,
                                                      String basePrompt,
                                                      Function<String, String> llmCaller) {
        if (llmCaller == null) {
            return fail(fileName, "LLM caller is null");
        }

        List<PanelistOutcome> panelistOutcomes = new ArrayList<>();
        for (PanelistSpec spec : PANELIST_SPECS) {
            String panelistPrompt = buildPanelistPrompt(spec, basePrompt);
            panelistOutcomes.add(executePanelistPrompt(spec, fileName, fileSource, clone, panelistPrompt, llmCaller));
        }

        CuratorSelectionResult curatorSelection = runCuratorSelection(fileName, fileSource, clone, panelistOutcomes, llmCaller);
        PanelistOutcome selectedOutcome = resolveSelectedOutcome(curatorSelection, panelistOutcomes);
        if (selectedOutcome == null || selectedOutcome.result == null
                || selectedOutcome.result.newSource == null || selectedOutcome.result.newSource.isBlank()) {
            String detail = buildCandidateSelectionFailureMessage(curatorSelection, panelistOutcomes);
            return fail(fileName, detail);
        }

        RefactorResult selectedResult = selectedOutcome.result;
        selectedResult.selectedPanelistId = selectedOutcome.panelistId;
        selectedResult.curatorGeneratedPlan = false;
        if (curatorSelection != null) {
            selectedResult.curatorMatchedCategories = curatorSelection.matchedCategories == null
                    ? List.of()
                    : List.copyOf(curatorSelection.matchedCategories);
            selectedResult.curatorSummary = curatorSelection.summary == null ? "" : curatorSelection.summary;
            selectedResult.curatorFeedback = curatorSelection.feedback == null ? "" : curatorSelection.feedback;
            selectedResult.curatorConfidence = curatorSelection.confidence;
        }
        return selectedResult;
    }

    private PanelistOutcome executePanelistPrompt(PanelistSpec spec,
                                                  String fileName,
                                                  String fileSource,
                                                  DetectedClone clone,
                                                  String prompt,
                                                  Function<String, String> llmCaller) {
        String rawOutput;

        try {
            rawOutput = llmCaller.apply(prompt);

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
            return new PanelistOutcome(
                    spec.id,
                    "",
                    fail(fileName, "LLM caller threw exception: " + e.getMessage()),
                    false,
                    "LLM caller threw exception: " + e.getMessage(),
                    "",
                    List.of()
            );
        }

        try {
            System.out.println("[DEBUG][REFACTOR] raw LLM output length = " + rawOutput.length());
            int previewLen = Math.min(800, rawOutput.length());
            System.out.println("[DEBUG][REFACTOR] raw LLM output preview:\n" +
                    rawOutput.substring(0, previewLen));
        } catch (Throwable t) {
            System.out.println("[DEBUG][REFACTOR] failed to log raw LLM output: " + t.getMessage());
        }

        if (rawOutput == null || rawOutput.isEmpty()) {
            return new PanelistOutcome(
                    spec.id,
                    rawOutput,
                    fail(fileName, "LLM caller returned empty output"),
                    false,
                    "LLM caller returned empty output",
                    "",
                    List.of()
            );
        }

        StructuredRefactorOutcome structuredOutcome = tryApplyStructuredRefactor(rawOutput, fileSource, clone);
        String newSource = null;
        String detail = "";
        if (structuredOutcome != null && structuredOutcome.recognized) {
            if (structuredOutcome.newSource == null || structuredOutcome.newSource.isBlank()) {
                detail = structuredOutcome.error == null || structuredOutcome.error.isBlank()
                        ? "Structured JSON refactor output was recognized but could not be applied."
                        : "Failed to apply structured refactor output: " + structuredOutcome.error;
                return new PanelistOutcome(
                        spec.id,
                        rawOutput,
                        fail(fileName, detail),
                        true,
                        detail,
                        structuredOutcome.helperMethod,
                        structuredOutcome.occurrenceReplacements
                );
            }
            newSource = structuredOutcome.newSource;
        } else {
            newSource = extractJavaCodeBlock(rawOutput);
        }
        if (newSource == null) {
            String jsonStr = extractJsonSubstring(rawOutput);
            if (jsonStr != null) {
                try {
                    JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
                    if (obj.has("new_source")) {
                        newSource = obj.get("new_source").getAsString();
                    } else if (obj.has("newSource")) {
                        newSource = obj.get("newSource").getAsString();
                    } else {
                        detail = "JSON output missing 'new_source' or 'newSource' field";
                        return new PanelistOutcome(
                                spec.id,
                                rawOutput,
                                fail(fileName, detail),
                                false,
                                detail,
                                structuredOutcome == null ? "" : structuredOutcome.helperMethod,
                                structuredOutcome == null ? List.of() : structuredOutcome.occurrenceReplacements
                        );
                    }
                } catch (JsonSyntaxException e) {
                    detail = "Failed to parse JSON output: " + e.getMessage();
                    return new PanelistOutcome(
                            spec.id,
                            rawOutput,
                            fail(fileName, detail),
                            false,
                            detail,
                            structuredOutcome == null ? "" : structuredOutcome.helperMethod,
                            structuredOutcome == null ? List.of() : structuredOutcome.occurrenceReplacements
                    );
                }
            } else {
                detail = "Failed to extract Java code block or JSON from LLM output";
                return new PanelistOutcome(
                        spec.id,
                        rawOutput,
                        fail(fileName, detail),
                        false,
                        detail,
                        structuredOutcome == null ? "" : structuredOutcome.helperMethod,
                        structuredOutcome == null ? List.of() : structuredOutcome.occurrenceReplacements
                );
            }
        }

        if (newSource == null || newSource.isEmpty()) {
            detail = "Extracted new source is empty";
            return new PanelistOutcome(
                    spec.id,
                    rawOutput,
                    fail(fileName, detail),
                    false,
                    detail,
                    structuredOutcome == null ? "" : structuredOutcome.helperMethod,
                    structuredOutcome == null ? List.of() : structuredOutcome.occurrenceReplacements
            );
        }

        return new PanelistOutcome(
                spec.id,
                rawOutput,
                new RefactorResult("refactored", fileName, newSource, "Refactoring successful"),
                true,
                "",
                structuredOutcome == null ? "" : structuredOutcome.helperMethod,
                structuredOutcome == null ? List.of() : structuredOutcome.occurrenceReplacements
        );
    }

    private String buildPanelistPrompt(PanelistSpec spec, String basePrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.title).append(" (").append(spec.id).append(")\n");
        sb.append("You are one of three independent Java refactoring panelists.\n");
        sb.append("Produce your own best Extract Method candidate for the provided target clone.\n");
        sb.append("Do not try to match the other panelists. Return the strongest valid candidate you can.\n\n");
        sb.append(basePrompt == null ? "" : basePrompt);
        return sb.toString();
    }

    private CuratorSelectionResult runCuratorSelection(String fileName,
                                                       String fileSource,
                                                       DetectedClone clone,
                                                       List<PanelistOutcome> panelistOutcomes,
                                                       Function<String, String> llmCaller) {
        CuratorSelectionResult fallback = new CuratorSelectionResult();
        if (llmCaller == null) {
            fallback.error = "LLM caller is null";
            return fallback;
        }

        String prompt = buildCuratorPrompt(fileName, fileSource, clone, panelistOutcomes);
        String raw;
        try {
            raw = llmCaller.apply(prompt);
        } catch (Exception e) {
            fallback.error = "Curator LLM call failed: " + e.getMessage();
            return fallback;
        }
        return parseCuratorSelection(raw);
    }

    private String buildCuratorPrompt(String fileName,
                                      String fileSource,
                                      DetectedClone clone,
                                      List<PanelistOutcome> panelistOutcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the refactoring curator.\n");
        sb.append("You must review three candidate Extract Method refactorings.\n");
        sb.append("Choose the single best panelist candidate for downstream usefulness validation.\n");
        sb.append("If no candidate is fully acceptable, still select the least-bad panelist candidate and explain the residual risks in feedback.\n");
        sb.append("A useful panelist candidate must clearly achieve EXTRACT_METHOD_CONFIRMED and must not trigger any not-useful category.\n\n");

        sb.append("=== USEFUL CATEGORY DEFINITION ===\n");
        sb.append("- ")
                .append(USEFUL_CATEGORY_NAME)
                .append(": ")
                .append(USEFULNESS_CATEGORY_GUIDANCE.get(USEFUL_CATEGORY_NAME))
                .append("\n\n");

        sb.append("=== NOT-USEFUL CATEGORY DEFINITIONS ===\n");
        for (String category : NOT_USEFUL_CATEGORY_NAMES) {
            sb.append("- ")
                    .append(category)
                    .append(": ")
                    .append(USEFULNESS_CATEGORY_GUIDANCE.getOrDefault(category, "Not-useful category"))
                    .append("\n");
        }
        sb.append("\n");

        sb.append("=== TARGET FILE ===\n");
        sb.append("File name: ").append(fileName).append("\n");
        sb.append("Full file source (for context only):\n");
        sb.append("```\n").append(safeTruncate(fileSource, CURATOR_CANDIDATE_MAX_CHARS)).append("\n```\n\n");

        sb.append("=== TARGET CLONE ===\n");
        if (clone != null) {
            if (clone.id != null && !clone.id.isBlank()) {
                sb.append("Clone ID: ").append(clone.id).append("\n");
            }
            if (clone.refactorType != null && !clone.refactorType.isBlank()) {
                sb.append("Requested refactor type: ").append(clone.refactorType).append("\n");
            }
            if (clone.reason != null && !clone.reason.isBlank()) {
                sb.append("Detection rationale: ").append(safeTruncate(clone.reason, 800)).append("\n");
            }
            List<OccurrenceSpec> occurrences = buildOccurrenceSpecs(clone, fileSource);
            if (!occurrences.isEmpty()) {
                for (OccurrenceSpec occurrence : occurrences) {
                    sb.append(occurrence.occurrenceId).append(":\n");
                    sb.append("```\n").append(safeTruncate(occurrence.snippet, 1200)).append("\n```\n");
                }
            }
        }
        sb.append("\n");

        sb.append("=== PANELIST CANDIDATES ===\n");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        for (PanelistOutcome outcome : panelistOutcomes) {
            JsonObject obj = new JsonObject();
            obj.addProperty("panelist_id", outcome.panelistId);
            obj.addProperty("parsed", outcome.parsed);
            obj.addProperty("status", outcome.result == null ? "" : outcome.result.status);
            obj.addProperty("message", outcome.result == null ? "" : outcome.result.message);
            obj.addProperty("error", outcome.error);
            obj.addProperty("helper_method", outcome.helperMethod);

            JsonArray replacements = new JsonArray();
            for (OccurrenceRewrite rewrite : outcome.occurrenceReplacements) {
                if (rewrite == null) continue;
                JsonObject replacement = new JsonObject();
                replacement.addProperty("occurrence_id", rewrite.occurrenceId);
                replacement.addProperty("replacement_code", rewrite.replacementCode);
                replacements.add(replacement);
            }
            obj.add("occurrence_replacements", replacements);
            obj.addProperty(
                    "candidate_source_preview",
                    outcome.result == null ? "" : safeTruncate(outcome.result.newSource, CURATOR_CANDIDATE_MAX_CHARS)
            );
            obj.addProperty("raw_response_preview", safeTruncate(outcome.rawResponse, 3000));

            sb.append("[").append(outcome.panelistId).append("]\n");
            sb.append(gson.toJson(obj)).append("\n\n");
        }

        sb.append("=== SELECTION RULES ===\n");
        sb.append("1) First classify every panelist candidate using the useful and not-useful definitions above.\n");
        sb.append("2) A panelist candidate is useful only if it clearly triggers EXTRACT_METHOD_CONFIRMED and triggers none of the not-useful categories.\n");
        sb.append("3) Reject panelist candidates that likely trigger any not-useful category: ")
                .append(String.join(", ", NOT_USEFUL_CATEGORY_NAMES))
                .append(".\n");
        sb.append("4) If one or more panelist candidates are useful, choose the best useful candidate. Prefer the most conservative change with the smallest safe extraction boundary.\n");
        sb.append("5) If no panelist candidate is useful, still choose the least-bad valid panelist candidate; do not generate your own refactoring plan.\n");
        sb.append("6) If a panelist candidate failed to parse or apply, treat it as not useful.\n\n");

        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("Return ONLY a JSON object with this exact shape.\n");
        sb.append("{\n");
        sb.append("  \"decision\": \"select_panelist\",\n");
        sb.append("  \"selected_panelist_id\": \"P1\",\n");
        sb.append("  \"matched_categories\": [\"EXTRACT_METHOD_CONFIRMED\"],\n");
        sb.append("  \"summary\": \"short explanation of why this candidate is best or least-bad\",\n");
        sb.append("  \"feedback\": \"optional short guidance about the remaining risk of the selected candidate\",\n");
        sb.append("  \"confidence\": 0.85\n");
        sb.append("}\n");
        return sb.toString();
    }

    private CuratorSelectionResult parseCuratorSelection(String raw) {
        CuratorSelectionResult result = new CuratorSelectionResult();
        result.rawResponse = raw == null ? "" : raw;
        String jsonStr = extractJsonSubstring(raw);
        if (jsonStr == null || jsonStr.isBlank()) {
            result.error = "Could not extract curator JSON";
            return result;
        }

        try {
            JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
            result.parsed = true;
            result.selectedPanelistId = getString(
                    obj,
                    "selected_panelist_id",
                    "selectedPanelistId",
                    "panelist_id",
                    "panelistId",
                    "selected_candidate_id",
                    "selectedCandidateId",
                    "candidate_id",
                    "candidateId",
                    "best_panelist_id",
                    "bestPanelistId"
            );
            JsonArray categories = getArray(obj, "matched_categories", "matchedCategories", "categories");
            result.matchedCategories = parseStringArray(categories);
            result.summary = optional(getString(obj, "summary"));
            result.feedback = optional(getString(obj, "feedback"));
            result.confidence = parseDouble(obj, "confidence");
            return result;
        } catch (Throwable t) {
            result.error = "Could not parse curator JSON: " + t.getMessage();
            return result;
        }
    }

    private List<String> parseStringArray(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement element : arr) {
            if (element == null || element.isJsonNull()) continue;
            try {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) out.add(value);
            } catch (Throwable ignored) {
                // ignore malformed items
            }
        }
        return out;
    }

    private double parseDouble(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0.0d;
        try {
            return obj.get(key).getAsDouble();
        } catch (Throwable ignored) {
            return 0.0d;
        }
    }

    private boolean curatorSelectedUsefulPanelist(CuratorSelectionResult curatorSelection) {
        if (curatorSelection == null
                || !curatorSelection.parsed
                || curatorSelection.selectedPanelistId == null
                || curatorSelection.selectedPanelistId.isBlank()) {
            return false;
        }
        if (containsAnyCategory(curatorSelection.matchedCategories, NOT_USEFUL_CATEGORY_NAMES)) {
            return false;
        }
        return containsCategory(curatorSelection.matchedCategories, USEFUL_CATEGORY_NAME);
    }

    private boolean containsAnyCategory(List<String> categories, List<String> candidates) {
        if (categories == null || categories.isEmpty() || candidates == null || candidates.isEmpty()) return false;
        for (String candidate : candidates) {
            if (containsCategory(categories, candidate)) return true;
        }
        return false;
    }

    private boolean containsCategory(List<String> categories, String candidate) {
        if (categories == null || candidate == null || candidate.isBlank()) return false;
        for (String category : categories) {
            if (category != null && candidate.equalsIgnoreCase(category.trim())) return true;
        }
        return false;
    }

    private String buildCandidateSelectionFailureMessage(CuratorSelectionResult curatorSelection,
                                                         List<PanelistOutcome> panelistOutcomes) {
        StringBuilder sb = new StringBuilder();
        if (curatorSelection == null || !curatorSelection.parsed) {
            sb.append("Curator selection failed");
            if (curatorSelection != null && curatorSelection.error != null && !curatorSelection.error.isBlank()) {
                sb.append(": ").append(curatorSelection.error);
            }
        } else if (curatorSelectedUsefulPanelist(curatorSelection)) {
            sb.append("Curator selected a useful panelist candidate, but the selected candidate could not be applied");
            if (curatorSelection.selectedPanelistId != null && !curatorSelection.selectedPanelistId.isBlank()) {
                sb.append(" (selected=").append(curatorSelection.selectedPanelistId.trim()).append(")");
            }
        } else {
            sb.append("No valid panelist candidate could be applied");
        }

        if (curatorSelection != null && curatorSelection.summary != null && !curatorSelection.summary.isBlank()) {
            sb.append(": ").append(safeTruncate(curatorSelection.summary, 220).replace("\n", " "));
        }

        String diagnostics = summarizePanelistOutcomes(panelistOutcomes);
        if (!diagnostics.isBlank()) {
            sb.append(". Panelist diagnostics: ").append(diagnostics);
        }
        return sb.toString();
    }

    private String summarizePanelistOutcomes(List<PanelistOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        for (PanelistOutcome outcome : outcomes) {
            if (outcome == null) continue;
            String id = outcome.panelistId == null || outcome.panelistId.isBlank() ? "UNKNOWN" : outcome.panelistId;
            String state;
            if (outcome.result != null && outcome.result.newSource != null && !outcome.result.newSource.isBlank()) {
                state = "candidate_source_available";
            } else if (outcome.error != null && !outcome.error.isBlank()) {
                state = outcome.error;
            } else if (outcome.result != null && outcome.result.message != null && !outcome.result.message.isBlank()) {
                state = outcome.result.message;
            } else {
                state = outcome.parsed ? "empty refactored source" : "unparsed response";
            }
            parts.add(id + "=" + safeTruncate(state, 180).replace("\n", " "));
        }
        return String.join("; ", parts);
    }

    private PanelistOutcome resolveSelectedOutcome(CuratorSelectionResult curatorSelection,
                                                   List<PanelistOutcome> panelistOutcomes) {
        if (panelistOutcomes == null || panelistOutcomes.isEmpty()) return null;

        String selectedPanelistId = normalizeSelectedPanelistId(curatorSelection, panelistOutcomes);
        if (selectedPanelistId != null && !selectedPanelistId.isBlank()) {
            for (PanelistOutcome outcome : panelistOutcomes) {
                if (outcome == null || outcome.result == null) continue;
                if (!outcome.panelistId.equals(selectedPanelistId)) continue;
                if (outcome.result.newSource != null && !outcome.result.newSource.isBlank()) {
                    return outcome;
                }
            }
        }
        return highestScoringPanelistOutcome(panelistOutcomes);
    }

    private PanelistOutcome highestScoringPanelistOutcome(List<PanelistOutcome> panelistOutcomes) {
        if (panelistOutcomes == null || panelistOutcomes.isEmpty()) return null;
        PanelistOutcome best = null;
        double bestScore = 0.0d;
        for (PanelistOutcome outcome : panelistOutcomes) {
            double score = scorePanelistOutcome(outcome);
            if (score <= 0.0d) continue;
            if (best == null || score > bestScore) {
                best = outcome;
                bestScore = score;
            }
        }
        return best;
    }

    private double scorePanelistOutcome(PanelistOutcome outcome) {
        if (outcome == null
                || outcome.result == null
                || outcome.result.newSource == null
                || outcome.result.newSource.isBlank()) {
            return 0.0d;
        }
        double score = 0.5d;
        if (outcome.parsed) score += 0.2d;
        if (outcome.occurrenceReplacements != null && !outcome.occurrenceReplacements.isEmpty()) score += 0.2d;
        if (outcome.error == null || outcome.error.isBlank()) score += 0.1d;
        return score;
    }

    private String normalizeSelectedPanelistId(CuratorSelectionResult curatorSelection,
                                               List<PanelistOutcome> panelistOutcomes) {
        if (curatorSelection == null
                || curatorSelection.selectedPanelistId == null
                || curatorSelection.selectedPanelistId.isBlank()
                || panelistOutcomes == null
                || panelistOutcomes.isEmpty()) {
            return "";
        }

        String raw = curatorSelection.selectedPanelistId.trim();
        String normalized = raw.toUpperCase(Locale.ROOT);
        for (PanelistOutcome outcome : panelistOutcomes) {
            if (outcome == null || outcome.panelistId == null || outcome.panelistId.isBlank()) continue;
            String candidateId = outcome.panelistId.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals(candidateId) || normalized.contains(candidateId)) {
                return outcome.panelistId;
            }
        }

        String digits = normalized.replaceAll("[^0-9]", "");
        if (!digits.isBlank()) {
            for (PanelistOutcome outcome : panelistOutcomes) {
                if (outcome == null || outcome.panelistId == null || outcome.panelistId.isBlank()) continue;
                String candidateDigits = outcome.panelistId.toUpperCase(Locale.ROOT).replaceAll("[^0-9]", "");
                if (!candidateDigits.isBlank() && candidateDigits.equals(digits)) {
                    return outcome.panelistId;
                }
            }
        }
        return raw;
    }


    private String buildRefactorPrompt(String fileName, String fileSource, DetectedClone clone, String ragExamples) {
        StringBuilder sb = new StringBuilder();

        // ===== Refactoring Agent prompt (paper listing: Refactoring Agent prompt with RAG context) =====
//        sb.append("You are a Java software engineer.\n\n");
//        sb.append("Your task is to refactor the given Java file to remove cloned code.\n");
//        sb.append("You must apply Extract Method refactoring only.\n\n");
//
//        sb.append("Input:\n");
//        sb.append("- A Java source file.\n");
//        sb.append("- Detected cloned code snippets copied verbatim from the file.\n\n");
//
//        sb.append("Java source file (FULL FILE):\n");
//        sb.append("```\n").append(fileSource).append("\n```\n\n");
//
//        sb.append("Detected cloned code snippets (copied verbatim):\n");
//        if (clone != null) {
//            if (clone.cloneCode != null && !clone.cloneCode.trim().isEmpty()) {
//                sb.append("```\n").append(clone.cloneCode).append("\n```\n\n");
//            }
//            if (clone.ranges != null && !clone.ranges.isEmpty()) {
//                sb.append("(Approximate line ranges, secondary hint)\n");
//                for (CloneRange range : clone.ranges) {
//                    sb.append("- from line ").append(range.startLine)
//                      .append(" to line ").append(range.endLine).append("\n");
//                }
//                sb.append("\n");
//            }
//        }
//
//        sb.append("Retrieved refactoring examples (for guidance only):\n");
//        sb.append("Each example includes a BEFORE code fragment, an AFTER code fragment,\n");
//        sb.append("and a short note describing the refactoring intent.\n");
//        sb.append("Use these examples only as guidance.\n");
//        sb.append("Do not copy code directly from them.\n");
//        sb.append("Always follow the Extract Method pattern.\n\n");
//
//        // RAG block (keep placeholder even if empty so the prompt matches the paper listing)
//        sb.append("[RAG_EXAMPLES]\n");
//        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
//            sb.append(ragExamples.trim()).append("\n");
//        }
//        sb.append("\n");
//
//        sb.append("Instructions:\n");
//        sb.append("- Use Extract Method to remove clones.\n");
//        sb.append("- Do not apply any other refactoring type.\n");
//        sb.append("- Do not rename existing classes, methods, or variables unless required by Extract Method.\n");
//        sb.append("- Do not modify package or import declarations.\n");
//        sb.append("- Restrict all changes to this file only.\n");
//        sb.append("- Preserve the original program behavior.\n");
//        sb.append("- Keep the refactoring minimal and focused.\n\n");
//
//        sb.append("Output:\n");
//        sb.append("- Return the full updated Java file.\n");
//        sb.append("- Output exactly one Java code block.\n");
//        sb.append("- Do not include any explanation or text outside the code block.\n");
//        sb.append("\n");

//         ===== Role =====
        sb.append("You are an expert Java refactoring agent.\n");
        sb.append("You specialize in clone removal via Extract Method and improving software quality ");
        sb.append("(readability, maintainability, cohesion, and low coupling).\n");
        sb.append("Follow the constraints and output format EXACTLY.\n");
        sb.append("Do not skip the refactoring task. If the target clone can be extracted with parameters, you must still perform Extract Method.\n");
        sb.append("If anything is unclear, prefer the most conservative valid Extract Method that targets the provided clone rather than leaving the file unchanged.\n\n");

        // ===== Working Set =====
        sb.append("=== WORKING SET (Single File Only) ===\n");
        sb.append("File name: ").append(fileName).append("\n");
        sb.append("You are given the FULL file source below. You may ONLY modify this file.\n");
        sb.append("```\n").append(fileSource).append("\n```\n\n");

        // ===== Context =====
        sb.append("=== CONTEXT ===\n");
        sb.append("- Language: Java\n");
        sb.append("- Goal: Remove duplicated code (clone) with Extract Method if clones are worth refactoring.\n");
        sb.append("- Constraints: Preserve public API, preserve package/imports, keep semantics.\n\n");

        // ===== Clone Context =====
        sb.append("=== CLONE CONTEXT ===\n");
        sb.append("Clone ID: ").append(clone.id).append("\n");
        if (clone.ranges != null && !clone.ranges.isEmpty()) {
            sb.append("Approximate target clone ranges (secondary location hints):\n");
            for (CloneRange range : clone.ranges) {
                sb.append("- lines ").append(range.startLine)
                  .append(" to ").append(range.endLine).append("\n");
            }
            sb.append("\n");
        }
        if (clone.cloneCode != null && !clone.cloneCode.trim().isEmpty()) {
            sb.append("Representative clone snippet (MANDATORY refactoring target):\n");
            sb.append("The refactoring must focus on occurrences corresponding to this snippet.\n");
            sb.append("Use the ranges above only as approximate location hints.\n");
            sb.append("If the snippet and ranges do not align perfectly, prioritize the snippet.\n");
            sb.append("Do NOT ignore this snippet and do NOT switch to a different duplicated region.\n");
            sb.append("```\n").append(clone.cloneCode).append("\n```\n\n");
        }

//        if (clone.reason != null && !clone.reason.trim().isEmpty()) {
//            sb.append("Reason for refactoring: ").append(clone.reason).append("\n\n");
//        }

        if (ragExamples != null && !ragExamples.trim().isEmpty()) {
            sb.append("=== FEW-SHOT GUIDANCE (RAG) ===\n");
            sb.append("Use these examples only as guidance for structure and style. Do NOT copy verbatim.\n");
            sb.append(ragExamples).append("\n\n");
        }

        // ===== Refactoring Task (pattern + intent) =====
        sb.append("=== REFACTORING TASK ===\n");
        sb.append("Refactoring Pattern: Extract Method (ONLY)\n");
        sb.append("Intent: To improve maintainability and reduce duplication while preserving behavior.\n");
        sb.append("Requirements:\n");
        sb.append("1) Refactor the provided target clone, not some other duplication in the file.\n");
        sb.append("2) Extract the duplicated logic into one private helper method.\n");
        sb.append("3) Replace ALL target clone occurrences with calls to the extracted method.\n");
        sb.append("4) Prefer a parameterized helper method when the clone occurrences have small local differences.\n");
        sb.append("5) Choose the smallest safe extraction boundary that still removes the target duplication.\n");
        sb.append("6) Ensure the result compiles as valid Java.\n");
        sb.append("7) Do not leave the target clone unrefactored unless Extract Method is truly impossible within this file.\n\n");

        // ===== Steps (structured, but must not be printed) =====
        sb.append("=== STEPS TO FOLLOW (DO NOT OUTPUT THESE STEPS) ===\n");
        sb.append("Step 1: Use the provided clone snippet as the primary target. Use the clone ranges only as approximate hints to locate the snippet in the file.\n");
        sb.append("Step 2: Locate only the occurrences that correspond to that target clone.\n");
        sb.append("Step 3: Create a private helper method with a clear name and parameters if needed.\n");
        sb.append("Step 4: Replace the target clone occurrences with helper calls.\n");
        sb.append("Step 5: Re-check for compilation issues (imports, generics, visibility, checked exceptions).\n\n");

        // ===== Strict Constraints (hard rules) =====
        sb.append("=== STRICT CONSTRAINTS (HARD RULES) ===\n");
        sb.append("- Modify ONLY this file. Do NOT reference or require changes in other files.\n");
        sb.append("- Preserve the package line and ALL import statements EXACTLY (do not add/remove/reorder).\n");
        sb.append("- Do NOT change public/protected method signatures or class public API.\n");
        sb.append("- Do NOT introduce new classes or new files.\n");
        sb.append("- The provided clone snippet is the mandatory refactoring target.\n");
        sb.append("- The clone ranges are approximate hints and must not override the snippet.\n");
        sb.append("- Do NOT refactor unrelated duplicated code elsewhere in the file.\n");
        sb.append("- Do NOT leave the target clone unchanged if a valid Extract Method can be applied within this file.\n");
        sb.append("- If exact matching is difficult, prefer the closest valid extraction centered on the target clone.\n");
        sb.append("- Do NOT change external behavior; keep outputs and side effects the same.\n");
        sb.append("- Minimize edits outside the target clone regions.\n");
        sb.append("- Do NOT add explanatory prose outside code.\n\n");

        List<OccurrenceSpec> occurrences = buildOccurrenceSpecs(clone, fileSource);
        if (!occurrences.isEmpty()) {
            sb.append("=== TARGET OCCURRENCES ===\n");
            sb.append("You must refactor ALL of the following occurrences.\n");
            for (OccurrenceSpec occurrence : occurrences) {
                sb.append(occurrence.occurrenceId);
                if (occurrence.preferredRange != null) {
                    sb.append(" (approximate lines ")
                      .append(occurrence.preferredRange.startLine)
                      .append("-")
                      .append(occurrence.preferredRange.endLine)
                      .append(")");
                }
                sb.append(":\n");
                sb.append("```\n").append(occurrence.snippet).append("\n```\n\n");
            }
        }

        // ===== Output Format (partial refactor + local reinsertion) =====
        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("- Output ONLY a valid JSON object.\n");
        sb.append("- Do NOT return the whole file.\n");
        sb.append("- Do NOT use markdown fences.\n");
        sb.append("- The JSON must have this exact shape:\n");
        sb.append("{\n");
        sb.append("  \"helper_method\": \"full private helper method declaration only\",\n");
        sb.append("  \"occurrence_replacements\": [\n");
        sb.append("    {\n");
        sb.append("      \"occurrence_id\": \"OCCURRENCE_1\",\n");
        sb.append("      \"replacement_code\": \"only the code that should replace that occurrence\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("- `helper_method` must contain only the extracted helper method, not the class or file.\n");
        sb.append("- Each `replacement_code` must contain only the replacement for that occurrence, not the surrounding method or file.\n");
        sb.append("- If an occurrence snippet includes a full method for location context, prefer returning only the updated cloned statements or helper call for that method body.\n");
        sb.append("- Include one replacement entry for every listed occurrence.\n");
        sb.append("- Do NOT omit `occurrence_replacements`, even if all replacements are similar.\n");
        sb.append("- Do NOT include explanation text before or after the JSON.\n");

        return sb.toString();
    }

    private String buildFeedbackRevisionPrompt(String fileName,
                                               String fileSource,
                                               DetectedClone clone,
                                               String feedback) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are revising a previous Java Extract Method refactoring attempt.\n");
        sb.append("The previous attempt was rejected by the usefulness checker.\n");
        sb.append("Use the feedback below as the PRIMARY instruction for the retry.\n");
        sb.append("Do not explain your reasoning. Do not describe the issue. Output ONLY the required JSON object.\n\n");

        sb.append("=== FEEDBACK ===\n");
        sb.append(feedback == null ? "" : feedback.trim()).append("\n\n");

        sb.append("=== WORKING SET (Single File Only) ===\n");
        sb.append("File name: ").append(fileName).append("\n");
        sb.append("You may ONLY modify this file.\n");
        sb.append("```\n").append(fileSource).append("\n```\n\n");

        sb.append("=== CLONE CONTEXT ===\n");
        sb.append("Clone ID: ").append(clone.id).append("\n");
        if (clone.ranges != null && !clone.ranges.isEmpty()) {
            sb.append("Approximate target clone ranges:\n");
            for (CloneRange range : clone.ranges) {
                sb.append("- lines ").append(range.startLine)
                        .append(" to ").append(range.endLine).append("\n");
            }
            sb.append("\n");
        }
        if (clone.cloneCode != null && !clone.cloneCode.trim().isEmpty()) {
            sb.append("Representative clone snippet:\n");
            sb.append("```\n").append(clone.cloneCode).append("\n```\n\n");
        }

        List<OccurrenceSpec> occurrences = buildOccurrenceSpecs(clone, fileSource);
        if (!occurrences.isEmpty()) {
            sb.append("=== TARGET OCCURRENCES ===\n");
            sb.append("You must refactor ALL of these occurrences.\n");
            for (OccurrenceSpec occurrence : occurrences) {
                sb.append(occurrence.occurrenceId);
                if (occurrence.preferredRange != null) {
                    sb.append(" (approximate lines ")
                            .append(occurrence.preferredRange.startLine)
                            .append("-")
                            .append(occurrence.preferredRange.endLine)
                            .append(")");
                }
                sb.append(":\n");
                sb.append("```\n").append(occurrence.snippet).append("\n```\n\n");
            }
        }

        sb.append("=== STRICT REQUIREMENTS ===\n");
        sb.append("- Apply Extract Method only.\n");
        sb.append("- Follow the usefulness feedback above exactly.\n");
        sb.append("- Modify only this file.\n");
        sb.append("- Preserve package/imports and public API.\n");
        sb.append("- Replace all target occurrences with helper calls.\n");
        sb.append("- Do not return prose, analysis, markdown fences, or partial method sketches.\n\n");

        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("- Output ONLY a valid JSON object.\n");
        sb.append("- Do NOT return the whole file.\n");
        sb.append("- Do NOT use markdown fences.\n");
        sb.append("- The JSON must have this exact shape:\n");
        sb.append("{\n");
        sb.append("  \"helper_method\": \"full private helper method declaration only\",\n");
        sb.append("  \"occurrence_replacements\": [\n");
        sb.append("    {\n");
        sb.append("      \"occurrence_id\": \"OCCURRENCE_1\",\n");
        sb.append("      \"replacement_code\": \"only the code that should replace that occurrence\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("- `helper_method` must contain only the extracted helper method, not the class or file.\n");
        sb.append("- Each `replacement_code` must contain only the replacement for that occurrence.\n");
        sb.append("- If an occurrence snippet includes a full method for location context, prefer returning only the updated cloned statements or helper call for that method body.\n");
        sb.append("- Include one replacement entry for every listed occurrence.\n");
        sb.append("- Do NOT include explanation text before or after the JSON.\n");

        return sb.toString();
    }

    private StructuredRefactorOutcome tryApplyStructuredRefactor(String rawOutput, String fileSource, DetectedClone clone) {
        StructuredRefactorOutcome outcome = new StructuredRefactorOutcome();
        String jsonStr = extractJsonSubstring(rawOutput);
        if (jsonStr == null) return outcome;

        try {
            JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
            boolean looksLikePartialPlan = obj.has("helper_method")
                    || obj.has("helperMethod")
                    || obj.has("occurrence_replacements")
                    || obj.has("occurrenceReplacements")
                    || obj.has("replacements")
                    || obj.has("occurrences")
                    || obj.has("refactored_occurrences");
            if (!looksLikePartialPlan) return outcome;

            outcome.recognized = true;

            PartialRefactorPlan plan = new PartialRefactorPlan();
            plan.helperMethod = getString(obj, "helper_method", "helperMethod");
            JsonArray replacementsJson = getArray(
                    obj,
                    "occurrence_replacements",
                    "occurrenceReplacements",
                    "replacements",
                    "occurrences",
                    "refactored_occurrences",
                    "refactoredOccurrences"
            );
            plan.occurrenceReplacements = parseOccurrenceReplacements(replacementsJson, buildOccurrenceSpecs(clone, fileSource));

            if ((plan.helperMethod == null || plan.helperMethod.isBlank()) && (plan.occurrenceReplacements == null || plan.occurrenceReplacements.isEmpty())) {
                outcome.error = "Structured JSON was recognized, but both helper_method and occurrence replacements were missing.";
                return outcome;
            }
            outcome.helperMethod = plan.helperMethod == null ? "" : plan.helperMethod;
            outcome.occurrenceReplacements = plan.occurrenceReplacements == null
                    ? List.of()
                    : List.copyOf(plan.occurrenceReplacements);
            outcome.newSource = applyPartialRefactorPlan(fileSource, clone, plan);
            return outcome;
        } catch (JsonSyntaxException e) {
            outcome.error = "Invalid JSON: " + e.getMessage();
            return outcome;
        } catch (IllegalStateException e) {
            outcome.error = e.getMessage();
            return outcome;
        }
    }

    private List<OccurrenceRewrite> parseOccurrenceReplacements(JsonArray arr, List<OccurrenceSpec> orderedSpecs) {
        List<OccurrenceRewrite> out = new ArrayList<>();
        if (arr == null) return out;
        int inferredIndex = 0;
        for (JsonElement el : arr) {
            OccurrenceRewrite rewrite = new OccurrenceRewrite();
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                rewrite.occurrenceId = getString(obj, "occurrence_id", "occurrenceId", "id");
                rewrite.replacementCode = getString(
                        obj,
                        "replacement_code",
                        "replacementCode",
                        "refactored_code",
                        "refactoredCode",
                        "replacement",
                        "code",
                        "updated_code",
                        "updatedCode"
                );
            } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                rewrite.replacementCode = el.getAsString();
            } else {
                continue;
            }
            if ((rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank()) && orderedSpecs != null && inferredIndex < orderedSpecs.size()) {
                rewrite.occurrenceId = orderedSpecs.get(inferredIndex).occurrenceId;
            }
            if (rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank()) continue;
            if (rewrite.replacementCode == null) rewrite.replacementCode = "";
            out.add(rewrite);
            inferredIndex++;
        }
        return out;
    }

    private String applyPartialRefactorPlan(String fileSource, DetectedClone clone, PartialRefactorPlan plan) {
        List<OccurrenceSpec> specs = buildOccurrenceSpecs(clone, fileSource);
        if (specs.isEmpty()) {
            throw new IllegalStateException("No occurrence snippets available for partial refactoring");
        }
        recalibrateOccurrenceSpecs(specs, fileSource);

        Map<String, OccurrenceRewrite> rewritesById = new LinkedHashMap<>();
        if (plan.occurrenceReplacements != null) {
            for (OccurrenceRewrite rewrite : plan.occurrenceReplacements) {
                if (rewrite == null || rewrite.occurrenceId == null || rewrite.occurrenceId.isBlank()) continue;
                rewritesById.put(rewrite.occurrenceId, rewrite);
            }
        }

        List<ResolvedReplacement> resolved = new ArrayList<>();
        List<TextSpan> usedSpans = new ArrayList<>();
        for (OccurrenceSpec spec : specs) {
            OccurrenceRewrite rewrite = rewritesById.get(spec.occurrenceId);
            if (rewrite == null || rewrite.replacementCode == null || rewrite.replacementCode.isBlank()) {
                throw new IllegalStateException("Missing replacement_code for " + spec.occurrenceId);
            }
            TextSpan span = locateOccurrence(fileSource, spec, usedSpans);
            if (span == null) {
                throw new IllegalStateException(
                        "Could not locate source for " + spec.occurrenceId +
                                formatPreferredRange(spec.preferredRange) +
                                " snippet=" + previewSnippet(spec.snippet, 160)
                );
            }
            ReplacementTarget target = selectReplacementTarget(fileSource, span, rewrite.replacementCode);
            usedSpans.add(target.span);
            resolved.add(new ResolvedReplacement(target.span, target.newText, target.preformatted));
        }

        resolved.sort((a, b) -> Integer.compare(b.span.start, a.span.start));
        String updated = fileSource;
        for (ResolvedReplacement replacement : resolved) {
            String originalText = updated.substring(replacement.span.start, replacement.span.end);
            String adjusted = replacement.preformatted
                    ? replacement.newText
                    : reindentLikeOriginal(replacement.newText, originalText);
            updated = updated.substring(0, replacement.span.start) + adjusted + updated.substring(replacement.span.end);
        }

        String helperMethod = plan.helperMethod == null ? "" : plan.helperMethod.trim();
        if (helperMethod.isBlank()) {
            throw new IllegalStateException("Missing helper_method in partial refactor response");
        }

        int anchorPosition = resolved.isEmpty() ? -1 : resolved.get(resolved.size() - 1).span.start;
        int insertPos = findEnclosingTypeInsertionPoint(updated, anchorPosition);
        String memberIndent = detectMemberIndent(updated, insertPos);
        String helperBlock = reindentBlock(helperMethod, memberIndent);
        String insertion = "\n\n" + helperBlock + "\n";
        return updated.substring(0, insertPos) + insertion + updated.substring(insertPos);
    }

    private List<OccurrenceSpec> buildOccurrenceSpecs(DetectedClone clone, String fileSource) {
        List<OccurrenceSpec> specs = new ArrayList<>();
        if (clone == null) return specs;

        int counter = 1;
        Set<String> usedKeys = new LinkedHashSet<>();
        List<String> orderedCloneCodes = collectOrderedCloneCodes(clone, fileSource);
        int occurrenceCount = Math.max(
                orderedCloneCodes.size(),
                clone.ranges == null ? 0 : clone.ranges.size()
        );

        for (int i = 0; i < occurrenceCount; i++) {
            CloneRange range = getRange(clone.ranges, i);
            String snippet = i < orderedCloneCodes.size() ? orderedCloneCodes.get(i) : "";
            if ((snippet == null || snippet.isBlank()) && range != null) {
                snippet = sliceByLineRange(fileSource, range);
            }
            if ((snippet == null || snippet.isBlank()) && clone.cloneCode != null && !clone.cloneCode.isBlank()) {
                snippet = clone.cloneCode;
            }
            if (snippet == null || snippet.isBlank()) continue;

            String key = snippet + "::" + rangeKey(range);
            if (usedKeys.add(key)) {
                specs.add(new OccurrenceSpec("OCCURRENCE_" + counter++, snippet, range));
            }
        }

        if (specs.isEmpty() && clone.cloneCode != null && !clone.cloneCode.isBlank()) {
            specs.add(new OccurrenceSpec("OCCURRENCE_1", clone.cloneCode, getRange(clone.ranges, 0)));
        }

        return specs;
    }

    private List<String> collectOrderedCloneCodes(DetectedClone clone, String fileSource) {
        List<String> out = new ArrayList<>();
        if (clone == null) return out;

        if (clone.cloneCodes != null) {
            for (String code : clone.cloneCodes) {
                out.add(code == null ? "" : code);
            }
        }

        if (out.isEmpty()) {
            if (clone.cloneCodeA != null && !clone.cloneCodeA.isBlank()) out.add(clone.cloneCodeA);
            if (clone.cloneCodeB != null && !clone.cloneCodeB.isBlank()) out.add(clone.cloneCodeB);
        }

        int rangeCount = clone.ranges == null ? 0 : clone.ranges.size();
        while (out.size() < rangeCount) {
            CloneRange range = getRange(clone.ranges, out.size());
            out.add(range == null ? "" : sliceByLineRange(fileSource, range));
        }
        return out;
    }

    private void recalibrateOccurrenceSpecs(List<OccurrenceSpec> specs, String fileSource) {
        if (specs == null || specs.isEmpty() || fileSource == null || fileSource.isBlank()) return;

        List<TextSpan> usedSpans = new ArrayList<>();
        for (OccurrenceSpec spec : specs) {
            if (spec == null || spec.snippet == null || spec.snippet.isBlank()) continue;

            TextSpan resolved = null;
            if (spec.preferredRange != null && rangeLikelyContainsSnippet(fileSource, spec.preferredRange, spec.snippet)) {
                resolved = locateOccurrence(fileSource, spec, usedSpans);
            }

            if (resolved == null) {
                OccurrenceSpec relaxed = new OccurrenceSpec(spec.occurrenceId, spec.snippet, null);
                resolved = locateOccurrence(fileSource, relaxed, usedSpans);
            }

            if (resolved != null) {
                usedSpans.add(resolved);
                spec.preferredRange = toCloneRange(fileSource, resolved);
                continue;
            }

            if (spec.preferredRange != null) {
                String rangeSnippet = sliceByLineRange(fileSource, spec.preferredRange);
                if (rangeSnippet != null && !rangeSnippet.isBlank()) {
                    spec.snippet = rangeSnippet;
                }
            }
        }
    }

    private boolean rangeLikelyContainsSnippet(String source, CloneRange range, String snippet) {
        if (source == null || source.isBlank() || range == null || snippet == null || snippet.isBlank()) return false;
        String rangeText = sliceByLineRange(source, range);
        if (rangeText == null || rangeText.isBlank()) return false;
        if (rangeText.contains(snippet)) return true;

        String normalizedRange = normalizeWhitespaceFree(rangeText);
        String normalizedSnippet = normalizeWhitespaceFree(snippet);
        if (normalizedRange.isBlank() || normalizedSnippet.isBlank()) return false;
        return normalizedRange.contains(normalizedSnippet) || normalizedSnippet.contains(normalizedRange);
    }

    private CloneRange toCloneRange(String source, TextSpan span) {
        if (source == null || source.isBlank() || span == null) return null;
        int[] lineStarts = computeLineStarts(source);
        int startLine = lineOfOffset(lineStarts, span.start);
        int endLine = lineOfOffset(lineStarts, Math.max(span.start, span.end - 1));
        return new CloneRange(startLine, endLine);
    }

    private String sliceByLineRange(String source, CloneRange range) {
        if (source == null || source.isBlank() || range == null) return "";
        String normalized = normalizeNewlines(source);
        String[] lines = normalized.split("\n", -1);
        int start = Math.max(1, range.startLine);
        int end = Math.max(start, range.endLine);
        if (lines.length == 0) return "";

        int startIdx = Math.min(lines.length, start) - 1;
        int endIdx = Math.min(lines.length, end) - 1;
        if (startIdx < 0 || endIdx < startIdx || startIdx >= lines.length) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i <= endIdx; i++) {
            if (i > startIdx) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private CloneRange getRange(List<CloneRange> ranges, int index) {
        if (ranges == null || index < 0 || index >= ranges.size()) return null;
        return ranges.get(index);
    }

    private String rangeKey(CloneRange range) {
        if (range == null) return "";
        return range.startLine + ":" + range.endLine;
    }

    private TextSpan locateOccurrence(String source, OccurrenceSpec spec, List<TextSpan> usedSpans) {
        List<TextSpan> candidates = findExactCandidates(source, spec.snippet);
        if (candidates.isEmpty()) {
            candidates = findTrimmedLineCandidates(source, spec.snippet);
        }
        if (candidates.isEmpty()) {
            candidates = findWhitespaceNormalizedCandidates(source, spec.snippet);
        }
        if (candidates.isEmpty()) return null;

        int[] lineStarts = computeLineStarts(source);
        TextSpan best = null;
        long bestScore = Long.MAX_VALUE;
        for (TextSpan candidate : candidates) {
            if (overlapsUsed(candidate, usedSpans)) continue;
            long score = 0;
            if (spec.preferredRange != null) {
                int line = lineOfOffset(lineStarts, candidate.start);
                score = Math.abs((long) line - spec.preferredRange.startLine);
            }
            if (best == null || score < bestScore || (score == bestScore && candidate.start < best.start)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private ReplacementTarget selectReplacementTarget(String source,
                                                      TextSpan locatedSpan,
                                                      String replacementCode) {
        if (source == null || locatedSpan == null) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }
        if (replacementCode == null || replacementCode.isBlank()) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }
        if (looksLikeWholeMethodText(replacementCode)) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }

        MethodTextSpan methodSpan = resolveWholeMethodSpan(source, locatedSpan);
        if (methodSpan == null || methodSpan.bodyStart >= methodSpan.bodyEnd) {
            return new ReplacementTarget(locatedSpan, replacementCode, false);
        }

        String originalBody = source.substring(methodSpan.bodyStart, methodSpan.bodyEnd);
        String leadingWhitespace = leadingWhitespace(originalBody);
        String trailingWhitespace = trailingWhitespace(originalBody);
        String bodyCore = originalBody.substring(
                Math.min(leadingWhitespace.length(), originalBody.length()),
                Math.max(Math.min(leadingWhitespace.length(), originalBody.length()),
                        originalBody.length() - trailingWhitespace.length())
        );
        String indentBasis = bodyCore.isBlank() ? originalBody : bodyCore;
        String adjustedBody = reindentLikeOriginal(replacementCode, indentBasis);
        String wrappedBody = leadingWhitespace + adjustedBody + trailingWhitespace;
        return new ReplacementTarget(
                new TextSpan(methodSpan.bodyStart, methodSpan.bodyEnd),
                wrappedBody,
                true
        );
    }

    private boolean overlapsUsed(TextSpan candidate, List<TextSpan> usedSpans) {
        if (usedSpans == null) return false;
        for (TextSpan used : usedSpans) {
            if (used == null) continue;
            if (candidate.start < used.end && used.start < candidate.end) {
                return true;
            }
        }
        return false;
    }

    private List<TextSpan> findExactCandidates(String source, String snippet) {
        List<TextSpan> out = new ArrayList<>();
        if (source == null || snippet == null || snippet.isBlank()) return out;
        int from = 0;
        while (from <= source.length()) {
            int idx = source.indexOf(snippet, from);
            if (idx < 0) break;
            out.add(new TextSpan(idx, idx + snippet.length()));
            from = idx + Math.max(1, snippet.length());
        }
        return out;
    }

    private List<TextSpan> findTrimmedLineCandidates(String source, String snippet) {
        List<TextSpan> out = new ArrayList<>();
        if (source == null || snippet == null || snippet.isBlank()) return out;

        String normalizedSource = normalizeNewlines(source);
        String normalizedSnippet = normalizeNewlines(snippet);
        String[] sourceLines = normalizedSource.split("\n", -1);
        String[] snippetLines = normalizedSnippet.split("\n", -1);

        List<String> needed = new ArrayList<>();
        for (String line : snippetLines) {
            String trimmed = normalizeLineForMatch(line);
            if (!trimmed.isEmpty()) {
                needed.add(trimmed);
            }
        }
        if (needed.isEmpty()) return out;

        int[] lineStarts = computeLineStarts(normalizedSource);
        for (int start = 0; start < sourceLines.length; start++) {
            int iSource = start;
            int iNeed = 0;

            while (iSource < sourceLines.length && sourceLines[iSource].trim().isEmpty()) iSource++;
            int candidateStart = iSource;

            while (iSource < sourceLines.length && iNeed < needed.size()) {
                String trimmed = normalizeLineForMatch(sourceLines[iSource]);
                if (trimmed.isEmpty()) {
                    iSource++;
                    continue;
                }
                if (!trimmed.equals(needed.get(iNeed))) {
                    break;
                }
                iSource++;
                iNeed++;
            }

            if (iNeed == needed.size()) {
                int endLine = Math.max(candidateStart, iSource - 1);
                int startOffset = lineStarts[Math.min(candidateStart, lineStarts.length - 1)];
                int endOffset = endLine + 1 < lineStarts.length ? lineStarts[endLine + 1] : normalizedSource.length();
                out.add(new TextSpan(startOffset, endOffset));
            }
        }
        return out;
    }

    private List<TextSpan> findWhitespaceNormalizedCandidates(String source, String snippet) {
        List<TextSpan> out = new ArrayList<>();
        if (source == null || snippet == null || snippet.isBlank()) return out;

        NormalizedMatchText normalizedSource = normalizeForWhitespaceInsensitiveMatch(source);
        String normalizedSnippet = normalizeWhitespaceFree(snippet);
        if (normalizedSnippet.isBlank()) return out;

        int from = 0;
        while (from <= normalizedSource.text.length() - normalizedSnippet.length()) {
            int idx = normalizedSource.text.indexOf(normalizedSnippet, from);
            if (idx < 0) break;
            int startOffset = normalizedSource.offsets.get(idx);
            int endOffset = normalizedSource.offsets.get(idx + normalizedSnippet.length() - 1) + 1;
            out.add(new TextSpan(startOffset, endOffset));
            from = idx + Math.max(1, normalizedSnippet.length());
        }
        return out;
    }

    private int[] computeLineStarts(String source) {
        String normalized = normalizeNewlines(source);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n' && i + 1 <= normalized.length()) {
                starts.add(i + 1);
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) arr[i] = starts.get(i);
        return arr;
    }

    private int lineOfOffset(int[] lineStarts, int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = lineStarts[mid];
            int next = (mid + 1 < lineStarts.length) ? lineStarts[mid + 1] : Integer.MAX_VALUE;
            if (offset < start) {
                hi = mid - 1;
            } else if (offset >= next) {
                lo = mid + 1;
            } else {
                return mid + 1;
            }
        }
        return Math.max(1, Math.min(lineStarts.length, lo + 1));
    }

    private int findEnclosingTypeInsertionPoint(String source, int anchorPosition) {
        if (source == null || source.isEmpty()) return 0;
        int anchor = anchorPosition >= 0 ? anchorPosition : source.length() - 1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(class|interface|enum|record)\\b[^\\{;]*\\{")
                .matcher(source);

        int bestClose = -1;
        int bestSpan = Integer.MAX_VALUE;
        while (matcher.find()) {
            int openBrace = source.indexOf('{', matcher.start());
            if (openBrace < 0) continue;
            int closeBrace = findMatchingBrace(source, openBrace);
            if (closeBrace <= openBrace) continue;
            if (openBrace < anchor && anchor < closeBrace) {
                int span = closeBrace - openBrace;
                if (span < bestSpan) {
                    bestSpan = span;
                    bestClose = closeBrace;
                }
            }
        }

        if (bestClose >= 0) return bestClose;
        int fallback = source.lastIndexOf('}');
        return fallback >= 0 ? fallback : source.length();
    }

    private int findMatchingBrace(String source, int openBrace) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private MethodTextSpan resolveWholeMethodSpan(String source, TextSpan span) {
        if (source == null || source.isBlank() || span == null) return null;
        int start = Math.max(0, Math.min(span.start, source.length()));
        int end = Math.max(start, Math.min(span.end, source.length()));
        if (start >= end) return null;

        while (start < end && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        if (start >= end) return null;

        int openBrace = source.indexOf('{', start);
        if (openBrace < 0 || openBrace >= end) return null;

        int closeBrace = findMatchingBrace(source, openBrace);
        if (closeBrace < 0 || closeBrace + 1 != end) return null;

        String header = source.substring(start, openBrace).trim();
        if (!looksLikeMethodHeader(header)) return null;

        return new MethodTextSpan(start, end, openBrace + 1, closeBrace);
    }

    private boolean looksLikeWholeMethodText(String text) {
        if (text == null || text.isBlank()) return false;
        return resolveWholeMethodSpan(text, new TextSpan(0, text.length())) != null;
    }

    private boolean looksLikeMethodHeader(String header) {
        if (header == null) return false;
        String normalized = normalizeLineForMatch(header);
        if (normalized.isEmpty()) return false;

        String lower = normalized.toLowerCase(Locale.ROOT);
        String[] rejectedPrefixes = {
                "if ", "for ", "while ", "switch ", "try", "catch ", "do", "else",
                "synchronized ", "class ", "interface ", "enum ", "record ", "new "
        };
        for (String prefix : rejectedPrefixes) {
            if (lower.startsWith(prefix)) return false;
        }
        if (normalized.contains("->") || normalized.contains("=")) return false;

        int openParen = normalized.indexOf('(');
        int closeParen = normalized.lastIndexOf(')');
        if (openParen <= 0 || closeParen < openParen) return false;

        String beforeParen = normalized.substring(0, openParen).trim();
        if (beforeParen.isEmpty()) return false;
        int split = Math.max(beforeParen.lastIndexOf(' '), beforeParen.lastIndexOf('.'));
        String candidateName = beforeParen.substring(split + 1).trim();
        return candidateName.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    private String leadingWhitespace(String text) {
        if (text == null || text.isEmpty()) return "";
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return text.substring(0, index);
    }

    private String trailingWhitespace(String text) {
        if (text == null || text.isEmpty()) return "";
        int index = text.length();
        while (index > 0 && Character.isWhitespace(text.charAt(index - 1))) {
            index--;
        }
        return text.substring(index);
    }

    private String detectMemberIndent(String source, int insertPos) {
        int lineStart = Math.max(0, source.lastIndexOf('\n', Math.max(0, insertPos - 1)) + 1);
        int i = lineStart;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return source.substring(lineStart, i) + "    ";
    }

    private String reindentLikeOriginal(String newText, String originalText) {
        String baseIndent = firstNonEmptyIndent(originalText);
        return reindentBlock(newText, baseIndent);
    }

    private String reindentBlock(String block, String baseIndent) {
        if (block == null) return "";
        String normalized = normalizeNewlines(block).strip();
        if (normalized.isEmpty()) return "";

        String[] lines = normalized.split("\n", -1);
        int commonIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            commonIndent = Math.min(commonIndent, countIndent(line));
        }
        if (commonIndent == Integer.MAX_VALUE) commonIndent = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) sb.append("\n");
            if (line.trim().isEmpty()) continue;
            int cut = Math.min(commonIndent, countIndent(line));
            sb.append(baseIndent).append(line.substring(cut));
        }
        return sb.toString();
    }

    private int countIndent(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return i;
    }

    private String firstNonEmptyIndent(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = normalizeNewlines(text);
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                int indent = countIndent(line);
                return line.substring(0, indent);
            }
        }
        return "";
    }

    private String normalizeNewlines(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String normalizeLineForMatch(String line) {
        if (line == null) return "";
        return line.replaceAll("\\s+", " ").trim();
    }

    private NormalizedMatchText normalizeForWhitespaceInsensitiveMatch(String text) {
        String normalized = normalizeNewlines(text);
        StringBuilder sb = new StringBuilder(normalized.length());
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            sb.append(ch);
            offsets.add(i);
        }
        return new NormalizedMatchText(sb.toString(), offsets);
    }

    private String normalizeWhitespaceFree(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = normalizeNewlines(text);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!Character.isWhitespace(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    private String formatPreferredRange(CloneRange range) {
        if (range == null) return "";
        return " near lines " + range.startLine + "-" + range.endLine;
    }

    private String previewSnippet(String snippet, int maxChars) {
        if (snippet == null) return "\"\"";
        String text = normalizeNewlines(snippet).replace("\n", "\\n").trim();
        if (text.length() > maxChars) {
            text = text.substring(0, Math.max(0, maxChars)) + "...";
        }
        return "\"" + text + "\"";
    }

    private String getString(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                return obj.get(key).getAsString();
            } catch (Throwable ignored) {
                // ignore and continue
            }
        }
        return null;
    }

    private JsonArray getArray(JsonObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key) || obj.get(key).isJsonNull()) continue;
            try {
                return obj.getAsJsonArray(key);
            } catch (Throwable ignored) {
                // ignore and continue
            }
        }
        return null;
    }

    private String optional(String value) {
        return value == null ? "" : value;
    }

    private static class ResolvedReplacement {
        public TextSpan span;
        public String newText;
        public boolean preformatted;

        public ResolvedReplacement(TextSpan span, String newText, boolean preformatted) {
            this.span = span;
            this.newText = newText == null ? "" : newText;
            this.preformatted = preformatted;
        }
    }

    private static final class MethodTextSpan {
        private final int start;
        private final int end;
        private final int bodyStart;
        private final int bodyEnd;

        private MethodTextSpan(int start, int end, int bodyStart, int bodyEnd) {
            this.start = start;
            this.end = end;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    private static final class ReplacementTarget {
        private final TextSpan span;
        private final String newText;
        private final boolean preformatted;

        private ReplacementTarget(TextSpan span, String newText, boolean preformatted) {
            this.span = span;
            this.newText = newText == null ? "" : newText;
            this.preformatted = preformatted;
        }
    }

    private String extractJavaCodeBlock(String raw) {
        if (raw == null) return null;
        String javaFence = extractFencedCodeBlock(raw, "java");
        if (looksLikeJavaSource(javaFence)) {
            return javaFence;
        }

        String unlabeledFence = extractFencedCodeBlock(raw, "");
        if (looksLikeJavaSource(unlabeledFence)) {
            return unlabeledFence;
        }

        String trimmed = raw.trim();
        return looksLikeJavaSource(trimmed) ? trimmed : null;
    }

    private String extractFencedCodeBlock(String raw, String infoString) {
        if (raw == null) return null;
        String expected = infoString == null ? "" : infoString.trim().toLowerCase(Locale.ROOT);
        int idx = 0;
        while (idx >= 0 && idx < raw.length()) {
            int fenceStart = raw.indexOf("```", idx);
            if (fenceStart < 0) return null;

            int infoEnd = raw.indexOf('\n', fenceStart + 3);
            if (infoEnd < 0) return null;

            String actualInfo = raw.substring(fenceStart + 3, infoEnd).trim().toLowerCase(Locale.ROOT);
            int bodyStart = infoEnd + 1;
            int fenceEnd = raw.indexOf("```", bodyStart);
            String code = fenceEnd >= 0 ? raw.substring(bodyStart, fenceEnd).trim() : raw.substring(bodyStart).trim();

            if (actualInfo.equals(expected)) {
                return code.isEmpty() ? null : code;
            }
            idx = bodyStart;
        }
        return null;
    }

    private boolean looksLikeJavaSource(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false;
        return trimmed.contains("package ")
                || trimmed.contains(" class ")
                || trimmed.startsWith("class ")
                || trimmed.contains(" interface ")
                || trimmed.startsWith("interface ")
                || trimmed.contains(" enum ")
                || trimmed.startsWith("enum ")
                || trimmed.contains(" record ")
                || trimmed.startsWith("record ");
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
