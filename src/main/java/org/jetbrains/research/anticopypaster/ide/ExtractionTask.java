package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.refactoring.RefactoringActionHandlerFactory;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.CloneProcessor;
import org.jetbrains.research.anticopypaster.cloneprocessors.Parameter;
import org.jetbrains.research.anticopypaster.cloneprocessors.Variable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import java.awt.*;
import java.util.*;
import java.util.List;

public class ExtractionTask {
    public Project project;
    public PsiFile file;
    public String text;
    public RefactoringEvent event;

    public ExtractionTask(RefactoringEvent event) {
        this.project = event.getProject();
        this.file = event.getFile();
        this.text = event.getText();
        this.event = event;
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
    private String buildMethodText(Clone clone, String returnType, List<List<Variable>> normalizedLambdaArgs, String methodName, boolean extractToStatic) {
        // Method signature
        StringBuilder sb = new StringBuilder("private ");
        if (extractToStatic) sb.append("static ");
        sb.append(returnType == null ? "void" : returnType);
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
                sb.append(CloneProcessor.boxedType(parameterArgs.get(0).type()));
                sb.append(", ");
                sb.append(CloneProcessor.boxedType(type));
                sb.append(">");
            } else if (parameterArgs.size() == 2) { // Lambda arg, 2 params
                sb.append("java.util.function.BiFunction<");
                sb.append(CloneProcessor.boxedType(parameterArgs.get(0).type()));
                sb.append(", ");
                sb.append(CloneProcessor.boxedType(parameterArgs.get(1).type()));
                sb.append(", ");
                sb.append(CloneProcessor.boxedType(type));
                sb.append(">");
            }
            sb.append(" p");
            sb.append(i + 1);
            if (i != clone.parameters().size() - 1 || !clone.liveOutVars().isEmpty())
                sb.append(", ");
        }
        List<PsiVariable> liveInVars = clone.liveInVars().stream().toList();
        for (int i = 0; i < liveInVars.size(); i++) {
            PsiVariable variable = liveInVars.get(i);
            sb.append(variable.getType().getPresentableText());
            sb.append(" ");
            sb.append(variable.getName());
            if (i != liveInVars.size() - 1)
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
        if (returnType != null) {
            sb.append("\n\t\treturn ");
            sb.append(clone.liveOutVars().get(0).identifier());
            sb.append(";");
        }
        sb.append("\n\t}");
        return sb.toString();
    }

    private void generateMethodCall(Clone clone, PsiElementFactory factory, List<List<Variable>> lambdaArgs, String methodName) {
        PsiElement start = clone.start();
        PsiElement end = clone.end();
        PsiElement parent = start.getParent();
        StringBuilder sb = new StringBuilder();

        if (!clone.liveOutVars().isEmpty()) {
            Variable liveOutVar = clone.liveOutVars().get(0);
            String liveOutType = liveOutVar.type();
            boolean isObjectType = liveOutType.equals(CloneProcessor.boxedType(liveOutType));

            if (clone
                    .parameters()
                    .stream()
                    .map(p -> p.extractedValue().getText())
                    .noneMatch(s -> s.equals(liveOutVar.identifier()))){
                // if the live-out var is not also live-in
                // we must set the result of the extracted code to a new variable
                sb.append(liveOutType);
                sb.append(" ");
                sb.append(liveOutVar.identifier());
                sb.append(" = ");
            } else if (!isObjectType) {
                // otherwise if the live-out var is live-in AND a primitive (since primitives are pass by value)
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
            if (i != clone.parameters().size() - 1 || !clone.liveInVars().isEmpty())
                sb.append(", ");
        }
        List<PsiVariable> liveInVars = clone.liveInVars().stream().toList();
        for (int i = 0; i < liveInVars.size(); i++) {
            PsiVariable variable = liveInVars.get(i);
            sb.append(variable.getName());
            if (i != liveInVars.size() - 1)
                sb.append(", ");
        }
        sb.append(");\n");

        PsiElement caller = factory.createStatementFromText(sb.toString(), parent);
        parent.addAfter(caller, end);
        parent.deleteChildRange(start, end);
    }

    private String getNewMethodName(PsiClass containingClass) {
        String base = "extractedMethod";
        int i = 0;
        while (containingClass.findMethodsByName(i > 0 ? base + i : base).length > 0)
            i++;

        List<String> fieldNames = new ArrayList<>();
        for (PsiField field : containingClass.getFields())
            fieldNames.add(field.getName());

        while (fieldNames.contains(base + i))
            i++;

        return i > 0 ? base + i : base;
    }

    public void askWhichClonesToExtract(List<Clone> options) {
        Editor editor = event.getEditor();
        MarkupModel markupModel = editor.getMarkupModel();
        for (int i = options.size() - 1; i >= 0; i--) {
            Clone clone = options.get(i);
            int startOffset = clone.start().getTextOffset();
            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                    startOffset,
                    clone.end().getTextOffset() + clone.end().getTextLength(),
                    HighlighterLayer.LAST + 1000,
                    new TextAttributes(null, null, Color.red, EffectType.BOXED, Font.PLAIN),
                    HighlighterTargetArea.EXACT_RANGE
            );
            editor.getScrollingModel().scrollTo(editor.offsetToLogicalPosition(startOffset), ScrollType.CENTER);
            if (!MessageDialogBuilder.yesNo(
                            "AntiCopyPaster Method Extractor",
                            "Each clone that can be extracted will be highlighted "
                                    + "one by one. Please press the button below that corresponds "
                                    + "to the action you would like to take for each one.")
                    .yesText("Extract")
                    .noText("Don't Extract")
                    .icon(Messages.getQuestionIcon())
                    .ask(project))
                options.remove(i);
            markupModel.removeHighlighter(highlighter);
        }
    }

