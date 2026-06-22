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
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

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
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;
import java.awt.event.ActionEvent;

final class RefactoringSuggestionPanel {
    private static final String TOOL_WINDOW_ID = "AntiCopyPaster";
    private static final String CONTENT_TITLE = "Refactoring Suggestion";
    private static final String HELP_URL =
            "https://github.com/JetBrains-Research/anti-copy-paster#refactoring-suggestion-panel";
    private static final Map<String, SuggestionToolPanel> PANELS_BY_PROJECT = new ConcurrentHashMap<>();

    private RefactoringSuggestionPanel() {
    }

    static void installPersistentPanel(Project project, ToolWindow toolWindow) {
        if (project == null || project.isDisposed() || toolWindow == null) {
            return;
        }

        Runnable ui = () -> ensurePanel(project, toolWindow);
        runOnEdtAndWait(ui);
    }

    static RefactoringSuggestionDialog.Decision show(Project project,
                                                     RefactoringSuggestionDialog.SuggestionInfo info) {
        showPreview(project, info);
        return awaitDecision(project, info);
    }

    static void showPreview(Project project, RefactoringSuggestionDialog.SuggestionInfo info) {
        if (project == null || project.isDisposed()) {
            return;
        }

        Runnable ui = () -> {
            try {
                SuggestionToolPanel panel = ensurePanel(project, true);
                panel.showSuggestion(
                        info,
                        null,
                        false,
                        "Proposal generated. Running usefulness, compile, and tests before Apply is enabled."
                );
            } catch (Throwable ignored) {
            }
        };

        runOnEdtAndWait(ui);
    }

    static void markVerificationFailed(Project project, String message) {
        if (project == null || project.isDisposed()) {
            return;
        }

        Runnable ui = () -> {
            try {
                SuggestionToolPanel panel = ensurePanel(project, false);
                panel.setVerificationStatus(
                        message == null || message.isBlank()
                                ? "Verification failed. AntiCopyPaster will retry if attempts remain."
                                : message
                );
            } catch (Throwable ignored) {
            }
        };

        runOnEdtAndWait(ui);
    }

    static RefactoringSuggestionDialog.Decision awaitDecision(Project project,
                                                              RefactoringSuggestionDialog.SuggestionInfo info) {
        if (project == null || project.isDisposed()) {
            return RefactoringSuggestionDialog.Decision.cancel();
        }

        AtomicReference<RefactoringSuggestionDialog.Decision> out =
                new AtomicReference<>(RefactoringSuggestionDialog.Decision.cancel());
        CountDownLatch latch = new CountDownLatch(1);

        Runnable ui = () -> {
            try {
                SuggestionToolPanel panel = ensurePanel(project, true);
                panel.enableDecision(info, decision -> {
                    out.set(decision == null
                            ? RefactoringSuggestionDialog.Decision.cancel()
                            : decision);
                    latch.countDown();
                });
            } catch (Throwable ignored) {
                out.set(RefactoringSuggestionDialog.Decision.cancel());
                latch.countDown();
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
            return RefactoringSuggestionDialog.Decision.cancel();
        }

        try {
            ApplicationManager.getApplication().invokeAndWait(ui);
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelPendingDecision(project, "Workflow cancelled before a decision was selected.");
            return RefactoringSuggestionDialog.Decision.cancel();
        } catch (Throwable ignored) {
            cancelPendingDecision(project, "Workflow stopped before a decision was selected.");
            return RefactoringSuggestionDialog.Decision.cancel();
        }

        return out.get();
    }

    static void cancelPendingDecision(Project project, String message) {
        if (project == null || project.isDisposed()) {
            return;
        }

        Runnable ui = () -> {
            try {
                SuggestionToolPanel panel = ensurePanel(project, false);
                panel.cancelPendingDecision(
                        message == null || message.isBlank()
                                ? "Workflow stopped before a decision was selected."
                                : message
                );
            } catch (Throwable ignored) {
            }
        };

        runOnEdtAndWait(ui);
    }

    private static SuggestionToolPanel ensurePanel(Project project, boolean activate) {
        ToolWindowManager twm = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = twm.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            toolWindow = twm.registerToolWindow(RegisterToolWindowTask.notClosable(TOOL_WINDOW_ID));
        }
        moveToRight(toolWindow);
        SuggestionToolPanel panel = ensurePanel(project, toolWindow);
        if (activate) {
            toolWindow.activate(null);
        }
        return panel;
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        try {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                runnable.run();
            } else {
                ApplicationManager.getApplication().invokeAndWait(runnable);
            }
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static SuggestionToolPanel ensurePanel(Project project, ToolWindow toolWindow) {
        moveToRight(toolWindow);
        String key = projectKey(project);
        Content existingContent = toolWindow.getContentManager().findContent(CONTENT_TITLE);
        if (existingContent != null && existingContent.getComponent() instanceof SuggestionToolPanel panel) {
            PANELS_BY_PROJECT.put(key, panel);
            toolWindow.getContentManager().setSelectedContent(existingContent);
            return panel;
        }

        SuggestionToolPanel existingPanel = PANELS_BY_PROJECT.get(key);
        if (existingPanel != null && existingContent != null) {
            toolWindow.getContentManager().setSelectedContent(existingContent);
            return existingPanel;
        }

        SuggestionToolPanel panel = new SuggestionToolPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, CONTENT_TITLE, false);
        Disposer.register(content, () -> {
            panel.disposePanel();
            PANELS_BY_PROJECT.remove(key, panel);
        });

        if (existingContent != null) {
            toolWindow.getContentManager().removeContent(existingContent, true);
        }
        toolWindow.getContentManager().addContent(content);
        toolWindow.getContentManager().setSelectedContent(content);
        PANELS_BY_PROJECT.put(key, panel);
        return panel;
    }

