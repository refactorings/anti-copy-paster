package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public interface CloneProcessor {
    /**
     * Gets all the children of an element that aren't whitespace or comments.
     * @return The viable children for tree matching.
     */
    private static List<PsiElement> viableChildren(PsiElement element) {
        if (element == null) return new ArrayList<>();
        return Arrays.stream(element.getChildren()).filter(psiElement ->
                !(psiElement instanceof PsiWhiteSpace || psiElement instanceof PsiComment)).toList();
    }

    /**
     * Determines if two elements are an exact text match through tree traversal
     * by type 1 standards (no whitespace or comments count).
     * @return Whether the elements match
     */
    static boolean exactMatch(PsiElement a, PsiElement b) {
        if (a == null || b == null) return false;
        // Filter children of each element.
        List<PsiElement> childrenA = viableChildren(a);
        List<PsiElement> childrenB = viableChildren(b);
        // No need to traverse if different number of children.
        if (childrenA.size() != childrenB.size()) return false;
        if (childrenA.size() == 0) return a.textMatches(b);
        for (int i = 0; i < childrenA.size(); i++) {
            if (!exactMatch(childrenA.get(i), childrenB.get(i))) return false;
        }
        return true;
    }

    List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode);
}
