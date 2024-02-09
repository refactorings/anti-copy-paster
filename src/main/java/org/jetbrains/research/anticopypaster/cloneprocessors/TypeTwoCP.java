package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;
import java.util.stream.Collectors;

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
     * @return null if the element can't be extracted, or the list of lambda args it needs
     */
    static ParamCheckResult canBeParam(PsiElement e, MatchState ms) {
        if (e instanceof PsiPolyadicExpression polyE && polyE.getType() != null) {
            Collection<PsiIdentifier> idents = PsiTreeUtil.findChildrenOfType(polyE, PsiIdentifier.class);
            return new ParamCheckResult(
                    true,
                    polyE.getType().getPresentableText(),
                    idents.stream().map(ms::getAliasID).filter(id ->
                        id >= 0
                    ).collect(Collectors.toSet())
            );
        } else if (e instanceof PsiLiteralExpression litExp && litExp.getType() != null) {
            return new ParamCheckResult(litExp.getType().getPresentableText());
        } else if (e instanceof PsiReferenceExpression refExp && !refExp.isQualified()
                && refExp.getType() != null) {
            // Prevents extracting LHS of statements
            if (refExp.getParent() != null
                    && refExp.getParent().getParent() instanceof PsiExpressionStatement
                    && refExp.getStartOffsetInParent() == 0) return ParamCheckResult.FAILURE;
            HashSet<Integer> lambdaArgs = new HashSet<>();
            int aliasID = ms.getAliasID(refExp.getReferenceName());
            if (aliasID >= 0)
                lambdaArgs.add(aliasID);
            return new ParamCheckResult(refExp.getType().getPresentableText(), lambdaArgs);
        }

        return ParamCheckResult.FAILURE;
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
        // Build parameter stack
        ParamCheckResult canBeParamA = canBeParam(a, ma);
        ParamCheckResult canBeParamB = canBeParam(b, mb);
        if (canBeParamA.success && canBeParamB.success) {
            ma.addParameter(a, canBeParamA.type, canBeParamA.lambdaArgs);
            mb.addParameter(b, canBeParamB.type, canBeParamB.lambdaArgs);
            // Type two clone, so we can stop here and evaluate if worth extracting
            // to a parameter later.
            return true;
        }
        // Filter children of each element
        List<PsiElement> childrenA = CloneProcessor.viableChildren(a);
        List<PsiElement> childrenB = CloneProcessor.viableChildren(b);
        // No need to traverse if different number of children
        if (childrenA.size() != childrenB.size()) return false;
        if (childrenA.isEmpty()) {
            // Check for aliased variable equivalence
            int idA = ma.getAliasID(a);
            int idB = mb.getAliasID(b);
            return a.textMatches(b) || (idA == idB && idA >= 0);
        }
        // Next level of scoped variables
        MatchState childMa = ma.extend();
        MatchState childMb = mb.extend();
        // Detect if we need to add a new variable to scope from the current element
        CloneProcessor.updateScope(a, ma, childMa);
        CloneProcessor.updateScope(b, mb, childMb);
        // See if we have a type parameter
        if (a instanceof PsiTypeElement typeA && b instanceof PsiTypeElement typeB) {
            ma.typeParams().add(CloneProcessor.objectTypeIfPrimitive(typeA.getText()));
            mb.typeParams().add(CloneProcessor.objectTypeIfPrimitive(typeB.getText()));
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
        for (PsiElement match : matches) {
            MatchState ma = new MatchState();
            MatchState mb = new MatchState();
            PsiElement end = isDuplicateAt(pastedCode, blockStart, match, ma, mb);
            if (end != null) {
                results.add(new Clone(
                        match,
                        end,
                        CloneProcessor.liveOut(end, mb.scope()),
                        mb.parameters(),
                        mb.aliasMap()
                ));
            }
        }
        return results;
    }

    private record ParamCheckResult(boolean success, String type, Set<Integer> lambdaArgs) {
        public static final ParamCheckResult FAILURE = new ParamCheckResult(false, null, null);
        public ParamCheckResult(String type) {
            this(true, type, new HashSet<>());
        }
        public ParamCheckResult(String type, Set<Integer> lambdaArgs) {
            this(true, type, lambdaArgs);
        }
    }
}
