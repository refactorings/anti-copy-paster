package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;

import java.util.*;

public record MatchState(Stack<Variable> scope, Set<Variable> liveIn, List<PsiElement> parameters, Map<String, Integer> aliasMap) {
    public MatchState() {
        this(new Stack<>(), new HashSet<>(), new ArrayList<>(), new HashMap<>());
    }

    public MatchState extend() {
        Stack<Variable> newStack = new Stack<>();
        newStack.addAll(this.scope);
        return new MatchState(newStack, this.liveIn, this.parameters, this.aliasMap);
    }

    public String toString() {
        return "MatchState[\n\tscope=" + scope.toString() + "\n\tliveIn=" + liveIn.toString() + "\n\taliasMap=" + aliasMap.toString() + "\n]";
    }
}
