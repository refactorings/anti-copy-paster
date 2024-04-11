package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class TypeOneCP implements CloneProcessor {
    /**
     * Determines if two elements are an exact text match through tree traversal
     * by type 1 standards (no whitespace or comments count).
     * @param a The first element to compare
     * @param b The second element to compare
     * @param ma The match state for the first element
     * @param mb The match state for the second element
     * @return Whether the elements match
     */
    static boolean exactMatch(PsiElement a, PsiElement b, MatchState ma, MatchState mb) {
        if (a == null || b == null) return false;
        // Filter children of each element.
        List<PsiElement> childrenA = CloneProcessor.viableChildren(a);
        List<PsiElement> childrenB = CloneProcessor.viableChildren(b);
        // No need to traverse if different number of children.
        if (childrenA.size() != childrenB.size()) return false;
        if (childrenA.isEmpty()) return a.textMatches(b);
        // Next level of scoped variables
        MatchState childMa = ma.extend();
        MatchState childMb = mb.extend();
        // Detect if we need to add a new variable to scope from the current element
        CloneProcessor.updateScope(a, ma, childMa);
        CloneProcessor.updateScope(b, mb, childMb);
        // Process children
        for (int i = 0; i < childrenA.size(); i++) {
            if (!exactMatch(childrenA.get(i), childrenB.get(i), childMa, childMb))
                return false;
        }
        return true;
    }

    /**
     * Check for a clone of the fragment starting at the given element.
     * @return The last member element of the clone
     */
    private PsiElement isDuplicateAt(PsiElement fragmentStart, PsiElement last, PsiElement start, MatchState ma, MatchState mb) {
        if (fragmentStart == null) return null;
        PsiElement fragCurrent = fragmentStart;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null && dupeCurrent != null) {
            if (!exactMatch(fragCurrent, dupeCurrent, ma, mb))
                return null;
            if (fragCurrent == last) break;
            fragCurrent = fragCurrent.getNextSibling();
            dupeCurrent = dupeCurrent.getNextSibling();
        }
        return dupeCurrent;
    }

    @Override
    public List<Clone> getClonesOfType(PsiFile file, PsiStatement startStmt, PsiStatement endStmt) {
        ArrayList<Clone> results = new ArrayList<>();
        Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, PsiStatement.class);
        for (PsiElement match : matches) {
            MatchState ma = new MatchState();
            MatchState mb = new MatchState();
            PsiElement end = isDuplicateAt(startStmt, endStmt, match, ma, mb);
            if (end != null) {
                results.add(new Clone(
                        match,
                        end,
                        CloneProcessor.liveOut(end, mb.scope()),
                        mb.parameters(),
                        mb.aliasMap(),
                        mb.typeParams(),
                        mb.liveIn(),
                        mb.extractable()
                ));
            }
        }
        return results;
    }
}
