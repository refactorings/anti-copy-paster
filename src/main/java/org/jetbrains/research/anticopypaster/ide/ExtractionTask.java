package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.CloneProcessor;
import org.jetbrains.research.anticopypaster.cloneprocessors.Parameter;
import org.jetbrains.research.anticopypaster.cloneprocessors.Variable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import java.util.*;

public class ExtractionTask extends TimerTask {
    public Project project;
    public PsiFile file;
    public String text;
    public RefactoringEvent event;
    public List<Clone> clones;

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
     * @param normalizedLambdaArgs The set union combined lambda args across all clones
     * @param sb The StringBuilder to append to
     */
    private void buildMethodBody(PsiElement current, PsiElement last, List<PsiElement> extractedParameters, List<List<Variable>> normalizedLambdaArgs, StringBuilder sb) {
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
                    buildMethodBody(firstChild, last, extractedParameters, normalizedLambdaArgs, sb);
                }
            } else {
                sb.append("p");
                sb.append(idx + 1);
                List<String> idents = normalizedLambdaArgs.get(idx).stream().map(Variable::identifier).toList();
                if (idents.size() > 0) {
                    sb.append(".apply(");
                    sb.append(String.join(", ", idents));
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
     * @param clone The clone to use as the extracted method template
     * @param normalizedLambdaArgs The set union combined lambda args across all clones
     * @param methodName The name to give the method
     * @return The extracted method as text
     */
    private String buildMethodText(Clone clone, List<List<Variable>> normalizedLambdaArgs, String methodName, boolean extractToStatic) {
        boolean returnsValue = !clone.liveVars().isEmpty();
        // Method signature
        StringBuilder sb = new StringBuilder("private ");
        if (extractToStatic) sb.append("static ");
        sb.append(returnsValue ? clone.liveVars().get(0).type() : "void");
        sb.append(' ');
        sb.append(methodName);
        sb.append('(');
        // Build parameter list
        for (int i = 0; i < clone.parameters().size(); i++) {
            String type = clone.parameters().get(i).type();
            List<Variable> parameterArgs = normalizedLambdaArgs.get(i);
            if (parameterArgs.isEmpty()) { // Not a lambda argument
                sb.append(type);
            } else if (parameterArgs.size() == 1) { // Lambda arg, 1 param
                sb.append("java.util.function.Function<");
                sb.append(CloneProcessor.objectTypeIfPrimitive(parameterArgs.get(0).type()));
                sb.append(", ");
                sb.append(CloneProcessor.objectTypeIfPrimitive(type));
                sb.append(">");
            } else if (parameterArgs.size() == 2) { // Lambda arg, 2 params
                sb.append("java.util.function.BiFunction<");
                sb.append(CloneProcessor.objectTypeIfPrimitive(parameterArgs.get(0).type()));
                sb.append(", ");
                sb.append(CloneProcessor.objectTypeIfPrimitive(parameterArgs.get(1).type()));
                sb.append(", ");
                sb.append(CloneProcessor.objectTypeIfPrimitive(type));
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
                normalizedLambdaArgs,
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

    private void callMethod(Clone clone, PsiElementFactory factory, List<List<Variable>> lambdaArgs, String methodName) {
        PsiElement start = clone.start();
        PsiElement end = clone.end();
        PsiElement parent = PsiTreeUtil.findCommonParent(start, end);
        // TODO should we check if parent is null? I dont think it ever will be...
        StringBuilder sb = new StringBuilder();
        if (!clone.liveVars().isEmpty()) {
            Variable liveOutVar = clone.liveVars().get(0);
            String liveOutType = liveOutVar.type();
            if (clone
                    .parameters()
                    .stream()
                    .map(p -> p.extractedValue().getText())
                    .noneMatch(s -> s.equals(liveOutVar.identifier()))) {
                // if the live-out var is not also live-in we must set the result of the extracted code to a new variable
                sb.append(liveOutType);
                sb.append(" ");
            }

            if (!liveOutType.equals(CloneProcessor.objectTypeIfPrimitive(liveOutType))) {
                // otherwise if the live-out var is live-in AND a primitive (since non-primitives are pass by ref)
                // we just re-assign it to the value of the extracted code
                sb.append(liveOutVar.identifier());
                sb.append(" = ");
            }
        }
        sb.append(methodName);
        sb.append("(");
        for (int i = 0; i < clone.parameters().size(); i++) {
            Parameter p = clone.parameters().get(i);
            if (p.lambdaArgs().isEmpty()) { // Not a lambda argument
                sb.append(p.extractedValue().getText());
            } else if (p.lambdaArgs().size() == 1) { // Lambda arg, 1 param
                sb.append(lambdaArgs.get(i).get(0).identifier());
                sb.append(" -> ");
                sb.append(p.extractedValue().getText());
            } else if (p.lambdaArgs().size() == 2) { // Lambda arg, 2 params
                sb.append("(");
                sb.append(String.join(", ", lambdaArgs.get(i).stream().map(Variable::identifier).toArray(String[]::new)));
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
        List<String> fieldNames = new ArrayList<>();
        for (PsiField field : containingClass.getFields())
            fieldNames.add(field.getName());

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

                List<Clone> results = new DuplicatesInspection().resolve(file, text).results();
                if (results.size() < ProjectSettingsState.getInstance(project).minimumDuplicateMethods)
                    return;

                for (Clone clone : results)
                    if (clone.liveVars().size() > 1) return;

                // Remove unnecessary parameters
                for (int i = results.get(0).parameters().size() - 1; i >= 0; i--) {
                    Parameter firstParam = results.get(0).parameters().get(i);
                    String text = firstParam.extractedValue().getText();
                    boolean canRemove = firstParam.lambdaArgs().isEmpty();
                    int j = 1;
                    while (canRemove && j < results.size()) {
                        Parameter currentParam = results.get(j).parameters().get(i);
                        canRemove = text.equals(currentParam.extractedValue().getText())
                                && currentParam.lambdaArgs().isEmpty();
                        j++;
                    }
                    if (!canRemove) continue;
                    for (Clone clone : results)
                        clone.parameters().remove(i);
                }

                // Combine all lambda args per parameter
                List<Set<Integer>> combinedLambdaArgs = new ArrayList<>();
                for (int i = 0; i < results.get(0).parameters().size(); i++)
                    combinedLambdaArgs.add(new HashSet<>());
                for (Clone clone : results)
                    for (int i = 0; i < clone.parameters().size(); i++)
                        combinedLambdaArgs.get(i).addAll(clone.parameters().get(i).lambdaArgs());
                List<Variable> referenceMap = results.get(0).aliasMap();
                List<List<Variable>> normalizedLambdaArgs = new ArrayList<>();
                for (Set<Integer> lambdaArgs : combinedLambdaArgs) {
                    // Type limitations without extension
                    if (lambdaArgs.size() > 2) return;
                    normalizedLambdaArgs.add(lambdaArgs.stream().map(referenceMap::get).toList());
                }

                String methodName = getNewMethodName(containingClass);

                for (Clone location : results)
                    callMethod(location, factory, normalizedLambdaArgs, methodName);

                boolean extractToStatic = containingMethod.hasModifierProperty(PsiModifier.STATIC);

                PsiMethod extractedMethodElement = factory.createMethodFromText(
                        buildMethodText(
                                results.get(0),
                                normalizedLambdaArgs,
                                methodName,
                                extractToStatic
                        ),
                        containingClass
                );

                // shortens the lambda args from java.util.function.Function to just Function
                // and imports the module if it needs to be imported (same for BiFunction)
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
