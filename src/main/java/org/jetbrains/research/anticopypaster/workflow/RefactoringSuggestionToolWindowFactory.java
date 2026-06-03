package org.jetbrains.research.anticopypaster.workflow;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

public final class RefactoringSuggestionToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        try {
            if (toolWindow.getAnchor() != ToolWindowAnchor.RIGHT) {
                toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null);
            }
        } catch (Throwable ignored) {
        }
        RefactoringSuggestionPanel.installPersistentPanel(project, toolWindow);
    }
}
