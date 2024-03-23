package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiTypeElement;

import java.util.List;

public record Clone(PsiElement start, PsiElement end, List<Variable> liveVars, List<Parameter> parameters,
                    List<Variable> aliasMap, List<PsiTypeElement> typeParams) {
}
