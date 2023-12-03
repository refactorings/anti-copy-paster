package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;

import java.util.List;

public record Clone(PsiElement start, PsiElement end, List<Variable> liveVars, List<PsiElement> parameters) {}
