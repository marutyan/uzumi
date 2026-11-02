package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** 機能の選択順とバーごとの段数が表示に反映されることを確かめる。 */
class ToolbarPreferencesTest {
    @Test
    fun normalizesActionOrderAndIndependentRowCounts() {
        val preferences = ToolbarPreferences(
            selectedActions = listOf(ToolbarAction.SETTINGS, ToolbarAction.UNDO, ToolbarAction.SETTINGS),
            toolbarRows = 8,
            candidateRows = 0,
        ).normalized()

        assertEquals(listOf(ToolbarAction.SETTINGS, ToolbarAction.UNDO), preferences.selectedActions)
        assertEquals(3, preferences.toolbarRows)
        assertEquals(1, preferences.candidateRows)
    }

    @Test
    fun candidateRowsKeepEveryIndexInOriginalRowMajorOrder() {
        val rows = ToolbarPreferences(candidateRows = 3).candidateIndexRows(candidateCount = 7, maxItemsPerRow = 3)

        assertEquals(listOf(3, 2, 2), rows.map(List<Int>::size))
        assertEquals((0..6).toList(), rows.flatten())
    }

    @Test
    fun candidateRowsDoNotCreateExtraRowsWhenAllCandidatesFit() {
        val rows = ToolbarPreferences(candidateRows = 3).candidateIndexRows(candidateCount = 2, maxItemsPerRow = 4)

        assertEquals(listOf(listOf(0, 1)), rows)
    }

    @Test
    fun toolbarRowsKeepSelectedOrderAndSuppressEmptyRows() {
        val selected = listOf(
            ToolbarAction.PASTE,
            ToolbarAction.UNDO,
            ToolbarAction.SETTINGS,
            ToolbarAction.CLOSE_KEYBOARD,
        )
        val rows = ToolbarPreferences(selectedActions = selected, toolbarRows = 3)
            .toolbarActionRows(selected.filter { it != ToolbarAction.PASTE })

        assertEquals(3, rows.size)
        assertEquals(listOf(ToolbarAction.UNDO, ToolbarAction.SETTINGS, ToolbarAction.CLOSE_KEYBOARD), rows.flatten())
    }
}
