package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 機能の選択順とバーごとの段数が表示に反映されることを確かめる。 */
class ToolbarPreferencesTest {
    /** 編集と音声入力は選択可能な機能一覧に各一度だけ含める。 */
    @Test
    fun editAndMicrophoneActionsAreAvailableOnce() {
        assertEquals(1, ToolbarPreferences.AVAILABLE_ACTIONS.count { it == ToolbarAction.EDIT })
        assertEquals(1, ToolbarPreferences.AVAILABLE_ACTIONS.count { it == ToolbarAction.MICROPHONE })
    }

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

    /** 定型文タイルを設定画面から一つ選べる。 */
    @Test
    fun templatePickerIsAvailableOnce() {
        assertEquals(1, ToolbarPreferences.AVAILABLE_ACTIONS.count { it == ToolbarAction.TEMPLATES })
    }

    /** 既定の並びは、バー・右端・置き場に全機能を一度ずつ置く。 */
    @Test
    fun defaultLayoutPlacesEveryActionOnce() {
        val preferences = ToolbarPreferences().normalized()

        assertEquals(
            listOf(ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.UNDO, ToolbarAction.EDIT, ToolbarAction.SETTINGS),
            preferences.selectedActions,
        )
        assertEquals(ToolbarAction.MICROPHONE, preferences.rightAction)
        assertEquals(
            listOf(
                ToolbarAction.PASTE, ToolbarAction.EMOJI, ToolbarAction.TEMPLATES, ToolbarAction.USER_DICTIONARY,
                ToolbarAction.KISEKAE, ToolbarAction.HEIGHT, ToolbarAction.CLOSE_KEYBOARD,
            ),
            preferences.trayActions,
        )
        val placed = preferences.selectedActions + listOfNotNull(preferences.rightAction) + preferences.trayActions
        assertEquals(ToolbarPreferences.AVAILABLE_ACTIONS.toSet(), placed.toSet())
        assertEquals(ToolbarPreferences.AVAILABLE_ACTIONS.size, placed.size)
    }

    /** バーは5個まで、重複は除き、右端の機能はバーに置かず、あふれた機能と未配置の機能は置き場へ回す。 */
    @Test
    fun normalizedKeepsBarAtFiveWithoutDuplicatesAndFillsTray() {
        val preferences = ToolbarPreferences(
            selectedActions = listOf(
                ToolbarAction.PASTE, ToolbarAction.PASTE, ToolbarAction.UNDO, ToolbarAction.EDIT,
                ToolbarAction.MICROPHONE, ToolbarAction.SETTINGS, ToolbarAction.TEMPLATES, ToolbarAction.EMOJI,
            ),
            rightAction = ToolbarAction.MICROPHONE,
            trayActions = listOf(ToolbarAction.UNDO, ToolbarAction.HEIGHT),
        ).normalized()

        assertEquals(
            listOf(ToolbarAction.PASTE, ToolbarAction.UNDO, ToolbarAction.EDIT, ToolbarAction.SETTINGS, ToolbarAction.TEMPLATES),
            preferences.selectedActions,
        )
        assertEquals(ToolbarAction.MICROPHONE, preferences.rightAction)
        assertEquals(ToolbarAction.HEIGHT, preferences.trayActions.first())
        assertEquals(true, ToolbarAction.EMOJI in preferences.trayActions)
        assertEquals(true, ToolbarAction.UNDO !in preferences.trayActions)
        assertEquals(preferences.trayActions.distinct(), preferences.trayActions)
    }

    /** 右端は空にでき、空のときは右端の機能がどこにも残らない。 */
    @Test
    fun rightActionCanBeEmpty() {
        val preferences = ToolbarPreferences(rightAction = null).normalized()

        assertNull(preferences.rightAction)
        assertEquals(true, ToolbarAction.MICROPHONE in preferences.trayActions)
    }

    /** 導入前の保存値は、音声入力を右端へ移し、先頭の5個をバーへ、残りを置き場へ回す。 */
    @Test
    fun migratesLegacyBarActions() {
        val legacy = listOf(
            ToolbarAction.PASTE, ToolbarAction.MICROPHONE, ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.UNDO,
            ToolbarAction.USER_DICTIONARY, ToolbarAction.SETTINGS, ToolbarAction.CLOSE_KEYBOARD,
        )

        val migrated = ToolbarPreferences.fromStorage(ToolbarPreferences.encodeActions(legacy), rightName = null, trayNames = null)

        assertEquals(ToolbarAction.MICROPHONE, migrated.rightAction)
        assertEquals(
            listOf(
                ToolbarAction.PASTE, ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.UNDO,
                ToolbarAction.USER_DICTIONARY, ToolbarAction.SETTINGS,
            ),
            migrated.selectedActions,
        )
        assertEquals(ToolbarAction.CLOSE_KEYBOARD, migrated.trayActions.first())
        assertEquals(true, ToolbarAction.EMOJI in migrated.trayActions)
    }

