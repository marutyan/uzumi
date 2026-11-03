package dev.uzumi.ime.editor

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
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

    /** actionの指定がない欄へ、ソフトキーボードのEnterキーの押下と解放を送る。 */
    fun sendEnterKey(): Boolean

    /** 修飾キーを付けたキーの押下と解放を送る。 */
    fun sendKeyStroke(keyCode: Int, metaState: Int): Boolean

    /** 周辺文字列を読めない互換入力欄（TYPE_NULL）へ、Deleteキーの押下と解放を送る。 */
    fun sendDeleteKey(): Boolean

    /** 削除範囲計算に使う直前の文字列を取得する。 */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /** カーソル移動に使う直後の文字列を取得する。 */
    fun textAfterCursor(maxChars: Int): CharSequence?

    /** 選択範囲の文字列を取得する。取得できないEditorではnullを返す。 */
    fun selectedText(): CharSequence?

    /** Android標準のコピー・切り取り・貼り付け・全選択操作を実行する。 */
    fun performContextMenuAction(actionId: Int): Boolean

    /** Editorの選択位置を本文を保持せずに取得する。 */
    fun currentSelection(): Pair<Int, Int>?

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

    override fun sendEnterKey(): Boolean = sendDownUpKey(KeyEvent.KEYCODE_ENTER)

    override fun sendDeleteKey(): Boolean = sendDownUpKey(KeyEvent.KEYCODE_DEL)

    /** 指定した修飾キーを保ったまま、ソフトキーボード由来のキー操作を送る。 */
    override fun sendKeyStroke(keyCode: Int, metaState: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        return call {
            delegate.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, -1, 0, flags)) &&
                delegate.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, metaState, -1, 0, flags))
        }
    }

    /** 指定したキーの押下と解放を、ソフトキーボードからの入力として送る。 */
    private fun sendDownUpKey(keyCode: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        // InputMethodService.sendDownUpKeyEventsと同じ印を付け、ソフトキーボード由来の入力として扱わせる。
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        return call {
            delegate.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, flags)) &&
                delegate.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, flags))
        }
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        return runCatching { delegate.getTextBeforeCursor(maxChars, 0) }.getOrNull()
    }

    override fun textAfterCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        return runCatching { delegate.getTextAfterCursor(maxChars, 0) }.getOrNull()
    }

    override fun selectedText(): CharSequence? {
        if (!active) return null
        return runCatching { delegate.getSelectedText(0) }.getOrNull()
    }

    /** Editor標準のコピー・切り取り・貼り付け・全選択メニューを実行する。 */
    override fun performContextMenuAction(actionId: Int): Boolean =
        call { delegate.performContextMenuAction(actionId) }

    /** ExtractedTextの選択indexだけを取り出し、本文は保持しない。 */
    override fun currentSelection(): Pair<Int, Int>? {
        if (!active) return null
        return runCatching {
            val request = ExtractedTextRequest().apply {
                hintMaxChars = 1
                hintMaxLines = 1
            }
            delegate.getExtractedText(request, 0)?.let { extracted ->
                if (extracted.startOffset < 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) null
                else (extracted.startOffset + extracted.selectionStart) to
                    (extracted.startOffset + extracted.selectionEnd)
            }
        }.getOrNull()
    }

    private fun call(operation: () -> Boolean): Boolean {
        if (!active) return false
        return runCatching { operation() }.getOrDefault(false)
    }
}
