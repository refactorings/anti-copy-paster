package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.ide.DuplicatesInspection;
import org.jetbrains.research.anticopypaster.ide.ExtractionTask;
import org.jetbrains.research.anticopypaster.ide.RefactoringEvent;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class TypeTwoCPTest extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected void setUp() throws Exception {
        ensureCode2VecModelMarker();
        super.setUp();
    }

    private static void ensureCode2VecModelMarker() throws Exception {
        Path marker = Path.of(System.getProperty("user.dir"),
                "build",
                "idea-sandbox",
                "plugins-test",
                "AntiCopyPaster",
                "code2vec",
                "java14m_model",
                "models",
                "java14_model",
                "dictionaries.bin");
        Files.createDirectories(marker.getParent());
        if (Files.notExists(marker)) {
            Files.createFile(marker);
        }
    }

    public void testType2CCloneStaysExtractableAndUsesVoidCallParameter() {
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("DemoType2C.java", """
                class DemoType2C {
                    void foo(float sum, float prod) {
                    }

                    void first(int n) {
                        float sum = 0.0f;
                        float prod = 1.0f;
                        for (int i = 1; i <= n; i++) {
                            sum = sum + i;
                            prod = prod * i;
                            foo(sum, prod);
                        }
                    }

                    void second(int n) {
                        int sum = 0;
                        int prod = 1;
                        for (int i = 1; i <= n; i++) {
                            sum = sum + i;
                            prod = prod * i;
                            foo(sum, prod);
                        }
                    }
                }
                """);

        PsiClass psiClass = file.getClasses()[0];
        PsiMethod first = psiClass.findMethodsByName("first", false)[0];
        PsiStatement[] statements = Objects.requireNonNull(first.getBody()).getStatements();

        List<Clone> clones = new TypeTwoCP().getClonesOfType(
                file,
                statements[0],
                statements[statements.length - 1]
        );

        Clone secondClone = clones.stream()
                .filter(clone -> {
                    PsiMethod method = PsiTreeUtil.getParentOfType(clone.start(), PsiMethod.class);
                    return method != null && "second".equals(method.getName());
                })
                .findFirst()
                .orElseThrow();

        assertTrue(secondClone.extractable());
        assertTrue(secondClone.typeParams().stream().anyMatch(type -> "int".equals(type.getText())));
        assertTrue(secondClone.parameters().stream().anyMatch(parameter -> "void".equals(parameter.type())));
    }

    public void testType2CCloneWithForeachAndCompoundAssignment() {
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("DemoForeachType2C.java", """
                import java.util.ArrayList;
                import java.util.List;

                class DemoForeachType2C {
                    public int countNumbers(List<Integer> values) {
                        int total = 0;
                        for (Integer value : values) {
                            if (value != null) {
                                total += value;
                            }
                        }
                        return total;
                    }

                    public long countNumbersClone(ArrayList<Long> numbers) {
                        long sum = 0L;
                        for (Long item : numbers) {
                            if (item != null) {
                                sum += item;
                            }
                        }
                        return sum;
                    }
                }
                """);

        PsiClass psiClass = file.getClasses()[0];
        PsiMethod first = psiClass.findMethodsByName("countNumbers", false)[0];
        PsiStatement[] statements = Objects.requireNonNull(first.getBody()).getStatements();

        List<Clone> clones = new TypeTwoCP().getClonesOfType(
                file,
                statements[0],
                statements[statements.length - 1]
        );

        assertTrue(clones.stream().anyMatch(clone -> {
            PsiMethod method = PsiTreeUtil.getParentOfType(clone.start(), PsiMethod.class);
            return method != null && "countNumbersClone".equals(method.getName());
        }));
    }

    public void testDuplicatesInspectionFindsForeachType2CClone() {
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("DemoInspectionForeachType2C.java", """
                import java.util.ArrayList;
                import java.util.List;

                class DemoInspectionForeachType2C {
                    public int countNumbers(List<Integer> values) {
                        int total = 0;
                        for (Integer value : values) {
                            if (value != null) {
                                total += value;
                            }
                        }
                        return total;
                    }

                    public long countNumbersClone(ArrayList<Long> numbers) {
                        long sum = 0L;
                        for (Long item : numbers) {
                            if (item != null) {
                                sum += item;
                            }
                        }
                        return sum;
                    }
                }
                """);
        ProjectSettingsState.getInstance(getProject()).extractionType = ProjectSettingsState.ExtractionType.TYPE_TWO;
        PsiClass psiClass = file.getClasses()[0];
        PsiMethod cloneMethod = psiClass.findMethodsByName("countNumbersClone", false)[0];
        String pastedBodyText = """
                long sum = 0L;
                for (Long item : numbers) {
                    if (item != null) {
                        sum += item;
                    }
                }
                return sum;
                """;

        DuplicatesInspection.InspectionResult result =
                new DuplicatesInspection().resolve(file, cloneMethod, pastedBodyText);

        assertTrue(result.results().stream().anyMatch(clone -> {
            PsiMethod method = PsiTreeUtil.getParentOfType(clone.start(), PsiMethod.class);
            return method != null && "countNumbers".equals(method.getName());
        }));
    }

    public void testExtractionTextForForeachType2CReturnBodyUsesGenericReturn() throws Exception {
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("DemoExtractionForeachType2C.java", """
                import java.util.ArrayList;
                import java.util.List;

                class DemoExtractionForeachType2C {
                    public int countNumbers(List<Integer> values) {
                        int total = 0;
                        for (Integer value : values) {
                            if (value != null) {
                                total += value;
                            }
                        }
                        return total;
                    }

                    public long countNumbersClone(ArrayList<Long> numbers) {
                        long sum = 0L;
                        for (Long item : numbers) {
                            if (item != null) {
                                sum += item;
                            }
                        }
                        return sum;
                    }
                }
                """);
        ProjectSettingsState.getInstance(getProject()).extractionType = ProjectSettingsState.ExtractionType.TYPE_TWO;
        PsiClass psiClass = file.getClasses()[0];
        PsiMethod cloneMethod = psiClass.findMethodsByName("countNumbersClone", false)[0];
        String pastedBodyText = """
                long sum = 0L;
                for (Long item : numbers) {
                    if (item != null) {
                        sum += item;
                    }
                }
                return sum;
                """;

        List<Clone> results = new DuplicatesInspection().resolve(file, cloneMethod, pastedBodyText).results();
        normalizeExtractionInputs(results);
        List<List<Integer>> normalizedLambdaArgs = normalizedLambdaArgs(results);
        Clone template = results.stream()
                .filter(clone -> {
                    PsiMethod method = PsiTreeUtil.getParentOfType(clone.start(), PsiMethod.class);
                    return method != null && "countNumbers".equals(method.getName());
                })
                .findFirst()
                .orElseThrow();

        Method generatedReturnType = ExtractionTask.class.getDeclaredMethod("generatedReturnType", Clone.class);
        generatedReturnType.setAccessible(true);
        String returnType = (String) generatedReturnType.invoke(null, template);

        ExtractionTask task = new ExtractionTask(new RefactoringEvent(file, cloneMethod, pastedBodyText, getProject(), null));
        Method buildMethodText = ExtractionTask.class.getDeclaredMethod(
                "buildMethodText",
                Clone.class,
                String.class,
                List.class,
                String.class,
                boolean.class
        );
        buildMethodText.setAccessible(true);
        String methodText = (String) buildMethodText.invoke(
                task,
                template,
                returnType,
                normalizedLambdaArgs,
                "extractedMethod",
                false
        );

        assertTrue(methodText.contains("private <T1, T2> T1 extractedMethod"));
        assertTrue(methodText.contains("java.lang.Iterable<T2>"));
        assertTrue(methodText.contains("java.util.function.BiFunction<T1, T2, T1>"));
        assertTrue(methodText.contains("total = p"));
        assertTrue(methodText.contains(".apply(total, value);"));
        assertTrue(methodText.contains("return total;"));
    }

    private static void normalizeExtractionInputs(List<Clone> results) {
        for (int i = results.get(0).parameters().size() - 1; i >= 0; i--) {
            Parameter currentParam = results.get(0).parameters().get(i);
            String text = currentParam.extractedValue().getText();
            boolean canRemove = true;
            int j = 1;
            while (canRemove && j < results.size()) {
                currentParam = results.get(j).parameters().get(i);
                canRemove = text.equals(currentParam.extractedValue().getText());
                j++;
            }
            if (!canRemove) continue;
            for (Clone clone : results)
                clone.liveInVars().addAll(clone.parameters().remove(i).liveInDeps());
        }

        for (int i = results.get(0).typeParams().size() - 1; i >= 0; i--) {
            String text = results.get(0).typeParams().get(i).getText();
            boolean canRemove = true;
            int j = 1;
            while (canRemove && j < results.size()) {
                canRemove = text.equals(results.get(j).typeParams().get(i).getText());
                j++;
            }
            if (!canRemove) continue;
            for (Clone clone : results)
                clone.typeParams().remove(i);
        }
    }

    private static List<List<Integer>> normalizedLambdaArgs(List<Clone> results) {
        List<Set<Integer>> combinedLambdaArgs = new ArrayList<>();
        for (int i = 0; i < results.get(0).parameters().size(); i++)
            combinedLambdaArgs.add(new TreeSet<>());
        for (Clone clone : results)
            for (int i = 0; i < clone.parameters().size(); i++)
                combinedLambdaArgs.get(i).addAll(clone.parameters().get(i).lambdaArgs());
        List<List<Integer>> normalizedLambdaArgs = new ArrayList<>();
        for (Set<Integer> lambdaArgs : combinedLambdaArgs) {
            normalizedLambdaArgs.add(new ArrayList<>(lambdaArgs));
        }
        return normalizedLambdaArgs;
    }
}
