package dev.uzumi.ime.editor

/**
 * IMEが所有する読みと表示のcompositionを保持し、書記素単位の編集を提供する。
 * Android側の接続状態を持たないため、非同期結果の古さを単体で検証できる。
 */
class CompositionBuffer {
    private val clusters = mutableListOf<String>()
    private var displayOverride: String? = null
    private var selectionStart = 0
    private var selectionEnd = 0

    /** 現在の読み文字列。 */
    val reading: String
        get() = clusters.joinToString(separator = "")

    /** 現在エディタへ表示する文字列。未変換時は読みと同じ。 */
    val display: String
        get() = displayOverride ?: reading

    /** composition内の書記素カーソル位置。 */
    val cursorCluster: Int
        get() = selectionEnd

    /** composition内の選択開始位置。 */
    val selectedClusterStart: Int
        get() = selectionStart

    /** composition内の選択終了位置。 */
    val selectedClusterEnd: Int
        get() = selectionEnd

    /** compositionが空かどうかを返す。 */
    val isEmpty: Boolean
        get() = clusters.isEmpty()

    /** 選択範囲があるかどうかを返す。 */
    val hasSelection: Boolean
        get() = selectionStart != selectionEnd

    /**
     * 現在の状態を保存し、InputConnectionの失敗時に復元できるようにする。
     */
    fun snapshot(): CompositionSnapshot {
        return CompositionSnapshot(
            reading = reading,
            display = display,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
    }

    /**
     * 保存済み状態を復元する。表示だけが変換済みのsnapshotにも対応する。
     */
    fun restore(snapshot: CompositionSnapshot) {
        clusters.clear()
        clusters += GraphemeClusters.split(snapshot.reading)
        displayOverride = snapshot.display.takeUnless { it == snapshot.reading }
        selectionStart = snapshot.selectionStart.coerceIn(0, clusters.size)
        selectionEnd = snapshot.selectionEnd.coerceIn(0, clusters.size)
    }

    /**
     * 読みのカーソル位置へ移動する。入力欄から通知された境界だけを受け入れる。
     */
    fun setCursor(clusterIndex: Int): Boolean {
        val clamped = clusterIndex.coerceIn(0, clusters.size)
        if (selectionStart == clamped && selectionEnd == clamped) return false
        selectionStart = clamped
        selectionEnd = clamped
        displayOverride = null
        return true
    }

    /**
     * 読みの選択範囲を設定する。範囲外の値はcomposition内へ丸める。
     */
    fun setSelection(start: Int, end: Int): Boolean {
        val clampedStart = start.coerceIn(0, clusters.size)
        val clampedEnd = end.coerceIn(0, clusters.size)
        if (selectionStart == clampedStart && selectionEnd == clampedEnd) return false
        selectionStart = minOf(clampedStart, clampedEnd)
        selectionEnd = maxOf(clampedStart, clampedEnd)
        displayOverride = null
        return true
    }

    /**
     * 書記素単位でカーソルを移動する。選択中は移動方向の端へ折りたたむ。
     */
    fun moveCursor(delta: Int): Boolean {
        if (delta == 0) return false
        val destination = when {
            hasSelection && delta < 0 -> selectionStart
            hasSelection && delta > 0 -> selectionEnd
            else -> (selectionEnd + delta).coerceIn(0, clusters.size)
        }
        return setCursor(destination)
    }

    /**
     * 読みをカーソル位置へ挿入し、既存の変換表示を読みへ戻す。
     */
    fun insert(text: String): Boolean {
        if (text.isEmpty()) return false
        if (hasSelection) {
            clusters.subList(selectionStart, selectionEnd).clear()
            selectionEnd = selectionStart
        }
        val inserted = GraphemeClusters.split(text)
        clusters.addAll(selectionEnd, inserted)
        selectionStart = selectionEnd + inserted.size
        selectionEnd = selectionStart
        displayOverride = null
        return true
    }

    /**
     * 読みの直前の書記素を削除し、削除対象があったかを返す。
     */
    fun deleteBackward(): Boolean {
        if (hasSelection) {
            clusters.subList(selectionStart, selectionEnd).clear()
            selectionEnd = selectionStart
            displayOverride = null
            return true
        }
        if (selectionEnd == 0) return false
        clusters.removeAt(selectionEnd - 1)
        selectionStart = selectionEnd - 1
        displayOverride = null
        return true
    }

    /** カーソル直前の一書記素を、指定されたかな変換で置き換える。 */
    fun transformBeforeCursor(transform: (String) -> String?): Boolean {
        if (hasSelection || selectionEnd == 0) return false
        val targetIndex = selectionEnd - 1
        val replacement = transform(clusters[targetIndex]) ?: return false
        clusters[targetIndex] = replacement
        displayOverride = null
        return true
    }

    /**
     * 読みを変えずに表示文字列だけを候補へ差し替える。
     */
    fun setDisplay(text: String): Boolean {
        if (text.isEmpty() || text == reading) {
            val changed = displayOverride != null
            displayOverride = null
            return changed
        }
        if (displayOverride == text) return false
        displayOverride = text
        selectionStart = clusters.size
        selectionEnd = clusters.size
        return true
    }

    /**
     * compositionを内部状態だけ消去する。接続へ文字を送らないため切替時に使う。
     */
    fun clear() {
        clusters.clear()
        displayOverride = null
        selectionStart = 0
        selectionEnd = 0
    }

    /** 読みカーソル位置のUTF-16 offsetを返す。 */
    fun cursorUtf16Offset(): Int {
        return GraphemeClusters.utf16Offset(clusters, selectionEnd)
    }
}

/**
 * composition復元に必要な最小状態をまとめる。
 */
data class CompositionSnapshot(
    val reading: String,
    val display: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)
