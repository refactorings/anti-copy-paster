package org.jetbrains.research.anticopypaster.statistics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CloneUsageStatisticsTest {

    @Test
    void refactoringDecisionCountersUpdateApplyAndCancelCounts() {
        CloneUsageStatistics statistics = new CloneUsageStatistics();

        statistics.refactoringAccepted();
        statistics.refactoringAccepted();
        statistics.refactoringCancelled();

        CloneUsageStatistics.PluginState state = statistics.getState();
        assertNotNull(state);
        assertEquals(2, state.applyCount);
        assertEquals(1, state.cancelCount);
    }

    @Test
    void loadStateKeepsPersistedApplyAndCancelCountsAndContinuesIncrementing() {
        CloneUsageStatistics statistics = new CloneUsageStatistics();
        CloneUsageStatistics.PluginState persisted = new CloneUsageStatistics.PluginState();
        persisted.applyCount = 4;
        persisted.cancelCount = 3;

        statistics.loadState(persisted);
        statistics.refactoringAccepted();
        statistics.refactoringCancelled();

        CloneUsageStatistics.PluginState state = statistics.getState();
        assertNotNull(state);
        assertEquals(5, state.applyCount);
        assertEquals(4, state.cancelCount);
    }

    @Test
    void readLegacyCloneCountsParsesApplyAndCancelValues() throws IOException {
        Path legacyUsageFile = Files.createTempFile("legacy-clone-usage", ".xml");
        try {
            Files.writeString(legacyUsageFile, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project version="4">
                      <component name="AntiCopyPasterUsageStatistics">
                        <option name="applyCount" value="7" />
                        <option name="cancelCount" value="5" />
                        <option name="copyCount" value="11" />
                      </component>
                    </project>
                    """, StandardCharsets.UTF_8);

            CloneUsageStatistics.PluginState state = CloneUsageStatistics.readLegacyCloneCounts(legacyUsageFile);
            assertEquals(7, state.applyCount);
            assertEquals(5, state.cancelCount);
        } finally {
            Files.deleteIfExists(legacyUsageFile);
        }
    }
}
