package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class TypeTwoCP implements CloneProcessor {
    /**
     * Check for a clone of the fragment starting at the given element.
     * @return The last member element of the clone
     */
    private PsiElement isDuplicateAt(PsiCodeBlock block, PsiElement fragment, PsiElement start, MatchState ma, MatchState mb) {
        if (fragment == null) return null;
        PsiElement fragCurrent = fragment;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null) {
            if (dupeCurrent == null) return null;
            if (!matchStack(fragCurrent, dupeCurrent, ma, mb))
                return null;
            fragCurrent = fragCurrent.getNextSibling();
            if (fragCurrent == block.getRBrace()) break;
            dupeCurrent = dupeCurrent.getNextSibling();
        }
        return dupeCurrent;
    }

    /**
     * Determines if the provided element can be extracted as a parameter.
     * @param e The element to examine
     * @param ms The match state in which it was found
     * @return Whether the element can be extracted
     */
    static boolean canBeParam(PsiElement e, MatchState ms) {
        return (e instanceof PsiReferenceExpression refExp
                && !refExp.isQualified()
                && !CloneProcessor.isInScope(refExp.getReferenceName(), ms.scope()))
               || e instanceof PsiLiteralExpression;
    }

    /**
     * Determines if two elements are an exact match except for identifiers,
     * literals, whitespace or comments. In other words, type two clone detection.
     * @param a The first element to compare
     * @param b The second element to compare
     * @param ma The match state for the first element
     * @param mb The match state for the second element
     * @return Whether the elements match
     */
    static boolean matchStack(PsiElement a, PsiElement b, MatchState ma, MatchState mb) {
        if (a == null || b == null) return false;
        // Filter children of each element
        List<PsiElement> childrenA = CloneProcessor.viableChildren(a);
        List<PsiElement> childrenB = CloneProcessor.viableChildren(b);
        // No need to traverse if different number of children
        if (childrenA.size() != childrenB.size()) return false;
        if (childrenA.isEmpty()) return a.textMatches(b);
        // Next level of scoped variables
        MatchState childMa = ma.extend();
        MatchState childMb = mb.extend();
        // Detect if we need to add a new variable to scope from the current element
        CloneProcessor.updateScope(a, ma, childMa);
        CloneProcessor.updateScope(b, mb, childMb);
        // Build parameter stack
        if (canBeParam(a, ma) && canBeParam(b, mb)) {
            ma.parameters().add(a);
            mb.parameters().add(b);
            // Type two clone, so we can stop here and evaluate if worth extracting
            // to a parameter later.
            return true;
        }
        // Process children
        for (int i = 0; i < childrenA.size(); i++) {
            if (!matchStack(childrenA.get(i), childrenB.get(i), childMa, childMb))
                return false;
        }
        return true;
    }

    @Override
    public List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode) {
        ArrayList<Clone> results = new ArrayList<>();
        PsiElement blockStart = pastedCode.getStatements()[0];
        Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, blockStart.getClass());
        ArrayList<List<PsiElement>> pStacks = new ArrayList<>();
        for (PsiElement match : matches) {
            MatchState ma = new MatchState();
            MatchState mb = new MatchState();
            PsiElement end = isDuplicateAt(pastedCode, blockStart, match, ma, mb);
            if (end != null) {
                results.add(new Clone(match, end, CloneProcessor.liveOut(end, mb.scope()), mb.parameters()));
                System.out.print("Live-in: ");
                System.out.println(Arrays.toString(mb.liveIn().toArray()));
                System.out.print("Live-out: ");
                System.out.println(Arrays.toString(CloneProcessor.liveOut(end, mb.scope()).toArray()));
                if (pStacks.isEmpty()) pStacks.add(ma.parameters());
                pStacks.add(mb.parameters());
            }
        }
        return results;
    }
}
