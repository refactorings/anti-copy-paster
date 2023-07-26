package org.jetbrains.research.anticopypaster.ide;

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.checkers.FragmentCorrectnessChecker;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Timer;

import static org.jetbrains.research.anticopypaster.utils.PsiUtil.findMethodByOffset;
import static org.jetbrains.research.anticopypaster.utils.PsiUtil.getCountOfCodeLines;

/**
 * Handles any copy-paste action and checks if the pasted code fragment could be extracted into a separate method.
 */
public class AntiCopyPastePreProcessor implements CopyPastePreProcessor {
    private final DuplicatesInspection inspection = new DuplicatesInspection();
    private final Timer timer = new Timer(true);
    private final ArrayList<RefactoringNotificationTask> refactoringNotificationTask = new ArrayList<>();

    private static final Logger LOG = Logger.getInstance(AntiCopyPastePreProcessor.class);

    public AntiCopyPastePreProcessor() {
        refactoringNotificationTask.add(new RefactoringNotificationTask(inspection, timer, ProjectManager.getInstance().getOpenProjects()[0]));
        setCheckingForRefactoringOpportunities(refactoringNotificationTask.get(0), ProjectManager.getInstance().getOpenProjects()[0]);
    }

    /**
     * Triggers on each copy action.
     */
    @Nullable
    @Override
    public String preprocessOnCopy(PsiFile file, int[] startOffsets, int[] endOffsets, String text) {
        AntiCopyPasterUsageStatistics.getInstance(file.getProject()).onCopy();
        return null;
    }

    /**
     * Triggers on each paste action to search for duplicates and check the Extract Method refactoring opportunities
     * for a copied-pasted code fragment.
     */
    @NotNull
    @Override
    public String preprocessOnPaste(Project project, PsiFile file, Editor editor, String text, RawText rawText) {
        HashSet<String> variablesInCodeFragment = new HashSet<>();
        HashMap<String, Integer> variablesCountsInCodeFragment = new HashMap<>();

        RefactoringNotificationTask rnt = getRefactoringTask(project);

        if (rnt == null) {
            rnt = new RefactoringNotificationTask(inspection, timer, project);
            refactoringNotificationTask.add(rnt);
            setCheckingForRefactoringOpportunities(rnt, project);
        }

        AntiCopyPasterUsageStatistics.getInstance(project).onPaste();

        if (editor == null || file == null || !FragmentCorrectnessChecker.isCorrect(project, file,
                text,
                variablesInCodeFragment,
                variablesCountsInCodeFragment)) {
            return text;
        }

        @Nullable Caret caret = CommonDataKeys.CARET.getData(DataManager.getInstance().getDataContext());
        int offset = caret == null ? 0 : caret.getOffset();
        PsiMethod destinationMethod = findMethodByOffset(file, offset);

        // find number of code fragments considered as duplicated
        DuplicatesInspection.InspectionResult result = inspection.resolve(file, text);
        if (result.getDuplicatesCount() == 0) {
            return text;
        }

        //number of lines in fragment
        int linesOfCode = getCountOfCodeLines(text);

        rnt.addEvent(
                new RefactoringEvent(file, destinationMethod, text, result.getDuplicatesCount(),
                        project, editor, linesOfCode));

        return text;
    }

    /**
     * Finds the RefactoringNotificationTask in the refactoringNotificationTask ArrayList that is associated with the
     * given project. Returns the RefactoringNotificationTask if it exists, and null if it does not.
     * */
     private RefactoringNotificationTask getRefactoringTask(Project project) {
        for (RefactoringNotificationTask t:refactoringNotificationTask) {
            if (t.getProject() == project) {
                return t;
            }
        }
        return null;
    }

    /**
     * Sets the regular checking for Extract Method refactoring opportunities.
     */
    private void setCheckingForRefactoringOpportunities(RefactoringNotificationTask task, Project project) {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        int scheduleDelayInMs = settings.timeBuffer * 1000;

        try {
            timer.schedule(task, scheduleDelayInMs, scheduleDelayInMs);
        } catch (Exception ex) {
            LOG.error("[ACP] Failed to schedule the checking for refactorings.", ex.getMessage());
        }
    }
}