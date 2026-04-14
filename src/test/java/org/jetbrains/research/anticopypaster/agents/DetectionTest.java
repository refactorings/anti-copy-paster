package org.jetbrains.research.anticopypaster.agents;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DetectionTest {

    @Test
    void detectReturnsCuratorDecisionWhenPanelistsAndCuratorParse() {
        detection agent = new detection();
        Map<String, String> promptsByRole = new HashMap<>();

        String fileSource = """
                class Demo {
                    void first() {
                        int x = 1;
                        System.out.println(x);
                    }

                    void second() {
                        int y = 1;
                        System.out.println(y);
                    }
                }
                """;
        String selectedSnippet = """
                        int x = 1;
                        System.out.println(x);
                """;

        detection.DetectionResult result = agent.detect(
                "Demo.java",
                fileSource,
                selectedSnippet,
                prompt -> {
                    if (prompt.contains("Detection Panelist 1 (P1)")) {
                        promptsByRole.put("P1", prompt);
                        return """
                                {
                                  "status": "no_clones",
                                  "file": "Demo.java",
                                  "clones": []
                                }
                                """;
                    }
                    if (prompt.contains("Detection Panelist 2 (P2)")) {
                        promptsByRole.put("P2", prompt);
                        return """
                                {
                                  "status": "found_clones",
                                  "file": "Demo.java",
                                  "clones": [
                                    {
                                      "id": "clone_p2",
                                      "ranges": [
                                        { "startLine": 3, "endLine": 4 },
                                        { "startLine": 8, "endLine": 9 }
                                      ],
                                      "cloneCodes": [
                                        "int x = 1;\\nSystem.out.println(x);",
                                        "int y = 1;\\nSystem.out.println(y);"
                                      ],
                                      "refactorType": "extracted_method",
                                      "reason": "same shape",
                                      "cloneCodeA": "int x = 1;\\nSystem.out.println(x);",
                                      "cloneCodeB": "int y = 1;\\nSystem.out.println(y);"
                                    }
                                  ]
                                }
                                """;
                    }
                    if (prompt.contains("Detection Panelist 3 (P3)")) {
                        promptsByRole.put("P3", prompt);
                        return """
                                {
                                  "status": "found_clones",
                                  "file": "Demo.java",
                                  "clones": [
                                    {
                                      "id": "clone_p3",
                                      "ranges": [
                                        { "startLine": 3, "endLine": 4 },
                                        { "startLine": 8, "endLine": 9 }
                                      ],
                                      "cloneCodes": [
                                        "int x = 1;\\nSystem.out.println(x);",
                                        "int y = 1;\\nSystem.out.println(y);"
                                      ],
                                      "refactorType": "extracted_method",
                                      "reason": "same shape",
                                      "cloneCodeA": "int x = 1;\\nSystem.out.println(x);",
                                      "cloneCodeB": "int y = 1;\\nSystem.out.println(y);"
                                    }
                                  ]
                                }
                                """;
                    }
                    if (prompt.contains("You are the detection curator.")) {
                        promptsByRole.put("CURATOR", prompt);
                        return """
                                {
                                  "status": "found_clones",
                                  "file": "Demo.java",
                                  "clones": [
                                    {
                                      "id": "clone_curated",
                                      "ranges": [
                                        { "startLine": 3, "endLine": 4 },
                                        { "startLine": 8, "endLine": 9 }
                                      ],
                                      "cloneCodes": [
                                        "int x = 1;\\nSystem.out.println(x);",
                                        "int y = 1;\\nSystem.out.println(y);"
                                      ],
                                      "refactorType": "extracted_method",
                                      "reason": "panelists agree this pasted snippet has one same-file clone",
                                      "cloneCodeA": "int x = 1;\\nSystem.out.println(x);",
                                      "cloneCodeB": "int y = 1;\\nSystem.out.println(y);"
                                    }
                                  ]
                                }
                                """;
                    }
                    fail("Unexpected prompt: " + prompt);
                    return "";
                }
        );

        assertNotNull(result);
        assertEquals("found_clones", result.status);
        assertEquals("Demo.java", result.file);
        assertNotNull(result.clones);
        assertEquals(1, result.clones.size());
        assertEquals("clone_curated", result.clones.get(0).id);

        assertTrue(promptsByRole.get("P1").contains("All panelists review the same file and pasted snippet independently."));
        assertTrue(promptsByRole.get("P2").contains("Selected snippet:"));
        assertTrue(promptsByRole.get("P3").contains("\"status\": \"found_clones\" or \"no_clones\""));
        assertTrue(promptsByRole.get("CURATOR").contains("[P1]"));
        assertTrue(promptsByRole.get("CURATOR").contains("[P2]"));
        assertTrue(promptsByRole.get("CURATOR").contains("[P3]"));
    }

    @Test
    void detectFallsBackToMergedPanelistOutputsWhenCuratorJsonCannotBeParsed() {
        detection agent = new detection();

        String fileSource = """
                class Demo {
                    void first() {
                        int x = 1;
                        System.out.println(x);
                    }

                    void second() {
                        int y = 1;
                        System.out.println(y);
                    }
                }
                """;
        String selectedSnippet = """
                        int x = 1;
                        System.out.println(x);
                """;

        detection.DetectionResult result = agent.detect(
                "Demo.java",
                fileSource,
                selectedSnippet,
                prompt -> {
                    if (prompt.contains("Detection Panelist 1 (P1)")) {
                        return """
                                {
                                  "status": "no_clones",
                                  "file": "Demo.java",
                                  "clones": []
                                }
                                """;
                    }
                    if (prompt.contains("Detection Panelist 2 (P2)")) {
                        return """
                                {
                                  "status": "found_clones",
                                  "file": "Demo.java",
                                  "clones": [
                                    {
                                      "id": "clone_p2",
                                      "ranges": [
                                        { "startLine": 3, "endLine": 4 },
                                        { "startLine": 8, "endLine": 9 }
                                      ],
                                      "cloneCodes": [
                                        "int x = 1;\\nSystem.out.println(x);",
                                        "int y = 1;\\nSystem.out.println(y);"
                                      ],
                                      "refactorType": "extracted_method",
                                      "reason": "same shape",
                                      "cloneCodeA": "int x = 1;\\nSystem.out.println(x);",
                                      "cloneCodeB": "int y = 1;\\nSystem.out.println(y);"
                                    }
                                  ]
                                }
                                """;
                    }
                    if (prompt.contains("Detection Panelist 3 (P3)")) {
                        return """
                                {
                                  "status": "found_clones",
                                  "file": "Demo.java",
                                  "clones": [
                                    {
                                      "id": "clone_p3",
                                      "ranges": [
                                        { "startLine": 3, "endLine": 4 },
                                        { "startLine": 8, "endLine": 9 }
                                      ],
                                      "cloneCodes": [
                                        "int x = 1;\\nSystem.out.println(x);",
                                        "int y = 1;\\nSystem.out.println(y);"
                                      ],
                                      "refactorType": "extracted_method",
                                      "reason": "same shape",
                                      "cloneCodeA": "int x = 1;\\nSystem.out.println(x);",
                                      "cloneCodeB": "int y = 1;\\nSystem.out.println(y);"
                                    }
                                  ]
                                }
                                """;
                    }
                    if (prompt.contains("You are the detection curator.")) {
                        return "not json";
                    }
                    fail("Unexpected prompt: " + prompt);
                    return "";
                }
        );

        assertNotNull(result);
        assertEquals("found_clones", result.status);
        assertNotNull(result.clones);
        assertEquals(1, result.clones.size());
        assertEquals(2, result.clones.get(0).ranges.size());
    }

    @Test
    void detectRegroundsIncorrectLlmLineNumbersUsingCloneCodes() {
        detection agent = new detection();

        String fileSource = """
                class Demo {
                    void first() {
                        int x = 1;
                        System.out.println(x);
                    }

                    void second() {
                        int y = 1;
                        System.out.println(y);
                    }
                }
                """;
        String selectedSnippet = """
                        int x = 1;
                        System.out.println(x);
                """;

        detection.DetectionResult result = agent.detect(
                "Demo.java",
                fileSource,
                selectedSnippet,
                prompt -> """
                        {
                          "status": "found_clones",
                          "file": "Demo.java",
                          "clones": [
                            {
                              "id": "clone_wrong_lines",
                              "ranges": [
                                { "startLine": 30, "endLine": 31 },
                                { "startLine": 40, "endLine": 41 }
                              ],
                              "cloneCodes": [
                                "int x = 1;\\nSystem.out.println(x);",
                                "int y = 1;\\nSystem.out.println(y);"
                              ],
                              "refactorType": "extracted_method",
                              "reason": "same shape",
                              "cloneCodeA": "int x = 1;\\nSystem.out.println(x);",
                              "cloneCodeB": "int y = 1;\\nSystem.out.println(y);"
                            }
                          ]
                        }
                        """
        );

        assertNotNull(result);
        assertEquals("found_clones", result.status);
        assertNotNull(result.clones);
        assertEquals(1, result.clones.size());
        assertEquals(2, result.clones.get(0).ranges.size());
        assertEquals(3, result.clones.get(0).ranges.get(0).startLine);
        assertEquals(4, result.clones.get(0).ranges.get(0).endLine);
        assertEquals(8, result.clones.get(0).ranges.get(1).startLine);
        assertEquals(9, result.clones.get(0).ranges.get(1).endLine);
    }
}
