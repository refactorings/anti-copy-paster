package org.jetbrains.research.anticopypaster.ide;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.NameSuggestionProvider;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.NameUtilCore;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.App;
import org.jetbrains.research.anticopypaster.JPredict.src.main.java.JavaExtractor.FeaturesEntities.ProgramFeatures;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.Variable;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;
import java.util.stream.Collectors;

public class JavaNameSuggestionProvider implements NameSuggestionProvider {
    @Override
    @Nullable
    public SuggestedNameInfo getSuggestedNames(final PsiElement element, final PsiElement nameSuggestionContext, Set<String> result) {
        if (!element.getLanguage().isKindOf(JavaLanguage.INSTANCE)) return null;
        String initialName = UsageViewUtil.getShortName(element);
        SuggestedNameInfo info = suggestNamesForElement(element, nameSuggestionContext);
        if (info != null) {
            info = JavaCodeStyleManager.getInstance(element.getProject()).suggestUniqueVariableName(info, element, true, true);
        }

        String parameterName = null;
        String superMethodName = null;
        if (nameSuggestionContext instanceof PsiParameter) {
            final PsiElement nameSuggestionContextParent = nameSuggestionContext.getParent();
            if (nameSuggestionContextParent instanceof PsiParameterList) {
                final PsiElement parentOfParent = nameSuggestionContextParent.getParent();
                if (parentOfParent instanceof PsiMethod) {
                    final String propName = PropertyUtilBase.getPropertyName((PsiMethod)parentOfParent);
                    if (propName != null) {
                        parameterName = propName;
                    }
                    superMethodName = getSuperMethodName((PsiParameter) nameSuggestionContext, (PsiMethod) parentOfParent);
                }
            }
        }
        final String[] strings = info != null ? info.names : ArrayUtilRt.EMPTY_STRING_ARRAY;
        List<List<String>> code2vec_namerecs = generateName();
        // append code2vec output to strings before it gets processed
        final ArrayList<String> list = new ArrayList<>(Arrays.asList(strings));
        final String[] properlyCased = suggestProperlyCasedName(element);
        if (properlyCased != null) {
            Collections.addAll(list, properlyCased);
        }
        if (parameterName != null && !list.contains(parameterName)) {
            list.add(parameterName);
        }
        if (superMethodName != null && !list.contains(superMethodName)) {
            list.add(0, superMethodName);
        }

        list.remove(initialName);
        list.add(initialName);
        ContainerUtil.removeDuplicates(list);
        result.addAll(list);
        return info;
    }

    @Nullable
    private static String getSuperMethodName(final PsiParameter psiParameter, final PsiMethod method) {
        final int index = method.getParameterList().getParameterIndex(psiParameter);
        final PsiMethod[] superMethods = method.findSuperMethods();
        for (PsiMethod superMethod : superMethods) {
            final PsiParameterList superParameters = superMethod.getParameterList();
            if (index < superParameters.getParametersCount()) {
                return superParameters.getParameters() [index].getName();
            }
        }
        return null;
    }

    private static String @Nullable [] suggestProperlyCasedName(PsiElement psiElement) {
        if (!(psiElement instanceof PsiNamedElement)) return null;
        if (psiElement instanceof PsiFile) return null;
        String name = ((PsiNamedElement)psiElement).getName();
        if (name == null) return null;
        String prefix = "";
        if (psiElement instanceof PsiVariable) {
            final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(psiElement.getProject());
            final VariableKind kind = codeStyleManager.getVariableKind((PsiVariable)psiElement);
            prefix = codeStyleManager.getPrefixByVariableKind(kind);
            if (kind == VariableKind.STATIC_FINAL_FIELD) {
                final String[] words = NameUtilCore.splitNameIntoWords(name);
                String buffer = Arrays.stream(words).map(StringUtil::toUpperCase).collect(Collectors.joining("_"));
                return new String[] {buffer};
            }
        }
        final List<String> result = new ArrayList<>();
        result.add(suggestProperlyCasedName(prefix, NameUtilCore.splitNameIntoWords(name)));
        if (name.startsWith(prefix) && !prefix.isEmpty()) {
            name = name.substring(prefix.length());
            result.add(suggestProperlyCasedName(prefix, NameUtilCore.splitNameIntoWords(name)));
        }
        result.add(suggestProperlyCasedName(prefix, NameUtilCore.splitNameIntoWords(StringUtil.toLowerCase(name))));
        return ArrayUtilRt.toStringArray(result);
    }

