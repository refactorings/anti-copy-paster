package org.jetbrains.research.anticopypaster.agents;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmUsefulnessEvaluatorTest {

    @Test
    void evaluateAssignsFullCategoryCatalogToEveryPanelist() {
        List<String> expectedCategories = List.of(
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
        Map<String, String> promptsByLabel = new HashMap<>();

        LlmUsefulnessEvaluator.UsefulnessInput input = new LlmUsefulnessEvaluator.UsefulnessInput(
                "Helper.java",
                LlmUsefulnessEvaluator.CloneKind.WHOLE_METHOD,
                "Clone ID: clone_full_catalog",
                "class Before {}",
                "class After {}"
        );

        LlmUsefulnessEvaluator.EvaluationResult result = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> {
                    promptsByLabel.put(label, prompt);
                    if ("CURATOR".equals(label)) {
                        return """
                                {
                                  "is_useful": true,
                                  "reasons": [],
                                  "summary": "No issue",
                                  "feedback": "",
                                  "confidence": 0.80
                                }
                                """;
                    }
                    return """
                            {
                              "panelist_id": "P",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "No issue",
                              "feedback": ""
                            }
                            """;
                }
        );

        assertEquals(3, result.panelistResults.size());
        for (LlmUsefulnessEvaluator.PanelistResult panelistResult : result.panelistResults) {
            assertEquals(expectedCategories, panelistResult.checkedCategories);
        }

        for (String panelistId : List.of("P1", "P2", "P3")) {
            String prompt = promptsByLabel.get(panelistId);
            assertNotNull(prompt);
            assertTrue(prompt.contains("All panelists review the same categories independently."));
            assertTrue(prompt.contains("\"panelist_id\": \"" + panelistId + "\""));
            for (String category : expectedCategories) {
                assertTrue(prompt.contains(category), "Missing category " + category + " in " + panelistId + " prompt");
            }
        }
    }

    @Test
    void evaluateReturnsCuratorDecisionWhenPanelistsAndCuratorParse() {
        LlmUsefulnessEvaluator.UsefulnessInput input = new LlmUsefulnessEvaluator.UsefulnessInput(
                "Helper.java",
                LlmUsefulnessEvaluator.CloneKind.WHOLE_METHOD,
                "Clone ID: clone_1",
                "class Before {}",
                "class After {}"
        );

        LlmUsefulnessEvaluator.EvaluationResult result = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> switch (label) {
                    case "P1" -> """
                            {
                              "panelist_id": "P1",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "No completeness issue",
                              "feedback": ""
                            }
                            """;
                    case "P2" -> """
                            {
                              "panelist_id": "P2",
                              "is_useful": false,
                              "matched_categories": ["DIRECT_CLONE_REMOVAL_DETECTED"],
                              "summary": "A clone was removed directly",
                              "feedback": "Restore the removed clone logic and extract a helper instead."
                            }
                            """;
                    case "P3" -> """
                            {
                              "panelist_id": "P3",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "No scope issue",
                              "feedback": ""
                            }
                            """;
                    case "CURATOR" -> """
                            {
                              "is_useful": false,
                              "reasons": ["DIRECT_CLONE_REMOVAL_DETECTED"],
                              "summary": "The proposal is not useful because a target clone was deleted directly.",
                              "feedback": "Do not delete a target clone directly. Extract one helper and keep both original clone sites.",
                              "confidence": 0.91
                            }
                            """;
                    default -> fail("Unexpected label: " + label);
                }
        );

        assertTrue(result.available);
        assertFalse(result.useful);
        assertNotNull(result.curatorResult);
        assertEquals(List.of("DIRECT_CLONE_REMOVAL_DETECTED"), result.curatorResult.reasons);
        assertEquals(3, result.panelistResults.size());
        assertTrue(result.notes.contains("P2=not_useful"));
    }

    @Test
    void evaluateUsesMajorityFallbackWhenCuratorJsonCannotBeParsed() {
        LlmUsefulnessEvaluator.UsefulnessInput input = new LlmUsefulnessEvaluator.UsefulnessInput(
                "Helper.java",
                LlmUsefulnessEvaluator.CloneKind.FRAGMENT,
                "Clone ID: clone_2",
                "before",
                "after"
        );

        LlmUsefulnessEvaluator.EvaluationResult result = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> {
                    if ("CURATOR".equals(label)) {
                        return "not json";
                    }
                    return """
                            {
                              "panelist_id": "P",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "ok",
                              "feedback": ""
                            }
                            """;
                }
        );

        assertTrue(result.available);
        assertTrue(result.useful);
        assertNotNull(result.curatorResult);
        assertTrue(result.curatorResult.parsed);
        assertTrue(result.curatorResult.error.contains("majority vote fallback"));
        assertTrue(result.curatorResult.summary.contains("3 useful, 0 not useful"));
    }

    @Test
    void evaluateInfersPanelistNotUsefulWhenUsefulFieldMissingOrMalformedAndCategoriesPresent() {
        LlmUsefulnessEvaluator.UsefulnessInput input = new LlmUsefulnessEvaluator.UsefulnessInput(
                "Helper.java",
                LlmUsefulnessEvaluator.CloneKind.WHOLE_METHOD,
                "Clone ID: clone_panelist_inference",
                "before",
                "after"
        );

        LlmUsefulnessEvaluator.EvaluationResult result = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> switch (label) {
                    case "P1" -> """
                            {
                              "panelist_id": "P1",
                              "matched_categories": ["DIRECT_CLONE_REMOVAL_DETECTED"],
                              "summary": "A clone was removed directly",
                              "feedback": "Restore the removed clone logic and extract a helper instead."
                            }
                            """;
                    case "P2" -> """
                            {
                              "panelist_id": "P2",
                              "is_useful": {"value": false},
                              "matched_categories": ["EXTRACT_METHOD_NOT_FOUND"],
                              "summary": "No extracted helper is visible",
                              "feedback": "Introduce an extracted helper and replace both clone sites."
                            }
                            """;
                    case "P3" -> """
                            {
                              "panelist_id": "P3",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "No issue",
                              "feedback": ""
                            }
                            """;
                    case "CURATOR" -> """
                            {
                              "is_useful": true,
                              "reasons": [],
                              "summary": "Curator accepted",
                              "feedback": "",
                              "confidence": 0.80
                            }
                            """;
                    default -> fail("Unexpected label: " + label);
                }
        );

        assertEquals(3, result.panelistResults.size());
        assertFalse(result.panelistResults.get(0).useful);
        assertEquals(List.of("DIRECT_CLONE_REMOVAL_DETECTED"), result.panelistResults.get(0).matchedCategories);
        assertFalse(result.panelistResults.get(1).useful);
        assertEquals(List.of("EXTRACT_METHOD_NOT_FOUND"), result.panelistResults.get(1).matchedCategories);
        assertTrue(result.panelistResults.get(2).useful);
        assertTrue(result.notes.contains("P1=not_useful"));
        assertTrue(result.notes.contains("P2=not_useful"));
    }

    @Test
    void evaluateInfersCuratorNotUsefulWhenUsefulFieldMissingOrMalformedAndReasonsPresent() {
        LlmUsefulnessEvaluator.UsefulnessInput input = new LlmUsefulnessEvaluator.UsefulnessInput(
                "Helper.java",
                LlmUsefulnessEvaluator.CloneKind.FRAGMENT,
                "Clone ID: clone_curator_inference",
                "before",
                "after"
        );

        LlmUsefulnessEvaluator.EvaluationResult missingFieldResult = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> {
                    if ("CURATOR".equals(label)) {
                        return """
                                {
                                  "reasons": ["DIRECT_CLONE_REMOVAL_DETECTED"],
                                  "summary": "The target clone was removed directly.",
                                  "feedback": "Restore the removed clone logic and extract a helper instead.",
                                  "confidence": 0.76
                                }
                                """;
                    }
                    return """
                            {
                              "panelist_id": "P",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "ok",
                              "feedback": ""
                            }
                            """;
                }
        );

        LlmUsefulnessEvaluator.EvaluationResult malformedFieldResult = LlmUsefulnessEvaluator.evaluate(
                input,
                (label, prompt) -> {
                    if ("CURATOR".equals(label)) {
                        return """
                                {
                                  "is_useful": ["not", "a", "boolean"],
                                  "reasons": ["EXTRACT_METHOD_NOT_FOUND"],
                                  "summary": "No extracted helper is visible.",
                                  "feedback": "Introduce an extracted helper and replace both clone sites.",
                                  "confidence": 0.72
                                }
                                """;
                    }
                    return """
                            {
                              "panelist_id": "P",
                              "is_useful": true,
                              "matched_categories": [],
                              "summary": "ok",
                              "feedback": ""
                            }
                            """;
                }
        );

        assertFalse(missingFieldResult.useful);
        assertEquals(List.of("DIRECT_CLONE_REMOVAL_DETECTED"), missingFieldResult.curatorResult.reasons);
        assertFalse(malformedFieldResult.useful);
        assertEquals(List.of("EXTRACT_METHOD_NOT_FOUND"), malformedFieldResult.curatorResult.reasons);
    }
}
