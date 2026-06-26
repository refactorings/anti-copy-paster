package org.jetbrains.research.anticopypaster.Copilot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class CopilotPromptBuilderTest {

    @Test
    public void promptIncludesPastedSnippetCloneHintsAndPatchContract() {
        String source = """
                class Demo {
                    void first() {
                        int total = 1;
                        total += 2;
                        System.out.println(total);
                    }

                    void second() {
                        int total = 1;
                        total += 2;
                        System.out.println(total);
                    }
                }
                """;

        String prompt = CopilotPromptBuilder.buildPrompt(
                """
                        int total = 1;
                        total += 2;
                        System.out.println(total);
                        """,
                List.of(new CopilotPromptBuilder.FileContext("src/Demo.java", source)),
                List.of(
                        new CopilotPromptBuilder.CloneHint("src/Demo.java", 3, 5, "int total = 1;"),
                        new CopilotPromptBuilder.CloneHint("src/Demo.java", 9, 11, "int total = 1;")
                )
        );

        assertTrue(prompt.contains("Verdict: CLONES_FOUND, NO_CLONES, or NO_SAFE_REFACTOR"));
        assertTrue(prompt.contains("Patch: a unified diff"));
        assertTrue(prompt.contains("Pasted snippet:"));
        assertTrue(prompt.contains("src/Demo.java: lines 3-5"));
        assertTrue(prompt.contains("src/Demo.java: lines 9-11"));
        assertTrue(prompt.contains("   3 |"));
    }

    @Test
    public void largeFileContextKeepsOriginalLineNumbersAroundHints() {
        StringBuilder source = new StringBuilder("class Demo {\n");
        for (int i = 1; i <= 900; i++) {
            source.append("    void filler").append(i).append("() {}\n");
        }
        source.append("    void cloneA() {\n");
        source.append("        System.out.println(\"clone\");\n");
        source.append("    }\n");
        for (int i = 901; i <= 1800; i++) {
            source.append("    void filler").append(i).append("() {}\n");
        }
        source.append("}\n");

        String prompt = CopilotPromptBuilder.buildPrompt(
                "System.out.println(\"clone\");",
                List.of(new CopilotPromptBuilder.FileContext("src/LargeDemo.java", source.toString())),
                List.of(new CopilotPromptBuilder.CloneHint("src/LargeDemo.java", 902, 904, "System.out.println(\"clone\");"))
        );

        assertTrue(prompt.contains("src/LargeDemo.java (chars="));
        assertTrue(prompt.contains("excerpted"));
        assertTrue(prompt.contains(" 902 |"));
        assertFalse(prompt.contains("  10 |     void filler10() {}"));
    }

    @Test
    public void sdkPromptRequiresJsonAndFullReplacementSources() {
        String prompt = CopilotPromptBuilder.buildSdkJsonRefactorPrompt(
                "System.out.println(value);",
                List.of(new CopilotPromptBuilder.FileContext("src/Demo.java", """
                        class Demo {
                            void a(int value) {
                                System.out.println(value);
                            }
                        }
                        """)),
                List.of(new CopilotPromptBuilder.CloneHint("src/Demo.java", 3, 3, "System.out.println(value);"))
        );

        assertTrue(prompt.contains("Return ONLY one JSON object"));
        assertTrue(prompt.contains("\"full_source\":\"complete replacement Java source\""));
        assertTrue(prompt.contains("Only change files listed below"));
        assertTrue(prompt.contains("### src/Demo.java"));
        assertFalse(prompt.contains("Working-set file context"));
    }
}
