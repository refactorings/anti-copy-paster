package org.jetbrains.research.anticopypaster.ide;

import com.intellij.lang.LanguageRefactoringSupport;
import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.refactoring.RefactoringActionHandlerFactory;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.CloneProcessor;
import org.jetbrains.research.anticopypaster.cloneprocessors.Parameter;
import org.jetbrains.research.anticopypaster.cloneprocessors.Variable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;

import java.awt.*;
import java.io.*;
import java.net.Socket;
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
        if (returnType != null) {
            sb.append("\n\t\treturn ");
            sb.append(clone.liveVars().get(0).identifier());
            sb.append(";");
        }
        sb.append("\n\t}");
        return sb.toString();
    }

    private void generateMethodCall(Clone clone, PsiElementFactory factory, List<List<Variable>> lambdaArgs, String methodName) {
        PsiElement start = clone.start();
        PsiElement end = clone.end();
        PsiElement parent = start.getParent();
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
        parent.addAfter(caller, end);
        parent.deleteChildRange(start, end);
    }

    private String getNewMethodName(PsiClass containingClass, String base) {
        int i = 0;
        while (containingClass.findMethodsByName(i > 0 ? base + i : base).length > 0)
            i++;

        PsiField[] fields = containingClass.getFields();
        List<String> fieldNames = new ArrayList<>();
        for (PsiField field : containingClass.getFields())
            fieldNames.add(field.getName());

        while (fieldNames.contains(base + i))
            i++;

        return i > 0 ? base + i : base;
    }

    public void askWhichClonesToExtract(List<Clone> options) {
        MarkupModel markupModel = event.getEditor().getMarkupModel();
        for (int i = options.size() - 1; i >= 0; i--) {
            Clone clone = options.get(i);
            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                    clone.start().getTextOffset(),
                    clone.end().getTextOffset() + clone.end().getTextLength(),
                    HighlighterLayer.LAST + 1000,
                    new TextAttributes(null, null, Color.red, EffectType.BOXED, Font.PLAIN),
                    HighlighterTargetArea.EXACT_RANGE
            );
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
     * @return A list of the top 3 predictions.
     */
    public static List<String> extractEncasedText(String input) {
        List<String> result = new ArrayList<>();
        int i = input.indexOf('[');
        input = input.substring(0, i) + input.substring(i + 1);
        // Regular expressions to match text inside the predictions
        String regexSquareBrackets = "\\[([^\\]]*)\\]";

        // Find matches using the regular expressions
        java.util.regex.Pattern patternSquareBrackets = java.util.regex.Pattern.compile(regexSquareBrackets);
        java.util.regex.Matcher matcherSquareBrackets = patternSquareBrackets.matcher(input);

        // Extract and store the matches in the result list
        while (matcherSquareBrackets.find() && result.size() < 3) {
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
    public List<String> generateName(Clone clone, String returnType, List<List<Variable>> normalizedLambdaArgs, String methodName, boolean extractToStatic) throws IOException {
        String code = buildMethodText(clone, returnType, normalizedLambdaArgs, methodName, extractToStatic);
        String[] args = {
                "--max_path_length",
                "8",
                "--max_path_width",
                "2",
                "--file",
                code,
                "--no_hash"
        };
        ArrayList<ProgramFeatures> extracted = App.execute(args);
        List<String> extractedText = null;
        try{
            Socket socket = new Socket("localhost", 8081);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
            out.println(extracted);
            String predictions = in.readLine();
            socket.close();
            extractedText = extractEncasedText(predictions);
        }catch(Exception e){
        }
        return extractedText;
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
                Parameter firstParam = results.get(0).parameters().get(i);
                String text = firstParam.extractedValue().getText();
                boolean canRemove = true;
                int j = 1;
                while (canRemove && j < results.size()) {
                    Parameter currentParam = results.get(j).parameters().get(i);
                    canRemove = text.equals(currentParam.extractedValue().getText());
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

            // Generate method return type
            Clone template = results.get(0);
            String returnType = null;
            for (Clone clone : results) {
                if (clone.liveVars().size() > 0) {
                    if (returnType == null) {
                        template = clone;
                        returnType = clone.liveVars().get(0).type();
                    } else if (!returnType.equals(clone.liveVars().get(0).type())) return;
                }
            }
            boolean extractToStatic = containingMethod.hasModifierProperty(PsiModifier.STATIC);
            List<String> pred = null;
            try {
                pred = generateName(template, returnType, normalizedLambdaArgs, "extractedMethod", extractToStatic);
                if(pred == null){
                    pred = new ArrayList<>();
                    pred.add("defaultMethod");
                }
            } catch (Exception e) {
            }
            /*try{
                String FILE_PATH = "C:\\Users\\Dimitri\\Desktop\\extract.txt";
                new FileWriter(FILE_PATH, false).close();
                FileWriter predtxt = new FileWriter(FILE_PATH);
                for (String line : pred) {
                    predtxt.write(line + "\n"); // Write each element of the list followed by a newline
                }
                predtxt.close();
            } catch (IOException e) {
            }*/
            String methodName = getNewMethodName(containingClass, pred.get(0));

            String code = buildMethodText( //for naming method
                    template,
                    returnType,
                    normalizedLambdaArgs,
                    methodName,
                    extractToStatic
                    );
            PsiMethod extractedMethodElement = factory.createMethodFromText(
                    code,
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