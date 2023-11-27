package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class TypeOneCP implements CloneProcessor {
    /**
     * Check for a clone of the fragment starting at the given element.
     * @return The last member element of the clone
     */
    private PsiElement isDuplicateAt(PsiCodeBlock block, PsiElement fragment, PsiElement start, Set<Variable> liveIn) {
        if (fragment == null) return null;
        Stack<Variable> inScope = new Stack<>();
        PsiElement fragCurrent = fragment;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null) {
            if (dupeCurrent == null) return null;
            if (!CloneProcessor.exactMatch(fragCurrent, dupeCurrent, inScope, liveIn))
                return null;
            fragCurrent = fragCurrent.getNextSibling();
            if (fragCurrent == block.getRBrace()) break;
            dupeCurrent = dupeCurrent.getNextSibling();
        }
        return dupeCurrent;
    }

    @Override
    public List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode) {
        // Get clones
        ArrayList<Clone> results = new ArrayList<>();
        PsiElement blockStart = pastedCode.getStatements()[0];
        Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, blockStart.getClass());
        Set<Variable> liveIn = new HashSet<>();
        for (PsiElement match : matches) {
            PsiElement end = isDuplicateAt(pastedCode, blockStart, match, liveIn);
            if (end != null) results.add(new Clone(match, end, new ArrayList<>(), new Stack<>()));
        }
        return results;
    }
}