    /** 保存が何も無ければ既定の並び、右端の保存があれば三か所を保存どおりに読む（右端が空の保存を含む）。 */
    @Test
    fun readsStoredLayoutAndFallsBackToDefaults() {
        assertEquals(ToolbarPreferences().normalized(), ToolbarPreferences.fromStorage(null, null, null))

        val stored = ToolbarPreferences.fromStorage(
            barNames = "SETTINGS,UNKNOWN,UNDO",
            rightName = "",
            trayNames = "HEIGHT",
        )

        assertEquals(listOf(ToolbarAction.SETTINGS, ToolbarAction.UNDO), stored.selectedActions)
        assertNull(stored.rightAction)
        assertEquals(ToolbarAction.HEIGHT, stored.trayActions.first())
    }

    /** 候補の区切り線は既定で「あり」。 */
    @Test
    fun candidateDividersAreOnByDefault() {
        assertEquals(true, ToolbarPreferences().candidateDividers)
        assertEquals(false, ToolbarPreferences(candidateDividers = false).normalized().candidateDividers)
    }

    /** バーの帯は、置いた後の個数で幅を等分した帯の番号で決まる。 */
    @Test
    fun barDropIndexUsesEqualBandsOfTheResultingCount() {
        // 他に3個あるので、置いた後は4個。幅400を100ずつの4帯に分ける。
        assertEquals(0, ToolbarLayout.barDropIndex(x = 10f, left = 0f, width = 400f, othersCount = 3))
        assertEquals(1, ToolbarLayout.barDropIndex(x = 150f, left = 0f, width = 400f, othersCount = 3))
        assertEquals(3, ToolbarLayout.barDropIndex(x = 399f, left = 0f, width = 400f, othersCount = 3))
        // 範囲外の指は端の帯へ収める。左端の位置も差し引く。
        assertEquals(0, ToolbarLayout.barDropIndex(x = -20f, left = 0f, width = 400f, othersCount = 3))
        assertEquals(3, ToolbarLayout.barDropIndex(x = 900f, left = 0f, width = 400f, othersCount = 3))
        assertEquals(2, ToolbarLayout.barDropIndex(x = 260f, left = 50f, width = 400f, othersCount = 3))
        // 空のバーには必ず先頭へ入る。
        assertEquals(0, ToolbarLayout.barDropIndex(x = 200f, left = 0f, width = 400f, othersCount = 0))
    }

    /** 置き場は2列のマス目で決まり、行は48dp＋間隔8dp、上の余白は10dp。 */
    @Test
    fun trayDropIndexUsesTwoColumnGrid() {
        val others = 7
        assertEquals(0, ToolbarLayout.trayDropIndex(x = 10f, y = 20f, areaWidth = 400f, othersCount = others))
        assertEquals(1, ToolbarLayout.trayDropIndex(x = 300f, y = 20f, areaWidth = 400f, othersCount = others))
        assertEquals(2, ToolbarLayout.trayDropIndex(x = 10f, y = 10f + 56f + 1f, areaWidth = 400f, othersCount = others))
        assertEquals(5, ToolbarLayout.trayDropIndex(x = 300f, y = 10f + 56f * 2 + 1f, areaWidth = 400f, othersCount = others))
        // 余白の上は先頭行、並びの最後より先はいちばん後ろへ収める。
        assertEquals(0, ToolbarLayout.trayDropIndex(x = 10f, y = -5f, areaWidth = 400f, othersCount = others))
        assertEquals(others, ToolbarLayout.trayDropIndex(x = 300f, y = 900f, areaWidth = 400f, othersCount = others))
    }

    private val layout = ToolbarLayout(
        bar = listOf(ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.UNDO, ToolbarAction.EDIT),
        right = ToolbarAction.MICROPHONE,
        tray = listOf(ToolbarAction.PASTE, ToolbarAction.EMOJI, ToolbarAction.TEMPLATES),
    )

