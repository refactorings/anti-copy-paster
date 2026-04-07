package org.jetbrains.research.anticopypaster.statistics;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Stores information about the plugin usage on the project level in the ./idea/anticopypaster-plugin.xml file.
 */
@Service(Service.Level.PROJECT)
@State(name = "AntiCopyPasterUsageStatistics", storages = {@Storage("anticopypaster-plugin-usage.xml")})
public final class AntiCopyPasterUsageStatistics implements PersistentStateComponent<AntiCopyPasterUsageStatistics.PluginState> {

    /** Specifies the minimum time between usage statistics transmissions to the server. */
    public static final long TRANSMISSION_INTERVAL = TimeUnit.MILLISECONDS.convert(3, TimeUnit.DAYS);

    private PluginState usageState = new PluginState();

    @Override
    public @Nullable PluginState getState() {
        usageState.normalize();
        return usageState;
    }

    @Override
    public void loadState(@NotNull PluginState state) {
        usageState = state;
    }

    public static AntiCopyPasterUsageStatistics getInstance(Project project) {
        return project.getService(AntiCopyPasterUsageStatistics.class);
    }

    public void notificationShown() {
        usageState.notification();
    }

    public void extractMethodApplied() {
        usageState.extractMethodApplied();
    }

    public void extractMethodRejected() {
        usageState.extractMethodRejected();
    }

    public void onCopy() {
        usageState.onCopy();
    }

    public void onPaste() {
        usageState.onPaste();
    }

    public void refactoringApplied() {
        usageState.refactoringApplied();
    }

    public void refactoringCancelled() {
        usageState.refactoringCancelled();
    }

    public static class PluginState {
        public int notificationCount = 0;
        public int extractMethodAppliedCount = 0;
        public int extractMethodRejectedCount = 0;
        public int copyCount = 0;
        public int pasteCount = 0;
        public Integer applyCount;
        public Integer cancelCount;
        public long lastTransmissionTime = 0;

        public void notification() {
            notificationCount += 1;
        }

        public void extractMethodApplied() {
            extractMethodAppliedCount += 1;
        }

        public void extractMethodRejected() {
            extractMethodRejectedCount += 1;
        }

        public void onCopy() {
            copyCount += 1;
        }

        public void onPaste() {
            pasteCount += 1;
        }

        public void refactoringApplied() {
            applyCount = getApplyCount() + 1;
        }

        public void refactoringCancelled() {
            cancelCount = getCancelCount() + 1;
        }

        public int getApplyCount() {
            return applyCount == null ? 0 : applyCount;
        }

        public int getCancelCount() {
            return cancelCount == null ? 0 : cancelCount;
        }

        public void normalize() {
            applyCount = getApplyCount();
            cancelCount = getCancelCount();
        }

        public void saveToMongoDB(Project project) {
            AntiCopyPasterTelemetry.saveStatistics(
                    project,
                    notificationCount,
                    extractMethodAppliedCount,
                    extractMethodRejectedCount,
                    copyCount,
                    pasteCount,
                    getApplyCount(),
                    getCancelCount()
            );
        }
    }
}
