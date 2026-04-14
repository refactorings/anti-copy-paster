package org.jetbrains.research.anticopypaster.statistics;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stores clone workflow accept/cancel counters on the project level in the
 * {@code .idea/clone-plugin-usage.xml} file.
 */
@Service(Service.Level.PROJECT)
@State(name = "CloneUsageStatistics", storages = {@Storage("clone-plugin-usage.xml")})
public final class CloneUsageStatistics implements PersistentStateComponent<CloneUsageStatistics.PluginState> {
    private static final Pattern OPTION_PATTERN =
            Pattern.compile("<option\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]+)\"\\s*/?>");

    private PluginState usageState = new PluginState();
    private boolean legacyMigrationChecked = false;

    @Override
    public @Nullable PluginState getState() {
        return usageState;
    }

    @Override
    public void loadState(@NotNull PluginState state) {
        usageState = state;
        legacyMigrationChecked = false;
    }

    public static CloneUsageStatistics getInstance(Project project) {
        CloneUsageStatistics statistics = project.getService(CloneUsageStatistics.class);
        statistics.ensureLegacyMigration(project);
        return statistics;
    }

    public void refactoringAccepted() {
        usageState.refactoringAccepted();
    }

    public void refactoringCancelled() {
        usageState.refactoringCancelled();
    }

    private void ensureLegacyMigration(Project project) {
        if (legacyMigrationChecked) {
            return;
        }

        if (usageState.applyCount == 0 && usageState.cancelCount == 0) {
            PluginState legacyState = readLegacyCloneCounts(legacyUsageFile(project));
            usageState.applyCount = legacyState.applyCount;
            usageState.cancelCount = legacyState.cancelCount;
        }

        legacyMigrationChecked = true;
    }

    private static @Nullable Path legacyUsageFile(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        return Path.of(basePath, ".idea", "anticopypaster-plugin-usage.xml");
    }

    static PluginState readLegacyCloneCounts(@Nullable Path legacyUsageFile) {
        PluginState legacyState = new PluginState();

        if (legacyUsageFile == null || !Files.isRegularFile(legacyUsageFile)) {
            return legacyState;
        }

        try {
            String xml = Files.readString(legacyUsageFile, StandardCharsets.UTF_8);
            Matcher matcher = OPTION_PATTERN.matcher(xml);
            while (matcher.find()) {
                String optionName = matcher.group(1);
                int optionValue = parseNonNegativeInt(matcher.group(2));
                if ("applyCount".equals(optionName)) {
                    legacyState.applyCount = optionValue;
                } else if ("cancelCount".equals(optionName)) {
                    legacyState.cancelCount = optionValue;
                }
            }
        } catch (IOException ignored) {
            return legacyState;
        }

        return legacyState;
    }

    private static int parseNonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static class PluginState {
        public int applyCount = 0;
        public int cancelCount = 0;

        public void refactoringAccepted() {
            applyCount += 1;
        }

        public void refactoringCancelled() {
            cancelCount += 1;
        }
    }
}
