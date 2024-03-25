package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;

import java.util.*;

public record MatchState(Stack<Variable> scope, Set<PsiVariable> liveIn, List<Parameter> parameters,
                         List<Variable> aliasMap, List<PsiTypeElement> typeParams) {
    public MatchState() {
        this(new Stack<>(), new HashSet<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public MatchState extend() {
        Stack<Variable> newStack = new Stack<>();
        newStack.addAll(scope);
        return new MatchState(newStack, liveIn, parameters, aliasMap, typeParams);
    }

    public void addParameter(PsiElement extractedValue, String type, Set<Integer> lambdaArgs, boolean liveIn) {
        if (liveIn) {
            if (((PsiReferenceExpression) extractedValue).resolve() instanceof PsiVariable variable) {
                this.liveIn.add(variable);
            }
        } else {
            parameters.add(new Parameter(
                    extractedValue,
                    type,
                    lambdaArgs
            ));
        }
    }

    /**
     * If the given element can be aliased, look up its alias ID.
     *
     * @param e The element to examine
     * @return The alias ID, or -1 if none
     */
    public int getAliasID(PsiElement e) {
        if (e instanceof PsiIdentifier ident &&
                CloneProcessor.isInScope(ident.getText(), scope)) {
            return getAliasID(ident.getText());
        }
        return -1;
    }

    public int getAliasID(String ident) {
        for (int i = aliasMap.size() - 1; i >= 0; i--)
            if (aliasMap.get(i).identifier().equals(ident)) return i;
        return -1;
    }

    public String toString() {
        return "MatchState[\n\tscope="
                + scope.toString()
                + "\n\tliveIn="
                + liveIn.toString()
                + "\n\taliasMap="
                + aliasMap.toString()
                + "\n\ttypeParams="
                + typeParams.toString()
                + "\n\tparameters="
                + parameters.toString()
                + "\n]";
    }
}