    public void run() {
        ApplicationManager.getApplication().invokeLater(() -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            PsiMethod containingMethod = event.getDestinationMethod();
            if (containingMethod == null) return;
            PsiClass containingClass = containingMethod.getContainingClass();
            if (containingClass == null) return;

            List<Clone> results = new DuplicatesInspection().resolve(file, event.getDestinationMethod(), text).results();
            if (results.size() < ProjectSettingsState.getInstance(project).minimumDuplicateMethods)
                return;
            // Allow the user to choose to extract each clone
            askWhichClonesToExtract(results);

            String methodName = getNewMethodName(containingClass);

            if (results.isEmpty()) {
                Messages.showInfoMessage(
                        project,
                        "No clones selected for extraction, nothing to do.",
                        "AntiCopyPaster Method Extractor"
                );
                return;
            }

            // Remove unnecessary parameters
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
                    clone.parameters().remove(i);
            }
            // And unnecessary type parameters
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

            // Generate method return type
            Clone template = results.get(0);

            String returnType = null;
            for (Clone clone : results) {
                if (clone.liveOutVars().size() > 0) {
                    if (returnType == null) {
                        template = clone;
                        returnType = clone.liveOutVars().get(0).type();
                    } else if (!returnType.equals(clone.liveOutVars().get(0).type())) return;
                }
            }

            boolean extractToStatic = containingMethod.hasModifierProperty(PsiModifier.STATIC);

            PsiMethod extractedMethodElement = factory.createMethodFromText(
                    buildMethodText(
                            template,
                            returnType,
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
            ApplicationManager.getApplication().runWriteAction(() -> {
                CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            PsiElement spacer = PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n");
                            PsiElement lastElement = containingClass.addBefore(extractedMethodElement, containingClass.getRBrace());
                            containingClass.addAfter(spacer, lastElement);
                            CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
                            styleManager.reformat(lastElement);
                            for (Clone location : results)
                                generateMethodCall(location, factory, normalizedLambdaArgs, methodName);

                            ApplicationManager.getApplication().invokeLater(() ->
                                RefactoringActionHandlerFactory.getInstance().createRenameHandler().invoke(
                                        project,
                                        new PsiElement[]{lastElement},
                                        SimpleDataContext.getProjectContext(project)
                                )
                            );
//                            var ref = MemberInplaceRenamer.getActiveInplaceRenamer(event.getEditor());
//                            ref.setElementToRename((PsiMethod)lastElement);
//                            ref.performInplaceRefactoring(new LinkedHashSet<>());
                        },
                        "Clone Extraction",
                        null
                );
            });


        });
    }
}
