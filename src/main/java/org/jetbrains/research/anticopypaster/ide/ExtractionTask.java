package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.CloneProcessor;
import org.jetbrains.research.anticopypaster.cloneprocessors.Parameter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public class ExtractionTask extends TimerTask {
    public Project project;
    public PsiFile file;
    public String text;
    public RefactoringEvent event;
    public List<Clone> clones;
    private static AtomicInteger numExtractedMethods = new AtomicInteger(0);

    public ExtractionTask(RefactoringEvent event, DuplicatesInspection.InspectionResult inspectionResult) {
        this.project = event.getProject();
        this.file = event.getFile();
        this.text = event.getText();
        this.event = event;
        this.clones = inspectionResult.results();
    }

    /**
     * Recursively builds the extracted method body, replacing parameters in
     * the text as needed.
     * @param current The current element
     * @param last The last element in the body
     * @param extractedParameters The elements that should be extracted as parameters
     * @param parameters The full Parameter information for each parameter
     * @param sb The StringBuilder to append to
     */
    private void buildMethodBody(PsiElement current, PsiElement last, List<PsiElement> extractedParameters, List<Parameter> parameters, StringBuilder sb) {
        // Iterates through all siblings at this level
        while (current != null) {
            int idx = extractedParameters.indexOf(current);
            if (idx == -1)  {
                PsiElement firstChild = current.getFirstChild();
                if (firstChild == null) {
                    // This element has no children, stringify it
                    sb.append(current.getText());
                } else {
                    // The current element has children, descend
                    buildMethodBody(firstChild, last, extractedParameters, parameters, sb);
                }
            } else {
                sb.append("p");
                sb.append(idx + 1);
                Parameter p = parameters.get(idx);
                if (p.lambdaArgs().size() > 0) {
                    sb.append(".apply(");
                    sb.append(String.join(", ", p.lambdaArgs()));
                    sb.append(')');
                }
            }
            if (current == last) break;
            current = current.getNextSibling();
        }
    }

    /**
     * Takes in a Clone record and outputs an equivalent extracted method as
     * text.
     * TODO: vet arguments with more than 2 parameters before method call
     * @param clone The clone to use as the extracted method template
     * @param methodName The name to give the method
     * @return The extracted method as text
     */
    private String buildMethodText(Clone clone, String methodName) {
        boolean returnsValue = !clone.liveVars().isEmpty();
        // Method signature
        boolean importFunctions = false, importBiFunctions = false;
        StringBuilder sb = new StringBuilder("private ");
        sb.append(returnsValue ? clone.liveVars().get(0).type() : "void");
        sb.append(' ');
        sb.append(methodName);
        sb.append('(');
        // Build parameter list
        for (int i = 0; i < clone.parameters().size(); i++) {
            Parameter p = clone.parameters().get(i);
            if (p.lambdaArgs().isEmpty()) { // Not a lambda argument
                sb.append(p.type());
            } else if (p.lambdaArgs().size() == 1) { // Lambda arg, 1 param
                sb.append("java.util.function.Function<");
                sb.append(CloneProcessor.objectTypeIfPrimitive(p.lambdaTypes().get(0)));
                sb.append(", ");
                sb.append(CloneProcessor.objectTypeIfPrimitive(p.type()));
                sb.append(">");
            } else if (p.lambdaArgs().size() == 2) { // Lambda arg, 2 params
                sb.append("java.util.function.BiFunction<");
                sb.append(CloneProcessor.objectTypeIfPrimitive(p.lambdaTypes().get(0)));
                sb.append(", ");
                sb.append(CloneProcessor.objectTypeIfPrimitive(p.lambdaTypes().get(1)));
                sb.append(", ");
                sb.append(CloneProcessor.objectTypeIfPrimitive(p.type()));
                sb.append(">");
            }
            sb.append(" p");
            sb.append(i + 1);
            if (i != clone.parameters().size() - 1)
                sb.append(", ");
        }
        sb.append(") {\n\t\t");
        // Construct body recursively
        buildMethodBody(
                clone.start(),
                clone.end(),
                clone.parameters().stream().map(Parameter::extractedValue).toList(),
                clone.parameters(),
                sb
        );
        if (returnsValue) {
            sb.append("\n\t\treturn ");
            sb.append(clone.liveVars().get(0).identifier());
            sb.append(";");
        }
        sb.append("\n\t}");
        return sb.toString();
    }

    private void callMethod(Clone clone, PsiElementFactory factory, String methodName) {
        PsiElement start = clone.start();
        PsiElement end = clone.end();
        PsiElement parent = PsiTreeUtil.findCommonParent(start, end);
        // TODO should we check if parent is null? I dont think it ever will be...
        StringBuilder sb = new StringBuilder();
        if (!clone.liveVars().isEmpty()) {
            sb.append(clone.liveVars().get(0).type());
            sb.append(" resultVar = ");
        }
        sb.append(methodName);
        sb.append("(");
        for (int i = 0; i < clone.parameters().size(); i++) {
            Parameter p = clone.parameters().get(i);

            if (p.lambdaArgs().isEmpty()) { // Not a lambda argument
                sb.append(p.extractedValue().getText());
            } else if (p.lambdaArgs().size() == 1) { // Lambda arg, 1 param
                sb.append(p.lambdaArgs().get(0));
                sb.append(" -> ");
                sb.append(p.extractedValue().getText());
            } else if (p.lambdaArgs().size() == 2) { // Lambda arg, 2 params
                sb.append("(");
                sb.append(String.join(", ", p.lambdaArgs()));
                sb.append(") -> ");
                sb.append(p.extractedValue().getText());
            }
            if (i != clone.parameters().size() - 1)
                sb.append(", ");
        }
        sb.append(");\n");

        PsiElement caller = factory.createStatementFromText(sb.toString(), parent);
        CommandProcessor.getInstance().executeCommand(
                project,
                () -> {
                    parent.addAfter(caller, end);
                    parent.deleteChildRange(start, end);
                },
                "Clone Extraction",
                null
        );
    }

    private String getNewMethodName(PsiClass containingClass) {
        String base = "extractedMethod";
        int i = 0;
        while (containingClass.findMethodsByName(base + i).length > 0)
            i++;

        PsiField[] fields = containingClass.getFields();
        String[] fieldStrings = (String[]) Arrays.stream(fields).map(PsiField::getName).toArray();
        HashSet<String> fieldNames = new HashSet<>(List.of(fieldStrings));
        while (fieldNames.contains(base + i))
            i++;


        return base + i;
    }

    @Override
    public void run() {
        ApplicationManager.getApplication().invokeLater(() ->
            ApplicationManager.getApplication().runWriteAction(() -> {
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                //PsiElement spacer = PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n\n");

                PsiMethod containingMethod = event.getDestinationMethod();
                if (containingMethod == null) return;
                PsiClass containingClass = containingMethod.getContainingClass();
                if (containingClass == null) return;

                // TODO: run check on all clones to determine which parameters need to be lambdas
                DuplicatesInspection.InspectionResult result = new DuplicatesInspection().resolve(file, text);
                Clone template = result.results().get(0);

                String methodName = getNewMethodName(containingClass);

                for (Clone location : result.results()) {
                    callMethod(location, factory, methodName);
                }

                PsiMethod extractedMethodElement = factory.createMethodFromText(
                        buildMethodText(template, methodName),
                        containingClass
                );

                JavaCodeStyleManager styleManagerForLambdas = JavaCodeStyleManager.getInstance(project);
                styleManagerForLambdas.shortenClassReferences(extractedMethodElement);

                PsiMethod[] existingMethods = containingClass.getMethods();
                CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            if (existingMethods.length > 0)
                                containingClass.addAfter(extractedMethodElement, existingMethods[existingMethods.length - 1]);
                            else containingClass.add(extractedMethodElement);
                            CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
                            styleManager.reformat(extractedMethodElement);
                        },
                        "Clone Extraction",
                        null
                );
            })
        );
    }
}
