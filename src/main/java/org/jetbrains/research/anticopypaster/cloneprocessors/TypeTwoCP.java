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
    private PsiElement isDuplicateAt(PsiElement fragmentStart, PsiElement last, PsiElement start, MatchState ma, MatchState mb) {
        if (fragmentStart == null) return null;
        PsiElement fragCurrent = fragmentStart;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null && dupeCurrent != null) {
            if (!matchStack(fragCurrent, dupeCurrent, ma, mb))
                return null;
            if (fragCurrent == last) break;
            fragCurrent = CloneProcessor.nextNonWSOrCommentSibling(fragCurrent);
            dupeCurrent = CloneProcessor.nextNonWSOrCommentSibling(dupeCurrent);
        }
        return dupeCurrent;
    }

    /**
     * Resolves a reference to a variable, or returns null if the reference is not a variable.
     * @param refExp The reference to resolve.
     * @return The PsiVariable referenced, or null if not a variable.
     */
    static PsiVariable variableFromRefExp(PsiReferenceExpression refExp) {
        return refExp.resolve() instanceof PsiVariable psiVar ? psiVar : null;
    }

    /**
     * Resolves an identifier to a variable, or returns null if the identifier is not a variable.
     * @param ident The identifier to resolve.
     * @return The PsiVariable identified, or null if not a variable.
     */
    static PsiVariable variableFromIdent(PsiIdentifier ident) {
        return ident.getParent() instanceof PsiReferenceExpression refExp ? variableFromRefExp(refExp) : null;
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
            // All variables are liveIn, no lambda needed just embed the expression in the call
            if (idents.stream().map(ms::getAliasID).allMatch(id -> id == -1)) {
                Set<PsiVariable> liveInDeps = idents.stream().map(TypeTwoCP::variableFromIdent).collect(Collectors.toSet());
                for (PsiVariable var : liveInDeps)
                    if (var == null) return ParamCheckResult.FAILURE;
                return ParamCheckResult.liveIn(polyE.getType().getPresentableText(), liveInDeps);
            }
            // Mixed bag of variables, can't extract b/c of Java lambda limitations
            if (idents.stream().map(ms::getAliasID).anyMatch(id -> id == -1))
                return ParamCheckResult.FAILURE;
            return ParamCheckResult.asLambda(
                    polyE.getType().getPresentableText(),
                    idents.stream().map(ms::getAliasID).collect(Collectors.toSet())
            );
        } else if (e instanceof PsiLiteralExpression litExp && litExp.getType() != null) {
            return new ParamCheckResult(litExp.getType().getPresentableText());
        } else if (e instanceof PsiReferenceExpression refExp && !refExp.isQualified()
                && refExp.getType() != null) {
            // Prevents extracting LHS of statements & method calls
            if (refExp.getParent() != null
                    && (refExp.getParent().getParent() instanceof PsiExpressionStatement
                    && refExp.getStartOffsetInParent() == 0)
                    || refExp.getParent() instanceof PsiCallExpression) return ParamCheckResult.FAILURE;
            String paramType = refExp.getType().getPresentableText();
            int aliasID = ms.getAliasID(refExp.getReferenceName());
            if (aliasID >= 0) {
                HashSet<Integer> lambdaArgs = new HashSet<>();
                lambdaArgs.add(aliasID);
                return ParamCheckResult.asLambda(paramType, lambdaArgs);
            } else {
                PsiVariable asVar = variableFromRefExp(refExp);
                if (asVar == null) return ParamCheckResult.FAILURE;
                HashSet<PsiVariable> liveInDeps = new HashSet<>();
                liveInDeps.add(asVar);
                return ParamCheckResult.liveIn(paramType, liveInDeps);
            }
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
//        System.out.println(a.getText() + '~' + canBeParamA.success + '|' + b.getText() + '~' + canBeParamB.success);
        if (canBeParamA.success && canBeParamB.success && canBeParamA.liveInDeps.size() == canBeParamB.liveInDeps.size()) {
            ma.addParameter(a, canBeParamA.type, canBeParamA.lambdaArgs, canBeParamA.liveInDeps);
            mb.addParameter(b, canBeParamB.type, canBeParamB.lambdaArgs, canBeParamB.liveInDeps);
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
            ma.typeParams().add(typeA);
            mb.typeParams().add(typeB);
            if (!typeA.getText().equals(typeB.getText()))
                mb.setExtractable(false);
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

    private record ParamCheckResult(boolean success, String type, Set<Integer> lambdaArgs, Set<PsiVariable> liveInDeps) {
        public static final ParamCheckResult FAILURE = new ParamCheckResult(false, null, null, null);
        public ParamCheckResult(String type) {
            this(true, type, new HashSet<>(), new HashSet<>());
        }
        public static ParamCheckResult liveIn(String type, Set<PsiVariable> liveInDeps) {
            return new ParamCheckResult(true, type, new HashSet<>(), liveInDeps);
        }
        public static ParamCheckResult asLambda(String type, Set<Integer> lambdaArgs) {
            return new ParamCheckResult(true, type, lambdaArgs, new HashSet<>());
        }
    }
}
