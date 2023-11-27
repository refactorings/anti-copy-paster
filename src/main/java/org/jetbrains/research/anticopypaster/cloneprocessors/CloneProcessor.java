package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public interface CloneProcessor {
    /**
     * Gets all the children of an element that aren't whitespace or comments.
     * @return The viable children for tree matching.
     */
    static List<PsiElement> viableChildren(PsiElement element) {
        if (element == null) return new ArrayList<>();
        return Arrays.stream(element.getChildren()).filter(psiElement ->
                !(psiElement instanceof PsiWhiteSpace || psiElement instanceof PsiComment)).toList();
    }

    /**
     * Adds all declarations from a declaration statement into the provided stack.
     * @param stmt Statement containing the declarations
     * @param stack Variable stack
     */
    private static void processStatementDecls(PsiDeclarationStatement stmt, Stack<Variable> stack) {
        PsiElement[] decls = stmt.getDeclaredElements();
        for (PsiElement decl : decls) {
            if (!(decl instanceof PsiLocalVariable localVar)) continue;
            stack.add(new Variable(localVar.getName(), localVar.getTypeElement().getText()));
        }
    }

    /**
     * Updates the stacks for currently in-scope and next-in-scope variables.
     * @param anElement Element to process
     * @param inScope Variables currently in-scope
     * @param inScopeChildren Variables next-in-scope (next block level)
     */
    static void updateScope(PsiElement anElement, Stack<Variable> inScope, Stack<Variable> inScopeChildren, Set<Variable> liveIn) {
        if (anElement instanceof PsiDeclarationStatement stmt) {
            processStatementDecls(stmt, inScope);
            processStatementDecls(stmt, inScopeChildren);
        } else if (anElement instanceof PsiForeachStatement forEachStmt) {
            PsiParameter param = forEachStmt.getIterationParameter();
            inScopeChildren.add(new Variable(param.getName(), param.getType().getPresentableText()));
        } else if (anElement instanceof PsiForStatement forStmt) {
            PsiStatement stmt = forStmt.getInitialization();
            if (stmt != null)
                processStatementDecls((PsiDeclarationStatement) stmt, inScopeChildren);
        } else if (anElement instanceof PsiIfStatement ifStmt) {
            Collection<PsiTypeTestPattern> tests = PsiTreeUtil.findChildrenOfType(
                    ifStmt.getCondition(), PsiTypeTestPattern.class);
            tests.forEach((test) -> {
                PsiPatternVariable pVar = test.getPatternVariable();
                if (pVar != null)
                    inScopeChildren.add(new Variable(pVar.getName(), pVar.getType().getPresentableText()));
            });
        } else if (anElement instanceof PsiReferenceExpression refExp && !refExp.isQualified()) {
            if (!isInScope(refExp.getReferenceName(), inScope) && refExp.getType() != null) {
                liveIn.add(new Variable(refExp.getReferenceName(), refExp.getType().getPresentableText()));
            }
        }
    }

    /**
     * Determines if a variable is present in the scope by name.
     * @param varName Name of variable to search for
     * @param scope Scope to search through
     * @return If the variable is in scope
     */
    static boolean isInScope(String varName, Stack<Variable> scope) {
        return scope.stream().anyMatch((var) -> var.identifier().equals(varName));
    }

    /**
     * Recursive tree descent helper to find variable references in statement children.
     * TODO Make sure redefined variables don't trigger live-out detection
     * @param curr Current element
     * @param scope Scope to consider
     * @param out Live-out variables
     */
    private static void findLiveOut(PsiElement curr, Stack<Variable> scope, Set<String> out) {
        if (curr == null) return;
        if (curr instanceof PsiReferenceExpression refExp && !refExp.isQualified()) {
            if (isInScope(refExp.getReferenceName(), scope))
                out.add(refExp.getReferenceName());
            return;
        }
        for (PsiElement child : curr.getChildren())
            findLiveOut(child, scope, out);
    }

    /**
     * Determines which variables are live after a given code segment executes.
     * @param last Last element of the code segment
     * @param scope Variables declared in the code segment at the top level
     * @return The live-out variables
     */
    static List<Variable> liveOut(PsiElement last, Stack<Variable> scope) {
        Set<String> out = new HashSet<>();
        while ((last = last.getNextSibling()) != null) {
            findLiveOut(last, scope, out);
        }
        return new ArrayList<>(
            out.stream().map((name) ->
                scope.stream().filter((var) ->
                    var.identifier().equals(name)
                ).findFirst().get()
        ).toList());
    }

    /**
     * Determines if two elements are an exact text match through tree traversal
     * by type 1 standards (no whitespace or comments count).
     * @param inScope The variables in-scope at the present element
     * @return Whether the elements match
     */
    static boolean exactMatch(PsiElement a, PsiElement b, Stack<Variable> inScope, Set<Variable> liveIn) {
        if (a == null || b == null) return false;
        // Filter children of each element.
        List<PsiElement> childrenA = viableChildren(a);
        List<PsiElement> childrenB = viableChildren(b);
        // No need to traverse if different number of children.
        if (childrenA.size() != childrenB.size()) return false;
        if (childrenA.isEmpty()) return a.textMatches(b);
        // Next level of scoped variables
        Stack<Variable> inScopeChildren = new Stack<>();
        inScopeChildren.addAll(inScope);
        // Detect if we need to add a new variable to scope from the current element
        updateScope(b, inScope, inScopeChildren, liveIn);
        // Process children
        for (int i = 0; i < childrenA.size(); i++) {
            if (!exactMatch(childrenA.get(i), childrenB.get(i), inScopeChildren, liveIn))
                return false;
        }
        return true;
    }

    List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode);
}
