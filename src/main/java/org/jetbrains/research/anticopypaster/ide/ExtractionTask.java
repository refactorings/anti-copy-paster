package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.refactoring.RefactoringActionHandlerFactory;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.CloneProcessor;
import org.jetbrains.research.anticopypaster.cloneprocessors.Parameter;
import org.jetbrains.research.anticopypaster.cloneprocessors.Variable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterTelemetry;

import java.awt.*;
import java.io.*;
import java.net.Socket;

import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

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
    private void buildMethodBody(PsiElement current, PsiElement last, List<PsiElement> extractedParameters, List<List<Integer>> normalizedLambdaArgs, List<Variable> aliasMap, List<PsiTypeElement> typeParams, StringBuilder sb) {
        // Iterates through all siblings at this level
        while (current != null) {
            int idx = extractedParameters.indexOf(current);
            int idx2 = typeParams.indexOf(current);
            if (idx2 != -1) {
                sb.append("T");
                sb.append(idx2 + 1);
            } else if (idx == -1)  {
                PsiElement firstChild = current.getFirstChild();
                if (firstChild == null) {
                    // This element has no children, stringify it
                    sb.append(current.getText());
                } else {
                    // The current element has children, descend
                    buildMethodBody(firstChild, last, extractedParameters, normalizedLambdaArgs, aliasMap, typeParams, sb);
                }
            } else {
                sb.append("p");
                sb.append(idx + 1);
                List<String> idents = normalizedLambdaArgs.get(idx).stream().map((j) -> aliasMap.get(j).identifier()).toList();
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
    private String buildMethodText(Clone clone, String returnType, List<List<Integer>> normalizedLambdaArgs, String methodName, boolean extractToStatic) {
        // Method signature
        StringBuilder sb = new StringBuilder("private ");
        if (extractToStatic) sb.append("static ");
        if (!clone.typeParams().isEmpty()) {
            sb.append("<");
            for (int i = 0; i < clone.typeParams().size(); i++) {
                sb.append("T");
                sb.append(i + 1);
                if (i != clone.typeParams().size() - 1)
                    sb.append(", ");
            }
            sb.append("> ");
        }
        sb.append(returnType == null ? "void" : returnType);
        sb.append(' ');
        sb.append(methodName);
        sb.append('(');
        // Build parameter list
        for (int i = 0; i < clone.parameters().size(); i++) {
            String type = clone.parameters().get(i).type();
            List<Variable> parameterArgs = normalizedLambdaArgs.get(i).stream().map((j) -> clone.aliasMap().get(j)).toList();
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
            if (i != clone.parameters().size() - 1 || !clone.liveInVars().isEmpty())
                sb.append(", ");
        }
        List<PsiVariable> liveInVars = clone.liveInVars().stream().sorted(Comparator.comparing(PsiNamedElement::getName)).toList();
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
                clone.aliasMap(),
                clone.typeParams(),
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

    public String renameInExpression(String identifier, String expression) {
        return expression.replaceAll("(?<![a-zA-Z0-9_$])" + identifier + "(?![a-zA-Z0-9_$])", identifier + "Arg");
    }

    private void generateMethodCall(Clone clone, PsiElementFactory factory, List<List<Integer>> normalizedLambdaArgs, String methodName) {
        PsiElement start = clone.start();
        PsiElement end = clone.end();
        PsiElement parent = start.getParent();
        StringBuilder sb = new StringBuilder();
        String resultVarName = null;

        if (!clone.liveOutVars().isEmpty()) {
            Variable liveOutVar = clone.liveOutVars().get(0);
            String liveOutType = liveOutVar.type();
            boolean isObjectType = liveOutType.equals(CloneProcessor.boxedType(liveOutType));

            if (clone
                    .liveInVars()
                    .stream()
                    .map(PsiVariable::getName)
                    .noneMatch(s -> s != null && s.equals(liveOutVar.identifier()))){
                // if the live-out var is not also live-in
                // we must set the result of the extracted code to a new variable
                sb.append(liveOutType);
                sb.append(" ");
                sb.append(liveOutVar.identifier());
                sb.append(" = ");
                resultVarName = liveOutVar.identifier();
            } else if (!isObjectType) {
                // otherwise if the live-out var is live-in AND a primitive (since primitives are pass by value)
                // we just re-assign it to the value of the extracted code
                sb.append(liveOutVar.identifier());
                sb.append(" = ");
                resultVarName = liveOutVar.identifier();
            }
        }
        sb.append(methodName);
        sb.append("(");
        for (int i = 0; i < clone.parameters().size(); i++) {
            Parameter p = clone.parameters().get(i);
            if (normalizedLambdaArgs.get(i).isEmpty()) { // Not a lambda argument
                sb.append(p.extractedValue().getText());
            } else if (normalizedLambdaArgs.get(i).size() == 1) { // Lambda arg, 1 param
                String identifier = clone.aliasMap().get(normalizedLambdaArgs.get(i).get(0)).identifier();
                String expression = p.extractedValue().getText();
                sb.append(identifier);
                if (identifier.equals(resultVarName)) {
                    sb.append("Arg");
                    expression = renameInExpression(identifier, expression);
                }
                sb.append(" -> ");
                sb.append(expression);
            } else if (normalizedLambdaArgs.get(i).size() == 2) { // Lambda arg, 2 params
                String identifier1 = clone.aliasMap().get(normalizedLambdaArgs.get(i).get(0)).identifier();
                String identifier2 = clone.aliasMap().get(normalizedLambdaArgs.get(i).get(1)).identifier();
                String expression = p.extractedValue().getText();
                sb.append("(");
                sb.append(identifier1);
                if (identifier1.equals(resultVarName)) {
                    sb.append("Arg");
                    expression = renameInExpression(identifier1, expression);
                }
                sb.append(", ");
                sb.append(identifier2);
                if (identifier2.equals(resultVarName)) {
                    sb.append("Arg");
                    expression = renameInExpression(identifier2, expression);
                }
                sb.append(") -> ");
                sb.append(expression);
            }
            if (i != clone.parameters().size() - 1 || !clone.liveInVars().isEmpty())
                sb.append(", ");
        }
        List<PsiVariable> liveInVars = clone.liveInVars().stream().sorted(Comparator.comparing(PsiNamedElement::getName)).toList();
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

    private String getNewMethodName(PsiClass containingClass, String base) {
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

    /**
     * Method to turn the code name prediction into a useable list.
     * @param input: The string representation of the prediction.
     * @return A list of the top x predictions.
     */
    public static List<String> extractEncasedText(String input, int numOfPreds) {
        List<String> result = new ArrayList<>();
        int i = input.indexOf('[');
        input = input.substring(0, i) + input.substring(i + 1);
        // Regular expressions to match text inside the predictions
        String regexSquareBrackets = "\\[([^\\]]*)\\]";

        // Find matches using the regular expressions
        java.util.regex.Pattern patternSquareBrackets = java.util.regex.Pattern.compile(regexSquareBrackets);
        java.util.regex.Matcher matcherSquareBrackets = patternSquareBrackets.matcher(input);

        // Extract and store the matches in the result list
        while (matcherSquareBrackets.find() && result.size() < numOfPreds) {
            String textInSquareBrackets = matcherSquareBrackets.group(1);
            textInSquareBrackets = textInSquareBrackets.replaceAll(" ", "");
            textInSquareBrackets = textInSquareBrackets.replaceAll(",", "_");
            textInSquareBrackets = textInSquareBrackets.replaceAll("'", "");
            if(textInSquareBrackets.length() >= 3){
                result.add(textInSquareBrackets);
            }
        }
        return result;
    }
    /**
     * Takes in a text representation of the method and returns an array of potential names to use.
     * @param clone The clone to use as the extracted method template
     * @param normalizedLambdaArgs The set union combined lambda args across all clones
     * @param methodName The name to give the method
     * @return The extracted method as text
     */
    public List<String> generateName(Clone clone, String returnType, List<List<Integer>> normalizedLambdaArgs, String methodName, boolean extractToStatic) {
        String code = buildMethodText(clone, returnType, normalizedLambdaArgs, methodName, extractToStatic);
        ProjectSettingsState state = ProjectSettingsState.getInstance(project);


        String[] args = {
                "--max_path_length",
                "8",
                "--max_path_width",
                "2",
                "--file",
                code,
                "--no_hash"
        };
        List<String> extractedText = null;
        try{
            ArrayList<ProgramFeatures> extracted = App.execute(args);
            Socket socket = new Socket("localhost", 8081);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(extracted);
            StringBuilder predictionsBuilder = new StringBuilder();
            char[] buffer = new char[8];
            int bytesRead;
            String curr;
            socket.setSoTimeout(200);
            while ((bytesRead = in.read(buffer)) != -1) {
                predictionsBuilder.append(buffer, 0, bytesRead);
                curr = predictionsBuilder.toString();
                if(curr.charAt(curr.length()-1)  == '\n'){
                    break;
                }
            }
            
            String predictions = predictionsBuilder.toString();
            socket.close();
            extractedText = extractEncasedText(predictions, ProjectSettingsState.getInstance(project).numOfPreds);
        }catch(Exception ignored){
        }
        return extractedText;
    }

    public void passPreds(List<String> preds){
        String predstr = "";
        for (String pred : preds){
            predstr += (pred+"-");
        }
        try{
            Socket socket = new Socket("localhost", 8082);
            PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
            out.println(predstr);
            socket.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
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
                    clone.liveInVars().addAll(clone.parameters().remove(i).liveInDeps());
            }

            int maxParams = ProjectSettingsState.getInstance(project).maxParams;
            int neededParams = results.get(0).parameters().size();
            if (neededParams > maxParams) {
                Messages.showInfoMessage(
                        project,
                        "Selected clones would result in an extracted method with "
                                + neededParams
                                + " parameters, but the maximum set in your settings is "
                                + maxParams
                                + ". Extraction aborted.",
                        "AntiCopyPaster Method Extractor"
                );
                return;
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
            List<List<Integer>> normalizedLambdaArgs = new ArrayList<>();
            for (Set<Integer> lambdaArgs : combinedLambdaArgs) {
                // Type limitations without extension
                if (lambdaArgs.size() > 2) return;
                normalizedLambdaArgs.add(lambdaArgs.stream().toList());
            }

            // Generate method return type
            Clone template = results.get(0);

            String returnType = null;
            for (Clone clone : results) {
                if (!clone.liveOutVars().isEmpty()) {
                    if (returnType == null) {
                        template = clone;
                        returnType = clone.liveOutVars().get(0).type();
                    } else if (!returnType.equals(clone.liveOutVars().get(0).type())) return;
                }
            }

            boolean extractToStatic = containingMethod.hasModifierProperty(PsiModifier.STATIC);
            String methodName;
            // Predictions
            List<String> pred = null;
            if(ProjectSettingsState.getInstance(project).useNameRec == 0) {
                try {
                    List<String> recs = generateName(template, returnType, normalizedLambdaArgs, "extractedMethod", extractToStatic);
                    if (recs != null) pred = recs;
                } catch (Exception ignored) {
                    ignored.printStackTrace();
                } finally {
                    if (pred == null) {
                        pred = new ArrayList<>();
                        pred.add("extractedMethod");
                    }
                }
                methodName = getNewMethodName(containingClass, pred.get(0));
            }
            else if (ProjectSettingsState.getInstance(project).useNameRec == 1) {
                pred = new ArrayList<>();
                pred.add("extractedMethod");
                methodName = getNewMethodName(containingClass, pred.get(0));
            } else {
                String methodSuggestion = AiderHelper.suggestMethodName(
                    project,
                    buildMethodText(template, returnType, normalizedLambdaArgs, "tempName", extractToStatic),
                    ProjectSettingsState.getInstance(project).getLlmprovider(),
                    ProjectSettingsState.getInstance(project).getAiderModel(),
                    ProjectSettingsState.getInstance(project).getAiderApiKey(),
                    ProjectSettingsState.getInstance(project).getAiderPath(),
                    ProjectSettingsState.getInstance(project).numOfPreds
                );
                if (methodSuggestion != null && !methodSuggestion.isEmpty()) {
                    pred = new ArrayList<>();
                    pred.add(methodSuggestion);
                    methodName = getNewMethodName(containingClass, methodSuggestion);
                } else {
                    pred = new ArrayList<>();
                    pred.add("extractedMethod");
                    methodName = getNewMethodName(containingClass, "extractedMethod");
                }
            }
            passPreds(pred);

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
                        },
                        "Clone Extraction",
                        null
                );
            });


        });
    }
}