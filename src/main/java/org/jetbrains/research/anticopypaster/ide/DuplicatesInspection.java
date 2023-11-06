package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.TypeOneCP;

import java.util.ArrayList;
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
        ArrayList<Clone> results = new ArrayList<>();
        try {
            PsiCodeBlock block = PsiElementFactory.getInstance(file.getProject())
                    .createCodeBlockFromText("{" + code + "}", file.getContext());
            if (block.isEmpty()) return new InspectionResult(results);
            // Process Type 1
            results.addAll(new TypeOneCP().getClonesOfType(file, block));
        } catch (IncorrectOperationException ex) {
            LOG.error(ex);
            return new InspectionResult(results);
        }

        return new InspectionResult(results);
    }

    public static class InspectionResult {
        private final List<Clone> results;

        public InspectionResult(List<Clone> results) {
            this.results = results;
        }

        public int getDuplicatesCount() {
            return this.results.size();
        }
    }
}
