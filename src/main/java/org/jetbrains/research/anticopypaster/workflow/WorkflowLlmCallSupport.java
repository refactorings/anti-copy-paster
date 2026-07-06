package org.jetbrains.research.anticopypaster.workflow;

import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.logStage;
import static org.jetbrains.research.anticopypaster.workflow.WorkflowUiSupport.showNotification;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import java.util.function.Consumer;
import org.jetbrains.research.anticopypaster.llm.LlmClient;

final class WorkflowLlmCallSupport {
    private WorkflowLlmCallSupport() {}

    static String callDetection(LlmClient llm, String prompt, Consumer<String> viewer, Project project) {
        String stageLabel = inferDetectionLabel(prompt);
        try {
            logStage(viewer, "LLM_DETECTION", "[" + stageLabel + "] request sent");
            String resp = llm == null ? "" : llm.complete(prompt);
            String full = resp == null ? "" : resp;
            String preview = resp == null ? "null" : resp.strip();
            if (preview.length() > 300) preview = preview.substring(0, 300) + "...";
            logStage(viewer, "LLM_DETECTION", "[" + stageLabel + "] response preview: " + preview.replace("\n", "\\n"));
            logStage(viewer, "LLM_DETECTION", "[" + stageLabel + "] full response begin");
            writeFullResponse(viewer, "LLM_DETECTION", stageLabel, full);
            logStage(viewer, "LLM_DETECTION", "[" + stageLabel + "] full response end");
            return full;
        } catch (Exception e) {
            logStage(viewer, "LLM_DETECTION", "[" + stageLabel + "] exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            showNotification(project, "[Clone] LLM call failed: " + e.getMessage(), NotificationType.ERROR);
            return "";
        }
    }

    static String callRefactor(LlmClient llm, String prompt, Consumer<String> viewer, Project project) {
        String stageLabel = inferRefactorLabel(prompt);
        try {
            String resp = llm == null ? "" : llm.complete(prompt);
            String full = resp == null ? "" : resp;
            String preview = full.strip();
            if (preview.length() > 300) preview = preview.substring(0, 300) + "...";
            logStage(viewer, "LLM_REFACTOR", "[" + stageLabel + "] request sent");
            logStage(viewer, "LLM_REFACTOR", "[" + stageLabel + "] response preview: " + preview.replace("\n", "\\n"));
            logStage(viewer, "LLM_REFACTOR", "[" + stageLabel + "] full response begin");
            writeFullResponse(viewer, "LLM_REFACTOR", stageLabel, full);
            logStage(viewer, "LLM_REFACTOR", "[" + stageLabel + "] full response end");
            return full;
        } catch (Exception e) {
            logStage(viewer, "LLM_REFACTOR", "[" + stageLabel + "] exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            showNotification(project, "[Clone] LLM refactor call failed: " + e.getMessage(), NotificationType.ERROR);
            return "";
        }
    }

    static String callUsefulness(LlmClient llm, String label, String prompt, Consumer<String> viewer, Project project) {
        String stageLabel = switch (label == null ? "" : label) {
            case "P1" -> "Usefulness Panelist 1";
            case "P2" -> "Usefulness Panelist 2";
            case "P3" -> "Usefulness Panelist 3";
            case "CURATOR" -> "Usefulness Curator";
            default -> "Usefulness";
        };
        try {
            logStage(viewer, "LLM_USEFULNESS", "[" + stageLabel + "] request sent");
            String resp = llm == null ? "" : llm.complete(prompt);
            String full = resp == null ? "" : resp;
            String preview = full.strip();
            if (preview.length() > 300) preview = preview.substring(0, 300) + "...";
            logStage(viewer, "LLM_USEFULNESS", "[" + stageLabel + "] response preview: " + preview.replace("\n", "\\n"));
            logStage(viewer, "LLM_USEFULNESS", "[" + stageLabel + "] full response begin");
            writeFullResponse(viewer, "LLM_USEFULNESS", stageLabel, full);
            logStage(viewer, "LLM_USEFULNESS", "[" + stageLabel + "] full response end");
            return full;
        } catch (Exception e) {
            logStage(viewer, "LLM_USEFULNESS", "[" + stageLabel + "] exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            showNotification(project, "[Clone] LLM usefulness call failed: " + e.getMessage(), NotificationType.ERROR);
            return "";
        }
    }

    private static void writeFullResponse(Consumer<String> viewer, String stage, String stageLabel, String full) {
        if (viewer == null) return;
        StringBuilder block = new StringBuilder();
        block.append("[").append(stage).append("] [").append(stageLabel).append("] output").append("\n");
        block.append(full == null ? "" : full);
        if (full == null || !full.endsWith("\n")) {
            block.append("\n");
        }
        viewer.accept(block.toString());
    }

    private static String inferDetectionLabel(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "Detection";
        }
        if (prompt.contains("Detection Panelist 1 (P1)")) {
            return "Detection Panelist 1";
        }
        if (prompt.contains("Detection Panelist 2 (P2)")) {
            return "Detection Panelist 2";
        }
        if (prompt.contains("Detection Panelist 3 (P3)")) {
            return "Detection Panelist 3";
        }
        if (prompt.contains("You are the detection curator.")) {
            return "Detection Curator";
        }
        return "Detection";
    }

    private static String inferRefactorLabel(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "Refactoring";
        }
        if (prompt.contains("Refactoring Panelist 1 (P1)")) {
            return "Refactoring Panelist 1";
        }
        if (prompt.contains("Refactoring Panelist 2 (P2)")) {
            return "Refactoring Panelist 2";
        }
        if (prompt.contains("Refactoring Panelist 3 (P3)")) {
            return "Refactoring Panelist 3";
        }
        if (prompt.contains("You are the refactoring curator.")) {
            return "Refactoring Curator";
        }
        return "Refactoring";
    }
}
