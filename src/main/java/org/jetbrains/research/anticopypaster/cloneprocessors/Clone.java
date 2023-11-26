package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiElement;

import java.util.List;
import java.util.Stack;

public record Clone(PsiElement start, PsiElement end, List<Variable> liveVars, Stack<PsiElement> parameters) {}
