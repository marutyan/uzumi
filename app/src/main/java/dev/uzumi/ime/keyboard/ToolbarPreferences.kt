package dev.uzumi.ime.keyboard

/**
 * 機能バーの上段に固定する機能と、機能バー・候補バーの表示段数を表す。
 * 選択状態は設定画面で保存し、表示時に利用可能な機能だけを順番どおり並べる。
 */
data class ToolbarPreferences(
    val selectedActions: List<ToolbarAction> = DEFAULT_ACTIONS,
    val toolbarRows: Int = DEFAULT_ROWS,
    val candidateRows: Int = DEFAULT_ROWS,
) {
    /** 保存値を許容範囲へ収め、重複や未対応の機能を取り除く。 */
    fun normalized(): ToolbarPreferences = copy(
        selectedActions = normalizeActions(selectedActions),
        toolbarRows = normalizeRows(toolbarRows),
        candidateRows = normalizeRows(candidateRows),
    )

    /** 候補の元のindexを保ったまま、選択した段数へ均等に分ける。 */
    fun candidateIndexRows(candidateCount: Int, maxItemsPerRow: Int): List<List<Int>> {
        val indices = (0 until candidateCount.coerceAtLeast(0)).toList()
        if (indices.isEmpty()) return emptyList()
        val capacity = maxItemsPerRow.coerceAtLeast(1)
        val rowsNeeded = (indices.size + capacity - 1) / capacity
        return splitAcrossRows(indices, minOf(candidateRows, rowsNeeded))
    }

    /** 選んだ機能を順番のまま、空段を作らず設定段数まで分ける。 */
    fun toolbarActionRows(visibleActions: List<ToolbarAction> = selectedActions): List<List<ToolbarAction>> =
        splitAcrossRows(
            selectedActions.filter { it in visibleActions && it in AVAILABLE_ACTIONS },
            toolbarRows,
        )

    companion object {
        const val MIN_ROWS = 1
        const val MAX_ROWS = 3
        const val DEFAULT_ROWS = 1

        /** 現時点で実際に動作する機能。未実装機能は選択肢にも表示しない。 */
        val AVAILABLE_ACTIONS = listOf(
            ToolbarAction.PASTE,
            ToolbarAction.UNDO,
            ToolbarAction.USER_DICTIONARY,
            ToolbarAction.SETTINGS,
            ToolbarAction.CLOSE_KEYBOARD,
        )

        /** よく使う機能を最初から上段に置く。Undoは戻せる操作があるときだけ表示する。 */
        val DEFAULT_ACTIONS = listOf(
            ToolbarAction.PASTE,
            ToolbarAction.UNDO,
            ToolbarAction.USER_DICTIONARY,
            ToolbarAction.SETTINGS,
            ToolbarAction.CLOSE_KEYBOARD,
        )

        fun normalizeRows(value: Int): Int = value.coerceIn(MIN_ROWS, MAX_ROWS)

        fun normalizeActions(actions: List<ToolbarAction>): List<ToolbarAction> =
            actions.filter { it in AVAILABLE_ACTIONS }.distinct()

        private fun <T> splitAcrossRows(values: List<T>, requestedRows: Int): List<List<T>> {
            if (values.isEmpty()) return emptyList()
            val rowCount = normalizeRows(requestedRows).coerceAtMost(values.size)
            val baseSize = values.size / rowCount
            val extraRows = values.size % rowCount
            var start = 0
            return List(rowCount) { row ->
                val size = baseSize + if (row < extraRows) 1 else 0
                values.subList(start, start + size).also { start += size }
            }
        }
    }
}

/** 候補バーと機能タイルで共通に使う、実装済みの機能。 */
enum class ToolbarAction(val icon: String) {
    PASTE("▣"),
    UNDO("↶"),
    USER_DICTIONARY("辞"),
    SETTINGS("⚙"),
    CLOSE_KEYBOARD("⌄"),
}
