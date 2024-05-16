package org.jetbrains.research.anticopypaster.cloneprocessors;


import com.intellij.psi.*;

import java.util.*;

public final class MatchState {
    private final Stack<Variable> scope;
    private final Set<PsiVariable> liveIn;
    private final List<Parameter> parameters;
    private final List<Variable> aliasMap;
    private final List<PsiTypeElement> typeParams;
    private Boolean extractable;

    public MatchState(Stack<Variable> scope, Set<PsiVariable> liveIn, List<Parameter> parameters,
                      List<Variable> aliasMap, List<PsiTypeElement> typeParams, boolean extractable) {
        this.scope = scope;
        this.liveIn = liveIn;
        this.parameters = parameters;
        this.aliasMap = aliasMap;
        this.typeParams = typeParams;
        this.extractable = extractable;
    }

    public MatchState() {
        this(new Stack<>(), new HashSet<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), true);
    }

    public MatchState extend() {
        Stack<Variable> newStack = new Stack<>();
        newStack.addAll(scope);
        return new MatchState(newStack, liveIn, parameters, aliasMap, typeParams, extractable);
    }

    public void addParameter(PsiElement extractedValue, String type, Set<Integer> lambdaArgs, Set<PsiVariable> liveInDeps) {
        parameters.add(new Parameter(
                extractedValue,
                type,
                lambdaArgs,
                liveInDeps
        ));
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


    public Stack<Variable> scope() {
        return scope;
    }

    public Set<PsiVariable> liveIn() {
        return liveIn;
    }

    public List<Parameter> parameters() {
        return parameters;
    }

    public List<Variable> aliasMap() {
        return aliasMap;
    }

    public List<PsiTypeElement> typeParams() {
        return typeParams;
    }

    public boolean extractable() {
        return extractable;
    }

    public void setExtractable(boolean extractable) {
        this.extractable = extractable;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchState) obj;
        return Objects.equals(this.scope, that.scope) &&
                Objects.equals(this.liveIn, that.liveIn) &&
                Objects.equals(this.parameters, that.parameters) &&
                Objects.equals(this.aliasMap, that.aliasMap) &&
                Objects.equals(this.typeParams, that.typeParams) &&
                Objects.equals(this.extractable, that.extractable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, liveIn, parameters, aliasMap, typeParams, extractable);
    }

}
