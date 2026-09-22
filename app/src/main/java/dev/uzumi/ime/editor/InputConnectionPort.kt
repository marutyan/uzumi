package dev.uzumi.ime.editor

import android.view.inputmethod.InputConnection

/**
 * InputConnectionの必要な操作だけを包み、接続無効化とfalse返却を統一して扱う。
 */
interface EditorConnectionPort {
    /** composition文字列を送る。 */
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean

    /** compositionを確定する。 */
    fun finishComposingText(): Boolean

    /** 文字列を確定入力する。 */
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean

    /** UTF-16幅で周辺文字を削除する。 */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean

    /** code point単位の削除へフォールバックする。 */
    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean

    /** 外部選択を移動する。 */
    fun setSelection(start: Int, end: Int): Boolean

    /** editor actionを送る。 */
    fun performEditorAction(actionCode: Int): Boolean

    /** 削除範囲計算に使う直前の文字列を取得する。 */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /** カーソル移動に使う直後の文字列を取得する。 */
    fun textAfterCursor(maxChars: Int): CharSequence?

    /** 接続を無効にする。 */
    fun invalidate()
}

/**
 * AndroidのInputConnectionを安全な接続ポートへ変換する。
 * セッション切替後はdelegateへ一度も文字を送らない。
 */
class AndroidInputConnectionPort(
    private val delegate: InputConnection,
) : EditorConnectionPort {
    private var active = true

    /** 接続がまだ操作可能かを返す。 */
    val isActive: Boolean
        get() = active

    /**
     * セッションを終了し、以後の遅延操作を拒否する。
     */
    override fun invalidate() {
        active = false
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        return call { delegate.setComposingText(text, newCursorPosition) }
    }

    override fun finishComposingText(): Boolean {
        return call { delegate.finishComposingText() }
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        return call { delegate.commitText(text, newCursorPosition) }
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        return call { delegate.deleteSurroundingText(beforeLength, afterLength) }
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return call { delegate.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        return call { delegate.setSelection(start, end) }
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        return call { delegate.performEditorAction(actionCode) }
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        return runCatching { delegate.getTextBeforeCursor(maxChars, 0) }.getOrNull()
    }

    override fun textAfterCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        return runCatching { delegate.getTextAfterCursor(maxChars, 0) }.getOrNull()
    }

    private fun call(operation: () -> Boolean): Boolean {
        if (!active) return false
        return runCatching { operation() }.getOrDefault(false)
    }
}
