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
     * @param provider    LLM provider identifier (e.g., OpenAI, Gemini, Anthropic, Azure, Deepseek, xAI)
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
                passPreds(pred);
                Clone finalTemplate1 = template;
                String finalReturnType1 = returnType;
                ApplicationManager.getApplication().executeOnPooledThread(() ->
                        finalizeExtraction(project, containingClass, factory, results, finalTemplate1, finalReturnType1, normalizedLambdaArgs, methodName, extractToStatic)
                );
            }
            else if (ProjectSettingsState.getInstance(project).useNameRec == 1) {
                pred = new ArrayList<>();
                pred.add("extractedMethod");
                methodName = getNewMethodName(containingClass, pred.get(0));
                passPreds(pred);
                Clone finalTemplate2 = template;
                String finalReturnType2 = returnType;
                ApplicationManager.getApplication().invokeLater(() ->
                        finalizeExtraction(project, containingClass, factory, results, finalTemplate2, finalReturnType2, normalizedLambdaArgs, methodName, extractToStatic)
                );
            } else {
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
                        }
                );
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