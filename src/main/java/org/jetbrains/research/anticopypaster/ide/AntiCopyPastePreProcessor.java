package org.jetbrains.research.anticopypaster.ide;

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor;
import com.intellij.ide.DataManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
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
import org.jetbrains.research.anticopypaster.Copilot.CopilotBridge;
import java.io.File;
import java.util.ArrayList;
import java.util.Timer;
import java.util.List;

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

        ProjectSettingsState state = ProjectSettingsState.getInstance(project);
        String selectedAnalysisButton = state.getSelectedAnalysisButton();
        String filesPath = state.getFilesPath();
        ArrayList<JCheckBox> filesCheckboxes = new ArrayList<>(state.getAllFilesCheckboxes());

        // If user selects Copilot as the judgement model, hand off to Copilot Chat UI.
        if (currentModelType == ProjectSettingsState.JudgementModel.COPILOT) {
            if (editor != null) {
                // Reuse unified target file collection for three scopes
                List<VirtualFile> targets = collectTargetFiles(project, file, selectedAnalysisButton, filesPath, filesCheckboxes);

                // Friendly validations mirroring previous behavior
                if ("Multiple Files".equals(selectedAnalysisButton)) {
                    if (filesPath == null || filesPath.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "No directory path provided. Please configure a valid directory for Copilot multi-file analysis.");
                        });
                        return text;
                    }
                    File filesDir = new File(filesPath);
                    if (!filesDir.isDirectory()) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "Invalid directory path for Copilot multi-file analysis. Please select a valid directory.");
                        });
                        return text;
                    }
                    boolean filesSelected = targets != null && !targets.isEmpty();
                    if (!filesSelected) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notify(project, "No files have been selected. Please pick at least one file for Copilot to analyze.");
                        });
                        return text;
                    }
                }

                String prompt = buildCopilotPrompt(project, targets);

                if (CopilotBridge.isCopilotChatAvailable()) {
                    CopilotBridge.openChatWithClipboardPrompt(project, editor, prompt);
                } else {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        notify(project, "GitHub Copilot Chat is not available. Please install/enable the Copilot plugin.");
                    });
                }
            }
            // We still return the original text so the paste content remains unchanged.
            return text;
        }

        if (currentModelType == ProjectSettingsState.JudgementModel.AIDER) {
            ProjectSettingsState.CloneMode cloneMode = state.getCloneMode();

            // Reuse unified target selection across three scopes
            List<VirtualFile> targets =
                    collectTargetFiles(project, file, selectedAnalysisButton, filesPath, filesCheckboxes);

            if ("Multiple Files".equals(selectedAnalysisButton)) {
                if (filesPath == null || filesPath.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            notify(project, "Invalid directory path provided in the plugin menu. Please input a valid directory and select at least one file."));
                    return text;
                }
                File filesDir = new File(filesPath);
                if (!filesDir.isDirectory()) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            notify(project, "Invalid directory path provided in the plugin menu. Please input a valid directory and select at least one file."));
                    return text;
                }
                if (targets == null || targets.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            notify(project, "No files have been selected in the plugin menu. Please select at least one file."));
                    return text;
                }
            }

            // Fallback to current file if nothing was selected
            if ((targets == null || targets.isEmpty()) && file != null && file.getVirtualFile() != null) {
                targets = List.of(file.getVirtualFile());
            }

            if (cloneMode == ProjectSettingsState.CloneMode.SINGLE_AGENT) {
                // ===== SINGLE-AGENT CLONE PIPELINE (original behavior) =====
                String model = state.getAiderModel();
                String apiKey = state.getAiderApiKey();
                String provider = state.getLlmprovider();
                String aiderPath = state.getAiderPath();
                String apiBase = "";
                String apiVersion = "";

                if ("Azure".equals(provider)) {
                    apiBase = state.getApiBase();
                    apiVersion = state.getApiVersion();
                }
                if ("Ollama".equals(provider)) {
                    apiBase = state.getApiBase();
                    model = state.getOllamaModelName();
                }

                if (targets != null && !targets.isEmpty()) {
                    for (VirtualFile vf : targets) {
                        AiderHelper.checkAndSuggestRefactor(
                                project, vf, provider, model, apiKey, aiderPath, apiBase, apiVersion
                        );
                    }
                }
                return text;
            }

            // ===== MULTI-AGENT CLONE PIPELINE =====
            List<VirtualFile> finalTargets = targets;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    org.jetbrains.research.anticopypaster.workflow.CloneRefactorWorkflow.run(
                            project,
                            finalTargets,
                            text
                    );
                } catch (Throwable t) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            notify(project, "Multi-agent refactoring workflow failed: " + t.getMessage()));
                }
            });

            return text;
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
     * Collect target files according to the selected analysis scope.
     * Mirrors the three scopes used by both Aider and Copilot:
     * "Current File", "All Files in Current Directory", "Multiple Files".
     */
    private static List<VirtualFile> collectTargetFiles(Project project,
                                                       @Nullable PsiFile currentPsiFile,
                                                       String selectedAnalysisButton,
                                                       String filesPath,
                                                       ArrayList<JCheckBox> filesCheckboxes) {
        ArrayList<VirtualFile> result = new ArrayList<>();

        if ("Current File".equals(selectedAnalysisButton)) {
            if (currentPsiFile != null && currentPsiFile.getVirtualFile() != null) {
                result.add(currentPsiFile.getVirtualFile());
            }
            return result;
        }

        if ("All Files in Current Directory".equals(selectedAnalysisButton)) {
            if (currentPsiFile != null && currentPsiFile.getVirtualFile() != null) {
                VirtualFile parentDir = currentPsiFile.getVirtualFile().getParent();
                if (parentDir != null) {
                    File currDir = new File(parentDir.getPath());
                    if (currDir.isDirectory()) {
                        File[] filesInDir = currDir.listFiles();
                        if (filesInDir != null) {
                            for (File fInDir : filesInDir) {
                                if (fInDir.isFile()) {
                                    VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fInDir.getAbsolutePath());
                                    if (vf != null) result.add(vf);
                                }
                            }
                        }
                    }
                }
            }
            return result;
        }

        if ("Multiple Files".equals(selectedAnalysisButton)) {
            if (filesPath == null || filesPath.isEmpty()) return result;
            File filesDir = new File(filesPath);
            if (!filesDir.isDirectory()) return result;
            if (filesCheckboxes == null || filesCheckboxes.isEmpty()) return result;

            for (JCheckBox cb : filesCheckboxes) {
                if (cb.isSelected()) {
                    String singleName = cb.getText();
                    String abs = filesPath + "/" + singleName;
                    File f = new File(abs);
                    if (f.isFile()) {
                        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(f.getAbsolutePath());
                        if (vf != null) result.add(vf);
                    }
                }
            }
            return result;
        }

        // Fallback to current file if no option matched or not selected.
        if (currentPsiFile != null && currentPsiFile.getVirtualFile() != null) {
            result.add(currentPsiFile.getVirtualFile());
        }
        return result;
    }

    /**
     * Build a Copilot prompt that enumerates project-relative file paths,
     * so Copilot Chat can auto-load them as context.
     */
    private static String buildCopilotPrompt(Project project, List<VirtualFile> targets) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Please detect any clones across the following files and refactor them by Extract Method.\n")
                .append("Keep behavior identical, use a clear intention-revealing method name, update all call sites,\n")
                .append("and show the final version of the class or classes.\n\n");

        if (targets != null && !targets.isEmpty()) {
            promptBuilder.append("Files:\n");
            for (VirtualFile vf : targets) {
                String abs = vf.getPath();
                promptBuilder.append("- ").append(toProjectRelative(project, abs)).append("\n");
            }
            promptBuilder.append("\n");
        } else {
            promptBuilder.append("Files: (current editor file)\n\n");
        }
        return promptBuilder.toString();
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

    /**
     * Returns a project-relative path if possible, otherwise returns the original absolute path.
     * This produces cleaner paths that Copilot Chat can often resolve within the current project.
     */
    private static String toProjectRelative(Project project, String absolutePath) {
        if (project == null || absolutePath == null) return absolutePath;
        String base = project.getBasePath();
        if (base == null) return absolutePath;
        if (absolutePath.startsWith(base)) {
            String rel = absolutePath.substring(base.length());
            if (rel.startsWith("/") || rel.startsWith("\\")) {
                rel = rel.substring(1);
            }
            return rel.isEmpty() ? absolutePath : rel;
        }
        return absolutePath;
    }

    private static void notify(Project project, String content) {
        Notification notification = new Notification(
                "AiderRefactor",
                "Aider Refactoring",
                content,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }
}