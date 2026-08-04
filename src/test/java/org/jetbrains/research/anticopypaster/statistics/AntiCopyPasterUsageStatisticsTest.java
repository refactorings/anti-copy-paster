package org.jetbrains.research.anticopypaster.statistics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AntiCopyPasterUsageStatisticsTest {

    @Test
    void copyAndPasteCountersUpdateWithoutResettingExistingValues() {
        AntiCopyPasterUsageStatistics statistics = new AntiCopyPasterUsageStatistics();

        statistics.onCopy();
        statistics.onPaste();
        statistics.onPaste();

        AntiCopyPasterUsageStatistics.PluginState state = statistics.getState();
        assertNotNull(state);
        assertEquals(1, state.copyCount);
        assertEquals(2, state.pasteCount);
    }

    @Test
    void loadStateKeepsPersistedNonCloneUsageCounts() {
        AntiCopyPasterUsageStatistics statistics = new AntiCopyPasterUsageStatistics();
        AntiCopyPasterUsageStatistics.PluginState persisted = new AntiCopyPasterUsageStatistics.PluginState();
        persisted.copyCount = 9;
        persisted.pasteCount = 3;
        persisted.notificationCount = 2;

        statistics.loadState(persisted);

        AntiCopyPasterUsageStatistics.PluginState state = statistics.getState();
        assertNotNull(state);
        assertEquals(9, state.copyCount);
        assertEquals(3, state.pasteCount);
        assertEquals(2, state.notificationCount);
    }

    @Test
    void refactoringPanelCountersTrackEditAndHelpActions() {
        AntiCopyPasterUsageStatistics statistics = new AntiCopyPasterUsageStatistics();

        statistics.refactoringEdited();
        statistics.refactoringHelpOpened();
        statistics.refactoringHelpOpened();

        AntiCopyPasterUsageStatistics.PluginState state = statistics.getState();
        assertNotNull(state);
        assertEquals(1, state.getEditCount());
        assertEquals(2, state.getHelpCount());
    }
}
