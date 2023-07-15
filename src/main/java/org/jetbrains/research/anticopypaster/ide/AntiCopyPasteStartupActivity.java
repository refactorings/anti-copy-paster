package org.jetbrains.research.anticopypaster.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics;

import static org.jetbrains.research.anticopypaster.statistics.AntiCopyPasterUsageStatistics.TRANSMISSION_INTERVAL;

public class AntiCopyPasteStartupActivity implements StartupActivity.DumbAware {

    // TODO: Update implementation for 2023.1+

    @Override
    public void runActivity(@NotNull Project project) {
        ProjectSettingsState settings = ProjectSettingsState.getInstance(project);
        if (settings.statisticsUsername == null || settings.statisticsUsername.isEmpty() || !settings.statisticsPasswordIsSet)
            return;

        AntiCopyPasterUsageStatistics.PluginState usageState =
                AntiCopyPasterUsageStatistics.getInstance(project).getState();
        long now = System.currentTimeMillis();
        if (usageState != null && now - usageState.lastTransmissionTime >= TRANSMISSION_INTERVAL) {
            usageState.saveToMongoDB(project);
            usageState.lastTransmissionTime = now;
        }
    }
}
