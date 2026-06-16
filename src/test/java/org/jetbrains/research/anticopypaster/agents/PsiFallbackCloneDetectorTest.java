package org.jetbrains.research.anticopypaster.agents;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.junit.Test;

import java.util.List;

public class PsiFallbackCloneDetectorTest extends LightJavaCodeInsightFixtureTestCase {

    @Test
    public void testDetectsType2ClonesWithRenamedIdentifiersAndLiterals() {
        String source = """
                class Demo {
                    void first() {
                        int total = 1;
                        total += 2;
                        System.out.println(total);
                    }

                    void second() {
                        int count = 3;
                        count += 4;
                        System.out.println(count);
                    }
                }
                """;
        String pastedSnippet = """
                        int total = 1;
                        total += 2;
                        System.out.println(total);
                """;

        PsiFile psiFile = myFixture.addFileToProject("DemoType2.java", source);
        List<PsiFallbackCloneDetector.CloneCandidate> candidates =
                PsiFallbackCloneDetector.detectInSameFile(getProject(), psiFile.getVirtualFile(), pastedSnippet);

        assertTrue("Expected Type-2 fallback to find both normalized clones: " + candidates,
                candidates.size() >= 2);
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.cloneCode.contains("int total = 1")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.cloneCode.contains("int count = 3")));
    }

    @Test
    public void testDetectsType3ClonesWithInsertedStatement() {
        String source = """
                class Demo {
                    int first(int seed) {
                        int total = seed + 1;
                        total = total * 2;
                        if (total > 10) {
                            System.out.println(total);
                        }
                        return total;
                    }

                    int second(int start) {
                        int count = start + 3;
                        count = count * 2;
                        count = count + 5;
                        if (count > 20) {
                            System.out.println(count);
                        }
                        return count;
                    }
                }
                """;
        String pastedSnippet = """
                        int total = seed + 1;
                        total = total * 2;
                        if (total > 10) {
                            System.out.println(total);
                        }
                        return total;
                """;

        PsiFile psiFile = myFixture.addFileToProject("DemoType3.java", source);
        List<PsiFallbackCloneDetector.CloneCandidate> candidates =
                PsiFallbackCloneDetector.detectInSameFile(getProject(), psiFile.getVirtualFile(), pastedSnippet);

        assertTrue("Expected Type-3 fallback to find approximate clones: " + candidates,
                candidates.size() >= 2);
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.cloneCode.contains("int total = seed + 1")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.cloneCode.contains("count = count + 5")));
    }
}
