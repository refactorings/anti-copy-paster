package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class TypeTwoCP implements CloneProcessor {
    /**
     * Check for a clone of the fragment starting at the given element.
     * @return The last member element of the clone
     */
    private PsiElement isDuplicateAt(PsiCodeBlock block, PsiElement fragment, PsiElement start, Stack<Variable> inScope, Set<Variable> liveIn, Stack<PsiElement> pStackA, Stack<PsiElement> pStackB) {
        if (fragment == null) return null;
        PsiElement fragCurrent = fragment;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null) {
            if (dupeCurrent == null) return null;
            if (!matchStack(fragCurrent, dupeCurrent, inScope, liveIn, pStackA, pStackB))
                return null;
            fragCurrent = fragCurrent.getNextSibling();
            if (fragCurrent == block.getRBrace()) break;
            dupeCurrent = dupeCurrent.getNextSibling();
        }
        return dupeCurrent;
    }

    /**
     * Determines if two elements are an exact match except for identifiers,
     * literals, whitespace or comments. In other words, type two clone detection.
     * @param inScope The variables in-scope at the present element
     * @return Whether the elements match
     */
    static boolean matchStack(PsiElement a, PsiElement b, Stack<Variable> inScope, Set<Variable> liveIn, Stack<PsiElement> pStackA, Stack<PsiElement> pStackB) {
        if (a == null || b == null) return false;
        // Filter children of each element
        List<PsiElement> childrenA = CloneProcessor.viableChildren(a);
        List<PsiElement> childrenB = CloneProcessor.viableChildren(b);
        // No need to traverse if different number of children
        if (childrenA.size() != childrenB.size()) return false;
        if (childrenA.isEmpty()) return a.textMatches(b);
        // Next level of scoped variables
        Stack<Variable> inScopeChildren = new Stack<>();
        inScopeChildren.addAll(inScope);
        // Detect if we need to add a new variable to scope from the current element
        CloneProcessor.updateScope(b, inScope, inScopeChildren, liveIn);
        // Build parameter stack
        if ((a instanceof PsiReferenceExpression refExpA && !refExpA.isQualified()
            && b instanceof PsiReferenceExpression refExpB && !refExpB.isQualified()
            && !CloneProcessor.isInScope(refExpA.getReferenceName(), inScope)
            && !CloneProcessor.isInScope(refExpB.getReferenceName(), inScope))
            || (a instanceof PsiLiteralExpression && b instanceof PsiLiteralExpression)) {
            pStackA.add(a);
            pStackB.add(b);
            // Type two clone, so we can stop here and evaluate if worth extracting
            // to a parameter later.
            return true;
        }
        // Process children
        for (int i = 0; i < childrenA.size(); i++) {
            if (!matchStack(childrenA.get(i), childrenB.get(i), inScopeChildren, liveIn, pStackA, pStackB))
                return false;
        }
        return true;
    }

    @Override
    public List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode) {
        ArrayList<Clone> results = new ArrayList<>();
        PsiElement blockStart = pastedCode.getStatements()[0];
        Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, blockStart.getClass());
        ArrayList<Stack<PsiElement>> pStacks = new ArrayList<>();
        for (PsiElement match : matches) {
            Stack<PsiElement> pStackA = new Stack<>();
            Stack<PsiElement> pStackB = new Stack<>();
            Stack<Variable> inScope = new Stack<>();
            Set<Variable> liveIn = new HashSet<>();
            PsiElement end = isDuplicateAt(pastedCode, blockStart, match, inScope, liveIn, pStackA, pStackB);
            if (end != null) {
                results.add(new Clone(match, end, CloneProcessor.liveOut(end, inScope), pStackB));
                System.out.print("Live-in: ");
                System.out.println(Arrays.toString(liveIn.toArray()));
                System.out.print("Live-out: ");
                System.out.println(Arrays.toString(CloneProcessor.liveOut(end, inScope).toArray()));
                if (pStacks.isEmpty()) pStacks.add(pStackA);
                pStacks.add(pStackB);
            }
        }
        return results;
    }
}