    private static void moveToRight(ToolWindow toolWindow) {
        if (toolWindow == null) {
            return;
        }
        try {
            if (toolWindow.getAnchor() != ToolWindowAnchor.RIGHT) {
                toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String projectKey(Project project) {
        if (project == null) {
            return "<no-project>";
        }
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            return basePath;
        }
        return Integer.toHexString(System.identityHashCode(project));
    }

    private static final class SuggestionToolPanel extends JPanel {
        private final Project project;
        private Disposable diffDisposable;
        private RefactoringSuggestionDialog.SuggestionInfo currentInfo;
        private Consumer<RefactoringSuggestionDialog.Decision> decisionConsumer;
        private JTextArea editInstructionsArea;
        private JLabel statusLabel;
        private JButton applyButton;
        private JButton regenerateButton;
        private JButton cancelButton;

        private SuggestionToolPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            showWaitingState();
        }

        private void showWaitingState() {
            disposeCurrentDiff();
            currentInfo = null;
            decisionConsumer = null;
            editInstructionsArea = null;
            applyButton = null;
            regenerateButton = null;
            cancelButton = null;
            removeAll();

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(16, 18, 16, 18));

            JPanel header = new JPanel(new BorderLayout(8, 0));
            JLabel title = new JLabel("[AntiCopyPaster] Refactoring Suggestion");
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2.0f));
            header.add(new JLabel(loadHeaderIcon()), BorderLayout.WEST);
            header.add(title, BorderLayout.CENTER);

            panel.add(header);
            panel.add(Box.createVerticalStrut(10));
            panel.add(new JSeparator());
            panel.add(Box.createVerticalStrut(10));
            panel.add(nonEditableTextArea(
                    "Waiting for a refactoring proposal. " +
                            "When the refactoring agent produces a suggestion, the proposal will appear here while verification runs."
            ));
            panel.add(Box.createVerticalStrut(8));
            statusLabel = new JLabel("No active proposal.");
            panel.add(statusLabel);

