package org.jetbrains.research.anticopypaster.ide;

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Timer;

import static org.jetbrains.research.anticopypaster.utils.PsiUtil.findMethodByOffset;

/**
 * Handles any copy-paste action and checks if the pasted code fragment could be extracted into a separate method.
 */
public class AntiCopyPastePreProcessor implements CopyPastePreProcessor {
    private final Timer timer = new Timer(true);
    private final ArrayList<RefactoringNotificationTask> refactoringNotificationTask = new ArrayList<>();

    private static final Logger LOG = Logger.getInstance(AntiCopyPastePreProcessor.class);

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
        RefactoringNotificationTask rnt = getRefactoringTask(project);
        ProjectSettingsState.JudgementModel currentModelType = ProjectSettingsState.getInstance(project).judgementModel;
        if (currentModelType == ProjectSettingsState.JudgementModel.AIDER) {
            ProjectSettingsState state = ProjectSettingsState.getInstance(project);
            String model = state.getAiderModel();
            String apiKey = state.getAiderApiKey();
            String provider = state.getLlmprovider();
            String aiderPath = state.getAiderPath();
            String filesPath = state.getFilesPath();
            ArrayList<JCheckBox> filesCheckboxes = new ArrayList<>(state.getAllFilesCheckboxes());
            String selectedAnalysisButton = state.getSelectedAnalysisButton();

            System.out.println("Selected button: '" + selectedAnalysisButton + "'");

            // Check which analysis option was chosen ("Current File", "All Files in Current Directory", or "Multiple Files")
            // Take proper action according to selected option
            if(selectedAnalysisButton.equals("Current File")) {
                // If "Current File": default operation occurs (analysis of only the file that is currently open by the user).

                System.out.println("Current File properly selected");
                System.out.println(file.getVirtualFile().getName());

                AiderHelper.checkAndSuggestRefactor(project, file.getVirtualFile(), provider, model, apiKey, aiderPath);
            } else if(selectedAnalysisButton.equals("All Files in Current Directory")) {
                // If "All Files [...]": get dir of current file, get all files in dir
                // Call checkAndSuggestRefactor on each file in dir

                System.out.println("All Files in Current Directory properly selected");

                VirtualFile currFileVF = file.getVirtualFile();
                VirtualFile parentDir = currFileVF.getParent();
                String dirPath = parentDir.getPath();
                File currDir = new File(dirPath);
                if(currDir.isDirectory()) {
                    File[] filesInDir = currDir.listFiles();
                    if(filesInDir != null) {
                        for(File fileInDir : filesInDir) {
                            String fileAbsPath = fileInDir.getAbsolutePath();
                            VirtualFile virtualFileInDir = LocalFileSystem.getInstance().findFileByPath(fileAbsPath);
                            if(virtualFileInDir != null) {

                                System.out.println(virtualFileInDir.getName());

                                AiderHelper.checkAndSuggestRefactor(project, virtualFileInDir, provider, model, apiKey, aiderPath);
                            }
                        }
                    }
                }
            } else if(selectedAnalysisButton.equals("Multiple Files")) {
                // If "Multiple Files":
                // If a path to search for files has been provided and a file's checkbox has been selected:
                // Call checkAndSuggestRefactor on the particular file

                System.out.println("Multiple Files properly selected");

                if (!(filesPath == null || filesPath.equals(""))) {
                    for (int i = 0; i < filesCheckboxes.size(); i++) {
                        if(filesCheckboxes.get(i).isSelected()) {
                            String fileName = (filesCheckboxes.get(i)).getText();
                            String filePath = filesPath + "/" + fileName;
                            VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
                            if (virtualFile != null) {

                                System.out.println(virtualFile.getName());

                                AiderHelper.checkAndSuggestRefactor(project, virtualFile, provider, model, apiKey, aiderPath);
                            }
                        }
                    }
                }
            }
        }
        else{
            if (rnt == null) {
                rnt = new RefactoringNotificationTask(project);
                refactoringNotificationTask.add(rnt);
                setCheckingForRefactoringOpportunities(rnt, project);
            }

            AntiCopyPasterUsageStatistics.getInstance(project).onPaste();

            if (editor == null || file == null) return text;

            DataContext dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
            @Nullable Caret caret = CommonDataKeys.CARET.getData(dataContext);
            int offset = caret == null ? 0 : caret.getOffset();
            PsiMethod destinationMethod = findMethodByOffset(file, offset);

            RefactoringNotificationTask finalRnt = rnt;
            ApplicationManager.getApplication().invokeLater(() -> {
                finalRnt.addEvent(new RefactoringEvent(file, destinationMethod, text, project, editor));
            });
        }

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