    /** バーの満杯の判定は、他の場所から来た機能だけに効き、バーの中の並べ替えでは効かない。 */
    @Test
    fun barTargetIsFullOnlyForActionsComingFromElsewhere() {
        val full = ToolbarLayout(
            bar = listOf(
                ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.UNDO, ToolbarAction.EDIT,
                ToolbarAction.SETTINGS, ToolbarAction.PASTE,
            ),
            right = null,
            tray = listOf(ToolbarAction.EMOJI),
        )

        assertEquals(ToolbarLayout.DropTarget.BarFull, full.barTarget(ToolbarAction.EMOJI, 10f, 0f, 400f))
        assertEquals(ToolbarLayout.DropTarget.Bar(0), full.barTarget(ToolbarAction.EDIT, 10f, 0f, 400f))
        // 満杯でなければ、指の帯の番号になる。
        assertEquals(ToolbarLayout.DropTarget.Bar(3), layout.barTarget(ToolbarAction.PASTE, 399f, 0f, 400f))
    }

    /** 置き場からバーへ入れると、置き場から消えてバーの指定位置へ入る。 */
    @Test
    fun dropFromTrayIntoBar() {
        val moved = layout.drop(ToolbarAction.EMOJI, ToolbarLayout.DropTarget.Bar(1))

        assertEquals(
            listOf(ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.EMOJI, ToolbarAction.UNDO, ToolbarAction.EDIT),
            moved.bar,
        )
        assertEquals(listOf(ToolbarAction.PASTE, ToolbarAction.TEMPLATES), moved.tray)
        assertEquals(ToolbarAction.MICROPHONE, moved.right)
    }

    /** バーから置き場へ戻す操作と、バーの中・置き場の中の並べ替え。 */
    @Test
    fun dropBackToTrayAndReorderWithinZones() {
        val toTray = layout.drop(ToolbarAction.UNDO, ToolbarLayout.DropTarget.Tray(0))
        assertEquals(listOf(ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.EDIT), toTray.bar)
        assertEquals(listOf(ToolbarAction.UNDO, ToolbarAction.PASTE, ToolbarAction.EMOJI, ToolbarAction.TEMPLATES), toTray.tray)

        // 動かした機能を除いた並びでの位置を指定する。先頭のものを最後（位置2）へ動かす。
        val inBar = layout.drop(ToolbarAction.CLIPBOARD_HISTORY, ToolbarLayout.DropTarget.Bar(2))
        assertEquals(listOf(ToolbarAction.UNDO, ToolbarAction.EDIT, ToolbarAction.CLIPBOARD_HISTORY), inBar.bar)

        val inTray = layout.drop(ToolbarAction.PASTE, ToolbarLayout.DropTarget.Tray(2))
        assertEquals(listOf(ToolbarAction.EMOJI, ToolbarAction.TEMPLATES, ToolbarAction.PASTE), inTray.tray)
    }

    /** 右端の丸へ重ねると入れ替わり、元にあった機能はドラッグを始めた位置へ移る。右端は空にもできる。 */
    @Test
    fun dropOnRightSwapsWithOriginalPosition() {
        val fromBar = layout.drop(ToolbarAction.UNDO, ToolbarLayout.DropTarget.Right)
        assertEquals(ToolbarAction.UNDO, fromBar.right)
        assertEquals(listOf(ToolbarAction.CLIPBOARD_HISTORY, ToolbarAction.MICROPHONE, ToolbarAction.EDIT), fromBar.bar)

        val fromTray = layout.drop(ToolbarAction.TEMPLATES, ToolbarLayout.DropTarget.Right)
        assertEquals(ToolbarAction.TEMPLATES, fromTray.right)
        assertEquals(listOf(ToolbarAction.PASTE, ToolbarAction.EMOJI, ToolbarAction.MICROPHONE), fromTray.tray)

        val emptied = layout.drop(ToolbarAction.MICROPHONE, ToolbarLayout.DropTarget.Tray(3))
        assertNull(emptied.right)
        assertEquals(ToolbarAction.MICROPHONE, emptied.tray.last())
    }

    /** 満杯で入れられないときと、移し先が無いときは、元の位置へ戻る。 */
    @Test
    fun dropWithoutValidTargetRestoresOriginalPosition() {
        assertEquals(layout, layout.drop(ToolbarAction.EMOJI, ToolbarLayout.DropTarget.BarFull))
        assertEquals(layout, layout.drop(ToolbarAction.UNDO, null))
        assertEquals(layout, layout.drop(ToolbarAction.MICROPHONE, null))
        assertEquals(layout, layout.drop(ToolbarAction.MICROPHONE, ToolbarLayout.DropTarget.Right))
    }
}
