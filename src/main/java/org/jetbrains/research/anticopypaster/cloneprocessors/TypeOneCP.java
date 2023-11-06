package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TypeOneCP implements CloneProcessor {
    /**
     * Check for a clone of the fragment starting at the given element.
     * @return The last member element of the clone
     */
    private PsiElement isDuplicateAt(PsiCodeBlock block, PsiElement fragment, PsiElement start) {
        if (fragment == null) return null;
        PsiElement fragCurrent = fragment;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null && fragCurrent != block.getRBrace()) {
            if (dupeCurrent == null) return null;
            if (!CloneProcessor.exactMatch(fragCurrent, dupeCurrent)) return null;
            fragCurrent = fragCurrent.getNextSibling();
            dupeCurrent = dupeCurrent.getNextSibling();
        }
        return dupeCurrent.getPrevSibling();
    }

    @Override
    public List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode) {
        ArrayList<Clone> results = new ArrayList<>();
        PsiElement blockStart = pastedCode.getStatements()[0];
        Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, blockStart.getClass());
        for (PsiElement match : matches) {
            PsiElement end = isDuplicateAt(pastedCode, blockStart, match);
            if (end != null) results.add(new Clone(match, end, new ArrayList<>()));
        }
        return results;
    }
}
