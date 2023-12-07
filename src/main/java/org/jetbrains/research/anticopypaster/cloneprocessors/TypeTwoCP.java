package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;

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
        boolean binOpParam = true;
        if (e instanceof PsiBinaryExpression bin_e) {
            // TODO: add check if psielement is a psiParenthesizedExpression
            PsiReferenceExpression[] PsiRefExps = Arrays.stream(bin_e.getChildren())
                        .filter(
                                (psiElement -> psiElement instanceof PsiReferenceExpression)
                        ).toList();
            for (PsiReferenceExpression element : PsiRefExps) {
                String ident = element.getReferenceName();
                if (CloneProcessor.isInScope(ident, ms.scope())) {
                    binOpParam = false;
                    break;
                }
            }
        }

        return (e instanceof PsiReferenceExpression refExp
                && !refExp.isQualified()
                && !CloneProcessor.isInScope(refExp.getReferenceName(), ms.scope()))
                || e instanceof PsiLiteralExpression
                || binOpParam;
    }

    /**
     * If the given element can be aliased, look up its alias ID.
     * @param e The element to examine
     * @param ms The current match state
     * @return The appropriate alias ID, or null if not appropriate
     */
    static int aliasId(PsiElement e, MatchState ms) {
        if (e instanceof PsiIdentifier ident &&
                CloneProcessor.isInScope(ident.getText(), ms.scope())) {
            Integer id = ms.aliasMap().get(ident.getText());
            return id == null ? -1 : id;
        }
        return -1;
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
        if (childrenA.isEmpty()) {
            // Check for aliased variable equivalence
            int idA = aliasId(a, ma);
            int idB = aliasId(b, mb);
            return a.textMatches(b) || (idA == idB && idA >= 0);
        }
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
                if (pStacks.isEmpty()) {
                    System.out.println(ma);
                    pStacks.add(ma.parameters());
                }
                System.out.println(mb);
                pStacks.add(mb.parameters());
            }
        }
        return results;
    }
}
