package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.extractJsonObjectSubstring;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonArray;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonInt;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.getJsonString;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileJsonSupport.safeTruncate;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.buildCrossFileSourceIndex;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.cloneContainsPastedAnchor;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.resolveCrossFileSource;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.resolvePastedSnippetAnchors;
import static org.jetbrains.research.anticopypaster.workflow.CrossFileSourceSupport.resolveVerifiedOccurrence;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.llm.LlmClient;

final class CrossFileDetectionSupport {

    private static final List<CrossFilePanelistSpec> DETECTION_PANELISTS = List.of(
            new CrossFilePanelistSpec("P1", "Detection Panelist 1"),
            new CrossFilePanelistSpec("P2", "Detection Panelist 2"),
            new CrossFilePanelistSpec("P3", "Detection Panelist 3")
    );

    private CrossFileDetectionSupport() {}

    static CrossFileDetectionResult runCrossFileDetectionAgent(LlmClient llm,
                                                                       Project project,
                                                                       Consumer<String> viewer,
                                                                       List<CrossFileSource> sources,
                                                                       String pastedSnippet) {
        java.util.ArrayList<CrossFileDetectionPanelistOutcome> panelistOutcomes = new java.util.ArrayList<>();
        for (CrossFilePanelistSpec spec : DETECTION_PANELISTS) {
            String prompt = buildCrossFileDetectionPanelistPrompt(spec, sources, pastedSnippet);
            String raw = WorkflowLlmCallSupport.callDetection(llm, prompt, viewer, project);
            CrossFileDetectionResult parsed = parseCrossFileDetectionResult(raw, sources, pastedSnippet);
            panelistOutcomes.add(new CrossFileDetectionPanelistOutcome(
                    spec.id,
                    raw,
                    parsed,
                    parsed != null && parsed.parsed,
                    parsed == null ? "No detection result returned." : parsed.message
            ));
            if (parsed != null) {
                for (String warning : parsed.warnings) {
                    logStage(viewer, "DETECTION", "[" + spec.id + "] warning: " + warning);
                }
            }
        }

        String curatorPrompt = buildCrossFileDetectionCuratorPrompt(sources, pastedSnippet, panelistOutcomes);
        String curatorRaw = WorkflowLlmCallSupport.callDetection(llm, curatorPrompt, viewer, project);
        CrossFileDetectionResult curatorResult = parseCrossFileDetectionResult(curatorRaw, sources, pastedSnippet);
        if (curatorResult != null && curatorResult.parsed) {
            return curatorResult;
        }

        CrossFileDetectionResult fallback = mergeCrossFileDetectionPanelists(panelistOutcomes);
        if (curatorResult != null && curatorResult.message != null && !curatorResult.message.isBlank()) {
            fallback.warnings.add("Detection curator result could not be parsed; using panelist fallback. Curator error: "
                    + curatorResult.message);
        }
        return fallback;
    }

