package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class DuplicatesInspection {
    private static final Logger LOG = Logger.getInstance(AntiCopyPastePreProcessor.class);

//    private final ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);

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
//        final List<String> tokensOfPastedCode = getTokens(code);
//        @NotNull Collection<PsiMethod> methods = PsiTreeUtil.findChildrenOfType(file, PsiMethod.class);
//        final List<DuplicateResult> results = methods.stream()
//                .map(method -> new DuplicateResultComputable(code, method, tokensOfPastedCode))
//                .map(computable -> pool.submit(() -> ApplicationManager.getApplication().runReadAction(computable)))
//                .map(future -> {
//                    try {
//                        return future.get();
//                    } catch (Exception e) {
//                        LOG.warn("[ACP] Failed while searching for code duplicates.", e);
//                        return null;
//                    }
//                })
//                .filter(Objects::nonNull)
//                .collect(Collectors.toList());
//        return new InspectionResult(results);
        ArrayList<DuplicateResult> results = new ArrayList<>();
        try {
            PsiCodeBlock block = PsiElementFactory.getInstance(file.getProject())
                    .createCodeBlockFromText("{" + code + "}", file.getContext());
            PsiElement blockStart = block.getFirstChild();
            while (blockStart instanceof PsiWhiteSpace || blockStart instanceof PsiComment)
                blockStart = blockStart.getNextSibling();
            if (blockStart == null) return new InspectionResult(results);
            Collection<PsiElement> matches = PsiTreeUtil.findChildrenOfType(file, blockStart.getClass());
            for (PsiElement match : matches) {
                PsiElement end = isDuplicateAt(block, match);
                if (end != null)
                    results.add(new DuplicateResult(match, end));
            }
        } catch (IncorrectOperationException ex) {
            LOG.error(ex);
            return new InspectionResult(results);
        }

        LOG.info("[ACP] Found " + results.size() + " duplicates");

        return new InspectionResult(results);
    }

    public PsiElement nextNonEmptyNorCommentLeaf(PsiElement current) {
        do {
            current = PsiTreeUtil.nextVisibleLeaf(current);
        } while (current instanceof PsiComment);
        return current;
    }

    public PsiElement isDuplicateAt(PsiCodeBlock fragment, PsiElement start) {
        PsiElement fragCurrent = fragment.getFirstBodyElement();
        PsiElement dupeCurrent = start;
        if (fragCurrent != null && fragCurrent.getClass() != dupeCurrent.getClass())
            return null;
        while (fragCurrent != fragment.getLastBodyElement()) {
            fragCurrent = nextNonEmptyNorCommentLeaf(fragCurrent);
            dupeCurrent = nextNonEmptyNorCommentLeaf(dupeCurrent);
            if (dupeCurrent == null) return null;
            if (!fragCurrent.textMatches(dupeCurrent)) return null;
        }
        return dupeCurrent;
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

//    private static class DuplicateResultComputable implements Computable<DuplicateResult> {
//        private final String code;
//        private final PsiMethod psiMethod;
//        private final List<String> tokensOfPastedCode;
//
//        private DuplicateResultComputable(String code, PsiMethod psiMethod, List<String> tokensOfPastedCode) {
//            this.code = code;
//            this.psiMethod = psiMethod;
//            this.tokensOfPastedCode = tokensOfPastedCode;
//        }

//        @Override
//        public DuplicateResult compute() {
//            DuplicateResult duplicateResult = null;
//            PsiCodeBlock methodBody = psiMethod.getBody();
//            if (methodBody != null) {
//                String rawCode =
//                        code.replace('\n', ' ').replace('\t', ' ')
//                                .replace('\r', ' ').replaceAll("\\s+", "");
//                String rawMethodBody = psiMethod.getText().replace('\n', ' ').replace('\t', ' ')
//                        .replace('\r', ' ').replaceAll("\\s+", "");
//                boolean matches = StringUtils.contains(rawMethodBody, rawCode);
//                if (matches) {
//                    duplicateResult = new DuplicateResult(psiMethod, 1.0);
//                } else {
//                    List<String> tokensOfMethod = getTokens(methodBody.getText());
//                    double maxNumOfTokens = Math.max(tokensOfPastedCode.size(), tokensOfMethod.size());
//                    // Calculates the intersection of tokens
//                    tokensOfMethod.retainAll(tokensOfPastedCode);
//                    double threshold = tokensOfMethod.size() / maxNumOfTokens;
//                    if (threshold >= 0.8) {
//                        duplicateResult = new DuplicateResult(psiMethod, threshold);
//                    }
//                }
//            }
//            return duplicateResult;
//        }
//    }

//    private static List<String> getTokens(String text) {
//        return StringUtil.getWordsIn(text);
//    }
}
