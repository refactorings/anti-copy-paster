package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefactoringPanelistSelectionTest {

    @Test
    void refactorFileUsesPanelistsAndCuratorSelection() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();
        int[] callCount = {0};

        Function<String, String> llmCaller = prompt -> {
            callCount[0]++;
            if (prompt.contains("Refactoring Panelist 1 (P1)")) {
                return candidatePlanJson("extractedP1");
            }
            if (prompt.contains("Refactoring Panelist 2 (P2)")) {
                return candidatePlanJson("extractedP2");
            }
            if (prompt.contains("Refactoring Panelist 3 (P3)")) {
                return candidatePlanJson("extractedP3");
            }
            assertTrue(prompt.contains("You are the refactoring curator."));
            assertCuratorPromptSeparatesUsefulAndNotUsefulCategories(prompt);
            return curatorSelectionJson("P2", "Best candidate");
        };

        refactoring.RefactorResult result = agent.refactorFile(
                "Demo.java",
                source,
                clone,
                "",
                llmCaller
        );

        assertEquals("refactored", result.status);
        assertEquals(4, callCount[0]);
        assertTrue(result.newSource.contains("private void extractedP2()"));
        assertTrue(result.newSource.contains("extractedP2();"));
        assertEquals("P2", result.selectedPanelistId);
        assertEquals("Best candidate", result.curatorSummary);
        assertFalse(result.curatorGeneratedPlan);
    }

    @Test
    void refactorWithPromptUsesPanelistsAndCuratorSelection() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();
        int[] callCount = {0};

        Function<String, String> llmCaller = prompt -> {
            callCount[0]++;
            if (prompt.contains("Refactoring Panelist 1 (P1)")) {
                assertTrue(prompt.contains("The previous attempt was rejected by the usefulness checker."));
                return candidatePlanJson("extractedRetryP1");
            }
            if (prompt.contains("Refactoring Panelist 2 (P2)")) {
                assertTrue(prompt.contains("The previous attempt was rejected by the usefulness checker."));
                return candidatePlanJson("extractedRetryP2");
            }
            if (prompt.contains("Refactoring Panelist 3 (P3)")) {
                assertTrue(prompt.contains("The previous attempt was rejected by the usefulness checker."));
                return candidatePlanJson("extractedRetryP3");
            }
            assertTrue(prompt.contains("You are the refactoring curator."));
            return curatorSelectionJson("P3", "Retry candidate selected");
        };

        refactoring.RefactorResult result = agent.refactorWithPrompt(
                "Demo.java",
                source,
                clone,
                "Revise the refactoring but keep using Extract Method only.",
                llmCaller
        );

        assertEquals("refactored", result.status);
        assertEquals(4, callCount[0]);
        assertTrue(result.newSource.contains("private void extractedRetryP3()"));
        assertEquals("P3", result.selectedPanelistId);
        assertEquals("Retry candidate selected", result.curatorSummary);
    }

    @Test
    void bodyOnlyReplacementPreservesWholeMethodOccurrenceHeaders() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();

        Function<String, String> llmCaller = prompt -> {
            if (prompt.contains("Refactoring Panelist 1 (P1)")) {
                return candidatePlanJsonWithBodyOnlyReplacements("extractedBodyOnlyP1");
            }
            if (prompt.contains("Refactoring Panelist 2 (P2)")) {
                return candidatePlanJsonWithBodyOnlyReplacements("extractedBodyOnlyP2");
            }
            if (prompt.contains("Refactoring Panelist 3 (P3)")) {
                return candidatePlanJsonWithBodyOnlyReplacements("extractedBodyOnlyP3");
            }
            assertTrue(prompt.contains("You are the refactoring curator."));
            return curatorSelectionJson("P1", "Body-only replacement preserved");
        };

        refactoring.RefactorResult result = agent.refactorFile(
                "Demo.java",
                source,
                clone,
                "",
                llmCaller
        );

        assertEquals("refactored", result.status);
        assertTrue(result.newSource.contains("void alpha() {"));
        assertTrue(result.newSource.contains("void beta() {"));
        assertTrue(result.newSource.contains("extractedBodyOnlyP1();"));
        assertTrue(result.newSource.contains("private void extractedBodyOnlyP1()"));
        assertTrue(result.newSource.contains("void alpha() {\n        extractedBodyOnlyP1();\n    }"));
        assertTrue(result.newSource.contains("void beta() {\n        extractedBodyOnlyP1();\n    }"));
    }

    @Test
    void curatorFallsBackToHighestScoringPanelistWhenNoUsefulSelection() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();
        int[] callCount = {0};

        Function<String, String> llmCaller = prompt -> {
            callCount[0]++;
            if (prompt.contains("Refactoring Panelist 1 (P1)")) {
                return rawJavaCodeBlock("rawP1");
            }
            if (prompt.contains("Refactoring Panelist 2 (P2)")) {
                return candidatePlanJson("extractedP2");
            }
            if (prompt.contains("Refactoring Panelist 3 (P3)")) {
                return rawJavaCodeBlock("rawP3");
            }
            assertTrue(prompt.contains("You are the refactoring curator."));
            assertTrue(prompt.contains("If no panelist candidate is useful, still choose the least-bad valid panelist candidate"));
            assertFalse(prompt.contains("\"generated_refactoring_plan\""));
            return curatorGeneratedPlanJson("curatorExtracted", "No panelist candidate fully replaced every occurrence");
        };

        refactoring.RefactorResult result = agent.refactorFile(
                "Demo.java",
                source,
                clone,
                "",
                llmCaller
        );

        assertEquals("refactored", result.status);
        assertEquals(4, callCount[0]);
        assertFalse(result.newSource.contains("private void curatorExtracted()"));
        assertFalse(result.newSource.contains("curatorExtracted();"));
        assertTrue(result.newSource.contains("private void extractedP2()"));
        assertEquals("P2", result.selectedPanelistId);
        assertFalse(result.curatorGeneratedPlan);
    }

    private static String demoSource() {
        return """
                class Demo {
                    void alpha() {
                        int x = 1;
                        System.out.println(x);
                    }

                    void beta() {
                        int x = 1;
                        System.out.println(x);
                    }
                }
                """;
    }

    private static refactoring.DetectedClone demoClone() {
        String alpha = """
                    void alpha() {
                        int x = 1;
                        System.out.println(x);
                    }""";
        String beta = """
                    void beta() {
                        int x = 1;
                        System.out.println(x);
                    }""";
        return new refactoring.DetectedClone(
                "clone_demo",
                List.of(),
                "extracted_method",
                "alpha and beta are duplicated",
                "",
                List.of(alpha, beta),
                alpha,
                beta
        );
    }

    private static String candidatePlanJson(String helperName) {
        JsonObject obj = new JsonObject();
        obj.addProperty(
                "helper_method",
                """
                private void %s() {
                    int x = 1;
                    System.out.println(x);
                }
                """.formatted(helperName).trim()
        );

        JsonArray replacements = new JsonArray();
        replacements.add(replacement("OCCURRENCE_1", """
                void alpha() {
                    %s();
                }
                """.formatted(helperName).trim()));
        replacements.add(replacement("OCCURRENCE_2", """
                void beta() {
                    %s();
                }
                """.formatted(helperName).trim()));
        obj.add("occurrence_replacements", replacements);
        return obj.toString();
    }

    private static String candidatePlanJsonWithBodyOnlyReplacements(String helperName) {
        JsonObject obj = new JsonObject();
        obj.addProperty(
                "helper_method",
                """
                private void %s() {
                    int x = 1;
                    System.out.println(x);
                }
                """.formatted(helperName).trim()
        );

        JsonArray replacements = new JsonArray();
        replacements.add(replacement("OCCURRENCE_1", "%s();".formatted(helperName)));
        replacements.add(replacement("OCCURRENCE_2", "%s();".formatted(helperName)));
        obj.add("occurrence_replacements", replacements);
        return obj.toString();
    }

    private static JsonObject replacement(String occurrenceId, String replacementCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("occurrence_id", occurrenceId);
        obj.addProperty("replacement_code", replacementCode);
        return obj;
    }

    private static String rawJavaCodeBlock(String helperName) {
        return """
                ```java
                class Demo {
                    void alpha() {
                        %s();
                    }

                    void beta() {
                        %s();
                    }

                    private void %s() {
                        int x = 1;
                        System.out.println(x);
                    }
                }
                ```
                """.formatted(helperName, helperName, helperName);
    }

    private static void assertCuratorPromptSeparatesUsefulAndNotUsefulCategories(String prompt) {
        assertTrue(prompt.contains("=== USEFUL CATEGORY DEFINITION ==="));
        assertTrue(prompt.contains("- EXTRACT_METHOD_CONFIRMED:"));
        assertTrue(prompt.contains("=== NOT-USEFUL CATEGORY DEFINITIONS ==="));
        assertTrue(prompt.contains("3) Reject panelist candidates that likely trigger any not-useful category:"));
        assertTrue(prompt.contains("Choose the single best panelist candidate for downstream usefulness validation."));
        assertTrue(prompt.contains("If no candidate is fully acceptable, still select the least-bad panelist candidate"));
        assertFalse(prompt.contains("generate one new structured Extract Method refactoring plan"));
        assertFalse(prompt.contains("\"generated_refactoring_plan\""));

        int ruleStart = prompt.indexOf("3) Reject panelist candidates that likely trigger any not-useful category:");
        int ruleEnd = prompt.indexOf("4) If one or more panelist candidates are useful", ruleStart);
        String rejectRule = prompt.substring(ruleStart, ruleEnd);
        for (String category : notUsefulCategories()) {
            assertTrue(prompt.contains("- " + category + ":"), "Missing definition for " + category);
            assertTrue(rejectRule.contains(category), "Reject rule missing " + category);
        }
    }

    private static List<String> notUsefulCategories() {
        return List.of(
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
    }

    private static String curatorSelectionJson(String panelistId, String summary) {
        JsonObject obj = new JsonObject();
        obj.addProperty("selected_panelist_id", panelistId);
        JsonArray categories = new JsonArray();
        categories.add("EXTRACT_METHOD_CONFIRMED");
        obj.add("matched_categories", categories);
        obj.addProperty("summary", summary);
        obj.addProperty("feedback", "");
        obj.addProperty("confidence", 0.91);
        return obj.toString();
    }

    private static String curatorGeneratedPlanJson(String helperName, String summary) {
        JsonObject obj = new JsonObject();
        obj.addProperty("decision", "generate_plan");
        obj.addProperty("selected_panelist_id", "");
        JsonArray categories = new JsonArray();
        categories.add("INCOMPLETE_REFACTORING_DETECTED");
        obj.add("matched_categories", categories);
        obj.addProperty("summary", summary);
        obj.addProperty("feedback", "Generated a replacement plan instead of selecting a panelist candidate.");
        obj.addProperty("confidence", 0.88);

        JsonObject plan = new JsonObject();
        plan.addProperty(
                "helper_method",
                """
                private void %s() {
                    int x = 1;
                    System.out.println(x);
                }
                """.formatted(helperName).trim()
        );
        JsonArray replacements = new JsonArray();
        replacements.add(replacement("OCCURRENCE_1", "%s();".formatted(helperName)));
        replacements.add(replacement("OCCURRENCE_2", "%s();".formatted(helperName)));
        plan.add("occurrence_replacements", replacements);
        obj.add("generated_refactoring_plan", plan);
        return obj.toString();
    }

}
