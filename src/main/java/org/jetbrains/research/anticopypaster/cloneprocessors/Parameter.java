package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;

import java.util.Set;

public record Parameter(PsiElement extractedValue, String type, Set<Integer> lambdaArgs) {
    public String toString() {
        return "Parameter[extractedValue="
                + extractedValue.getText()
                + ", type="
                + type
                + ", lambdaArgs="
                + lambdaArgs.toString()
                + "]";
    }
}