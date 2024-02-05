package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;

import java.util.*;

public record MatchState(Stack<Variable> scope, Set<Variable> liveIn, List<Parameter> parameters,
                         Map<String, Integer> aliasMap, List<String> typeParams) {
    public MatchState() {
        this(new Stack<>(), new HashSet<>(), new ArrayList<>(), new HashMap<>(), new ArrayList<>());
    }

    public MatchState extend() {
        Stack<Variable> newStack = new Stack<>();
        newStack.addAll(scope);
        return new MatchState(newStack, liveIn, parameters, aliasMap, typeParams);
    }

    public void addParameter(PsiElement extractedValue, String type, Set<String> lambdaArgs) {
        parameters.add(new Parameter(
                extractedValue,
                type,
                lambdaArgs.stream().toList(),
                lambdaArgs.stream().map(this::typeOfParameter).toList()
        ));
    }

    public String typeOfParameter(String identifier) {
        return scope.stream().filter(variable -> variable.identifier().equals(identifier))
                .findFirst()
                .orElseThrow()
                .type();
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
