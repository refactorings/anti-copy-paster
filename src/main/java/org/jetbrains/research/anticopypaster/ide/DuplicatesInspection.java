package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.research.anticopypaster.cloneprocessors.Clone;
import org.jetbrains.research.anticopypaster.cloneprocessors.TypeTwoCP;
import org.jetbrains.research.anticopypaster.utils.PsiUtil;

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
    public InspectionResult resolve(PsiFile file, PsiMethod containingMethod, final String code) {
        ArrayList<Clone> results = new ArrayList<>();
        InspectionResult result = new InspectionResult(results);
        try {
            int startIdx = containingMethod.getBody().getText().indexOf(code);
            int endIdx = startIdx + code.length() - 1;
            PsiStatement[] stmts = containingMethod.getBody().getStatements();
            int startStmt = 0;
            while (startStmt < stmts.length - 1 && startIdx >= PsiUtil.endOffset(stmts[startStmt]))
                startStmt++;
            int endStmt = startStmt;
            while (endStmt < stmts.length - 1 && endIdx > stmts[endStmt + 1].getStartOffsetInParent())
                endStmt++;
//            PsiCodeBlock block = PsiElementFactory.getInstance(file.getProject())
//                    .createCodeBlockFromText("{" + code + "}", containingMethod.getParent());
//            if (block.isEmpty()) return result;
            // Process Type 1
//            results.addAll(new TypeOneCP().getClonesOfType(file, block));
            // Process Type 2
            results.addAll(new TypeTwoCP().getClonesOfType(file, stmts[startStmt], stmts[endStmt]));
        } catch (IncorrectOperationException ex) {
            LOG.error(ex);
            return result;
        }

        for (int i = results.size() - 1; i >= 0; i--)
            if (results.get(i).liveVars().size() > 1) results.remove(i);
        return result;
    }

    public record InspectionResult(List<Clone> results) {}
}
