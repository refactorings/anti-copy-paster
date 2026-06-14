package org.jetbrains.research.anticopypaster.ide;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
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
import org.jetbrains.research.anticopypaster.llm.LlmClient;
import org.jetbrains.research.anticopypaster.llm.LlmClientFactory;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterTelemetry;

import java.awt.*;
import java.io.*;
import java.net.Socket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

import static org.jetbrains.research.anticopypaster.ide.AiderHelper.openStreamingViewer;
import static org.jetbrains.research.anticopypaster.ide.AiderHelper.runAiderWithPromptStreaming;

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

    private static boolean isVoidType(String type) {
        return "void".equals(type);
    }

    private static String typeParameterName(int index) {
        return "T" + (index + 1);
    }

    private static int indexOfParameterForElement(List<Parameter> parameters, PsiElement element) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).extractedValue() == element) {
                return i;
            }
        }
        return -1;
    }

    private static PsiVariable owningVariable(PsiTypeElement typeElement) {
        PsiElement current = typeElement.getParent();
        while (current != null && !(current instanceof PsiVariable)) {
            current = current.getParent();
        }
        return current instanceof PsiVariable variable ? variable : null;
    }

    private static int aliasIDForVariableName(Clone clone, String name) {
        for (int i = clone.aliasMap().size() - 1; i >= 0; i--) {
            if (Objects.equals(clone.aliasMap().get(i).identifier(), name)) {
                return i;
            }
        }
        return -1;
    }

    private static String typeParameterForVariable(Clone clone, PsiVariable variable) {
        for (int i = 0; i < clone.typeParams().size(); i++) {
            PsiVariable owner = owningVariable(clone.typeParams().get(i));
            if (owner == variable || (owner != null && Objects.equals(owner.getName(), variable.getName()))) {
                return typeParameterName(i);
            }
        }
        return null;
    }

    private static String typeParameterForAlias(Clone clone, int aliasID) {
        if (aliasID < 0 || aliasID >= clone.aliasMap().size()) {
            return null;
        }
        String aliasName = clone.aliasMap().get(aliasID).identifier();
        for (int i = 0; i < clone.typeParams().size(); i++) {
            PsiVariable owner = owningVariable(clone.typeParams().get(i));
            if (owner != null && Objects.equals(owner.getName(), aliasName)) {
                return typeParameterName(i);
            }
        }
        return null;
    }

    private static String expectedTypeParameterForValue(Clone clone, PsiElement value) {
        PsiElement parent = value.getParent();
        if (parent instanceof PsiLocalVariable variable && variable.getInitializer() == value) {
            return typeParameterForVariable(clone, variable);
        }
        if (value instanceof PsiAssignmentExpression assignment && assignment.getOperationTokenType() != JavaTokenType.EQ) {
            PsiExpression leftExpression = assignment.getLExpression();
            if (leftExpression instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable) {
                return typeParameterForVariable(clone, variable);
            }
        }
        if (parent instanceof PsiAssignmentExpression assignment && assignment.getRExpression() == value) {
            PsiExpression leftExpression = assignment.getLExpression();
            if (leftExpression instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable) {
                return typeParameterForVariable(clone, variable);
            }
        }
        return null;
    }

    private static String generatedParameterType(Clone clone, Parameter parameter) {
        if (parameter.extractedValue() instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable) {
            String foreachTypeParameter = typeParameterForForeachIterable(clone, variable);
            if (foreachTypeParameter != null) {
                return "java.lang.Iterable<" + foreachTypeParameter + ">";
            }
        }
        String expectedTypeParameter = expectedTypeParameterForValue(clone, parameter.extractedValue());
        return expectedTypeParameter == null ? parameter.type() : expectedTypeParameter;
    }

    private static String typeParameterForForeachIterable(Clone clone, PsiVariable variable) {
        PsiElement current = clone.start();
        while (current != null) {
            if (current instanceof PsiForeachStatement foreachStatement) {
                String typeParameter = typeParameterForForeachIterable(clone, variable, foreachStatement);
                if (typeParameter != null) {
                    return typeParameter;
                }
            } else {
                for (PsiForeachStatement foreachStatement : PsiTreeUtil.findChildrenOfType(current, PsiForeachStatement.class)) {
                    String typeParameter = typeParameterForForeachIterable(clone, variable, foreachStatement);
                    if (typeParameter != null) {
                        return typeParameter;
                    }
                }
            }
            if (current == clone.end()) break;
            current = current.getNextSibling();
        }
        return null;
    }

    private static String typeParameterForForeachIterable(Clone clone, PsiVariable variable,
                                                          PsiForeachStatement foreachStatement) {
        PsiExpression iteratedValue = foreachStatement.getIteratedValue();
        if (iteratedValue instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable iteratedVariable
                && (iteratedVariable == variable || Objects.equals(iteratedVariable.getName(), variable.getName()))) {
            return typeParameterForVariable(clone, foreachStatement.getIterationParameter());
        }
        return null;
    }

    private static String generatedLiveInVariableType(Clone clone, PsiVariable variable) {
        String foreachTypeParameter = typeParameterForForeachIterable(clone, variable);
        if (foreachTypeParameter != null) {
            return "java.lang.Iterable<" + foreachTypeParameter + ">";
        }
        return variable.getType().getPresentableText();
    }

    private static String generatedLambdaArgType(Clone clone, int aliasID) {
        String typeParameter = typeParameterForAlias(clone, aliasID);
        return typeParameter == null ? clone.aliasMap().get(aliasID).type() : typeParameter;
    }

    private static boolean endsWithReturnValue(Clone clone) {
        return clone.end() instanceof PsiReturnStatement returnStatement
                && returnStatement.getReturnValue() != null;
    }

    private static String generatedTerminalReturnType(Clone clone) {
        if (!(clone.end() instanceof PsiReturnStatement returnStatement)) {
            return null;
        }
        PsiExpression returnValue = returnStatement.getReturnValue();
        if (returnValue == null) {
            return null;
        }
        if (returnValue instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable) {
            String typeParameter = typeParameterForVariable(clone, variable);
            if (typeParameter != null) {
                return typeParameter;
            }
        }
        PsiType type = returnValue.getType();
        return type == null ? null : type.getPresentableText();
    }

    private static String generatedReturnType(Clone clone) {
        String terminalReturnType = generatedTerminalReturnType(clone);
        if (terminalReturnType != null) {
            return terminalReturnType;
        }
        if (clone.liveOutVars().isEmpty()) {
            return null;
        }
        Variable liveOutVar = clone.liveOutVars().get(0);
        String typeParameter = typeParameterForAlias(clone, aliasIDForVariableName(clone, liveOutVar.identifier()));
        return typeParameter == null ? liveOutVar.type() : typeParameter;
    }

    private record ReturnPlan(Clone template, String returnType) {
    }

    private static ReturnPlan generatedReturnPlan(List<Clone> results) {
        Clone template = results.get(0);
        String returnType = null;
        for (Clone clone : results) {
            String cloneReturnType = generatedReturnType(clone);
            if (cloneReturnType == null) {
                continue;
            }
            if (returnType == null) {
                template = clone;
                returnType = cloneReturnType;
            } else if (!returnType.equals(cloneReturnType)) {
                return null;
            } else if (!endsWithReturnValue(template) && endsWithReturnValue(clone)) {
                template = clone;
            }
        }
        return new ReturnPlan(template, returnType);
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
    private void buildMethodBody(PsiElement current, PsiElement last, List<Parameter> extractedParameters, List<List<Integer>> normalizedLambdaArgs, List<Variable> aliasMap, List<PsiTypeElement> typeParams, StringBuilder sb) {
        // Iterates through all siblings at this level
        while (current != null) {
            int idx = indexOfParameterForElement(extractedParameters, current);
            int idx2 = typeParams.indexOf(current);
            if (idx2 != -1) {
                sb.append("T");
                sb.append(idx2 + 1);
            } else if (idx != -1 && current instanceof PsiAssignmentExpression assignment
                    && assignment.getOperationTokenType() != JavaTokenType.EQ) {
                sb.append(assignment.getLExpression().getText());
                sb.append(" = p");
                sb.append(idx + 1);
                appendFunctionalCall(
                        sb,
                        extractedParameters.get(idx),
                        normalizedLambdaArgs.get(idx).stream().map((j) -> aliasMap.get(j).identifier()).toList()
                );
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
                appendFunctionalCall(sb, extractedParameters.get(idx), idents);
            }
            if (current == last) break;
            current = current.getNextSibling();
        }
    }

    private static void appendFunctionalCall(StringBuilder sb, Parameter parameter, List<String> arguments) {
        if (!arguments.isEmpty()) {
            sb.append(isVoidType(parameter.type()) ? ".accept(" : ".apply(");
            sb.append(String.join(", ", arguments));
            sb.append(')');
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
            Parameter parameter = clone.parameters().get(i);
            String type = generatedParameterType(clone, parameter);
            List<String> parameterArgTypes = normalizedLambdaArgs.get(i).stream()
                    .map((j) -> generatedLambdaArgType(clone, j))
                    .toList();
            if (parameterArgTypes.isEmpty()) { // Not a lambda argument
                sb.append(type);
            } else if (parameterArgTypes.size() == 1) { // Lambda arg, 1 param
                if (isVoidType(type)) {
                    sb.append("java.util.function.Consumer<");
                    sb.append(CloneProcessor.boxedType(parameterArgTypes.get(0)));
                    sb.append(">");
                } else {
                    sb.append("java.util.function.Function<");
                    sb.append(CloneProcessor.boxedType(parameterArgTypes.get(0)));
                    sb.append(", ");
                    sb.append(CloneProcessor.boxedType(type));
                    sb.append(">");
                }
            } else if (parameterArgTypes.size() == 2) { // Lambda arg, 2 params
                if (isVoidType(type)) {
                    sb.append("java.util.function.BiConsumer<");
                    sb.append(CloneProcessor.boxedType(parameterArgTypes.get(0)));
                    sb.append(", ");
                    sb.append(CloneProcessor.boxedType(parameterArgTypes.get(1)));
                    sb.append(">");
                } else {
                    sb.append("java.util.function.BiFunction<");
                    sb.append(CloneProcessor.boxedType(parameterArgTypes.get(0)));
                    sb.append(", ");
                    sb.append(CloneProcessor.boxedType(parameterArgTypes.get(1)));
                    sb.append(", ");
                    sb.append(CloneProcessor.boxedType(type));
                    sb.append(">");
                }
            }
            sb.append(" p");
            sb.append(i + 1);
            if (i != clone.parameters().size() - 1 || !clone.liveInVars().isEmpty())
                sb.append(", ");
        }
        List<PsiVariable> liveInVars = clone.liveInVars().stream().sorted(Comparator.comparing(PsiNamedElement::getName)).toList();
        for (int i = 0; i < liveInVars.size(); i++) {
            PsiVariable variable = liveInVars.get(i);
            sb.append(generatedLiveInVariableType(clone, variable));
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
                clone.parameters(),
                normalizedLambdaArgs,
                clone.aliasMap(),
                clone.typeParams(),
                sb
        );
        if (returnType != null && !endsWithReturnValue(clone)) {
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

    private String buildMethodCallText(Clone clone, List<List<Integer>> normalizedLambdaArgs, String methodName) {
        StringBuilder sb = new StringBuilder();
        String resultVarName = null;
        boolean replaceWithReturn = endsWithReturnValue(clone);

        if (replaceWithReturn) {
            sb.append("return ");
        } else if (!clone.liveOutVars().isEmpty()) {
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
        return sb.toString();
    }

    private void generateMethodCall(Clone clone, PsiElementFactory factory, List<List<Integer>> normalizedLambdaArgs, String methodName) {
        PsiElement start = clone.start();
        PsiElement end = clone.end();
        PsiElement parent = start.getParent();
        String callText = buildMethodCallText(clone, normalizedLambdaArgs, methodName);
        PsiElement caller = factory.createStatementFromText(callText, parent);
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

    /**
     * Asynchronously generates Java method-name suggestions for a given code snippet using Aider,
     * shows a chooser dialog to the user, and returns the selected name via the callback.
     * Runs work on a pooled thread, streams output to a console tab, and switches to the EDT for UI.
     *
     * Behavior: creates a temporary file from the snippet, prompts Aider to list N names (one per line),
     * cleans and ranks the suggestions, lets the user pick (or enter a fallback), then deletes the temp file.
     * On errors or empty results, notifies the user and invokes the callback with a default or null.
     *
     * @param project     current IntelliJ project
     * @param codeSnippet Java code for which to propose an extracted method name
     * @param provider    LLM provider identifier (e.g., OpenAI, Google, Anthropic, Azure, Deepseek, xAI)
     * @param model       model name for Aider
     * @param apikey      API key to set in the subprocess environment
     * @param aiderPath   path to the {@code aider} executable
     * @param apiBase     optional API base (used by Azure or custom deployments)
     * @param apiVersion  optional API version (used by Azure)
     * @param count       number of name suggestions to request and consider
     * @param callback    consumer that receives the chosen name (or {@code null} if none)
     */
    public static void suggestMethodNameAsync(Project project, String codeSnippet, String provider, String model, String apikey, String aiderPath, String apiBase, String apiVersion, int count, java.util.function.Consumer<String> callback) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            File tempFile = null;
            try {
                File tempDir = new File(System.getProperty("java.io.tmpdir"));
                tempFile = File.createTempFile("aider_namegen_", ".java", tempDir);

                Files.writeString(tempFile.toPath(), codeSnippet, StandardCharsets.UTF_8);

                String prompt = String.format(
                        "Suggest %d concise and meaningful Java method names for the extracted method in this file. " +
                                "List ONLY the method names, one per line, ranked from most to least confident. " +
                                "Format: 1 methodName1\\n2 methodName2\\n etc. " +
                                "Use valid Java identifiers (camelCase). Do not include method bodies or explanations.",
                        count
                );

                notify(project, "Clone is generating names...");
                java.util.function.Consumer<String> viewer = openStreamingViewer(project, "Clone Name Suggestions");

                String filePath = tempFile.getAbsolutePath();
                String output = runAiderWithPromptStreaming(project, aiderPath, filePath, prompt, provider, model, apikey, apiBase, apiVersion, viewer);

                final File finalTempFile = tempFile;
                ApplicationManager.getApplication().invokeLater(() -> {
                    String selected = null;
                    if (output != null) {
                        List<String> candidates = output.lines()
                                .map(String::trim)
                                .filter(line -> !line.isEmpty())
                                .map(line -> line.replaceFirst("^[\\d]+[\\s\\).:]+", ""))
                                .filter(name -> name.matches("[a-zA-Z_$][a-zA-Z\\d_$]*"))
                                .distinct()
                                .limit(count)
                                .toList();

                        if (!candidates.isEmpty()) {
                            selected = Messages.showEditableChooseDialog(
                                    "Choose a method name:",
                                    "Clone Name Suggestions",
                                    Messages.getQuestionIcon(),
                                    candidates.toArray(new String[0]),
                                    candidates.get(0),
                                    null
                            );
                        } else {
                            notify(project, "Clone didn't return any usable name suggestions. Please enter a method name.");
                            selected = Messages.showInputDialog(
                                    project,
                                    "Enter a method name:",
                                    "Clone Name Suggestions",
                                    Messages.getQuestionIcon(),
                                    "extractedMethod",
                                    null
                            );
                        }
                    }
                    callback.accept(selected);

                    if (finalTempFile != null && finalTempFile.exists()) {
                        try {
                            finalTempFile.delete();
                        } catch (Exception e) {

                        }
                    }
                });
            } catch (Exception e) {
                notify(project, "Failed to generate method names: " + e.getMessage());
                e.printStackTrace();

                final File finalTempFile = tempFile;
                ApplicationManager.getApplication().invokeLater(() -> {
                    callback.accept(null);
                    if (finalTempFile != null && finalTempFile.exists()) {
                        try {
                            finalTempFile.delete();
                        } catch (Exception ex) {
                        }
                    }
                });
            }
        });
    }

    private static String requestMethodNameSuggestionsFromConfiguredLlm(Project project, String prompt) throws Exception {
        java.util.function.Consumer<String> viewer = content -> { };
        LlmClient llmClient = LlmClientFactory.fromProjectSettings(project, viewer);
        String response = llmClient.complete(prompt);
        return response == null ? null : response;
    }

    private static List<String> parseMethodNameCandidates(String output, int count) {
        if (output == null) {
            return Collections.emptyList();
        }
        return output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceFirst("^[\\d]+[\\s\\).:]+", ""))
                .map(line -> line.replace("`", "").trim())
                .filter(name -> name.matches("[a-zA-Z_$][a-zA-Z\\d_$]*"))
                .distinct()
                .limit(count)
                .toList();
    }

    private static String buildMethodNameSuggestionPrompt(String codeSnippet, int count) {
        return String.format(
                "Suggest %d concise and meaningful Java method names for the extracted method in this code. " +
                        "List ONLY the method names, one per line, ranked from most to least confident. " +
                        "Format: 1 methodName1\\n2 methodName2\\n etc. " +
                        "Use valid Java identifiers (camelCase). Do not include method bodies or explanations.\\n\\n" +
                        "```java\\n%s\\n```",
                count,
                codeSnippet
        );
    }

    public static void suggestMethodNameMultiagentAsync(Project project, String codeSnippet, String provider, String model, String apikey, String aiderPath, String apiBase, String apiVersion, int count, java.util.function.Consumer<String> callback) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String prompt = buildMethodNameSuggestionPrompt(codeSnippet, count);
                notify(project, "Clone_multiagent is generating names...");

                String output = requestMethodNameSuggestionsFromConfiguredLlm(project, prompt);
                if (output != null) {
                    System.out.println("[NAME_LLM] raw output:\n" + output);
                }
                List<String> candidates = parseMethodNameCandidates(output, count);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String selected;
                    if (!candidates.isEmpty()) {
                        selected = Messages.showEditableChooseDialog(
                                "Choose a method name:",
                                "Clone_multiagent Name Suggestions",
                                Messages.getQuestionIcon(),
                                candidates.toArray(new String[0]),
                                candidates.get(0),
                                null
                        );
                    } else {
                        notify(project, "Clone_multiagent didn't return any usable name suggestions. Please enter a method name. Check API key / provider settings if this keeps happening.");
                        selected = Messages.showInputDialog(
                                project,
                                "Enter a method name:",
                                "Clone_multiagent Name Suggestions",
                                Messages.getQuestionIcon(),
                                "extractedMethod",
                                null
                        );
                    }
                    callback.accept(selected);
                });
            } catch (Exception e) {
                notify(project, "Failed to generate method names: " + e.getMessage());
                e.printStackTrace();
                ApplicationManager.getApplication().invokeLater(() -> callback.accept(null));
            }
        });
    }


    public void passPreds(List<String> preds){
        String predstr = String.join("-", preds);
        try (Socket socket = new Socket("localhost", 8082);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(predstr);
        } catch (IOException e) {
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
                combinedLambdaArgs.add(new TreeSet<>());
            for (Clone clone : results)
                for (int i = 0; i < clone.parameters().size(); i++)
                    combinedLambdaArgs.get(i).addAll(clone.parameters().get(i).lambdaArgs());
            List<List<Integer>> normalizedLambdaArgs = new ArrayList<>();
            for (Set<Integer> lambdaArgs : combinedLambdaArgs) {
                // Type limitations without extension
                if (lambdaArgs.size() > 2) return;
                normalizedLambdaArgs.add(new ArrayList<>(lambdaArgs));
            }

            // Generate method return type
            ReturnPlan returnPlan = generatedReturnPlan(results);
            if (returnPlan == null) return;
            Clone template = returnPlan.template();
            String returnType = returnPlan.returnType();

            boolean extractToStatic = containingMethod.hasModifierProperty(PsiModifier.STATIC);
            String methodName;
            // Predictions
            List<String> pred = null;
            if ("code2vec".equals(ProjectSettingsState.getInstance(project).useNameRec)) {
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
                passPreds(pred);
                Clone finalTemplate1 = template;
                String finalReturnType1 = returnType;
                ApplicationManager.getApplication().executeOnPooledThread(() ->
                        finalizeExtraction(project, containingClass, factory, results, finalTemplate1, finalReturnType1, normalizedLambdaArgs, methodName, extractToStatic)
                );
            }
            else if ("built-in".equals(ProjectSettingsState.getInstance(project).useNameRec)) {
                pred = new ArrayList<>();
                pred.add("extractedMethod");
                methodName = getNewMethodName(containingClass, pred.get(0));
                passPreds(pred);
                Clone finalTemplate2 = template;
                String finalReturnType2 = returnType;
                ApplicationManager.getApplication().invokeLater(() ->
                        finalizeExtraction(project, containingClass, factory, results, finalTemplate2, finalReturnType2, normalizedLambdaArgs, methodName, extractToStatic)
                );
            }
            else if ("Clone".equals(ProjectSettingsState.getInstance(project).useNameRec)) {
                // Use the new async method name suggestion
                final Project finalProject = project;
                final PsiClass finalContainingClass = containingClass;
                final PsiElementFactory finalFactory = factory;
                final List<Clone> finalResults = results;
                final Clone finalTemplate = template;
                final String finalReturnType = returnType;
                final List<List<Integer>> finalLambdaArgs = normalizedLambdaArgs;
                final boolean finalExtractToStatic = extractToStatic;
                suggestMethodNameAsync(
                        finalProject,
                        buildMethodText(finalTemplate, finalReturnType, finalLambdaArgs, "tempName", finalExtractToStatic),
                        ProjectSettingsState.getInstance(finalProject).getLlmprovider(),
                        ProjectSettingsState.getInstance(finalProject).getAiderModel(),
                        ProjectSettingsState.getInstance(finalProject).getAiderApiKey(),
                        ProjectSettingsState.getInstance(finalProject).getAiderPath(),
                        ProjectSettingsState.getInstance(finalProject).getApiBase(),
                        ProjectSettingsState.getInstance(finalProject).getApiVersion(),
                        ProjectSettingsState.getInstance(finalProject).numOfPreds,
                        (String methodSuggestion) -> {
                            List<String> predLocal;
                            String methodNameLocal;
                            if (methodSuggestion != null && !methodSuggestion.isEmpty()) {
                                predLocal = new ArrayList<>();
                                predLocal.add(methodSuggestion);
                                methodNameLocal = getNewMethodName(finalContainingClass, methodSuggestion);
                            } else {
                                notify(finalProject, "Clone did not provide a name. Using default 'extractedMethod'.");
                                predLocal = new ArrayList<>();
                                predLocal.add("extractedMethod");
                                methodNameLocal = getNewMethodName(finalContainingClass, "extractedMethod");
                            }
                            passPreds(predLocal);
                            ApplicationManager.getApplication().invokeLater(() ->
                                    finalizeExtraction(finalProject, finalContainingClass, finalFactory, finalResults, finalTemplate, finalReturnType, finalLambdaArgs, methodNameLocal, finalExtractToStatic, false)
                            );
                        });
            }
            else if ("Clone_multiagent".equals(ProjectSettingsState.getInstance(project).useNameRec)) {
                // Use the new async method name suggestion
                final Project finalProject = project;
                final PsiClass finalContainingClass = containingClass;
                final PsiElementFactory finalFactory = factory;
                final List<Clone> finalResults = results;
                final Clone finalTemplate = template;
                final String finalReturnType = returnType;
                final List<List<Integer>> finalLambdaArgs = normalizedLambdaArgs;
                final boolean finalExtractToStatic = extractToStatic;
                suggestMethodNameMultiagentAsync(
                        finalProject,
                        buildMethodText(finalTemplate, finalReturnType, finalLambdaArgs, "tempName", finalExtractToStatic),
                        ProjectSettingsState.getInstance(finalProject).getLlmprovider(),
                        ProjectSettingsState.getInstance(finalProject).getAiderModel(),
                        ProjectSettingsState.getInstance(finalProject).getAiderApiKey(),
                        ProjectSettingsState.getInstance(finalProject).getAiderPath(),
                        ProjectSettingsState.getInstance(finalProject).getApiBase(),
                        ProjectSettingsState.getInstance(finalProject).getApiVersion(),
                        ProjectSettingsState.getInstance(finalProject).numOfPreds,
                        (String methodSuggestion) -> {
                            List<String> predLocal;
                            String methodNameLocal;
                            if (methodSuggestion != null && !methodSuggestion.isEmpty()) {
                                predLocal = new ArrayList<>();
                                predLocal.add(methodSuggestion);
                                methodNameLocal = getNewMethodName(finalContainingClass, methodSuggestion);
                            } else {
                                notify(finalProject, "Clone_multiagent did not provide a name. Using default 'extractedMethod'.");
                                predLocal = new ArrayList<>();
                                predLocal.add("extractedMethod");
                                methodNameLocal = getNewMethodName(finalContainingClass, "extractedMethod");
                            }
                            passPreds(predLocal);
                            ApplicationManager.getApplication().invokeLater(() ->
                                    finalizeExtraction(finalProject, finalContainingClass, finalFactory, finalResults, finalTemplate, finalReturnType, finalLambdaArgs, methodNameLocal, finalExtractToStatic, false)
                            );
                        });
            }
            else {
                // No Aider: ask the user for a method name (fallback to default).
                final Project finalProject = project;
                final PsiClass finalContainingClass = containingClass;
                final PsiElementFactory finalFactory = factory;
                final List<Clone> finalResults = results;
                final Clone finalTemplate = template;
                final String finalReturnType = returnType;
                final List<List<Integer>> finalLambdaArgs = normalizedLambdaArgs;
                final boolean finalExtractToStatic = extractToStatic;

                ApplicationManager.getApplication().invokeLater(() -> {
                    String suggested = "extractedMethod";
                    String input = Messages.showInputDialog(
                            finalProject,
                            "Enter a method name for the extracted method:",
                            "Method Name",
                            Messages.getQuestionIcon(),
                            suggested,
                            null
                    );

                    String chosenBase = (input != null && !input.isBlank()) ? input.trim() : suggested;

                    List<String> predLocal = new ArrayList<>();
                    predLocal.add(chosenBase);

                    String methodNameLocal = getNewMethodName(finalContainingClass, chosenBase);
                    passPreds(predLocal);

                    finalizeExtraction(finalProject, finalContainingClass, finalFactory, finalResults,
                            finalTemplate, finalReturnType, finalLambdaArgs, methodNameLocal, finalExtractToStatic, true);
                });
                return;
            }
        });
    }

    /**
     * Builds the extracted method text, inserts it into the containing class, formats it,
     * and replaces each clone occurrence with a call to the new method. Triggers a rename dialog.
     *
     * @param project            current IntelliJ project
     * @param containingClass    class where the new method will be inserted
     * @param factory            PSI element factory used to create the method
     * @param results            clone locations to replace with calls
     * @param template           clone chosen as the template for the new method
     * @param returnType         return type of the new method (null means void)
     * @param normalizedLambdaArgs normalized lambda arguments per parameter
     * @param methodName         base name for the new method
     * @param extractToStatic    whether to mark the method as static
     */
    private void finalizeExtraction(Project project, PsiClass containingClass, PsiElementFactory factory, List<Clone> results, Clone template, String returnType, List<List<Integer>> normalizedLambdaArgs, String methodName, boolean extractToStatic) {
        finalizeExtraction(project, containingClass, factory, results, template, returnType, normalizedLambdaArgs, methodName, extractToStatic, true);
    }


    /**
     * Creates the extracted method PSI from generated text, inserts it before the class closing brace,
     * shortens fully-qualified references, reformats, and replaces each clone occurrence with a call.
     * Optionally opens the Rename dialog for the newly inserted method.
     *
     * @param project            current IntelliJ project
     * @param containingClass    class where the new method is added
     * @param factory            PSI element factory
     * @param results            clone locations to be replaced by a call
     * @param template           representative clone used to generate the method body
     * @param returnType         return type for the new method (null for void)
     * @param normalizedLambdaArgs normalized lambda arguments per parameter
     * @param methodName         name of the new method to insert
     * @param extractToStatic    true to make the new method static
     * @param triggerRename      true to invoke the Rename refactoring UI after insertion
     */
    private void finalizeExtraction(Project project, PsiClass containingClass, PsiElementFactory factory, List<Clone> results, Clone template, String returnType, List<List<Integer>> normalizedLambdaArgs, String methodName, boolean extractToStatic, boolean triggerRename) {
        PsiMethod extractedMethodElement = factory.createMethodFromText(
                buildMethodText(template, returnType, normalizedLambdaArgs, methodName, extractToStatic),
                containingClass
        );

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

                        if (triggerRename) {
                            ApplicationManager.getApplication().invokeLater(() ->
                                    RefactoringActionHandlerFactory.getInstance().createRenameHandler().invoke(
                                            project,
                                            new PsiElement[]{lastElement},
                                            SimpleDataContext.getProjectContext(project)
                                    )
                            );
                        }
                    },
                    "Clone Extraction",
                    null
            );
        });
    }
    private static void notify(Project project, String content) {
        Notification notification = new Notification(
                "AiderRefactor",
                "Clone Refactoring",
                content,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }
}
