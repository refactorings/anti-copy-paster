package org.jetbrains.research.anticopypaster.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefactoringPanelistSelectionTest {

    @Test
    void curatorSelectsRequestedPanelistCandidate() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();

        Function<String, String> llmCaller = prompt -> {
            if (prompt.contains("Refactoring Panelist 1 (P1)")) {
                return candidatePlanJson("extractedP1");
            }
            if (prompt.contains("Refactoring Panelist 2 (P2)")) {
                return candidatePlanJson("extractedP2");
            }
            if (prompt.contains("Refactoring Panelist 3 (P3)")) {
                return candidatePlanJson("extractedP3");
            }
            if (prompt.contains("You are the refactoring curator.")) {
                assertTrue(prompt.contains("EXTRACT_METHOD_NOT_FOUND"));
                assertTrue(prompt.contains("candidate_source_preview"));
                return curatorSelectionJson("P2", "P2 is the cleanest Extract Method candidate.");
            }
            throw new IllegalArgumentException("Unexpected prompt: " + prompt);
        };

        refactoring.RefactorResult result = agent.refactorFile(
                "Demo.java",
                source,
                clone,
                "",
                llmCaller
        );

        assertEquals("refactored", result.status);
        assertEquals("P2", result.selectedPanelistId);
        assertTrue(result.newSource.contains("private void extractedP2()"));
        assertTrue(result.newSource.contains("extractedP2();"));
        assertTrue(result.curatorSummary.contains("cleanest Extract Method"));
    }

    @Test
    void fallsBackToFirstSuccessfulCandidateWhenCuratorSelectionIsInvalid() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();

        Function<String, String> llmCaller = prompt -> {
            if (prompt.contains("Refactoring Panelist 1 (P1)")) {
                return candidatePlanJson("extractedP1");
            }
            if (prompt.contains("Refactoring Panelist 2 (P2)")) {
                return candidatePlanJson("extractedP2");
            }
            if (prompt.contains("Refactoring Panelist 3 (P3)")) {
                return candidatePlanJson("extractedP3");
            }
            if (prompt.contains("You are the refactoring curator.")) {
                return "{\"selected_panelist_id\":\"P9\",\"matched_categories\":[],\"summary\":\"bad selection\",\"feedback\":\"\",\"confidence\":0.1}";
            }
            throw new IllegalArgumentException("Unexpected prompt: " + prompt);
        };

        refactoring.RefactorResult result = agent.refactorWithPrompt(
                "Demo.java",
                source,
                clone,
                "Revise the refactoring but keep using Extract Method only.",
                llmCaller
        );

        assertEquals("refactored", result.status);
        assertEquals("P1", result.selectedPanelistId);
        assertTrue(result.newSource.contains("private void extractedP1()"));
    }

    @Test
    void bodyOnlyReplacementPreservesWholeMethodOccurrenceHeaders() {
        refactoring agent = new refactoring();
        String source = demoSource();
        refactoring.DetectedClone clone = demoClone();

        Function<String, String> llmCaller = prompt -> {
            if (prompt.contains("Refactoring Panelist 1 (P1)")
                    || prompt.contains("Refactoring Panelist 2 (P2)")
                    || prompt.contains("Refactoring Panelist 3 (P3)")) {
                return candidatePlanJsonWithBodyOnlyReplacements("extractedBodyOnly");
            }
            if (prompt.contains("You are the refactoring curator.")) {
                return curatorSelectionJson("P1", "P1 preserves the original wrappers.");
            }
            throw new IllegalArgumentException("Unexpected prompt: " + prompt);
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
        assertTrue(result.newSource.contains("extractedBodyOnly();"));
        assertTrue(result.newSource.contains("private void extractedBodyOnly()"));
        assertTrue(result.newSource.contains("void alpha() {\n        extractedBodyOnly();\n    }"));
        assertTrue(result.newSource.contains("void beta() {\n        extractedBodyOnly();\n    }"));
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

    private static String curatorSelectionJson(String selectedPanelistId, String summary) {
        JsonObject obj = new JsonObject();
        obj.addProperty("selected_panelist_id", selectedPanelistId);
        JsonArray categories = new JsonArray();
        categories.add("EXTRACT_METHOD_CONFIRMED");
        obj.add("matched_categories", categories);
        obj.addProperty("summary", summary);
        obj.addProperty("feedback", "Residual risk is low.");
        obj.addProperty("confidence", 0.91d);
        return obj.toString();
    }
}
