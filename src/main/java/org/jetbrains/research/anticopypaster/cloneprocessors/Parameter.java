package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;

import java.util.List;

public record Parameter(PsiElement extractedValue, String type, List<String> lambdaArgs, List<String> lambdaTypes) {
    public String toString() {
        return "Parameter[extractedValue="
                + extractedValue.getText()
                + ", type="
                + type
                + ", lambdaArgs="
                + lambdaArgs.toString()
                + ", lambdaTypes="
                + lambdaTypes.toString()
                + "]";
    }
}