package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class DuplicatesInspection {
    private static final Logger LOG = Logger.getInstance(AntiCopyPastePreProcessor.class);

    /**
     * Searches for duplicates in methods extracted from the file.
     * First, it checks if a method contains the copy-pasted piece of code as a substring,
     * and if doesn't then it collects the bags of words of a method and a piece of code and calculates their
     * similarity.
     *
     * @param file to search duplicates in.
     * @param code the piece of code to search for.
     * @return the result of duplicates' detection.
     */
    public InspectionResult resolve(PsiFile file, final String code) {
        ArrayList<DuplicateResult> results = new ArrayList<>();
        try {
            PsiCodeBlock block = PsiElementFactory.getInstance(file.getProject())
                    .createCodeBlockFromText("{" + code + "}", file.getContext());
            if (block.isEmpty()) return new InspectionResult(results);
            PsiElement blockStart = block.getStatements()[0];
            Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, blockStart.getClass());
            for (PsiElement match : matches) {
                PsiElement end = isDuplicateAt(block, blockStart, match);
                if (end != null) results.add(new DuplicateResult(match, end));
            }
        } catch (IncorrectOperationException ex) {
            LOG.error(ex);
            return new InspectionResult(results);
        }

        return new InspectionResult(results);
    }

    /**
     * Gets all the children of an element that aren't whitespace or comments.
     * This conforms to the specifications of type 1 clones.
     *
     * @return The viable children for tree matching.
     */
    private List<PsiElement> viableChildren(PsiElement element) {
        if (element == null) return new ArrayList<>();
        return Arrays.stream(element.getChildren()).filter(psiElement ->
                !(psiElement instanceof PsiWhiteSpace || psiElement instanceof PsiComment)).toList();
    }

    /**
     * Determines if two elements are an exact text match through tree traversal.
     * @return Whether the elements match
     */
    private boolean exactMatch(PsiElement a, PsiElement b) {
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

    /**
     * Check for a clone of the fragment starting at the given element.
     * @return The last member element of the clone
     */
    private PsiElement isDuplicateAt(PsiCodeBlock block, PsiElement fragment, PsiElement start) {
        if (fragment == null) return null;
        PsiElement fragCurrent = fragment;
        PsiElement dupeCurrent = start;
        while (fragCurrent != null && fragCurrent != block.getRBrace()) {
            if (dupeCurrent == null) return null;
            if (!exactMatch(fragCurrent, dupeCurrent)) return null;
            fragCurrent = fragCurrent.getNextSibling();
            dupeCurrent = dupeCurrent.getNextSibling();
        }
        return dupeCurrent.getPrevSibling();
    }

    public record DuplicateResult(PsiElement start, PsiElement end) {}

    public static class InspectionResult {
        private final List<DuplicateResult> results;

        public InspectionResult(List<DuplicateResult> results) {
            this.results = results;
        }

        public int getDuplicatesCount() {
            return this.results.size();
        }
    }
}
