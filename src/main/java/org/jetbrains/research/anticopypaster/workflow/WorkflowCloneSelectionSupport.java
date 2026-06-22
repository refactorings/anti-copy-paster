package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowReasonSupport.previewOneLine;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showNotification;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.research.anticopypaster.agents.detection;
import org.jetbrains.research.anticopypaster.metrics.MetricCalculator;
import org.jetbrains.research.anticopypaster.metrics.features.FeaturesVector;

final class WorkflowCloneSelectionSupport {

    private WorkflowCloneSelectionSupport() {}

    static final class CloneSelectionOption {
        final detection.DetectedClone clone;
        final String label;
        final String details;

        CloneSelectionOption(detection.DetectedClone clone, String label, String details) {
            this.clone = clone;
            this.label = label == null ? "<unknown clone>" : label;
            this.details = details == null ? "" : details;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final class CloneRangeSelectionOption {
        final int rangeIndex;
        final detection.CloneRange range;
        final String label;
        final String details;
        final String snippet;
        final String uniqueKey;

        CloneRangeSelectionOption(int rangeIndex,
                                  detection.CloneRange range,
                                  String label,
                                  String details,
                                  String snippet,
                                  String uniqueKey) {
            this.rangeIndex = rangeIndex;
            this.range = range;
            this.label = label == null ? "<unknown occurrence>" : label;
            this.details = details == null ? "" : details;
            this.snippet = snippet == null ? "" : snippet;
            this.uniqueKey = uniqueKey == null ? "" : uniqueKey;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static FeaturesVector calculateCloneOccurrenceFeatures(Project project,
                                                           VirtualFile vf,
                                                           String fileSource,
                                                           detection.CloneRange range,
                                                           String code) {
        AtomicReference<FeaturesVector> out = new AtomicReference<>();
        try {
            ApplicationManager.getApplication().runReadAction(() -> {
                try {
                    String snippet = firstNonBlank(code, sliceSourceByCloneRange(fileSource, range));
                    if (snippet.isBlank()) return;

                    com.intellij.psi.PsiMethod method = WorkflowMethodSnapshotSupport.findMethodContainingCloneRange(project, vf, range);
                    if (method == null) {
                        method = WorkflowCloneRangeSupport.findMethodForCloneSnippet(project, vf, fileSource, snippet, range);
                    }
                    if (method == null) return;

                    int[] methodLines = WorkflowCloneRangeSupport.elementLineRange(project, vf, method);
                    if (methodLines == null && range != null) {
                        methodLines = new int[]{range.startLine, range.endLine};
                    }
                    if (methodLines == null) return;

                    out.set(new MetricCalculator(method, snippet, methodLines[0], methodLines[1]).getFeaturesVector());
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        return out.get();
    }

    static detection.DetectedClone chooseCloneToRefactor(Project project,
                                                         VirtualFile vf,
                                                         java.util.List<detection.DetectedClone> clones,
                                                         Consumer<String> viewer) {
        if (clones == null || clones.isEmpty()) return null;

        java.util.ArrayList<CloneSelectionOption> options = new java.util.ArrayList<>();
        int ordinal = 1;
        for (detection.DetectedClone clone : clones) {
            if (clone == null) continue;
            CloneSelectionOption option = buildCloneSelectionOption(project, vf, clone, ordinal++);
            options.add(option);
            logStage(viewer, "DETECTION", "clone candidate: " + option.label);
        }

        if (options.isEmpty()) return null;
        if (options.size() == 1) {
            CloneSelectionOption only = options.get(0);
            logStage(viewer, "DETECTION", "single clone group after merge; skipping group chooser: " + only.label);
            return only.clone;
        }
        for (int i = 0; i < options.size(); i++) {
            CloneSelectionOption option = options.get(i);
            previewCloneRangeInEditor(project, vf, getRepresentativeRange(option.clone));
            int choice = showSequentialChoiceDialog(
                    project,
                    "Refactor Clone Candidate",
                    buildSequentialClonePrompt(option, i + 1, options.size()),
                    "Refactor This Clone",
                    (i + 1) < options.size() ? "Next Clone" : "Skip",
                    "Cancel"
            );
            if (choice == Messages.CANCEL) {
                logStage(viewer, "DETECTION", "clone selection cancelled");
                return null;
            }
            if (choice == Messages.YES) {
                logStage(viewer, "DETECTION", "selected clone: " + option.label);
                return option.clone;
            }
        }

        logStage(viewer, "DETECTION", "no clone candidate selected");
        return null;
    }

    static detection.DetectedClone chooseCloneRangesToRefactor(Project project,
                                                               VirtualFile vf,
                                                               String fileSource,
                                                               detection.DetectedClone clone,
                                                               Consumer<String> viewer) {
        if (clone == null || clone.ranges == null || clone.ranges.size() <= 1) return clone;

        java.util.LinkedHashMap<String, CloneRangeSelectionOption> uniqueOptions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < clone.ranges.size(); i++) {
            CloneRangeSelectionOption option = buildCloneRangeSelectionOption(project, vf, fileSource, clone, i);
            if (option == null) continue;
            if (uniqueOptions.containsKey(option.uniqueKey)) continue;
            uniqueOptions.put(option.uniqueKey, option);
            logStage(viewer, "DETECTION", "clone range candidate: " + option.label);
        }
        java.util.ArrayList<CloneRangeSelectionOption> options = new java.util.ArrayList<>(uniqueOptions.values());
        if (options.size() <= 1) return clone;

        java.util.ArrayList<CloneRangeSelectionOption> selected = new java.util.ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            CloneRangeSelectionOption option = options.get(i);
            previewCloneRangeInEditor(project, vf, option.range);
            int choice = showSequentialChoiceDialog(
                    project,
                    "Select Clone Occurrence",
                    buildSequentialRangePrompt(option, i + 1, options.size(), selected.size()),
                    "Include",
                    "Exclude",
                    "Cancel"
            );
            if (choice == Messages.CANCEL) {
                logStage(viewer, "DETECTION", "clone range selection cancelled");
                return null;
            }
            if (choice == Messages.YES) {
                selected.add(option);
            }
        }

        if (selected.size() < 2) {
            showNotification(project,
                    "[Clone] Need at least two clone occurrences to refactor, but only " + selected.size() + " were selected.",
                    NotificationType.WARNING);
            logStage(viewer, "DETECTION", "clone range selection rejected: selected=" + selected.size());
            return null;
        }

        detection.DetectedClone selectedClone = buildSelectedCloneFromRanges(clone, selected, fileSource);
        logStage(viewer, "DETECTION", "selected clone ranges: " + summarizeSelectedRangeLabels(selected));
        return selectedClone;
    }

    static CloneSelectionOption buildCloneSelectionOption(Project project,
                                                          VirtualFile vf,
                                                          detection.DetectedClone clone,
                                                          int ordinal) {
        java.util.List<WorkflowMethodSnapshotSupport.CloneMethodSnapshot> snapshots =
                WorkflowMethodSnapshotSupport.captureCloneMethodSnapshots(project, vf, clone, null);
        String methodSummary = WorkflowMethodSnapshotSupport.summarizeCloneMethods(snapshots);
        String rangeSummary = summarizeCloneRanges(clone == null ? null : clone.ranges);

        String label = "Clone " + ordinal;
        if (!methodSummary.isBlank()) {
            label += ": " + methodSummary;
        }
        if (!rangeSummary.isBlank()) {
            label += " [" + rangeSummary + "]";
        }

        java.util.List<String> cloneCodes = getDetectedCloneCodes(clone, null);
        String[] ab = extractCloneCodeABFromReason(clone == null ? null : clone.reason);
        String cloneCodeA = !cloneCodes.isEmpty() ? firstNonBlank(cloneCodes.get(0), ab[0]) : firstNonBlank(clone == null ? null : clone.cloneCodeA, ab[0]);
        String cloneCodeB = cloneCodes.size() > 1 ? firstNonBlank(cloneCodes.get(1), ab[1]) : firstNonBlank(clone == null ? null : clone.cloneCodeB, ab[1]);

        StringBuilder details = new StringBuilder();
        details.append("ID: ").append(clone == null || clone.id == null || clone.id.isBlank() ? "<unknown>" : clone.id).append("\n");
        if (!methodSummary.isBlank()) {
            details.append("Methods: ").append(methodSummary).append("\n");
        }
        if (!rangeSummary.isBlank()) {
            details.append("Ranges: ").append(rangeSummary).append("\n");
        }
        if (clone != null && clone.refactorType != null && !clone.refactorType.isBlank()) {
            details.append("Suggested refactor type: ").append(clone.refactorType).append("\n");
        }

        String reasonPreview = previewOneLine(clone == null ? "" : clone.reason, 320);
        if (reasonPreview != null && !reasonPreview.isBlank()) {
            details.append("\nReason preview:\n").append(reasonPreview).append("\n");
        }

        String codeAPreview = previewCodeForSelection(cloneCodeA);
        if (!codeAPreview.isBlank()) {
            details.append("\nClone A preview:\n").append(codeAPreview).append("\n");
        }

        String codeBPreview = previewCodeForSelection(cloneCodeB);
        if (!codeBPreview.isBlank()) {
            details.append("\nClone B preview:\n").append(codeBPreview).append("\n");
        }

        return new CloneSelectionOption(clone, label, details.toString().trim());
    }

    static String summarizeCloneRanges(java.util.List<detection.CloneRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return "";
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (detection.CloneRange range : ranges) {
            if (range == null) continue;
            parts.add(range.startLine + "-" + range.endLine);
        }
        return String.join(", ", parts);
    }

    static String previewCodeForSelection(String code) {
        if (code == null || code.isBlank()) return "";
        String normalized = code.replace("\r\n", "\n").replace("\r", "\n").strip();
        if (normalized.length() > 800) {
            normalized = normalized.substring(0, 800) + "\n...<truncated>...";
        }
        return normalized;
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return "";
    }

    static String buildSequentialClonePrompt(CloneSelectionOption option, int ordinal, int total) {
        if (option == null) return "Refactor this clone?";
        StringBuilder sb = new StringBuilder();
        sb.append("Clone candidate ").append(ordinal).append("/").append(total).append("\n\n");
        sb.append(option.label);
        sb.append("\n\nThe clone code has been highlighted in the editor.");
        sb.append("\n\nDo you want to refactor this clone?");
        return sb.toString();
    }

    static String buildSequentialRangePrompt(CloneRangeSelectionOption option,
                                             int ordinal,
                                             int total,
                                             int currentSelectedCount) {
        if (option == null) return "Include this clone occurrence?";
        StringBuilder sb = new StringBuilder();
        sb.append("Clone occurrence ").append(ordinal).append("/").append(total).append("\n");
        sb.append("Currently selected: ").append(currentSelectedCount).append("\n\n");
        sb.append(option.label);
        sb.append("\n\nThis clone occurrence has been highlighted in the editor.");
        sb.append("\n\nInclude this clone occurrence in the refactoring?");
        return sb.toString();
    }

    static int showSequentialChoiceDialog(Project project,
                                          String title,
                                          String message,
                                          String yesText,
                                          String noText,
                                          String cancelText) {
        final java.util.concurrent.atomic.AtomicInteger out = new java.util.concurrent.atomic.AtomicInteger(Messages.CANCEL);
        Runnable ui = () -> {
            try {
                int choice = Messages.showYesNoCancelDialog(
                        project,
                        message == null ? "" : message,
                        title == null ? "Clone Refactoring" : title,
                        yesText == null ? "Yes" : yesText,
                        noText == null ? "No" : noText,
                        cancelText == null ? "Cancel" : cancelText,
                        Messages.getQuestionIcon()
                );
                out.set(choice);
            } catch (Throwable ignored) {
                out.set(Messages.CANCEL);
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }
        return out.get();
    }

    static detection.CloneRange getRepresentativeRange(detection.DetectedClone clone) {
        if (clone == null || clone.ranges == null || clone.ranges.isEmpty()) return null;
        return clone.ranges.get(0);
    }

    static void previewCloneRangeInEditor(Project project,
                                          VirtualFile vf,
                                          detection.CloneRange range) {
        if (project == null || project.isDisposed() || vf == null || range == null) return;
        Runnable ui = () -> {
            try {
                OpenFileDescriptor descriptor = new OpenFileDescriptor(project, vf, Math.max(0, range.startLine - 1), 0);
                Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                if (editor == null) return;

                Document doc = editor.getDocument();
                if (doc == null || doc.getLineCount() <= 0) return;

                int startLine = Math.max(0, Math.min(range.startLine - 1, doc.getLineCount() - 1));
                int endLine = Math.max(startLine, Math.min(range.endLine - 1, doc.getLineCount() - 1));
                int startOffset = doc.getLineStartOffset(startLine);
                int endOffset = doc.getLineEndOffset(endLine);

                editor.getSelectionModel().setSelection(startOffset, endOffset);
                editor.getCaretModel().moveToOffset(startOffset);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            } catch (Throwable ignored) {
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            ui.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(ui);
        }
    }

    static java.util.List<String> getDetectedCloneCodes(detection.DetectedClone clone, String fileSource) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (clone == null) return out;

        if (clone.cloneCodes != null) {
            for (String code : clone.cloneCodes) {
                out.add(code == null ? "" : code);
            }
        }

        if (out.isEmpty()) {
            if (clone.cloneCodeA != null && !clone.cloneCodeA.isBlank()) out.add(clone.cloneCodeA);
            if (clone.cloneCodeB != null && !clone.cloneCodeB.isBlank()) out.add(clone.cloneCodeB);
        }

        int rangeCount = clone.ranges == null ? 0 : clone.ranges.size();
        while (out.size() < rangeCount) {
            detection.CloneRange range = (clone.ranges == null || out.size() >= clone.ranges.size()) ? null : clone.ranges.get(out.size());
            out.add(range == null ? "" : sliceSourceByCloneRange(fileSource, range));
        }
        return out;
    }

    static String getDetectedCloneCodeAt(detection.DetectedClone clone,
                                         int rangeIndex,
                                         String fileSource) {
        java.util.List<String> codes = getDetectedCloneCodes(clone, fileSource);
        if (rangeIndex >= 0 && rangeIndex < codes.size()) {
            return codes.get(rangeIndex) == null ? "" : codes.get(rangeIndex);
        }
        return "";
    }

    static CloneRangeSelectionOption buildCloneRangeSelectionOption(Project project,
                                                                    VirtualFile vf,
                                                                    String fileSource,
                                                                    detection.DetectedClone clone,
                                                                    int rangeIndex) {
        if (clone == null || clone.ranges == null || rangeIndex < 0 || rangeIndex >= clone.ranges.size()) return null;
        detection.CloneRange range = clone.ranges.get(rangeIndex);
        if (range == null) return null;

        com.intellij.psi.PsiMethod method = WorkflowMethodSnapshotSupport.findMethodContainingLine(project, vf, range.startLine);
        if (method == null) method = WorkflowMethodSnapshotSupport.findMethodContainingLine(project, vf, range.endLine);
        String displayName = method == null ? "<unknown method>" : WorkflowMethodSnapshotSupport.buildMethodDisplayName(method);
        String uniqueKey = range.startLine + ":" + range.endLine;

        String snippet = firstNonBlank(getDetectedCloneCodeAt(clone, rangeIndex, fileSource), sliceSourceByCloneRange(fileSource, range));
        String label = "Occurrence " + (rangeIndex + 1) + ": " + displayName + " [" + range.startLine + "-" + range.endLine + "]";
        StringBuilder details = new StringBuilder();
        details.append("Method: ").append(displayName).append("\n");
        details.append("Lines: ").append(range.startLine).append("-").append(range.endLine).append("\n");

        String snippetPreview = previewCodeForSelection(snippet);
        if (!snippetPreview.isBlank()) {
            details.append("\nCode preview:\n").append(snippetPreview);
        }

        return new CloneRangeSelectionOption(rangeIndex, range, label, details.toString().trim(), snippet, uniqueKey);
    }

    static String buildRangeSelectionDetails(java.util.List<CloneRangeSelectionOption> selected) {
        if (selected == null || selected.isEmpty()) {
            return "No ranges selected.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Selected occurrences: ").append(selected.size()).append("\n\n");
        for (CloneRangeSelectionOption option : selected) {
            if (option == null) continue;
            sb.append(option.label).append("\n");
            if (option.details != null && !option.details.isBlank()) {
                sb.append(option.details).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    static detection.DetectedClone buildSelectedCloneFromRanges(detection.DetectedClone original,
                                                                java.util.List<CloneRangeSelectionOption> selected,
                                                                String fileSource) {
        detection.DetectedClone clone = new detection.DetectedClone();
        clone.id = (original == null || original.id == null || original.id.isBlank())
                ? "selected_clone"
                : original.id + "_selected";
        clone.refactorType = original == null ? null : original.refactorType;
        clone.reason = original == null ? null : original.reason;
        clone.ranges = new java.util.ArrayList<>();
        clone.cloneCodes = new java.util.ArrayList<>();

        java.util.ArrayList<CloneRangeSelectionOption> ordered = new java.util.ArrayList<>(selected);
        ordered.sort(java.util.Comparator.comparingInt(o -> o.rangeIndex));

        for (CloneRangeSelectionOption option : ordered) {
            if (option == null || option.range == null) continue;
            detection.CloneRange copy = new detection.CloneRange();
            copy.startLine = option.range.startLine;
            copy.endLine = option.range.endLine;
            clone.ranges.add(copy);
            clone.cloneCodes.add(firstNonBlank(option.snippet, sliceSourceByCloneRange(fileSource, option.range)));
        }

        clone.cloneCodeA = clone.cloneCodes.isEmpty() ? "" : clone.cloneCodes.get(0);
        clone.cloneCodeB = clone.cloneCodes.size() > 1 ? clone.cloneCodes.get(1) : "";
        return clone;
    }

    static detection.CloneRange getRangeAt(java.util.List<CloneRangeSelectionOption> options, int index) {
        if (options == null || index < 0 || index >= options.size()) return null;
        CloneRangeSelectionOption option = options.get(index);
        return option == null ? null : option.range;
    }

    static String summarizeSelectedRangeLabels(java.util.List<CloneRangeSelectionOption> selected) {
        if (selected == null || selected.isEmpty()) return "";
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (CloneRangeSelectionOption option : selected) {
            if (option == null || option.label == null || option.label.isBlank()) continue;
            labels.add(option.label);
        }
        return String.join(" | ", labels);
    }

    static String sliceSourceByCloneRange(String fileSource, detection.CloneRange range) {
        try {
            if (fileSource == null || fileSource.isBlank() || range == null) return "";
            String[] lines = fileSource.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
            if (lines.length == 0) return "";
            int start = Math.max(1, range.startLine);
            int end = Math.max(start, range.endLine);
            int startIdx = Math.min(lines.length, start) - 1;
            int endIdx = Math.min(lines.length, end) - 1;
            if (startIdx < 0 || endIdx < startIdx) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i <= endIdx; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().strip();
        } catch (Throwable t) {
            return "";
        }
    }

    static String[] extractCloneCodeABFromReason(String reason) {
        try {
            if (reason == null || reason.isBlank()) return new String[]{"", ""};
            Matcher ma = Pattern.compile("(?s)\\[CLONE_CODE_A\\](.*?)\\[/CLONE_CODE_A\\]").matcher(reason);
            Matcher mb = Pattern.compile("(?s)\\[CLONE_CODE_B\\](.*?)\\[/CLONE_CODE_B\\]").matcher(reason);
            String a = ma.find() ? ma.group(1) : "";
            String b = mb.find() ? mb.group(1) : "";
            return new String[]{a == null ? "" : a.strip(), b == null ? "" : b.strip()};
        } catch (Throwable t) {
            return new String[]{"", ""};
        }
    }
}
