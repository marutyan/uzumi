package dev.uzumi.ime.editor

/** 編集パネルから実行する操作を表す。 */
enum class EditPanelAction {
    LEFT,
    UP,
    DOWN,
    RIGHT,
    TOGGLE_SELECTION,
    SELECT_ALL,
    COPY,
    CUT,
    PASTE,
    HOME,
    END,
    DELETE,
    CLOSE,
}

/** Android標準の編集メニューへ渡す操作を表す。 */
enum class EditPanelContextAction {
    SELECT_ALL,
    COPY,
    CUT,
    PASTE,
}

/** 選択範囲の固定端と移動端を、向きを保ったまま表す。 */
data class EditPanelSelection(val anchor: Int, val focus: Int) {
    /** 2つの端点が異なる場合に範囲選択中とする。 */
    val hasRange: Boolean
        get() = anchor != focus
}

/** 編集パネルで選択端と操作可否を決める純粋関数をまとめる。 */
object EditPanelRules {
    /** 左右移動と選択の有無から、次にEditorへ設定するselection端点を求める。 */
    fun moveHorizontally(
        selection: EditPanelSelection,
        direction: Int,
        extendSelection: Boolean,
        adjacentUtf16Length: Int,
    ): EditPanelSelection {
        require(direction == -1 || direction == 1)
        if (!extendSelection && selection.hasRange) {
            val collapsed = if (direction < 0) minOf(selection.anchor, selection.focus)
            else maxOf(selection.anchor, selection.focus)
            return EditPanelSelection(collapsed, collapsed)
        }
        val width = adjacentUtf16Length.coerceAtLeast(0)
        if (width == 0) return selection
        val nextFocus = selection.focus + direction * width
        val nextAnchor = if (extendSelection) selection.anchor else nextFocus
        return EditPanelSelection(nextAnchor, nextFocus)
    }

    /** パスワード欄と選択・クリップボード状態から編集パネルの操作可否を決める。 */
    fun isEnabled(
        action: EditPanelAction,
        isPassword: Boolean,
        hasSelection: Boolean,
        hasClipboardText: Boolean,
    ): Boolean = when (action) {
        EditPanelAction.COPY, EditPanelAction.CUT -> hasSelection && !isPassword
        EditPanelAction.PASTE -> hasClipboardText
        else -> true
    }
}
