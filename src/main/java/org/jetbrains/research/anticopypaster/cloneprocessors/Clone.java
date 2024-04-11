package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiVariable;

import java.util.List;
import java.util.Set;

public record Clone(PsiElement start, PsiElement end, List<Variable> liveOutVars, List<Parameter> parameters,
                    List<Variable> aliasMap, List<PsiTypeElement> typeParams, Set<PsiVariable> liveInVars, boolean extractable) {
}