    static String buildCrossFileDetectionPanelistPrompt(CrossFilePanelistSpec spec,
                                                                List<CrossFileSource> sources,
                                                                String pastedSnippet) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.title).append(" (").append(spec.id).append(")\n");
        sb.append("You are one of three independent cross-file clone-detection panelists.\n");
        sb.append("Review the selected Java files independently and return your best evidence.\n");
        sb.append("Return ONLY one JSON object and no extra text.\n\n");
        appendCrossFileDetectionTask(sb, sources, pastedSnippet);
        return sb.toString();
    }

    static String buildCrossFileDetectionCuratorPrompt(List<CrossFileSource> sources,
                                                               String pastedSnippet,
                                                               List<CrossFileDetectionPanelistOutcome> panelistOutcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the detection curator.\n");
        sb.append("You must review three independent cross-file detection panelist outputs and produce the final clone detection result.\n");
        sb.append("Apply a majority vote across the three panelists.\n");
        sb.append("- If 2 or more panelists provide credible evidence of the same cross-file clone, return found_clones.\n");
        sb.append("- If 2 or more panelists report no clones or provide weak/unparsed evidence, return no_clones.\n");
        sb.append("- If exactly one panelist finds a clone, only return found_clones when the evidence has precise verified line ranges and clear structural similarity.\n");
        if (pastedSnippet != null && !pastedSnippet.isBlank()) {
            sb.append("- Because a pasted snippet anchor was provided, keep only clone groups that include an occurrence containing that pasted snippet.\n");
        }
        sb.append("Merge duplicate or overlapping clone groups when they clearly refer to the same cross-file clone class.\n");
        sb.append("Return ONLY one JSON object and no extra text.\n\n");

        appendCrossFileDetectionTask(sb, sources, pastedSnippet);

        sb.append("=== PANELIST OUTPUTS ===\n");
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        for (CrossFileDetectionPanelistOutcome outcome : panelistOutcomes) {
            sb.append("[").append(outcome.panelistId).append("]\n");
            sb.append(gson.toJson(toDetectionPanelistJson(outcome))).append("\n\n");
        }

        sb.append("=== CURATOR RULES ===\n");
        sb.append("- The final JSON must follow the exact schema above.\n");
        sb.append("- Keep only clone groups that span at least two selected files.\n");
        sb.append("- Use no_clones with an empty clones list when the panelists do not provide convincing cross-file clone evidence.\n");
        sb.append("- If you return an empty clones array, status MUST be \"no_clones\".\n");
        return sb.toString();
    }

    static void appendCrossFileDetectionTask(StringBuilder sb,
                                                     List<CrossFileSource> sources,
                                                     String pastedSnippet) {
        sb.append("You are acting as the Cross Files Detection Agent.\n");
        sb.append("Detect non-trivial duplicated Java code clones that occur ACROSS at least two selected files.\n");
        sb.append("Only report clones where the same extraction concept should affect multiple files.\n");
        sb.append("Return no_clones if duplication is only within one file or is too small/trivial to refactor.\n\n");

        if (pastedSnippet != null && !pastedSnippet.isBlank()) {
            sb.append("=== PASTED SNIPPET ANCHOR ===\n");
            sb.append("This is a HARD anchor, not a general hint.\n");
            sb.append("Only report a cross-file clone class if at least one occurrence contains this pasted snippet inside its exact line range/snippet.\n");
            sb.append("Do not search for an unrelated strongest clone elsewhere in the selected files.\n");
            sb.append("If no cross-file clone can be found around this pasted snippet, return {\"status\":\"no_clones\",\"clones\":[]}.\n");
            sb.append("```\n").append(pastedSnippet).append("\n```\n\n");
        }

        CrossFileRefactoringSupport.appendSelectedFiles(sb, sources);
        CrossFileRefactoringSupport.appendWorkingSet(sb, sources);

        sb.append("=== DETECTION RULES ===\n");
        sb.append("1) A valid cross-file clone must include occurrences in at least two different files.\n");
        sb.append("2) Prefer the strongest coherent clone class, not every possible duplicate.\n");
        sb.append("3) Give exact line ranges and the snippet for each occurrence.\n");
        sb.append("4) Do not invent line ranges. The workflow will verify every occurrence against the local source text and drop unverifiable occurrences.\n\n");
        if (pastedSnippet != null && !pastedSnippet.isBlank()) {
            sb.append("5) With a pasted snippet anchor, every returned clone class MUST include an occurrence whose verified code contains that pasted snippet.\n");
            sb.append("6) If you return an empty clones array, status MUST be \"no_clones\". Never return \"found_clones\" with no clone objects.\n\n");
        } else {
            sb.append("5) If you return an empty clones array, status MUST be \"no_clones\". Never return \"found_clones\" with no clone objects.\n\n");
        }

        appendCrossFileDetectionOutputSchema(sb);
    }

    static void appendCrossFileDetectionOutputSchema(StringBuilder sb) {
        sb.append("=== OUTPUT FORMAT ===\n");
        sb.append("Return ONLY a valid JSON object. Do not use markdown fences and do not include prose outside JSON.\n");
        sb.append("Use paths exactly as listed in SELECTED FILES.\n");
        sb.append("{\n");
        sb.append("  \"status\": \"found_clones\" | \"no_clones\" | \"failed\",\n");
        sb.append("  \"summary\": \"short explanation\",\n");
        sb.append("  \"clones\": [\n");
        sb.append("    {\n");
        sb.append("      \"id\": \"cross_clone_1\",\n");
        sb.append("      \"refactor_type\": \"Extract Method\",\n");
        sb.append("      \"reason\": \"why these occurrences form one clone class\",\n");
        sb.append("      \"occurrences\": [\n");
        sb.append("        {\n");
        sb.append("          \"path\": \"relative/path/ExactlyAsListed.java\",\n");
        sb.append("          \"start_line\": 10,\n");
        sb.append("          \"end_line\": 24,\n");
        sb.append("          \"snippet\": \"exact duplicated code occurrence\"\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
    }

    static CrossFileDetectionResult parseCrossFileDetectionResult(String raw,
                                                                         List<CrossFileSource> sources,
                                                                         String pastedSnippet) {
        CrossFileDetectionResult result = new CrossFileDetectionResult();
        String json = extractJsonObjectSubstring(raw);
        if (json == null || json.isBlank()) {
            result.message = "Could not extract JSON from Detection Agent output.";
            return result;
        }

        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            result.parsed = true;
            result.status = getJsonString(obj, "status", "result");
            result.summary = getJsonString(obj, "summary", "message", "reason");

            if ("no_clones".equalsIgnoreCase(result.status == null ? "" : result.status.trim())) {
                return result;
            }

            Map<String, CrossFileSource> index = buildCrossFileSourceIndex(sources);
            java.util.List<CrossFileOccurrence> pastedAnchors =
                    resolvePastedSnippetAnchors(sources, pastedSnippet);
            boolean requirePastedAnchor = pastedSnippet != null && !pastedSnippet.isBlank();
            if (requirePastedAnchor && pastedAnchors.isEmpty()) {
                result.status = "no_clones";
                result.message = "Pasted snippet anchor was not found in the selected files.";
                result.warnings.add(result.message);
                return result;
            }
            JsonArray clones = getJsonArray(obj, "clones", "clone_classes", "cloneClasses");
            if (clones == null && obj.has("occurrences")) {
                clones = new JsonArray();
                clones.add(obj);
            }
            if (clones == null) {
                result.message = "Detection JSON parsed but no clones array was present.";
                return result;
            }

            for (JsonElement cloneElement : clones) {
                if (cloneElement == null || cloneElement.isJsonNull() || !cloneElement.isJsonObject()) continue;
                JsonObject cloneObj = cloneElement.getAsJsonObject();
                CrossFileClone clone = new CrossFileClone();
                clone.id = getJsonString(cloneObj, "id", "clone_id", "cloneId");
                clone.refactorType = getJsonString(cloneObj, "refactor_type", "refactorType", "type");
                clone.reason = getJsonString(cloneObj, "reason", "summary", "message");

                JsonArray occurrences = getJsonArray(cloneObj, "occurrences", "ranges", "clone_ranges", "cloneRanges");
                if (occurrences == null) continue;

                for (JsonElement occurrenceElement : occurrences) {
                    if (occurrenceElement == null || occurrenceElement.isJsonNull() || !occurrenceElement.isJsonObject()) continue;
                    JsonObject occurrenceObj = occurrenceElement.getAsJsonObject();
                    String path = getJsonString(occurrenceObj, "path", "file", "file_path", "filePath", "relative_path", "relativePath");
                    CrossFileSource source = resolveCrossFileSource(path, index);
                    if (source == null) {
                        result.warnings.add("Skipping occurrence for unknown file path: " + path);
                        continue;
                    }

                    int startLine = getJsonInt(occurrenceObj, -1, "start_line", "startLine", "start");
                    int endLine = getJsonInt(occurrenceObj, -1, "end_line", "endLine", "end");
                    String snippet = getJsonString(occurrenceObj, "snippet", "code", "clone_code", "cloneCode");
                    CrossFileOccurrence occurrence = resolveVerifiedOccurrence(source, startLine, endLine, snippet);
                    if (occurrence == null) {
                        result.warnings.add("Dropping unverifiable occurrence in " + source.relativePath
                                + " near lines " + startLine + "-" + endLine + ".");
                        continue;
                    }
                    if (occurrence.startLine != startLine || occurrence.endLine != endLine) {
                        result.warnings.add("Corrected occurrence range in " + source.relativePath
                                + " from " + startLine + "-" + endLine
                                + " to " + occurrence.startLine + "-" + occurrence.endLine + ".");
                    }
                    clone.occurrences.add(occurrence);
                }

                if (clone.affectedSources().size() >= 2 && (!requirePastedAnchor || cloneContainsPastedAnchor(clone, pastedAnchors))) {
                    result.clones.add(clone);
                } else if (requirePastedAnchor && clone.affectedSources().size() >= 2) {
                    result.warnings.add("Skipping clone that does not include the pasted snippet anchor: " + clone.displayId());
                } else if (!clone.occurrences.isEmpty()) {
                    result.warnings.add("Skipping clone that does not span at least two files: " + clone.displayId());
                }
            }

            if (result.clones.isEmpty()) {
                if (!"no_clones".equalsIgnoreCase(result.status == null ? "" : result.status.trim())) {
                    result.warnings.add("Detection Agent returned status=" + result.status
                            + " but no verified cross-file clone objects; normalized status to no_clones.");
                }
                result.status = "no_clones";
                if (result.message == null || result.message.isBlank()) {
                    result.message = requirePastedAnchor
                            ? "No verified cross-file clone includes the pasted snippet anchor."
                            : "No clone in the Detection Agent output spans at least two selected files.";
                }
            }
            return result;
        } catch (Throwable t) {
            result.message = "Could not parse cross-file detection JSON: " + t.getMessage();
            return result;
        }
    }

    static CrossFileClone selectBestCrossFileClone(CrossFileDetectionResult detectionResult) {
        if (detectionResult == null || detectionResult.clones.isEmpty()) return null;
        CrossFileClone best = null;
        for (CrossFileClone clone : detectionResult.clones) {
            if (clone == null || clone.affectedSources().size() < 2) continue;
            if (best == null) {
                best = clone;
                continue;
            }
            int fileCompare = Integer.compare(clone.affectedSources().size(), best.affectedSources().size());
            if (fileCompare > 0 || (fileCompare == 0 && clone.occurrences.size() > best.occurrences.size())) {
                best = clone;
            }
        }
        return best;
    }

    static JsonObject toDetectionPanelistJson(CrossFileDetectionPanelistOutcome outcome) {
        JsonObject obj = new JsonObject();
        obj.addProperty("panelist_id", outcome == null ? "" : outcome.panelistId);
        obj.addProperty("parsed", outcome != null && outcome.parsed);
        obj.addProperty("error", outcome == null ? "" : outcome.error);
        CrossFileDetectionResult result = outcome == null ? null : outcome.result;
        obj.addProperty("status", result == null ? "" : result.status);
        obj.addProperty("summary", result == null ? "" : result.summary);
        obj.addProperty("message", result == null ? "" : result.message);

        JsonArray warnings = new JsonArray();
        if (result != null) {
            for (String warning : result.warnings) {
                warnings.add(warning == null ? "" : warning);
            }
        }
        obj.add("warnings", warnings);

        JsonArray clones = new JsonArray();
        if (result != null) {
            for (CrossFileClone clone : result.clones) {
                if (clone == null) continue;
                JsonObject cloneObj = new JsonObject();
                cloneObj.addProperty("id", clone.displayId());
                cloneObj.addProperty("refactor_type", clone.refactorType);
                cloneObj.addProperty("reason", safeTruncate(clone.reason, 1200));
                JsonArray occurrences = new JsonArray();
                for (CrossFileOccurrence occurrence : clone.occurrences) {
                    if (occurrence == null || occurrence.source == null) continue;
                    JsonObject occurrenceObj = new JsonObject();
                    occurrenceObj.addProperty("path", occurrence.source.relativePath);
                    occurrenceObj.addProperty("start_line", occurrence.startLine);
                    occurrenceObj.addProperty("end_line", occurrence.endLine);
                    occurrenceObj.addProperty("snippet", safeTruncate(occurrence.snippet, 1500));
                    occurrences.add(occurrenceObj);
                }
                cloneObj.add("occurrences", occurrences);
                clones.add(cloneObj);
            }
        }
        obj.add("clones", clones);
        obj.addProperty("raw_response_preview", safeTruncate(outcome == null ? "" : outcome.rawResponse, 2500));
        return obj;
    }

    static CrossFileDetectionResult mergeCrossFileDetectionPanelists(List<CrossFileDetectionPanelistOutcome> panelistOutcomes) {
        CrossFileDetectionResult merged = new CrossFileDetectionResult();
        merged.parsed = true;
        merged.status = "no_clones";
        merged.summary = "Detection curator failed; panelist fallback found no majority-supported cross-file clone.";
        if (panelistOutcomes == null || panelistOutcomes.isEmpty()) {
            merged.message = "No detection panelist outputs were available.";
            return merged;
        }

        java.util.ArrayList<CrossFileDetectionResult> foundResults = new java.util.ArrayList<>();
        for (CrossFileDetectionPanelistOutcome outcome : panelistOutcomes) {
            if (outcome == null || outcome.result == null || !outcome.result.parsed) continue;
            if (!outcome.result.clones.isEmpty()) {
                foundResults.add(outcome.result);
            }
        }
        if (foundResults.size() < 2) {
            return merged;
        }

        CrossFileDetectionResult best = null;
        CrossFileClone bestClone = null;
        for (CrossFileDetectionResult candidate : foundResults) {
            CrossFileClone clone = selectBestCrossFileClone(candidate);
            if (clone == null) continue;
            if (bestClone == null
                    || clone.affectedSources().size() > bestClone.affectedSources().size()
                    || (clone.affectedSources().size() == bestClone.affectedSources().size()
                    && clone.occurrences.size() > bestClone.occurrences.size())) {
                best = candidate;
                bestClone = clone;
            }
        }
        if (best != null) {
            best.warnings.add("Detection curator failed; using majority panelist fallback.");
            return best;
        }
        return merged;
    }
}
