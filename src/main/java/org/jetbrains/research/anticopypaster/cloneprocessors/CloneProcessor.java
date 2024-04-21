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
            childrenState.aliasMap().add(new Variable(param.getName(), param.getType().getPresentableText()));
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
        } else if (anElement instanceof PsiReferenceExpression refExp && !refExp.isQualified()
                && refExp.getType() != null) {
            // Prevents extracting LHS of statements & method calls
            if (refExp.getParent() != null
                    && (refExp.getParent().getParent() instanceof PsiExpressionStatement
                    && refExp.getStartOffsetInParent() == 0)
                    || refExp.getParent() instanceof PsiCallExpression) return;
            int aliasID = currentState.getAliasID(refExp.getReferenceName());
            if (aliasID == -1 && refExp.resolve() instanceof PsiVariable variable)
                currentState.liveIn().add(variable);
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

    List<Clone> getClonesOfType(PsiFile file, PsiStatement start, PsiStatement end);
}
