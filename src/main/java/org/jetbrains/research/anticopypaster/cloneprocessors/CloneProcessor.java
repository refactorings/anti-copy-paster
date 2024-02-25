package org.jetbrains.research.anticopypaster.cloneprocessors;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public interface CloneProcessor {
    /**
     * Converts primitive type labels to boxed versions in order to be
     * generic-safe.
     * @param type Type label to convert if necessary
     * @return The boxed type, or the original label if it isn't primitive
     */
    static String boxedType(String type) {
        return switch (type) {
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "float" -> "Float";
            case "double" -> "Double";
            case "boolean" -> "Boolean";
            case "char" -> "Character";
            default -> type;
        };
    }

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
     * @param ms Current match state
     */
    private static void processStatementDecls(PsiDeclarationStatement stmt, MatchState ms) {
        PsiElement[] decls = stmt.getDeclaredElements();
        for (PsiElement decl : decls) {
            if (!(decl instanceof PsiLocalVariable localVar)) continue;
            ms.aliasMap().add(new Variable(localVar.getName(), localVar.getType().getPresentableText()));
            ms.scope().add(new Variable(localVar.getName(), localVar.getTypeElement().getText()));
        }
    }

    /**
     * Updates the stacks for currently in-scope and next-in-scope variables.
     * @param anElement Element to process
     * @param currentState Variables currently in-scope
     * @param childrenState Variables next-in-scope (next block level)
     */
    static void updateScope(PsiElement anElement, MatchState currentState, MatchState childrenState) {
        if (anElement instanceof PsiDeclarationStatement stmt) {
            processStatementDecls(stmt, currentState);
            processStatementDecls(stmt, childrenState);
        } else if (anElement instanceof PsiForeachStatement forEachStmt) {
            PsiParameter param = forEachStmt.getIterationParameter();
            childrenState.scope().add(new Variable(param.getName(), param.getType().getPresentableText()));
        } else if (anElement instanceof PsiForStatement forStmt) {
            PsiStatement stmt = forStmt.getInitialization();
            if (stmt != null)
                processStatementDecls((PsiDeclarationStatement) stmt, childrenState);
        } else if (anElement instanceof PsiIfStatement ifStmt) {
            Collection<PsiTypeTestPattern> tests = PsiTreeUtil.findChildrenOfType(
                    ifStmt.getCondition(), PsiTypeTestPattern.class);
            tests.forEach((test) -> {
                PsiPatternVariable pVar = test.getPatternVariable();
                if (pVar != null)
                    childrenState.scope().add(new Variable(pVar.getName(), pVar.getType().getPresentableText()));
            });
        } else if (anElement instanceof PsiReferenceExpression refExp && !refExp.isQualified()) {
            if (!isInScope(refExp.getReferenceName(), currentState.scope()) && refExp.getType() != null) {
                currentState.liveIn().add(new Variable(refExp.getReferenceName(), refExp.getType().getPresentableText()));
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
     * @param a The first element to compare
     * @param b The second element to compare
     * @param ma The match state for the first element
     * @param mb The match state for the second element
     * @return Whether the elements match
     */
    static boolean exactMatch(PsiElement a, PsiElement b, MatchState ma, MatchState mb) {
        if (a == null || b == null) return false;
        // Filter children of each element.
        List<PsiElement> childrenA = viableChildren(a);
        List<PsiElement> childrenB = viableChildren(b);
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

    List<Clone> getClonesOfType(PsiFile file, PsiCodeBlock pastedCode);
}
