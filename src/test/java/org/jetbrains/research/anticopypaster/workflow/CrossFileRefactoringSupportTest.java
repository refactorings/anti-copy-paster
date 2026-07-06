package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CrossFileRefactoringSupportTest extends LightJavaCodeInsightFixtureTestCase {

    public void testCollectSharedOwnerCandidatesResolvesImportedSuperclassWithPsi() {
        myFixture.addFileToProject("common/BaseThing.java", """
                package common;

                public class BaseThing {
                    protected void shared() {}
                }
                """);
        PsiJavaFile childFile = (PsiJavaFile) myFixture.addFileToProject("child/ChildThing.java", """
                package child;

                import common.BaseThing;

                public class ChildThing extends BaseThing {
                    void work() {
                        shared();
                    }
                }
                """);

        VirtualFile childVf = childFile.getVirtualFile();
        CrossFileSource childSource = new CrossFileSource(
                childVf,
                new File(childVf.getPath()),
                childVf.getPath(),
                CrossFileSourceSupport.toProjectRelativePath(getProject(), childVf.getPath()),
                childFile.getText()
        );

        List<CrossFileSource> candidates = CrossFileRefactoringSupport.collectSharedOwnerCandidates(
                getProject(),
                List.of(childSource)
        );

        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).absolutePath.replace('\\', '/').endsWith("common/BaseThing.java"));
        assertTrue(candidates.get(0).source.contains("class BaseThing"));
    }

    public void testRefactorContextWarnsForSameSimpleNameDifferentFqns() {
        CrossFileSource first = addSource("one/Duplicate.java", """
                package one;

                public class Duplicate {
                    void work() {
                        System.out.println("x");
                    }
                }
                """);
        CrossFileSource second = addSource("two/Duplicate.java", """
                package two;

                public class Duplicate {
                    void work() {
                        System.out.println("x");
                    }
                }
                """);
        CrossFileClone clone = cloneFor(first, 5, 5, second, 5, 5);

        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        assertEquals(2, context.occurrenceFacts.size());
        assertTrue(context.warnings.stream().anyMatch(warning ->
                warning.contains("Duplicate")
                        && warning.contains("one.Duplicate")
                        && warning.contains("two.Duplicate")));
    }

    public void testRefactorContextAllowsIndirectCommonSuperclassProjectTarget() {
        addSource("common/BaseThing.java", """
                package common;

                public class BaseThing {
                    protected void shared() {}
                }
                """);
        addSource("left/LeftBase.java", """
                package left;

                import common.BaseThing;

                public class LeftBase extends BaseThing {}
                """);
        addSource("right/RightBase.java", """
                package right;

                import common.BaseThing;

                public class RightBase extends BaseThing {}
                """);
        CrossFileSource first = addSource("child/AThing.java", """
                package child;

                import left.LeftBase;

                public class AThing extends LeftBase {
                    void work() {
                        System.out.println("x");
                    }
                }
                """);
        CrossFileSource second = addSource("child/BThing.java", """
                package child;

                import right.RightBase;

                public class BThing extends RightBase {
                    void work() {
                        System.out.println("x");
                    }
                }
                """);
        CrossFileClone clone = cloneFor(first, 7, 7, second, 7, 7);

        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        List<CrossFileAllowedHelperTarget> projectTargets = context.targetsByStrategy("existing_project_file");
        assertEquals(1, projectTargets.size());
        assertEquals("common.BaseThing", projectTargets.get(0).classFqn);
        assertTrue(projectTargets.get(0).path.replace('\\', '/').endsWith("common/BaseThing.java"));
    }

    public void testRefactorContextRejectsUnrelatedProjectSuperclassTargets() {
        addSource("common/BaseOne.java", """
                package common;

                public class BaseOne {}
                """);
        addSource("common/BaseTwo.java", """
                package common;

                public class BaseTwo {}
                """);
        CrossFileSource first = addSource("child/AThing.java", """
                package child;

                import common.BaseOne;

                public class AThing extends BaseOne {
                    void work() {
                        System.out.println("x");
                    }
                }
                """);
        CrossFileSource second = addSource("child/BThing.java", """
                package child;

                import common.BaseTwo;

                public class BThing extends BaseTwo {
                    void work() {
                        System.out.println("x");
                    }
                }
                """);
        CrossFileClone clone = cloneFor(first, 7, 7, second, 7, 7);

        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        assertTrue(context.targetsByStrategy("existing_project_file").isEmpty());
        assertTrue(context.warnings.stream().anyMatch(warning ->
                warning.contains("No PSI-confirmed common superclass")));
    }

    public void testImportConflictDetectionRejectsSameSimpleName() {
        String source = """
                package demo;

                import java.util.Arrays;

                public class UsesArrays {}
                """;

        List<String> conflicts = CrossFileTextEditSupport.findJavaImportConflicts(
                source,
                List.of("org.apache.catalina.tribes.util.Arrays")
        );

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("java.util.Arrays"));
    }

    public void testParseRejectsExistingProjectFileOutsideAllowedTargets() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileClone clone = cloneFor(first, 5, 5, second, 5, 5);
        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        String raw = """
                {
                  "status": "refactored",
                  "shared_helper": {
                    "strategy": "existing_project_file",
                    "path": "demo/InventedParent.java",
                    "imports": [],
                    "helper_method": "static void helper() {}"
                  },
                  "files": []
                }
                """;

        CrossFileRefactorResult result = CrossFileRefactoringSupport.parseCrossFileRefactorResult(
                raw,
                getProject(),
                List.of(first, second),
                clone,
                context
        );

        assertFalse(result.parsed);
        assertTrue(result.message.contains("not in the PSI-verified allowed helper target list"));
    }

    public void testParseRejectsConflictingImportsForExistingTarget() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                import java.util.Arrays;

                public class A {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileClone clone = cloneFor(first, 7, 7, second, 5, 5);
        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        String raw = """
                {
                  "status": "refactored",
                  "shared_helper": {
                    "strategy": "existing_selected_file",
                    "path": "demo/A.java",
                    "imports": ["org.apache.catalina.tribes.util.Arrays"],
                    "helper_method": "static void helper() { Arrays.convert(\\"x\\"); }"
                  },
                  "files": []
                }
                """;

        CrossFileRefactorResult result = CrossFileRefactoringSupport.parseCrossFileRefactorResult(
                raw,
                getProject(),
                List.of(first, second),
                clone,
                context
        );

        assertFalse(result.parsed);
        assertTrue(result.message.contains("shared_helper.imports conflict"));
    }

    public void testParseRejectsConflictingImportsForNewHelperClass() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileClone clone = cloneFor(first, 5, 5, second, 5, 5);
        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        String raw = """
                {
                  "status": "refactored",
                  "shared_helper": {
                    "strategy": "new_helper_class",
                    "path": "demo/CrossFileCloneHelper.java",
                    "package_name": "demo",
                    "class_name": "CrossFileCloneHelper",
                    "imports": ["one.Arrays", "two.Arrays"],
                    "helper_method": "static void helper() {}"
                  },
                  "files": []
                }
                """;

        CrossFileRefactorResult result = CrossFileRefactoringSupport.parseCrossFileRefactorResult(
                raw,
                getProject(),
                List.of(first, second),
                clone,
                context
        );

        assertFalse(result.parsed);
        assertTrue(result.message.contains("shared_helper.imports conflict within requested imports"));
    }

    public void testPromptIncludesRepairModeWithPreviousProposal() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """);
        CrossFileClone clone = cloneFor(first, 5, 5, second, 5, 5);
        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );
        CrossFileRefactorResult previous = new CrossFileRefactorResult();
        previous.rawPlanJson = "{\"status\":\"refactored\",\"shared_helper\":{\"path\":\"demo/A.java\"}}";
        previous.selectedPanelistId = "P2";

        String prompt = CrossFileRefactoringSupport.buildCrossFileRefactorPrompt(
                getProject(),
                List.of(first, second),
                clone,
                "The previous refactoring attempt failed compilation.",
                previous,
                context
        );

        assertTrue(prompt.contains("REPAIR MODE"));
        assertTrue(prompt.contains("smallest possible JSON changes"));
        assertTrue(prompt.contains(previous.rawPlanJson));
        assertTrue(prompt.contains("Do not skip the refactoring task"));
        assertTrue(prompt.contains("prefer the most conservative valid cross-file Extract Method"));
        assertFalse(prompt.contains("return {\"status\":\"failed\""));
        assertFalse(prompt.contains("return failed"));
    }

    public void testCrossFileDetectionPromptMirrorsCurrentFileCloneRules() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void one() {
                        same();
                    }
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void two() {
                        same();
                    }
                }
                """);

        String prompt = CrossFileDetectionSupport.buildCrossFileDetectionPanelistPrompt(
                new CrossFilePanelistSpec("P1", "Detection Panelist 1"),
                null,
                List.of(first, second),
                "same();"
        );

        assertTrue(prompt.contains("exhaustively find every non-trivial clone of the user's pasted snippet"));
        assertTrue(prompt.contains("Do NOT report clones that do not involve the pasted snippet"));
        assertTrue(prompt.contains("Do not reject short, method-level, symmetric, or small-substitution clones"));
        assertTrue(prompt.contains("snippet for each occurrence must be copied from the listed file source"));
    }

    public void testCrossFileRefactorPromptUsesCurrentFileStyleSections() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void one() {
                        same();
                    }
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void two() {
                        same();
                    }
                }
                """);
        CrossFileClone clone = cloneFor(first, 5, 5, second, 5, 5);
        CrossFileRefactorContext context = CrossFileRefactorContextSupport.build(
                getProject(),
                List.of(first, second),
                clone
        );

        String prompt = CrossFileRefactoringSupport.buildCrossFileRefactorPrompt(
                getProject(),
                List.of(first, second),
                clone,
                "",
                null,
                context
        );

        assertTrue(prompt.contains("=== CONTEXT ==="));
        assertTrue(prompt.contains("=== REFACTORING TASK ==="));
        assertTrue(prompt.contains("=== STEPS TO FOLLOW (DO NOT OUTPUT THESE STEPS) ==="));
        assertTrue(prompt.contains("=== STRICT CONSTRAINTS (HARD RULES) ==="));
        assertTrue(prompt.contains("selected occurrence snippets are the mandatory refactoring target"));
    }

    public void testDetectionFallbackRequiresMajorityForSameClone() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void one() {
                        sameOne();
                    }
                    void two() {
                        sameTwo();
                    }
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void one() {
                        sameOne();
                    }
                    void two() {
                        sameTwo();
                    }
                }
                """);

        CrossFileDetectionResult merged = CrossFileDetectionSupport.mergeCrossFileDetectionPanelists(List.of(
                detectionOutcome("P1", detectionResult(cloneFor(first, 5, 5, second, 5, 5))),
                detectionOutcome("P2", detectionResult(cloneFor(first, 8, 8, second, 8, 8))),
                detectionOutcome("P3", noCloneDetectionResult())
        ));

        assertTrue(merged.parsed);
        assertEquals("no_clones", merged.status);
        assertTrue(merged.clones.isEmpty());
    }

    public void testDetectionFallbackAcceptsMajorityForSameClone() {
        CrossFileSource first = addSource("demo/A.java", """
                package demo;

                public class A {
                    void one() {
                        sameOne();
                    }
                }
                """);
        CrossFileSource second = addSource("demo/B.java", """
                package demo;

                public class B {
                    void one() {
                        sameOne();
                    }
                }
                """);

        CrossFileDetectionResult merged = CrossFileDetectionSupport.mergeCrossFileDetectionPanelists(List.of(
                detectionOutcome("P1", detectionResult(cloneFor(first, 5, 5, second, 5, 5))),
                detectionOutcome("P2", detectionResult(cloneFor(second, 5, 5, first, 5, 5))),
                detectionOutcome("P3", noCloneDetectionResult())
        ));

        assertTrue(merged.parsed);
        assertEquals("found_clones", merged.status);
        assertEquals(1, merged.clones.size());
        assertTrue(merged.warnings.get(0).contains("P1"));
        assertTrue(merged.warnings.get(0).contains("P2"));
    }

    public void testResolveSelectedPanelistFallbackUsesHighestScore() {
        CrossFileSource source = addSource("demo/A.java", """
                package demo;

                public class A {}
                """);
        CrossFileRefactorResult parsedNoChanges = refactorResult(true, "refactored", false, false, source);
        CrossFileRefactorResult changedWithWarnings = refactorResult(true, "refactored", true, true, source);
        CrossFileRefactorResult cleanChanged = refactorResult(true, "refactored", true, false, source);
        CrossFilePanelistSelection selection = new CrossFilePanelistSelection();
        selection.selectedPanelistId = "PX";

        CrossFileRefactorPanelistOutcome selected = CrossFileRefactoringSupport.resolveSelectedRefactorPanelist(
                selection,
                List.of(
                        refactorOutcome("P1", parsedNoChanges),
                        refactorOutcome("P2", changedWithWarnings),
                        refactorOutcome("P3", cleanChanged)
                )
        );

        assertNotNull(selected);
        assertEquals("P3", selected.panelistId);
    }

    public void testImportConflictDetectionRejectsDeclaredTypeName() {
        String source = """
                package demo;

                public class Arrays {}
                """;

        List<String> conflicts = CrossFileTextEditSupport.findJavaImportConflicts(
                source,
                List.of("org.example.Arrays")
        );

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("org.example.Arrays"));
        assertTrue(conflicts.get(0).contains("declared type Arrays"));
    }

    public void testWholeMethodReplacementRequiresMatchingMethodName() {
        String source = """
                class A {
                    void work() {
                        one();
                        two();
                    }
                }
                """;
        CrossFileTextEditSupport.TextSpan wholeMethod =
                CrossFileTextEditSupport.spanForLineRange(source, 2, 5);
        CrossFileTextEditSupport.MethodTextSpan methodSpan =
                CrossFileTextEditSupport.resolveWholeMethodSpan(source, wholeMethod);

        CrossFileTextEditSupport.ReplacementTarget target =
                CrossFileTextEditSupport.selectReplacementTarget(
                        source,
                        wholeMethod,
                        """
                        void helper() {
                            shared();
                        }
                        """
                );

        assertNotNull(methodSpan);
        assertEquals(methodSpan.bodyStart, target.span.start);
        assertEquals(methodSpan.bodyEnd, target.span.end);
    }

    public void testNewHelperClassDoesNotOverwriteExistingFile() throws Exception {
        Path baseDir = Files.createTempDirectory("acp-existing-helper");
        Path packageDir = baseDir.resolve("demo");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("CrossFileCloneHelper.java"), """
                package demo;

                final class CrossFileCloneHelper {}
                """, StandardCharsets.UTF_8);
        String firstText = """
                package demo;

                public class A {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """;
        String secondText = """
                package demo;

                public class B {
                    void work() {
                        same();
                    }
                    void same() {}
                }
                """;
        Path firstPath = packageDir.resolve("A.java");
        Path secondPath = packageDir.resolve("B.java");
        Files.writeString(firstPath, firstText, StandardCharsets.UTF_8);
        Files.writeString(secondPath, secondText, StandardCharsets.UTF_8);
        CrossFileSource first = sourceFromPath(baseDir, firstPath, firstText);
        CrossFileSource second = sourceFromPath(baseDir, secondPath, secondText);
        CrossFileClone clone = cloneFor(first, 5, 5, second, 5, 5);

        String raw = """
                {
                  "status": "refactored",
                  "shared_helper": {
                    "strategy": "new_helper_class",
                    "path": "demo/CrossFileCloneHelper.java",
                    "package_name": "demo",
                    "class_name": "CrossFileCloneHelper",
                    "imports": [],
                    "helper_method": "static void helper() {}"
                  },
                  "files": []
                }
                """;

        CrossFileRefactorResult result = CrossFileRefactoringSupport.parseCrossFileRefactorResult(
                raw,
                null,
                List.of(first, second),
                clone,
                null
        );

        assertFalse(result.parsed);
        assertTrue(result.message.contains("already exists"));
        assertTrue(result.message.contains("CrossFileCloneHelper.java"));
    }

    public void testSliceUsefulnessWindowCapsLineCount() {
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 180; i++) {
            source.append("line").append(i).append("();\n");
        }

        String window = CrossFileCloneRefactorWorkflow.sliceUsefulnessWindow(source.toString(), 40, 160);

        assertTrue(CrossFileSourceSupport.countLines(window) <= 80);
        assertTrue(window.contains("line160();"));
    }

    private CrossFileSource addSource(String path, String source) {
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.addFileToProject(path, source);
        VirtualFile vf = javaFile.getVirtualFile();
        return new CrossFileSource(
                vf,
                new File(vf.getPath()),
                vf.getPath(),
                CrossFileSourceSupport.toProjectRelativePath(getProject(), vf.getPath()),
                javaFile.getText()
        );
    }

    private CrossFileClone cloneFor(CrossFileSource first,
                                    int firstStart,
                                    int firstEnd,
                                    CrossFileSource second,
                                    int secondStart,
                                    int secondEnd) {
        CrossFileClone clone = new CrossFileClone();
        clone.id = "cross_clone_test";
        clone.refactorType = "Extract Method";
        clone.occurrences.add(new CrossFileOccurrence(
                first,
                firstStart,
                firstEnd,
                CrossFileSourceSupport.sliceLines(first.source, firstStart, firstEnd)
        ));
        clone.occurrences.add(new CrossFileOccurrence(
                second,
                secondStart,
                secondEnd,
                CrossFileSourceSupport.sliceLines(second.source, secondStart, secondEnd)
        ));
        return clone;
    }

    private CrossFileSource sourceFromPath(Path baseDir, Path file, String source) {
        String absolutePath = file.toFile().getAbsolutePath();
        String relativePath = baseDir.relativize(file).toString().replace(File.separatorChar, '/');
        return new CrossFileSource(null, file.toFile(), absolutePath, relativePath, source);
    }

    private CrossFileDetectionPanelistOutcome detectionOutcome(String panelistId, CrossFileDetectionResult result) {
        return new CrossFileDetectionPanelistOutcome(panelistId, "", result, result != null && result.parsed, "");
    }

    private CrossFileDetectionResult detectionResult(CrossFileClone clone) {
        CrossFileDetectionResult result = new CrossFileDetectionResult();
        result.parsed = true;
        result.status = "found_clones";
        result.clones.add(clone);
        return result;
    }

    private CrossFileDetectionResult noCloneDetectionResult() {
        CrossFileDetectionResult result = new CrossFileDetectionResult();
        result.parsed = true;
        result.status = "no_clones";
        return result;
    }

    private CrossFileRefactorPanelistOutcome refactorOutcome(String panelistId, CrossFileRefactorResult result) {
        return new CrossFileRefactorPanelistOutcome(panelistId, "", result, result != null && result.parsed, "");
    }

    private CrossFileRefactorResult refactorResult(boolean parsed,
                                                   String status,
                                                   boolean hasChanges,
                                                   boolean hasWarnings,
                                                   CrossFileSource source) {
        CrossFileRefactorResult result = new CrossFileRefactorResult();
        result.parsed = parsed;
        result.status = status;
        if (hasChanges) {
            result.newSourcesByFile.put(source, source.source + "\n");
        }
        if (hasWarnings) {
            result.warnings.add("warning");
        }
        return result;
    }
}