            add(panel, BorderLayout.NORTH);
            revalidate();
            repaint();
        }

        private void showSuggestion(RefactoringSuggestionDialog.SuggestionInfo info,
                                    Consumer<RefactoringSuggestionDialog.Decision> onDecision,
                                    boolean decisionEnabled,
                                    String statusText) {
            if (decisionConsumer != null) {
                complete(RefactoringSuggestionDialog.Decision.cancel(), "Previous suggestion was replaced.");
            }

            disposeCurrentDiff();
            currentInfo = info;
            decisionConsumer = onDecision;
            removeAll();

            RefactoringSuggestionDialog.SuggestionInfo safeInfo = info == null
                    ? emptyInfo()
                    : info;

            diffDisposable = Disposer.newDisposable("AntiCopyPasterPersistentSuggestionDiff");
            DiffRequestPanel diffPanel = createDiffPanel(project, diffDisposable, safeInfo);

            add(createTopPanel(safeInfo), BorderLayout.NORTH);
            add(diffPanel.getComponent(), BorderLayout.CENTER);
            add(createBottomPanel(safeInfo), BorderLayout.SOUTH);
            setDecisionButtonsEnabled(decisionEnabled);
            if (statusLabel != null && statusText != null && !statusText.isBlank()) {
                statusLabel.setText(statusText);
            }

            revalidate();
            repaint();
        }

        private void enableDecision(RefactoringSuggestionDialog.SuggestionInfo info,
                                    Consumer<RefactoringSuggestionDialog.Decision> onDecision) {
            if (currentInfo != info || statusLabel == null) {
                showSuggestion(
                        info,
                        onDecision,
                        true,
                        "Verification passed. Review the refactoring and choose an action."
                );
                return;
            }

            decisionConsumer = onDecision;
            setDecisionButtonsEnabled(true);
            statusLabel.setText("Verification passed. Review the refactoring and choose an action.");
            revalidate();
            repaint();
        }

        private void setVerificationStatus(String statusText) {
            decisionConsumer = null;
            setDecisionButtonsEnabled(false);
            if (statusLabel != null) {
                statusLabel.setText(statusText);
            }
            revalidate();
            repaint();
        }

        private void cancelPendingDecision(String statusText) {
            decisionConsumer = null;
            setDecisionButtonsEnabled(false);
            if (statusLabel != null) {
                statusLabel.setText(statusText);
            }
            revalidate();
            repaint();
        }

        private JComponent createTopPanel(RefactoringSuggestionDialog.SuggestionInfo info) {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(10, 12, 8, 12));

            JPanel header = new JPanel(new BorderLayout(8, 0));
            JLabel title = new JLabel("[AntiCopyPaster] Refactoring Suggestion");
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

        private JComponent createBottomPanel(RefactoringSuggestionDialog.SuggestionInfo info) {
            JPanel wrapper = new JPanel();
            wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
            wrapper.setBorder(JBUI.Borders.empty(8, 12, 10, 12));

            wrapper.add(createMetadataPanel(info));
            wrapper.add(Box.createVerticalStrut(8));
            wrapper.add(createEditPanel());
            wrapper.add(Box.createVerticalStrut(8));
            wrapper.add(createActionPanel());
            return wrapper;
        }

        private JComponent createMetadataPanel(RefactoringSuggestionDialog.SuggestionInfo info) {
            JPanel wrapper = new JPanel();
            wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));

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
                addMetadataRow(details, row++, entry.getKey(), entry.getValue(), info);
            }

            JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JButton toggle = new JButton(detailsTitle(false, info));
            toggle.setBorderPainted(false);
            toggle.setContentAreaFilled(false);
            toggle.setFocusPainted(false);
            toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            toggle.addActionListener(e -> {
                boolean expanded = !details.isVisible();
                details.setVisible(expanded);
                toggle.setText(detailsTitle(expanded, info));
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

        private String detailsTitle(boolean expanded, RefactoringSuggestionDialog.SuggestionInfo info) {
            String arrow = expanded ? "v" : ">";
            String confidence = info.confidenceLabel.isBlank() ? "Verified" : info.confidenceLabel;
            return arrow + " Details & Provenance (Confidence: " + confidence + ")";
        }

        private void addMetadataRow(JPanel panel,
                                    int row,
                                    String name,
                                    String value,
                                    RefactoringSuggestionDialog.SuggestionInfo info) {
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

            JComponent valueComponent = locationComponent(name, value, info);
            panel.add(valueComponent, right);
        }

        private JComponent locationComponent(String name,
                                             String value,
                                             RefactoringSuggestionDialog.SuggestionInfo info) {
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

        private JComponent createEditPanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 6));
            JLabel label = new JLabel("Edit instructions for regeneration");
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            panel.add(label, BorderLayout.NORTH);

            editInstructionsArea = new JTextArea(3, 78);
            editInstructionsArea.setLineWrap(true);
            editInstructionsArea.setWrapStyleWord(true);
            editInstructionsArea.setFont(UIUtil.getLabelFont());
            JScrollPane scrollPane = new JScrollPane(editInstructionsArea);
            scrollPane.setPreferredSize(new Dimension(760, 92));
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        private JComponent createActionPanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 0));
            statusLabel = new JLabel("Review the verified refactoring and choose an action.");
            panel.add(statusLabel, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton helpButton = new JButton("?");
            helpButton.setIcon(Messages.getQuestionIcon());
            helpButton.setToolTipText("Help and feedback");
            helpButton.addActionListener(e -> openHelp());

            applyButton = new JButton("Apply");
            regenerateButton = new JButton("Regenerate");
            cancelButton = new JButton("Cancel");

            applyButton.addActionListener(e ->
                    complete(RefactoringSuggestionDialog.Decision.apply(), "Apply selected. Workflow is applying the change."));
            regenerateButton.addActionListener(e -> regenerateFromInstructions());
            cancelButton.addActionListener(e ->
                    complete(RefactoringSuggestionDialog.Decision.cancel(), "Cancelled. The suggestion remains visible for review."));

            buttons.add(helpButton);
            buttons.add(applyButton);
            buttons.add(regenerateButton);
            buttons.add(cancelButton);
            panel.add(buttons, BorderLayout.EAST);
            return panel;
        }

        private void regenerateFromInstructions() {
            String instructions = editInstructionsArea == null ? "" : editInstructionsArea.getText().trim();
            if (instructions.isBlank()) {
                if (statusLabel != null) {
                    statusLabel.setText("Add edit instructions before regenerating.");
                }
                return;
            }

            AntiCopyPasterUsageStatistics.getInstance(project).refactoringEdited();
            complete(
                    RefactoringSuggestionDialog.Decision.edit(instructions),
                    "Regenerate selected. Workflow is sending your edit instructions."
            );
        }

        private void complete(RefactoringSuggestionDialog.Decision decision, String statusText) {
            Consumer<RefactoringSuggestionDialog.Decision> consumer = decisionConsumer;
            decisionConsumer = null;
            setDecisionButtonsEnabled(false);
            if (statusLabel != null) {
                statusLabel.setText(statusText == null ? "Decision recorded." : statusText);
            }
            if (consumer != null) {
                consumer.accept(decision == null
                        ? RefactoringSuggestionDialog.Decision.cancel()
                        : decision);
            }
        }

        private void setDecisionButtonsEnabled(boolean enabled) {
            if (applyButton != null) applyButton.setEnabled(enabled);
            if (regenerateButton != null) regenerateButton.setEnabled(enabled);
            if (cancelButton != null) cancelButton.setEnabled(enabled);
            if (editInstructionsArea != null) editInstructionsArea.setEnabled(enabled);
        }

        private void openHelp() {
            AntiCopyPasterUsageStatistics.getInstance(project).refactoringHelpOpened();
            try {
                BrowserUtil.browse(HELP_URL);
            } catch (Throwable t) {
                Messages.showInfoMessage(
                        project,
                        "See README.md, section \"Refactoring Suggestion Panel\".",
                        "AntiCopyPaster Help"
                );
            }
        }

        private void disposePanel() {
            if (decisionConsumer != null) {
                complete(RefactoringSuggestionDialog.Decision.cancel(), "Panel closed.");
            }
            disposeCurrentDiff();
        }

        private void disposeCurrentDiff() {
            if (diffDisposable == null) {
                return;
            }
            try {
                Disposer.dispose(diffDisposable);
            } catch (Throwable ignored) {
            } finally {
                diffDisposable = null;
            }
        }
    }

    private static DiffRequestPanel createDiffPanel(Project project,
                                                    Disposable disposable,
                                                    RefactoringSuggestionDialog.SuggestionInfo info) {
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

    private static RefactoringSuggestionDialog.SuggestionInfo emptyInfo() {
        return new RefactoringSuggestionDialog.SuggestionInfo(
                "",
                null,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                -1,
                "",
                -1,
                "",
                "",
                new LinkedHashMap<>()
        );
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
            return IconLoader.getIcon("/icons/sdk_16.svg", RefactoringSuggestionPanel.class);
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

    static int showCloneOccurrence(Project project,
                                   int occurrenceIndex,
                                   int occurrenceTotal,
                                   String methodName,
                                   int lineStart,
                                   int lineEnd) {
        AtomicReference<Integer> out = new AtomicReference<>(Messages.CANCEL);
        Runnable ui = () -> {
            CloneOccurrenceDialog dialog = new CloneOccurrenceDialog(
                    project, occurrenceIndex, occurrenceTotal, methodName, lineStart, lineEnd
            );
            dialog.show();
            out.set(dialog.getResult());
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }
        return out.get();
    }

    static final class CloneOccurrenceDialog extends com.intellij.openapi.ui.DialogWrapper {

        private final int occurrenceIndex;
        private final int occurrenceTotal;
        private final String methodName;
        private final int lineStart;
        private final int lineEnd;
        private int result = Messages.CANCEL;

        CloneOccurrenceDialog(Project project,
                              int occurrenceIndex,
                              int occurrenceTotal,
                              String methodName,
                              int lineStart,
                              int lineEnd) {
            super(project, true);
            this.occurrenceIndex = occurrenceIndex;
            this.occurrenceTotal = occurrenceTotal;
            this.methodName = methodName == null ? "Unknown" : methodName;
            this.lineStart = lineStart;
            this.lineEnd = lineEnd;
            setTitle("Select Clone Occurrence");
            init();
        }

        @Override
        protected @Nullable javax.swing.JComponent createCenterPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(4, 8, 8, 8));

            // Icon + bold title
            JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            titleRow.add(new JLabel(Messages.getQuestionIcon()));
            JLabel titleLabel = new JLabel("Select Clone Occurrence");
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 1.5f));
            titleRow.add(titleLabel);
            panel.add(titleRow);

            panel.add(Box.createVerticalStrut(10));

            // "Clone occurrence: X/Y"
            JLabel countLabel = new JLabel("Clone occurrence: " + occurrenceIndex + "/" + occurrenceTotal);
            countLabel.setBorder(JBUI.Borders.emptyLeft(8));
            panel.add(countLabel);

            // "Occurrence N: MethodName [Lines: #-#]"
            JLabel detailLabel = new JLabel(
                    "Occurrence " + occurrenceIndex + ": " + methodName
                            + " [Lines: " + lineStart + "-" + lineEnd + "]"
            );
            detailLabel.setBorder(JBUI.Borders.emptyLeft(8));
            panel.add(detailLabel);

            panel.add(Box.createVerticalStrut(12));

            // Static description lines
            JLabel highlightedLabel = new JLabel("This clone occurrence has been highlighted.");
            highlightedLabel.setBorder(JBUI.Borders.emptyLeft(8));
            panel.add(highlightedLabel);

            JLabel questionLabel = new JLabel("Include this occurrence in the refactoring?");
            questionLabel.setBorder(JBUI.Borders.emptyLeft(8));
            panel.add(questionLabel);

            return panel;
        }

        @Override
        protected javax.swing.Action[] createActions() {
            javax.swing.Action cancelAction = new DialogWrapperAction("Cancel") {
                @Override
                protected void doAction(java.awt.event.ActionEvent e) {
                    result = Messages.CANCEL;
                    doCancelAction();
                }
            };

            javax.swing.Action excludeAction = new DialogWrapperAction("Exclude") {
                @Override
                protected void doAction(java.awt.event.ActionEvent e) {
                    result = Messages.NO;
                    close(OK_EXIT_CODE);
                }
            };

            javax.swing.Action includeAction = new DialogWrapperAction("Include") {
                {
                    putValue(DEFAULT_ACTION, Boolean.TRUE);
                }
                @Override
                protected void doAction(java.awt.event.ActionEvent e) {
                    result = Messages.YES;
                    close(OK_EXIT_CODE);
                }
            };

            return new javax.swing.Action[]{cancelAction, excludeAction, includeAction};
        }

        @Override
        protected void doOKAction() {
            // button actions close the dialog directly
        }

        int getResult() {
            return result;
        }
    }
}