    private static String suggestProperlyCasedName(String prefix, String[] words) {
        StringBuilder buffer = new StringBuilder(prefix);
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            final boolean prefixRequiresCapitalization = prefix.length() > 0 && !StringUtil.endsWithChar(prefix, '_');
            if (i > 0 || prefixRequiresCapitalization) {
                buffer.append(StringUtil.capitalize(word));
            }
            else {
                buffer.append(StringUtil.decapitalize(word));
            }
        }
        return buffer.toString();
    }

    @Nullable
    private static SuggestedNameInfo suggestNamesForElement(final PsiElement element, PsiElement nameSuggestionContext) {
        PsiVariable var = null;
        if (element instanceof PsiVariable) {
            var = (PsiVariable)element;
        }
        else if (element instanceof PsiIdentifier identifier && identifier.getParent() instanceof PsiVariable parent) {
            var = parent;
        }

        if (var == null) return null;

        JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(element.getProject());
        VariableKind variableKind = codeStyleManager.getVariableKind(var);
        final SuggestedNameInfo nameInfo = codeStyleManager.suggestVariableName(variableKind, null, var.getInitializer(), var.getType());
        final PsiExpression expression = PsiTreeUtil.getParentOfType(nameSuggestionContext, PsiCallExpression.class, false, PsiLambdaExpression.class, PsiClass.class);
        if (expression != null) {
            return new SuggestedNameInfo.Delegate(codeStyleManager.suggestVariableName(variableKind, null, expression, var.getType()).names, nameInfo);

        }
        return nameInfo;
    }

    @Nullable
    // not final code at all for this, but we need a way to grab the recommendation data easily without using new parameters
    // ideally want to communicate with the server and pull the most recent prediction data
    // this definitely doesn't do that right now but it's a start...
    public List<List<String>> generateName(){
        List<List<String>> extractedText = null;
        try{
            String FILE_PATH2 = "C:/Users/squir/OneDrive/Desktop/sem6/extract.txt";
            FileWriter fileWriter2 = new FileWriter(FILE_PATH2, true);
            fileWriter2.write("hi");
            Socket socket = new Socket("hostname", 8081);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
            fileWriter2.write("hi2");
            String predictions = in.readLine();
            fileWriter2.write("hi3");
            socket.close();
            extractedText = extractEncasedText(predictions);
            fileWriter2.close();
        }catch(Exception e){
        }
        return extractedText;
    }

    // this is present for the function above
    private static List<List<String>> extractEncasedText(String input) {
        List<List<String>> result = new ArrayList<>();

        // Regular expressions to match text inside the predictions
        String regexParentheses = "\\(([^)]*)\\)";
        String regexSquareBrackets = "\\[([^\\]]*)\\]";

        // Find matches using the regular expressions
        java.util.regex.Pattern patternParentheses = java.util.regex.Pattern.compile(regexParentheses);
        java.util.regex.Pattern patternSquareBrackets = java.util.regex.Pattern.compile(regexSquareBrackets);
        java.util.regex.Matcher matcherParentheses = patternParentheses.matcher(input);
        java.util.regex.Matcher matcherSquareBrackets = patternSquareBrackets.matcher(input);

        // Extract and store the matches in the result list
        while (matcherParentheses.find() && matcherSquareBrackets.find() && result.size() <= 3) {
            String textInParentheses = matcherParentheses.group(1);
            String textInSquareBrackets = matcherSquareBrackets.group(1);
            textInSquareBrackets = textInSquareBrackets.replaceAll(" ", "");
            textInSquareBrackets = textInSquareBrackets.replaceAll(",", "_");
            textInSquareBrackets = textInSquareBrackets.replaceAll("'", "");
            if(textInSquareBrackets.length() >= 3){
                // Add the new pair to the result list
                List<String> pair = new ArrayList<>();
                pair.add(textInParentheses);
                pair.add(textInSquareBrackets);
                result.add(pair);
            }
        }

        return result;
    }

}
