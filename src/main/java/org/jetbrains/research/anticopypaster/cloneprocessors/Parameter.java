package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiVariable;

import java.util.HashSet;
import java.util.Set;

public record Parameter(PsiElement extractedValue, String type, Set<Integer> lambdaArgs, Set<PsiVariable> liveInDeps) {
    public String toString() {
        return "Parameter[extractedValue="
                + extractedValue.getText()
                + ", type="
                + type
                + ", lambdaArgs="
                + lambdaArgs.toString()
                + ", liveInDeps="
                + liveInDeps.toString()
                + "]";
    }
}