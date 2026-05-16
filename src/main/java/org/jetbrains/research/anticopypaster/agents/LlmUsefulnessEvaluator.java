package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LlmUsefulnessEvaluator {

    private LlmUsefulnessEvaluator() {}

    public enum CloneKind {
        WHOLE_METHOD,
        FRAGMENT
    }

    @FunctionalInterface
    public interface LabeledLlmCaller {
        String call(String label, String prompt) throws Exception;
    }

    public static final class UsefulnessInput {
        public final String fileName;
        public final CloneKind cloneKind;
        public final String cloneContext;
        public final String focusedBeforeCode;
        public final String focusedAfterCode;

        public UsefulnessInput(String fileName,
                               CloneKind cloneKind,
                               String cloneContext,
                               String focusedBeforeCode,
                               String focusedAfterCode) {
            this.fileName = safe(fileName);
            this.cloneKind = cloneKind == null ? CloneKind.WHOLE_METHOD : cloneKind;
            this.cloneContext = safe(cloneContext);
            this.focusedBeforeCode = safe(focusedBeforeCode);
            this.focusedAfterCode = safe(focusedAfterCode);
        }
    }

    public static final class PanelistResult {
        public final String panelistId;
        public final List<String> checkedCategories;
        public final boolean parsed;
        public final boolean useful;
        public final List<String> matchedCategories;
        public final String summary;
        public final String feedback;
        public final String rawResponse;
        public final String error;

        public PanelistResult(String panelistId,
                              List<String> checkedCategories,
                              boolean parsed,
                              boolean useful,
                              List<String> matchedCategories,
                              String summary,
                              String feedback,
                              String rawResponse,
                              String error) {
            this.panelistId = safe(panelistId);
            this.checkedCategories = checkedCategories == null ? List.of() : List.copyOf(checkedCategories);
            this.parsed = parsed;
            this.useful = useful;
            this.matchedCategories = matchedCategories == null ? List.of() : List.copyOf(matchedCategories);
            this.summary = safe(summary);
            this.feedback = safe(feedback);
            this.rawResponse = safe(rawResponse);
            this.error = safe(error);
        }
    }

    public static final class CuratorResult {
        public final boolean parsed;
        public final boolean useful;
        public final List<String> reasons;
        public final String summary;
        public final String feedback;
        public final double confidence;
        public final String rawResponse;
        public final String error;

        public CuratorResult(boolean parsed,
                             boolean useful,
                             List<String> reasons,
                             String summary,
                             String feedback,
                             double confidence,
                             String rawResponse,
                             String error) {
            this.parsed = parsed;
            this.useful = useful;
            this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
            this.summary = safe(summary);
            this.feedback = safe(feedback);
            this.confidence = confidence;
            this.rawResponse = safe(rawResponse);
            this.error = safe(error);
        }
    }

    public static final class EvaluationResult {
        public final boolean available;
        public final boolean useful;
        public final List<PanelistResult> panelistResults;
        public final CuratorResult curatorResult;
        public final String notes;

        public EvaluationResult(boolean available,
                                boolean useful,
                                List<PanelistResult> panelistResults,
                                CuratorResult curatorResult,
                                String notes) {
            this.available = available;
            this.useful = useful;
            this.panelistResults = panelistResults == null ? List.of() : List.copyOf(panelistResults);
            this.curatorResult = curatorResult;
            this.notes = safe(notes);
        }
    }

    private static final class PanelistSpec {
        final String id;
        final String title;
        final List<String> categories;

        PanelistSpec(String id, String title, List<String> categories) {
            this.id = id;
            this.title = title;
            this.categories = categories;
        }
    }

    private static final List<String> ALL_CATEGORY_NAMES = List.of(
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

    private static final List<PanelistSpec> PANELIST_SPECS = List.of(
            new PanelistSpec(
                    "P1",
                    "Usefulness Panelist 1",
                    ALL_CATEGORY_NAMES
            ),
            new PanelistSpec(
                    "P2",
                    "Usefulness Panelist 2",
                    ALL_CATEGORY_NAMES
            ),
            new PanelistSpec(
                    "P3",
                    "Usefulness Panelist 3",
                    ALL_CATEGORY_NAMES
            )
    );

    private static final Map<String, String> CATEGORY_GUIDANCE = Map.ofEntries(
            Map.entry("EXTRACT_METHOD_NOT_FOUND", "No valid Extract Method outcome is visible in the refactored code for the target clone."),
            Map.entry("INCOMPLETE_REFACTORING_DETECTED", "Some duplicated logic still remains in the original target methods after refactoring."),
            Map.entry("EXTRACTION_WITHOUT_CLONE_REPLACEMENT_DETECTED", "A helper was added, but the original duplicated body still remains instead of being replaced."),
            Map.entry("DIRECT_CLONE_REMOVAL_DETECTED", "A clone occurrence was removed directly instead of extracting shared logic."),
            Map.entry("POST_EXTRACTION_CLONE_DELETION_DETECTED", "A helper was introduced, but one target clone method was deleted afterward."),
            Map.entry("CALL_BASED_CLONE_SUBSTITUTION_DETECTED", "One target clone now calls another existing method instead of using a new shared helper."),
            Map.entry("CLONE_REMOVAL_BY_DELEGATION_DETECTED", "The change relies on delegation without clearly extracting one shared helper for all target clones."),
            Map.entry("FRAGMENTATION_OF_LOGIC_DETECTED", "Shared logic was split across multiple helpers instead of one coherent extraction."),
            Map.entry("NON_TARGET_CLONE_REFACTORING_DETECTED", "The refactoring seems to target a different duplication while the intended target remains unresolved."),
            Map.entry("EXCESSIVE_REFACTORING_DETECTED", "The extracted helper pulls in code beyond the intended cloned fragment.")
    );

    public static EvaluationResult evaluate(UsefulnessInput input, LabeledLlmCaller llmCaller) {
        if (input == null || llmCaller == null) {
            return new EvaluationResult(false, false, List.of(), null, "LLM usefulness input or caller missing");
        }

        List<PanelistResult> panelistResults = new ArrayList<>();
        for (PanelistSpec spec : PANELIST_SPECS) {
            panelistResults.add(runPanelist(spec, input, llmCaller));
        }

        CuratorResult curatorResult = runCurator(input, panelistResults, llmCaller);
        boolean available = curatorResult != null && curatorResult.parsed;
        boolean useful = available && curatorResult.useful;

        StringBuilder notes = new StringBuilder();
        notes.append("panelists=").append(panelistResults.size());
        for (PanelistResult panelistResult : panelistResults) {
            notes.append(", ")
                    .append(panelistResult.panelistId)
                    .append("=")
                    .append(panelistResult.parsed ? (panelistResult.useful ? "useful" : "not_useful") : "unparsed");
            if (!panelistResult.matchedCategories.isEmpty()) {
                notes.append(panelistResult.matchedCategories);
            }
        }
        if (curatorResult != null) {
            notes.append(", curator=").append(curatorResult.parsed ? (curatorResult.useful ? "useful" : "not_useful") : "unparsed");
            if (!curatorResult.reasons.isEmpty()) {
                notes.append(curatorResult.reasons);
            }
            if (curatorResult.confidence > 0.0d) {
                notes.append(", confidence=").append(String.format(Locale.ROOT, "%.2f", curatorResult.confidence));
            }
            if (!curatorResult.error.isBlank()) {
                notes.append(", curatorError=").append(curatorResult.error);
            }
        }

        return new EvaluationResult(available, useful, panelistResults, curatorResult, notes.toString());
    }

    private static PanelistResult runPanelist(PanelistSpec spec, UsefulnessInput input, LabeledLlmCaller llmCaller) {
        String prompt = buildPanelistPrompt(spec, input);
        String raw = "";
        try {
            raw = safe(llmCaller.call(spec.id, prompt));
        } catch (Throwable t) {
            return new PanelistResult(
                    spec.id,
                    spec.categories,
                    false,
                    false,
                    List.of(),
                    "",
                    "",
                    raw,
                    "LLM call failed: " + t.getMessage()
            );
        }
        return parsePanelistResult(spec, raw);
    }

    private static CuratorResult runCurator(UsefulnessInput input,
                                            List<PanelistResult> panelistResults,
                                            LabeledLlmCaller llmCaller) {
        String prompt = buildCuratorPrompt(input, panelistResults);
        String raw = "";
        try {
            raw = safe(llmCaller.call("CURATOR", prompt));
        } catch (Throwable t) {
            return new CuratorResult(false, false, List.of(), "", "", 0.0d, raw, "LLM call failed: " + t.getMessage());
        }
        return parseCuratorResult(raw, panelistResults);
    }

    private static String buildPanelistPrompt(PanelistSpec spec, UsefulnessInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(spec.title).append(" (").append(spec.id).append(").\n");
        sb.append("Your task is to evaluate whether a proposed Java refactoring is useful.\n");
        sb.append("You are responsible for the full category catalog listed below.\n");
        sb.append("All panelists review the same categories independently.\n");
        sb.append("If none of your assigned categories clearly apply, you must return is_useful=true.\n");
        sb.append("Return ONLY one JSON object and no extra text.\n\n");

        sb.append("=== ASSIGNED CATEGORIES ===\n");
        for (String category : spec.categories) {
            sb.append("- ").append(category).append(": ")
                    .append(CATEGORY_GUIDANCE.getOrDefault(category, "Assigned category")).append("\n");
        }
        sb.append("\n");

        sb.append("=== TARGET CONTEXT ===\n");
        sb.append("File: ").append(input.fileName).append("\n");
        sb.append("Clone kind: ").append(input.cloneKind).append("\n");
        sb.append(input.cloneContext).append("\n\n");

        sb.append("=== BEFORE (FOCUSED CONTEXT) ===\n");
        sb.append("```java\n").append(input.focusedBeforeCode).append("\n```\n\n");

        sb.append("=== AFTER (FOCUSED CONTEXT) ===\n");
        sb.append("```java\n").append(input.focusedAfterCode).append("\n```\n\n");

        sb.append("=== OUTPUT JSON ===\n");
        sb.append("{\n");
        sb.append("  \"panelist_id\": \"").append(spec.id).append("\",\n");
        sb.append("  \"is_useful\": true,\n");
        sb.append("  \"matched_categories\": [],\n");
        sb.append("  \"summary\": \"short decision summary\",\n");
        sb.append("  \"feedback\": \"specific revision instruction for the refactoring agent if not useful; otherwise empty string\"\n");
        sb.append("}\n");
        sb.append("Rules:\n");
        sb.append("- Use ONLY the assigned categories in matched_categories.\n");
        sb.append("- If you are not confident any assigned category applies, set is_useful=true and matched_categories=[].\n");
        sb.append("- feedback should be actionable and concise.\n");
        return sb.toString();
    }

    private static String buildCuratorPrompt(UsefulnessInput input, List<PanelistResult> panelistResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the usefulness curator.\n");
        sb.append("You must review three panelist outputs and make the final useful/not-useful decision.\n");
        sb.append("Apply a majority vote across the three panelists.\n");
        sb.append("- If 2 or more panelists set is_useful=false with at least one matched category, set is_useful=false.\n");
        sb.append("- If 2 or more panelists set is_useful=true, set is_useful=true.\n");
        sb.append("- If there is no majority, use your own judgment based on the strength of the evidence. Prefer is_useful=true unless the flagged category is a critical structural issue such as INCOMPLETE_REFACTORING_DETECTED or EXTRACT_METHOD_NOT_FOUND.\n");
        sb.append("Return ONLY one JSON object and no extra text.\n\n");

        sb.append("=== TARGET CONTEXT ===\n");
        sb.append("File: ").append(input.fileName).append("\n");
        sb.append("Clone kind: ").append(input.cloneKind).append("\n");
        sb.append(input.cloneContext).append("\n\n");

        sb.append("=== BEFORE (FOCUSED CONTEXT) ===\n");
        sb.append("```java\n").append(input.focusedBeforeCode).append("\n```\n\n");

        sb.append("=== AFTER (FOCUSED CONTEXT) ===\n");
        sb.append("```java\n").append(input.focusedAfterCode).append("\n```\n\n");

        sb.append("=== PANELIST OUTPUTS ===\n");
        for (PanelistResult panelistResult : panelistResults) {
            sb.append("[").append(panelistResult.panelistId).append("]\n");
            sb.append("{\n");
            sb.append("  \"parsed\": ").append(panelistResult.parsed).append(",\n");
            sb.append("  \"is_useful\": ").append(panelistResult.useful).append(",\n");
            sb.append("  \"matched_categories\": ").append(toJsonArrayLiteral(panelistResult.matchedCategories)).append(",\n");
            sb.append("  \"summary\": ").append(toJsonStringLiteral(panelistResult.summary)).append(",\n");
            sb.append("  \"feedback\": ").append(toJsonStringLiteral(panelistResult.feedback)).append(",\n");
            sb.append("  \"error\": ").append(toJsonStringLiteral(panelistResult.error)).append("\n");
            sb.append("}\n\n");
        }

        sb.append("=== OUTPUT JSON ===\n");
        sb.append("{\n");
        sb.append("  \"is_useful\": false,\n");
        sb.append("  \"reasons\": [\"CATEGORY_NAME\"],\n");
        sb.append("  \"summary\": \"short final summary\",\n");
        sb.append("  \"feedback\": \"merged revision guidance for the refactoring agent if not useful; otherwise empty string\",\n");
        sb.append("  \"confidence\": 0.84\n");
        sb.append("}\n");
        sb.append("Rules:\n");
        sb.append("- reasons must use only category names found by panelists.\n");
        sb.append("- Keep summary short and concrete.\n");
        sb.append("- feedback should directly tell the refactoring agent how to revise the code.\n");
        return sb.toString();
    }

    private static PanelistResult parsePanelistResult(PanelistSpec spec, String raw) {
        JsonObject obj = parseJsonObject(raw);
        if (obj == null) {
            return new PanelistResult(spec.id, spec.categories, false, false, List.of(), "", "", raw, "Could not parse panelist JSON");
        }

        List<String> matchedCategories = normalizeCategoryNames(getStringList(obj, "matched_categories", "matchedCategories", "reasons"));
        Boolean parsedUseful = getOptionalBoolean(obj, "is_useful", "isUseful", "useful");
        boolean useful = parsedUseful != null ? parsedUseful : matchedCategories.isEmpty();
        String summary = getString(obj, "summary", "decision_summary", "decisionSummary");
        String feedback = getString(obj, "feedback", "feedback_for_refactor_agent", "feedbackForRefactorAgent", "revision_instruction");

        return new PanelistResult(spec.id, spec.categories, true, useful, matchedCategories, summary, feedback, raw, "");
    }

    private static CuratorResult parseCuratorResult(String raw, List<PanelistResult> panelistResults) {
        JsonObject obj = parseJsonObject(raw);
        if (obj == null) {
            return buildMajorityFallbackCuratorResult(raw, panelistResults);
        }

        List<String> reasons = normalizeCategoryNames(getStringList(obj, "reasons", "matched_categories", "matchedCategories"));
        Boolean parsedUseful = getOptionalBoolean(obj, "is_useful", "isUseful", "useful");
        boolean useful = parsedUseful != null ? parsedUseful : reasons.isEmpty();
        if (reasons.isEmpty() && !useful) {
            LinkedHashSet<String> fallbackReasons = new LinkedHashSet<>();
            if (panelistResults != null) {
                for (PanelistResult panelistResult : panelistResults) {
                    fallbackReasons.addAll(panelistResult.matchedCategories);
                }
            }
            reasons = new ArrayList<>(fallbackReasons);
        }
        String summary = getString(obj, "summary", "decision_summary", "decisionSummary");
        String feedback = getString(obj, "feedback", "feedback_for_refactor_agent", "feedbackForRefactorAgent", "revision_instruction");
        double confidence = getDouble(obj, 0.0d, "confidence", "score");

        return new CuratorResult(true, useful, reasons, summary, feedback, confidence, raw, "");
    }

    private static CuratorResult buildMajorityFallbackCuratorResult(String raw, List<PanelistResult> panelistResults) {
        if (panelistResults == null || panelistResults.isEmpty()) {
            return new CuratorResult(false, false, List.of(), "", "", 0.0d, raw, "Could not parse curator JSON");
        }

        int usefulCount = 0;
        int notUsefulCount = 0;
        LinkedHashSet<String> fallbackReasons = new LinkedHashSet<>();
        StringBuilder feedback = new StringBuilder();
        for (PanelistResult panelistResult : panelistResults) {
            if (panelistResult == null || !panelistResult.parsed) continue;
            if (panelistResult.useful) {
                usefulCount++;
            } else if (!panelistResult.matchedCategories.isEmpty()) {
                notUsefulCount++;
                fallbackReasons.addAll(panelistResult.matchedCategories);
                if (panelistResult.feedback != null && !panelistResult.feedback.isBlank()) {
                    if (feedback.length() > 0) feedback.append("\n");
                    feedback.append(panelistResult.feedback);
                }
            }
        }

        boolean useful;
        if (notUsefulCount >= 2) {
            useful = false;
        } else if (usefulCount >= 2) {
            useful = true;
            fallbackReasons.clear();
            feedback.setLength(0);
        } else {
            useful = !containsCriticalUsefulnessReason(fallbackReasons);
            if (useful) {
                fallbackReasons.clear();
                feedback.setLength(0);
            }
        }

        int parsedCount = usefulCount + notUsefulCount;
        double confidence = parsedCount == 0 ? 0.0d : Math.max(usefulCount, notUsefulCount) / (double) parsedCount;
        String summary = "Curator JSON was unparseable; majority vote fallback applied (" +
                usefulCount + " useful, " + notUsefulCount + " not useful).";
        String error = "Could not parse curator JSON; majority vote fallback applied";
        return new CuratorResult(
                true,
                useful,
                new ArrayList<>(fallbackReasons),
                summary,
                feedback.toString(),
                confidence,
                raw,
                error
        );
    }

    private static boolean containsCriticalUsefulnessReason(LinkedHashSet<String> reasons) {
        if (reasons == null || reasons.isEmpty()) return false;
        for (String reason : reasons) {
            if ("INCOMPLETE_REFACTORING_DETECTED".equals(reason)
                    || "EXTRACT_METHOD_NOT_FOUND".equals(reason)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = extractJsonObject(raw);
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;

        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}') {
                if (depth == 0) continue;
                depth--;
                if (depth == 0 && start >= 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static Boolean getOptionalBoolean(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key) != null && !obj.get(key).isJsonNull()) {
                JsonElement element = obj.get(key);
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    return primitive.getAsBoolean();
                }
                if (primitive.isString()) {
                    String value = primitive.getAsString().trim();
                    if ("true".equalsIgnoreCase(value)) return true;
                    if ("false".equalsIgnoreCase(value)) return false;
                }
            }
        }
        return null;
    }

    private static double getDouble(JsonObject obj, double fallback, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key) != null && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsDouble();
                } catch (Throwable ignored) {
                }
            }
        }
        return fallback;
    }

    private static String getString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key) != null && !obj.get(key).isJsonNull()) {
                try {
                    return safe(obj.get(key).getAsString());
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    private static List<String> getStringList(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) continue;
            JsonElement element = obj.get(key);
            if (element.isJsonArray()) {
                List<String> out = new ArrayList<>();
                JsonArray arr = element.getAsJsonArray();
                for (JsonElement item : arr) {
                    if (item == null || item.isJsonNull()) continue;
                    try {
                        out.add(safe(item.getAsString()));
                    } catch (Throwable ignored) {
                    }
                }
                return out;
            }
            try {
                String single = safe(element.getAsString());
                if (!single.isBlank()) return List.of(single);
            } catch (Throwable ignored) {
            }
        }
        return List.of();
    }

    private static List<String> normalizeCategoryNames(List<String> rawCategories) {
        if (rawCategories == null || rawCategories.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawCategories) {
            String normalized = normalizeCategoryName(raw);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return new ArrayList<>(out);
    }

    private static String normalizeCategoryName(String raw) {
        String s = safe(raw);
        if (s.isBlank()) return "";
        s = s.toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (s.contains("__")) s = s.replace("__", "_");
        return s;
    }

    private static String toJsonArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(toJsonStringLiteral(values.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toJsonStringLiteral(String value) {
        String s = safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "\"" + s + "\"";
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
