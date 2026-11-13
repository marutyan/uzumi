package dev.uzumi.ime.keyboard

import dev.uzumi.ime.R

/**
 * 機能バーの並びと、機能バー・候補バーの表示段数、候補の区切り線の有無を表す。
 * 機能は「バーの中央」「バーの右端」「機能タイルの置き場」のどれか一か所に必ず置き、保存値から読むたびに矛盾を直す。
 */
data class ToolbarPreferences(
    /** バーの中央に並べる機能。最大[BAR_MAX]個。 */
    val selectedActions: List<ToolbarAction> = DEFAULT_BAR_ACTIONS,
    /** バーの右端の丸いボタンに置く機能。空ならnull。 */
    val rightAction: ToolbarAction? = DEFAULT_RIGHT_ACTION,
    /** 機能タイルの置き場に並べる機能。バーに無い機能はすべてここへ入る。 */
    val trayActions: List<ToolbarAction> = DEFAULT_TRAY_ACTIONS,
    val toolbarRows: Int = DEFAULT_ROWS,
    val candidateRows: Int = DEFAULT_ROWS,
    /** 候補の間に縦の区切り線を引くか。 */
    val candidateDividers: Boolean = true,
) {
    /** 三か所の並びをまとめた値。ドラッグでの並べ替えはこちらで行う。 */
    val layout: ToolbarLayout
        get() = ToolbarLayout(selectedActions, rightAction, trayActions)

    /** 三か所の並びだけを差し替え、段数と区切り線の設定は保つ。差し替えた結果は[normalized]で整える。 */
    fun withLayout(layout: ToolbarLayout): ToolbarPreferences = copy(
        selectedActions = layout.bar,
        rightAction = layout.right,
        trayActions = layout.tray,
    ).normalized()

    /**
     * 保存値を許容範囲へ収める。未対応の機能と重複を除き、バーは[BAR_MAX]個まで、右端の機能はバーに置かない。
     * どこにも無い機能は置き場の末尾へ補い、使えない機能も隠さずに一覧へ残す。
     */
    fun normalized(): ToolbarPreferences {
        val right = rightAction?.takeIf { it in AVAILABLE_ACTIONS }
        val bar = normalizeActions(selectedActions.filter { it != right })
        val tray = (trayActions + AVAILABLE_ACTIONS)
            .filter { it in AVAILABLE_ACTIONS && it !in bar && it != right }
            .distinct()
        return copy(
            selectedActions = bar,
            rightAction = right,
            trayActions = tray,
            toolbarRows = normalizeRows(toolbarRows),
            candidateRows = normalizeRows(candidateRows),
        )
    }

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

        /** バーの中央に置ける機能の上限。 */
        const val BAR_MAX = 5

        /** 現時点で実際に動作する機能。未実装機能は選択肢にも表示しない。 */
        val AVAILABLE_ACTIONS = listOf(
            ToolbarAction.CLIPBOARD_HISTORY,
            ToolbarAction.UNDO,
            ToolbarAction.EDIT,
            ToolbarAction.SETTINGS,
            ToolbarAction.MICROPHONE,
            ToolbarAction.PASTE,
            ToolbarAction.EMOJI,
            ToolbarAction.TEMPLATES,
            ToolbarAction.USER_DICTIONARY,
            ToolbarAction.KISEKAE,
            ToolbarAction.HEIGHT,
            ToolbarAction.CLOSE_KEYBOARD,
        )

        /** 最初からバーの中央に置く機能。 */
        val DEFAULT_BAR_ACTIONS = listOf(
            ToolbarAction.CLIPBOARD_HISTORY,
            ToolbarAction.UNDO,
            ToolbarAction.EDIT,
            ToolbarAction.SETTINGS,
        )

        /** 最初から右端に置く機能。 */
        val DEFAULT_RIGHT_ACTION: ToolbarAction = ToolbarAction.MICROPHONE

        /** 最初から置き場に並べる機能。 */
        val DEFAULT_TRAY_ACTIONS = listOf(
            ToolbarAction.PASTE,
            ToolbarAction.EMOJI,
            ToolbarAction.TEMPLATES,
            ToolbarAction.USER_DICTIONARY,
            ToolbarAction.KISEKAE,
            ToolbarAction.HEIGHT,
            ToolbarAction.CLOSE_KEYBOARD,
        )

        fun normalizeRows(value: Int): Int = value.coerceIn(MIN_ROWS, MAX_ROWS)

        /** バーの中央に置く機能の並びを整える。未対応の機能と重複を除き、先頭から[BAR_MAX]個までにする。 */
        fun normalizeActions(actions: List<ToolbarAction>): List<ToolbarAction> =
            actions.filter { it in AVAILABLE_ACTIONS }.distinct().take(BAR_MAX)

        /**
         * 三か所の並びを導入する前の保存値（バーに出す機能の一覧だけ）から並びを作る。
         * 音声入力は右端へ移し、残りの先頭[BAR_MAX]個をバーへ、あふれた分を置き場へ回す。
         */
        fun migrateFromBarActions(legacy: List<ToolbarAction>): ToolbarPreferences {
            val rest = legacy.filter { it in AVAILABLE_ACTIONS && it != DEFAULT_RIGHT_ACTION }.distinct()
            return ToolbarPreferences(
                selectedActions = rest.take(BAR_MAX),
                rightAction = DEFAULT_RIGHT_ACTION,
                trayActions = rest.drop(BAR_MAX),
            ).normalized()
        }

        /**
         * 保存した名前の列から並びを読む。右端の保存（[rightName]、空なら右端なし）があれば三か所を保存どおりに読み、
         * 無くてバーの一覧だけがあれば導入前の保存値として移行し、どちらも無ければ既定の並びにする。
         */
        fun fromStorage(barNames: String?, rightName: String?, trayNames: String?): ToolbarPreferences {
            if (rightName != null) {
                return ToolbarPreferences(
                    selectedActions = decodeActions(barNames),
                    rightAction = decodeAction(rightName),
                    trayActions = decodeActions(trayNames),
                ).normalized()
            }
            if (barNames != null) return migrateFromBarActions(decodeActions(barNames))
            return ToolbarPreferences().normalized()
        }

        /** 機能の名前をカンマで区切った保存値を読む。知らない名前は捨てる。 */
        fun decodeActions(names: String?): List<ToolbarAction> =
            names.orEmpty().split(',').mapNotNull(::decodeAction)

        /** 機能の名前から機能を探す。空や知らない名前ならnull。 */
        fun decodeAction(name: String): ToolbarAction? = ToolbarAction.entries.firstOrNull { it.name == name }

        /** 機能の並びを保存する文字列にする。 */
        fun encodeActions(actions: List<ToolbarAction>): String = actions.joinToString(",") { it.name }

        /** 値を[requestedRows]の段数へ、空段を作らず先頭から均等に分ける。 */
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

/**
 * 機能の置き場所の三つ組（バーの中央、右端、機能タイルの置き場）。タイルをドラッグして離したときの並びを、
 * 画面部品に依存せず計算する。
 */
data class ToolbarLayout(
    val bar: List<ToolbarAction>,
    val right: ToolbarAction?,
    val tray: List<ToolbarAction>,
) {
    /** ドラッグを離した場所。入る位置の番号は、動かした機能を除いた並びでの位置を表す。 */
    sealed interface DropTarget {
        /** バーの中央の[index]番目へ入れる。 */
        data class Bar(val index: Int) : DropTarget

        /** 置き場の[index]番目へ入れる。 */
        data class Tray(val index: Int) : DropTarget

        /** 右端の丸へ重ね、入れ替える。 */
        data object Right : DropTarget

        /** バーが満杯で入れられない。 */
        data object BarFull : DropTarget
    }

    /**
     * [action]を[target]へ移した後の並びを返す。移し先が無い（null）か、バーが満杯なら元の位置へ戻す。
     * 右端へ重ねたときは、元にあった機能をドラッグを始めた位置へ移す。
     */
    fun drop(action: ToolbarAction, target: DropTarget?): ToolbarLayout {
        val barIndex = bar.indexOf(action)
        val trayIndex = tray.indexOf(action)
        val fromRight = right == action
        val bars = bar.filter { it != action }.toMutableList()
        val trays = tray.filter { it != action }.toMutableList()
        var newRight = if (fromRight) null else right

        // 元の位置へ機能を戻す。バーや置き場での位置も元のままにする。
        fun putBack(moved: ToolbarAction) {
            when {
                barIndex >= 0 -> bars.add(barIndex.coerceAtMost(bars.size), moved)
                trayIndex >= 0 -> trays.add(trayIndex.coerceAtMost(trays.size), moved)
                else -> newRight = moved
            }
        }

        when (target) {
            is DropTarget.Bar -> bars.add(target.index.coerceIn(0, bars.size), action)
            is DropTarget.Tray -> trays.add(target.index.coerceIn(0, trays.size), action)
            DropTarget.Right -> {
                val replaced = right?.takeIf { it != action }
                newRight = action
                if (replaced != null) putBack(replaced)
            }
            DropTarget.BarFull, null -> putBack(action)
        }
        return ToolbarLayout(bars, newRight, trays)
    }

    /**
     * バーの帯の上へ指があるときの移し先を決める。バーの幅を「置いた後の個数」で等分し、指のある帯の番号を入る位置にする。
     * 動かした機能が他の場所から来て、バーがすでに[ToolbarPreferences.BAR_MAX]個なら満杯とする。
     */
    fun barTarget(action: ToolbarAction, touchX: Float, barLeft: Float, barWidth: Float): DropTarget {
        val others = bar.count { it != action }
        if (action !in bar && others >= ToolbarPreferences.BAR_MAX) return DropTarget.BarFull
        return DropTarget.Bar(barDropIndex(touchX, barLeft, barWidth, others))
    }

    /** 置き場の面の上へ指があるときの移し先を決める。[contentX]と[contentY]は置き場の内容の左上を原点にしたdp。 */
    fun trayTarget(action: ToolbarAction, contentX: Float, contentY: Float, areaWidthDp: Float): DropTarget {
        val others = tray.count { it != action }
        return DropTarget.Tray(trayDropIndex(contentX, contentY, areaWidthDp, others))
    }

    companion object {
        /** 置き場の列数。 */
        const val TRAY_COLUMNS = 2

        /** 置き場の1行の高さ48dpと行の間隔8dp。 */
        const val TRAY_ROW_STRIDE_DP = 56f

        /** 置き場の内容の上の余白（dp）。 */
        const val TRAY_TOP_PADDING_DP = 10f

        /**
         * バーの幅を[othersCount]+1の帯に等分し、[x]がどの帯にあるかを入る位置にする。
         * 描き直しでアイコンの間隔が変わっても、指の位置が同じなら結果が変わらない。
         */
        fun barDropIndex(x: Float, left: Float, width: Float, othersCount: Int): Int {
            if (width <= 0f) return othersCount
            val band = width / (othersCount + 1)
            return Math.floor(((x - left) / band).toDouble()).toInt().coerceIn(0, othersCount)
        }

        /** 置き場の2列のマス目のどこに指があるかを入る位置にする。 */
        fun trayDropIndex(x: Float, y: Float, areaWidth: Float, othersCount: Int): Int {
            val column = if (x < areaWidth / 2f) 0 else 1
            val row = Math.floor(((y - TRAY_TOP_PADDING_DP) / TRAY_ROW_STRIDE_DP).toDouble()).toInt().coerceAtLeast(0)
            return (row * TRAY_COLUMNS + column).coerceIn(0, othersCount)
        }
    }
}

/**
 * 機能バーと機能タイルで共通に使う、実装済みの機能。
 * [iconRes]はMaterial Symbolsのアイコン、[opensScreen]は別の画面を開く機能（タイルの右に「>」を付ける）、
 * [filledIconRes]はバーの右端の丸に置いたときだけ使う塗りのアイコン。
 */
enum class ToolbarAction(
    val iconRes: Int,
    val opensScreen: Boolean = false,
    val filledIconRes: Int? = null,
) {
    PASTE(R.drawable.ic_content_paste_go),
    CLIPBOARD_HISTORY(R.drawable.ic_content_paste, opensScreen = true),
    EDIT(R.drawable.ic_edit_note, opensScreen = true),
    MICROPHONE(R.drawable.ic_mic, filledIconRes = R.drawable.ic_mic_filled),
    UNDO(R.drawable.ic_undo),
    USER_DICTIONARY(R.drawable.ic_menu_book),
    TEMPLATES(R.drawable.ic_short_text, opensScreen = true),
    SETTINGS(R.drawable.ic_settings),
    CLOSE_KEYBOARD(R.drawable.ic_keyboard_hide),
    EMOJI(R.drawable.ic_sentiment_satisfied, opensScreen = true),
    KISEKAE(R.drawable.ic_palette, opensScreen = true),
    HEIGHT(R.drawable.ic_height),
}
