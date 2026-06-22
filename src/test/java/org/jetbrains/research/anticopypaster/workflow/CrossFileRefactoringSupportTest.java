package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.File;
import java.util.List;
import org.junit.Test;

public class CrossFileRefactoringSupportTest extends LightJavaCodeInsightFixtureTestCase {

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
