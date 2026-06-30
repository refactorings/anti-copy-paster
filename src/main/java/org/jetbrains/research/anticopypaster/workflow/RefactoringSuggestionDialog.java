package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.BorderFactory;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class RefactoringSuggestionDialog {
    private static final int EDIT_EXIT_CODE = DialogWrapper.NEXT_USER_EXIT_CODE;
    private static final int EDIT_CODE_EXIT_CODE = DialogWrapper.NEXT_USER_EXIT_CODE + 1;
    private static final String HELP_URL =
            "https://github.com/JetBrains-Research/anti-copy-paster#refactoring-suggestion-panel";

    private RefactoringSuggestionDialog() {
    }

    enum Choice {
        APPLY,
        EDIT,
        EDIT_CODE,
        CANCEL
    }

    static final class Decision {
        final Choice choice;
        final String editInstructions;
        final String editedCode;

        private Decision(Choice choice, String editInstructions, String editedCode) {
            this.choice = choice == null ? Choice.CANCEL : choice;
            this.editInstructions = editInstructions == null ? "" : editInstructions;
            this.editedCode = editedCode == null ? "" : editedCode;
        }

        static Decision apply() {
            return new Decision(Choice.APPLY, "", "");
        }

        static Decision edit(String editInstructions) {
            return new Decision(Choice.EDIT, editInstructions, "");
        }

        static Decision editCode(String editedCode) {
            return new Decision(Choice.EDIT_CODE, "", editedCode);
        }


        static Decision cancel() {
            return new Decision(Choice.CANCEL, "", "");
        }
    }

    static final class SuggestionInfo {
        final String fileName;
        final VirtualFile file;
        final String cloneType;
        final String cloneTypeDefinition;
        final String detectedMethodDisplayName;
        final String extractedMethodName;
        final String detectionExplanation;
        final String refactoringExplanation;
        final String usefulnessExplanation;
        final String beforeDiffText;
        final String afterDiffText;
        final String sourceLocationLabel;
        final int sourceLine;
        final String pastedLocationLabel;
        final int pastedLine;
        final String confidenceLabel;
        final String diffTitle;
        final LinkedHashMap<String, String> metadataRows;
        final boolean refactoringFailed; // Boolean to determine if refactoring failed and therefore if buttons must be disabled

        SuggestionInfo(String fileName,
                       VirtualFile file,
                       String cloneType,
                       String cloneTypeDefinition,
                       String detectedMethodDisplayName,
                       String extractedMethodName,
                       String detectionExplanation,
                       String refactoringExplanation,
                       String usefulnessExplanation,
                       String beforeDiffText,
                       String afterDiffText,
                       String sourceLocationLabel,
                       int sourceLine,
                       String pastedLocationLabel,
                       int pastedLine,
                       String confidenceLabel,
                       String diffTitle,
                       LinkedHashMap<String, String> metadataRows, boolean refactoringFailed) {
            this.fileName = safe(fileName);
            this.file = file;
            this.cloneType = safe(cloneType);
            this.cloneTypeDefinition = safe(cloneTypeDefinition);
            this.detectedMethodDisplayName = safe(detectedMethodDisplayName);
            this.extractedMethodName = safe(extractedMethodName);
            this.detectionExplanation = safe(detectionExplanation);
            this.refactoringExplanation = safe(refactoringExplanation);
            this.usefulnessExplanation = safe(usefulnessExplanation);
            this.beforeDiffText = safe(beforeDiffText);
            this.afterDiffText = safe(afterDiffText);
            this.sourceLocationLabel = safe(sourceLocationLabel);
            this.sourceLine = sourceLine;
            this.pastedLocationLabel = safe(pastedLocationLabel);
            this.pastedLine = pastedLine;
            this.confidenceLabel = safe(confidenceLabel);
            this.diffTitle = safe(diffTitle);
            this.metadataRows = metadataRows == null ? new LinkedHashMap<>() : metadataRows;
            this.refactoringFailed = refactoringFailed;
        }
    }

    static Decision show(Project project, SuggestionInfo info) {
        if (project == null || project.isDisposed()) {
            return Decision.cancel();
        }

        AtomicReference<Decision> out = new AtomicReference<>(Decision.cancel());
        Runnable ui = () -> out.set(showOnEdt(project, info));

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }

        return out.get();
    }

    private static Decision showOnEdt(Project project, SuggestionInfo info) {
        Disposable diffDisposable = Disposer.newDisposable("CLONESuggestionDiff");
        try {
            DiffRequestPanel diffPanel = createDiffPanel(project, diffDisposable, info);
            SuggestionDialog dialog = new SuggestionDialog(project, info, diffPanel);
            dialog.show();

            int exitCode = dialog.getExitCode();
            if (exitCode == DialogWrapper.OK_EXIT_CODE) {
                return Decision.apply();
            }

            if (exitCode == EDIT_EXIT_CODE) {
                AntiCopyPasterUsageStatistics.getInstance(project).refactoringEdited();
                String instructions = showEditInstructionsDialog(project);
                if (instructions == null || instructions.isBlank()) {
                    return Decision.cancel();
                }
                return Decision.edit(instructions);
            }

            if (exitCode == EDIT_CODE_EXIT_CODE) {
                String editedCode = dialog.getEditedCode();
                if (editedCode == null || editedCode.isBlank()) {
                    return Decision.cancel();
                }
                return Decision.editCode(editedCode);
            }


            return Decision.cancel();
        } catch (Throwable t) {
            return Decision.cancel();
        } finally {
            Disposer.dispose(diffDisposable);
        }
    }

    private static DiffRequestPanel createDiffPanel(Project project, Disposable disposable, SuggestionInfo info) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        var left = factory.create(info == null ? "" : info.beforeDiffText);
        var right = factory.create(info == null ? "" : info.afterDiffText);
        String title = info == null || info.diffTitle.isBlank() ? "Extract Method" : info.diffTitle;
        SimpleDiffRequest request = new SimpleDiffRequest(
                title,
                left,
                right,
                "Before: Original Code",
                "After: Proposed Refactoring"
        );
        DiffRequestPanel panel = DiffManager.getInstance().createRequestPanel(project, disposable, null);
        panel.setRequest(request);
        return panel;
    }

    private static String showEditInstructionsDialog(Project project) {
        JTextArea area = new JTextArea(10, 78);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIUtil.getLabelFont());

        DialogWrapper dialog = new DialogWrapper(project, true) {
            {
                setTitle("Edit instructions for regeneration");
                setOKButtonText("Regenerate");
                setCancelButtonText("Cancel");
                init();
            }

            @Override
            protected @Nullable JComponent createCenterPanel() {
                JPanel panel = new JPanel(new BorderLayout());
                panel.setBorder(JBUI.Borders.empty(8));

                JTextArea prompt = nonEditableTextArea(
                        "Describe what should change in the refactoring suggestion. " +
                                "CLONE will send this feedback to the refactoring agent and regenerate the proposal."
                );
                panel.add(prompt, BorderLayout.NORTH);

                JScrollPane scrollPane = new JScrollPane(area);
                scrollPane.setPreferredSize(new Dimension(760, 220));
                panel.add(scrollPane, BorderLayout.CENTER);
                return panel;
            }
        };

        boolean ok = dialog.showAndGet();
        return ok ? area.getText().trim() : "";
    }


    // New edit code button opens an editable text box with the "after" code from the diff, allowing the user
    // to directly edit the code before adding it to their program
    static String showCodeEditDialog(Project project, String initialCode) {
        JTextArea area = new JTextArea(20, 90);
        area.setLineWrap(false);
        area.setFont(UIUtil.getLabelFont().deriveFont(Font.PLAIN, 13f));
        area.setText(initialCode == null ? "" : initialCode);

        DialogWrapper dialog = new DialogWrapper(project, true) {
            {
                setTitle("Edit Proposed Code");
                setOKButtonText("Use This Code");
                setCancelButtonText("Back");
                init();
            }

            @Override
            protected @Nullable JComponent createCenterPanel() {
                JPanel panel = new JPanel(new BorderLayout());
                panel.setBorder(JBUI.Borders.empty(8));

                JTextArea prompt = nonEditableTextArea(
                        "Make any changes to the refactored code below, then click \"Use This Code\" " +
                                "to apply your edited version instead of the original suggestion."
                );
                panel.add(prompt, BorderLayout.NORTH);

                JScrollPane scrollPane = new JScrollPane(area);
                scrollPane.setPreferredSize(new Dimension(820, 420));
                panel.add(scrollPane, BorderLayout.CENTER);
                return panel;
            }
        };

        boolean ok = dialog.showAndGet();
        return ok ? area.getText() : "";
    }

    private static final class SuggestionDialog extends DialogWrapper {
        private final Project project;
        private final SuggestionInfo info;
        private final DiffRequestPanel diffPanel;
        private String editedCode = "";

        private SuggestionDialog(Project project, SuggestionInfo info, DiffRequestPanel diffPanel) {
            super(project, true);
            this.project = project;
            this.info = info;
            this.diffPanel = diffPanel;
            setTitle("[CLONE] Refactoring Suggestion");
            setOKButtonText("Apply");
            setCancelButtonText("Cancel");
            init();
        }

        // Simple getter method for editedCode variable
        public String getEditedCode() {
            return editedCode;
        }


        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(new Dimension(1120, 780));
            panel.setMinimumSize(new Dimension(900, 620));

            panel.add(createTopPanel(), BorderLayout.NORTH);
            panel.add(createDiffCardsPanel(), BorderLayout.CENTER); //CHANGE: panel.add(diffPanel.getComponent(), BorderLayout.CENTER);
            panel.add(createMetadataPanel(), BorderLayout.SOUTH);

            return panel;
        }

        private JComponent createDiffCardsPanel(){
            JPanel wrapper = new JPanel(new BorderLayout());

            JPanel comparisonPanel = new JPanel(new GridLayout(1, 2, 8, 0));
            comparisonPanel.setBorder(JBUI.Borders.empty(8));
            comparisonPanel.add(createCard(new JLabel("Original Duplicate Code")));
            comparisonPanel.add(createCard(new JLabel("Extracted (Proposed Refactoring)")));
            return comparisonPanel;
        }

        private JComponent createCard(JLabel header){
            JPanel cardPanel = new JPanel(new BorderLayout());
            cardPanel.setBorder(JBUI.Borders.empty(12));
            cardPanel.add(header, BorderLayout.WEST);
            //add code snippet
            return cardPanel;
        }

        private JComponent createTopPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(10, 12, 8, 12));

            JPanel header = new JPanel(new BorderLayout(8, 0));
            JLabel title = new JLabel("[CLONE] Refactoring Suggestion");
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2.0f));
            header.add(new JLabel(loadHeaderIcon()), BorderLayout.WEST);
            header.add(title, BorderLayout.CENTER);
            panel.add(header);

            panel.add(Box.createVerticalStrut(8));
            panel.add(new JSeparator());
            panel.add(Box.createVerticalStrut(8));

            panel.add(nonEditableTextArea(info.detectionExplanation));
            panel.add(Box.createVerticalStrut(6));
            panel.add(nonEditableTextArea(info.refactoringExplanation));
            if (!info.usefulnessExplanation.isBlank()) {
                panel.add(Box.createVerticalStrut(6));
                panel.add(nonEditableTextArea(info.usefulnessExplanation));
            }

            panel.add(Box.createVerticalStrut(8));
            JLabel diffTitle = new JLabel("Diff: " + (info.diffTitle.isBlank() ? "Extract Method" : info.diffTitle));
            diffTitle.setFont(diffTitle.getFont().deriveFont(Font.BOLD));
            panel.add(diffTitle);
            panel.add(Box.createVerticalStrut(6));

            return panel;
        }

        private JComponent createMetadataPanel() {
            JPanel wrapper = new JPanel();
            wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
            wrapper.setBorder(JBUI.Borders.empty(8, 12, 10, 12));

            JPanel details = new JPanel(new GridBagLayout());
            details.setBorder(JBUI.Borders.empty(6, 22, 0, 0));
            details.setVisible(false);

            LinkedHashMap<String, String> rows = new LinkedHashMap<>();
            rows.put("Clone Type", labelWithDefinition(info.cloneType, info.cloneTypeDefinition));
            rows.put("Source Location", info.sourceLocationLabel);
            rows.put("Pasted Location", info.pastedLocationLabel);
            rows.putAll(info.metadataRows);

            int row = 0;
            for (Map.Entry<String, String> entry : rows.entrySet()) {
                addMetadataRow(details, row++, entry.getKey(), entry.getValue());
            }

            JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JButton toggle = new JButton(detailsTitle(false));
            toggle.setBorderPainted(false);
            toggle.setContentAreaFilled(false);
            toggle.setFocusPainted(false);
            toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            toggle.addActionListener(e -> {
                boolean expanded = !details.isVisible();
                details.setVisible(expanded);
                toggle.setText(detailsTitle(expanded));
                wrapper.revalidate();
                wrapper.repaint();
            });
            toggleRow.add(toggle);

            wrapper.add(new JSeparator());
            wrapper.add(Box.createVerticalStrut(6));
            wrapper.add(toggleRow);
            wrapper.add(details);
            return wrapper;
        }

        /*

        private static DiffRequestPanel createDiffRequestPanel() {
            DiffRequestPanel panel = new DiffRequestPanel() {
            };
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(8, 12, 8, 12));

            return panel;

        }
         */

        private String detailsTitle(boolean expanded) {
            String arrow = expanded ? "v" : ">";
            String confidence = info.confidenceLabel.isBlank() ? "Verified" : info.confidenceLabel;
            return arrow + " Details & Provenance (Confidence: " + confidence + ")";
        }

        private void addMetadataRow(JPanel panel, int row, String name, String value) {
            GridBagConstraints left = new GridBagConstraints();
            left.gridx = 0;
            left.gridy = row;
            left.anchor = GridBagConstraints.NORTHWEST;
            left.insets = new Insets(3, 0, 3, 12);

            JLabel label = new JLabel(name + ":");
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            panel.add(label, left);

            GridBagConstraints right = new GridBagConstraints();
            right.gridx = 1;
            right.gridy = row;
            right.weightx = 1.0;
            right.fill = GridBagConstraints.HORIZONTAL;
            right.anchor = GridBagConstraints.NORTHWEST;
            right.insets = new Insets(3, 0, 3, 0);

            JComponent valueComponent = locationComponent(name, value);
            panel.add(valueComponent, right);
        }

        private JComponent locationComponent(String name, String value) {
            int line = -1;
            if ("Source Location".equals(name)) {
                line = info.sourceLine;
            } else if ("Pasted Location".equals(name)) {
                line = info.pastedLine;
            }

            if (line <= 0 || info.file == null) {
                return wrappedLabel(value);
            }

            JButton button = new JButton(value);
            button.setHorizontalAlignment(JButton.LEFT);
            button.setBorderPainted(false);
            button.setContentAreaFilled(false);
            button.setFocusPainted(false);
            button.setForeground(JBColor.BLUE);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            int targetLine = line;
            button.addActionListener(e -> openLocation(project, info.file, targetLine));
            return button;
        }

        @Override
        protected Action[] createActions() {
            // If the refactoring has failed, the apply action will be disabled
            Action applyAction = getOKAction();
            if(info.refactoringFailed) {
                applyAction.setEnabled(false);
            }

            return new Action[]{
                    applyAction,
                    new AbstractAction("Edit Instructions...") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            close(EDIT_EXIT_CODE);
                        }
                    },
                    // Adding in functionality for edit code button
                    new AbstractAction("Edit Code...") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            String result = showCodeEditDialog(project, info.afterDiffText);
                            if (result != null && !result.isBlank()) {
                                editedCode = result;
                                close(EDIT_CODE_EXIT_CODE);

                            }
                        }
                    },
                    getCancelAction(),
                    new AbstractAction("?") {
                        {
                            putValue(Action.SMALL_ICON, Messages.getQuestionIcon());
                            putValue(Action.SHORT_DESCRIPTION, "Help and feedback");
                        }

                        @Override
                        public void actionPerformed(ActionEvent e) {
                            AntiCopyPasterUsageStatistics.getInstance(project).refactoringHelpOpened();
                            try {
                                BrowserUtil.browse(HELP_URL);
                            } catch (Throwable t) {
                                Messages.showInfoMessage(
                                        project,
                                        "See README.md, section \"Refactoring Suggestion Panel\".",
                                        "CLONE Help"
                                );
                            }
                        }
                    }
            };
        }
    }

    private static void openLocation(Project project, VirtualFile file, int oneBasedLine) {
        try {
            if (project == null || project.isDisposed() || file == null || oneBasedLine <= 0) return;
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, Math.max(0, oneBasedLine - 1), 0);
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        } catch (Throwable ignored) {
        }
    }

    private static Icon loadHeaderIcon() {
        try {
            return IconLoader.getIcon("/icons/sdk_16.svg", RefactoringSuggestionDialog.class);
        } catch (Throwable ignored) {
            return Messages.getInformationIcon();
        }
    }

    private static JTextArea nonEditableTextArea(String text) {
        JTextArea area = new JTextArea(safe(text));
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(UIUtil.getLabelFont());
        area.setBorder(JBUI.Borders.empty());
        return area;
    }

    private static JComponent wrappedLabel(String text) {
        JTextArea area = nonEditableTextArea(text);
        area.setRows(1);
        return area;
    }

    private static String labelWithDefinition(String label, String definition) {
        String safeLabel = safe(label);
        String safeDefinition = safe(definition);
        if (safeDefinition.isBlank()) return safeLabel;
        return safeLabel + " (" + safeDefinition + ")";
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}