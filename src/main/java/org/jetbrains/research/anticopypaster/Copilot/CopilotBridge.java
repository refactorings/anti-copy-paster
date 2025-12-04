package org.jetbrains.research.anticopypaster.Copilot;

import com.intellij.ide.DataManager;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFocusManager;

import java.awt.datatransfer.StringSelection;
import java.util.Arrays;
import java.util.List;

/**
 * A tiny utility class that bridges AntiCopyPaster with the GitHub Copilot JetBrains plugin.
 *
 * Responsibilities:
 * - Copy a prepared prompt to the clipboard.
 * - Open Copilot Chat UI by invoking the Copilot action.
 * - Show a non-modal notification to guide the user to paste and send.
 *
 * Notes:
 * - This class intentionally does NOT send messages into Copilot programmatically.
 *   The Copilot plugin does not expose a public "send message" API.
 * - Action IDs may change across Copilot versions. We try a small set of known IDs first
 *   and then attempt a best-effort discovery.
 * - Define a Notification Group with id "AntiCopyPaster" in plugin.xml, or create it at runtime.
 */
public final class CopilotBridge {

    /**
     * Known Copilot Chat action IDs. These may vary between Copilot plugin versions.
     * We try them in order; if none is found, we will attempt a best-effort discovery.
     */
    private static final List<String> CHAT_ACTION_IDS = Arrays.asList(
            "Github.Copilot.OpenChat",
            "GitHub.Copilot.Chat",
            "Github.Copilot.Chat"
    );

    private CopilotBridge() {
        // Utility class; do not instantiate.
    }

    /**
     * Copies the given prompt to the system clipboard, focuses the active editor component,
     * and opens the Copilot Chat tool window by invoking the Copilot action.
     *
     * This is the primary entry point you should call immediately after a paste event.
     *
     * @param project the current IntelliJ project; must not be {@code null}
     * @param editor  the active editor associated with the paste; must not be {@code null}
     * @param prompt  the prompt text to copy to the clipboard for the user to paste in Copilot Chat
     */
    public static void openChatWithClipboardPrompt(Project project, Editor editor, String prompt) {
        if (project == null || editor == null) {
            return; // Nothing to do without valid context.
        }

        // 1) Put the prompt on the clipboard so the user can paste it into Copilot Chat.
        if (prompt != null && !prompt.isBlank()) {
            CopyPasteManager.getInstance().setContents(new StringSelection(prompt));
        }

        // 2) Ensure the editor has focus before we invoke Copilot.
        IdeFocusManager.getGlobalInstance().requestFocus(editor.getContentComponent(), true);

        // 3) Invoke the Copilot Chat action on the EDT, non-modally.
        ApplicationManager.getApplication().invokeLater(() -> {
            ActionManager actionManager = ActionManager.getInstance();
            AnAction chatAction = findChatAction(actionManager);

            if (chatAction == null) {
                notify(project,
                        "Copilot Chat action not found. Please install/enable or update the GitHub Copilot plugin.",
                        NotificationType.WARNING);
                return;
            }

            DataContext dataContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
            AnActionEvent event = AnActionEvent.createFromDataContext(ActionPlaces.EDITOR_POPUP, null, dataContext);

            // Open the Copilot Chat UI.
            chatAction.actionPerformed(event);

            // Inform the user what to do next.
            notify(project,
                    "Copilot Chat opened. The detection & refactoring prompt is on your clipboard — paste and send.",
                    NotificationType.INFORMATION);
        }, ModalityState.NON_MODAL);
    }

    /**
     * Attempts to resolve the Copilot Chat action.
     *
     * Strategy:
     * 1. Try a list of known action IDs used by Copilot across versions.
     * 2. If none is found, perform a best-effort name-based discovery.
     *
     * @param actionManager the IntelliJ ActionManager
     * @return a resolved {@link AnAction} for Copilot Chat, or {@code null} if not found
     */
    private static AnAction findChatAction(ActionManager actionManager) {
        // Try known IDs first.
        for (String id : CHAT_ACTION_IDS) {
            AnAction a = actionManager.getAction(id);
            if (a != null) {
                return a;
            }
        }

        // Fallback: best-effort discovery by scanning action IDs for keywords.
        for (String id : actionManager.getActionIdList("")) {
            String lower = id.toLowerCase();
            if (lower.contains("copilot") && lower.contains("chat")) {
                AnAction a = actionManager.getAction(id);
                if (a != null) {
                    return a;
                }
            }
        }
        return null;
    }

    /**
     * Shows a non-modal balloon notification under the notification group "AntiCopyPaster".
     *
     * To configure this group, add in plugin.xml:
     *   <notificationGroup id="AntiCopyPaster" displayType="BALLOON" isLogByDefault="false"/>
     *
     * @param project the current project
     * @param message the message to display
     * @param type    the notification type (INFO, WARNING, ERROR)
     */
    private static void notify(Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("AntiCopyPaster")
                .createNotification("Copilot", message, type)
                .notify(project);
    }

    /**
     * Utility helper that tells whether any Copilot Chat action seems available.
     *
     * This can be used by callers to decide whether to show Copilot-related UI.
     *
     * @return {@code true} if a Copilot Chat action can be resolved; {@code false} otherwise
     */
    public static boolean isCopilotChatAvailable() {
        ActionManager am = ActionManager.getInstance();
        return findChatAction(am) != null;
    }
}
