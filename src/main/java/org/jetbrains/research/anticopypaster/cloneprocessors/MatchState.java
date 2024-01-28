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
        newStack.addAll(this.scope);
        return new MatchState(newStack, this.liveIn, this.parameters, this.aliasMap, this.typeParams);
    }

    public void addParameter(PsiElement extractedValue, Set<String> lambdaArgs) {
        parameters.add(new Parameter(extractedValue, lambdaArgs));
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
                + "\n]";
    }

    public record Parameter(PsiElement extractedValue, Set<String> lambdaArgs) {}
}
