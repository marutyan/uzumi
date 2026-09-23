package dev.uzumi.ime.editor

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** InputConnectionの成功・失敗と編集状態の同期境界を検証する。 */
class EditorSessionTest {
    /** かな入力、カナ候補、確定を同じcompositionとして送る。 */
    @Test
    fun composesCandidateAndCommit() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy())

        assertTrue(session.inputText("かな"))
        assertEquals(listOf("かな", "カナ"), session.candidateOptions().map(CandidateOption::value))
        assertTrue(session.applyCandidate("カナ"))
        session.updateSelection(0, 0, 2, 2, 0, 2)
        assertEquals("カナ", session.compositionSnapshot().display)
        assertTrue(session.commitComposition())

        assertEquals(listOf("かな", "カナ"), connection.composingTexts)
        assertEquals(listOf("カナ"), connection.committedTexts)
        assertTrue(session.compositionSnapshot().reading.isEmpty())
    }

    /** composition送信失敗後は接続を無効化し、同じ読みを再送しない。 */
    @Test
    fun composingFailureInvalidatesSessionWithoutRetry() {
        val connection = FakeEditorConnection().apply { composingAccepted = false }
        val session = EditorSession(connection, normalPolicy())

        assertFalse(session.inputText("あ"))
        assertFalse(session.isActive)
        assertTrue(connection.invalidated)
        assertFalse(session.inputText("い"))
        assertEquals(listOf("あ"), connection.composingTexts)
    }

    /** composition後のselection失敗でも古い読みを次操作で再送しない。 */
    @Test
    fun selectionFailureAfterCompositionFailsClosed() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy())
        assertTrue(session.inputText("かな"))
        connection.selectionAccepted = false

        assertFalse(session.moveCursor(-1))
        assertFalse(session.isActive)
        assertFalse(session.inputText("い"))
        assertEquals(listOf("かな"), connection.composingTexts)
    }

    /** フィールド切替後の旧セッションへ文字を送らない。 */
    @Test
    fun closedSessionCannotWriteIntoNextField() {
        val oldConnection = FakeEditorConnection()
        val oldSession = EditorSession(oldConnection, normalPolicy())
        oldSession.inputText("あ")
        oldSession.close()

        val nextConnection = FakeEditorConnection()
        val nextSession = EditorSession(nextConnection, normalPolicy())
        assertFalse(oldSession.inputText("い"))
        assertTrue(nextSession.inputText("い"))

        assertEquals(listOf("あ"), oldConnection.composingTexts)
        assertEquals(listOf("い"), nextConnection.composingTexts)
    }

    /** composition外へのselection移動後は旧読みを捨て、新入力だけを送る。 */
    @Test
    fun selectionOutsideCompositionDropsOldReading() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy())
        assertTrue(session.inputText("あ"))

        session.updateSelection(1, 1, 10, 10, 0, 1)
        assertTrue(session.inputText("い"))

        assertEquals(listOf("あ", "い"), connection.composingTexts)
        assertEquals("い", session.compositionSnapshot().reading)
    }

    /** hostがcomposing spanを終了した後に旧readingを重複挿入しない。 */
    @Test
    fun hostFinishedCompositionStartsNextReadingAtCursor() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), 0, 0)
        assertTrue(session.inputText("あ"))
        connection.hostFinishComposition()

        session.updateSelection(1, 1, 1, 1, -1, -1)
        assertTrue(session.inputText("い"))

        assertEquals("あい", connection.text)
        assertEquals("い", session.compositionSnapshot().reading)
    }

    /** composition内の範囲選択を確定してから選択部分だけを次入力で置換する。 */
    @Test
    fun rangeSelectionDoesNotDeleteTextOutsideSelection() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), 0, 0)
        assertTrue(session.inputText("かな"))
        connection.hostSetSelection(0, 1)

        session.updateSelection(2, 2, 0, 1, 0, 2)
        assertTrue(session.inputText("き"))

        assertEquals("きな", connection.text)
        assertEquals(1, connection.finishComposingCalls)
    }

    /** initialSel不明時は実cursorを推測せず、最初のspan通知から位置を確定する。 */
    @Test
    fun unknownInitialSelectionAdoptsFirstComposingRange() {
        val connection = ModelEditorConnection(text = "XX", selectionStart = 2, selectionEnd = 2)
        val session = EditorSession(connection, normalPolicy(), -1, -1)
        assertTrue(session.inputText("あ"))

        session.updateSelection(-1, -1, 3, 3, 2, 3)
        assertTrue(session.inputText("い"))

        assertEquals("XXあい", connection.text)
        assertEquals("あい", session.compositionSnapshot().reading)
    }

    /** 逆向きselectionを範囲として保持し、通知でcompositionを誤破棄しない。 */
    @Test
    fun reversedInitialSelectionRemainsAReplacementRange() {
        val connection = ModelEditorConnection(text = "かな", selectionStart = 2, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), 2, 0)
        assertTrue(session.inputText("き"))

        session.updateSelection(2, 0, 1, 1, 0, 1)
        assertEquals("き", session.compositionSnapshot().reading)
        assertEquals(0, connection.finishComposingCalls)
        assertTrue(session.inputText("く"))
        assertEquals("きく", connection.text)
    }

    /** 古い短いcomposition通知で現在cursorを巻き戻さない。 */
    @Test
    fun delayedCompositionNotificationDoesNotRewindCursor() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), 0, 0)
        assertTrue(session.inputText("あ"))
        assertTrue(session.inputText("い"))

        session.updateSelection(0, 0, 1, 1, 0, 1)
        assertTrue(session.inputText("う"))

        assertEquals("あいう", connection.text)
        assertEquals("あいう", session.compositionSnapshot().reading)
    }

    /** 空白確定の遅延通知で、その後に始めたcompositionを破棄しない。 */
    @Test
    fun delayedSpaceCommitNotificationKeepsCurrentComposition() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), 0, 0)
        assertTrue(session.inputText("あ"))
        assertTrue(session.insertSpace())
        assertTrue(session.inputText("い"))

        session.updateSelection(2, 2, 3, 3, 2, 3)
        session.updateSelection(1, 1, 2, 2, -1, -1)
        assertTrue(session.inputText("う"))

        assertEquals("あ いう", connection.text)
        assertEquals("いう", session.compositionSnapshot().reading)
        assertEquals(0, connection.finishComposingCalls)
    }

    /** 文字確定と空白確定の通知が入力前・入力間・入力後でも本文を欠落させない。 */
    @Test
    fun commitNotificationsAroundNextInputKeepCurrentComposition() {
        val notificationTimings = listOf(
            CommitNotificationTiming.BEFORE_INPUT,
            CommitNotificationTiming.BETWEEN_INPUTS,
            CommitNotificationTiming.AFTER_INPUT,
        )

        notificationTimings.forEach { timing ->
            val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
            val session = EditorSession(connection, normalPolicy(), 0, 0)
            assertTrue(session.inputText("あ"))
            assertTrue(session.insertSpace())

            if (timing == CommitNotificationTiming.BEFORE_INPUT) {
                notifyCharacterAndSpaceCommitted(session)
            } else if (timing == CommitNotificationTiming.BETWEEN_INPUTS) {
                notifyCharacterCommitted(session)
            }
            assertTrue(session.inputText("い"))
            if (timing == CommitNotificationTiming.BETWEEN_INPUTS) {
                notifySpaceCommitted(session)
            } else if (timing == CommitNotificationTiming.AFTER_INPUT) {
                notifyCharacterAndSpaceCommitted(session)
            }
            assertTrue(session.inputText("う"))

            assertEquals(timing.name, "あ いう", connection.text)
            assertEquals(timing.name, "いう", session.compositionSnapshot().reading)
            assertEquals(timing.name, 0, connection.finishComposingCalls)
        }
    }

    /** 確定履歴と異なるno-span通知では、現在readingを再送せず破棄する。 */
    @Test
    fun currentHostFinishAfterSpaceStillDropsInternalReading() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), 0, 0)
        session.inputText("あ")
        session.insertSpace()
        session.inputText("い")
        connection.hostFinishComposition()

        session.updateSelection(2, 2, 3, 3, -1, -1)
        assertTrue(session.inputText("う"))

        assertEquals("あ いう", connection.text)
        assertEquals("う", session.compositionSnapshot().reading)
    }

    /** span確定が失敗した接続を無効化し、旧readingを再送しない。 */
    @Test
    fun finishFailureInvalidatesSessionWithoutRetry() {
        val connection = FakeEditorConnection().apply { finishAccepted = false }
        val session = EditorSession(connection, normalPolicy())
        assertTrue(session.inputText("かな"))

        session.updateSelection(2, 2, 0, 1, 0, 2)
        assertFalse(session.isActive)
        assertFalse(session.inputText("い"))
        assertEquals(listOf("かな"), connection.composingTexts)
    }

    /** 確定済み文字列の削除とカーソル移動を書記素境界で送る。 */
    @Test
    fun editsExternalTextAtGraphemeBoundaries() {
        val deleteConnection = FakeEditorConnection().apply { beforeCursor = "A👩‍💻" }
        val deleteSession = EditorSession(deleteConnection, normalPolicy(), 6, 6)
        assertTrue(deleteSession.deleteBackward())
        assertEquals(listOf(5 to 0), deleteConnection.deleteCalls)

        val moveConnection = FakeEditorConnection().apply { beforeCursor = "a😀bc" }
        val moveSession = EditorSession(moveConnection, normalPolicy(), 5, 5)
        assertTrue(moveSession.moveCursor(-2))
        assertEquals(listOf(3 to 3), moveConnection.selectionCalls)
    }

    /** composition中の左移動、直前削除、挿入が残った文字の前に反映される。 */
    @Test
    fun insertsBeforeRemainingCompositionAfterCursorMoveAndDelete() {
        val connection = ModelEditorConnection(text = "アイパッ ", selectionStart = 5, selectionEnd = 5)
        val session = EditorSession(connection, normalPolicy(), 5, 5)

        assertTrue(session.inputText("あ"))
        assertTrue(session.inputText("い"))
        assertTrue(session.moveCursor(-1))
        assertEquals(1, session.compositionSnapshot().selectionEnd)
        assertTrue(session.deleteBackward())
        assertEquals("い", session.compositionSnapshot().reading)
        assertEquals(0, session.compositionSnapshot().selectionEnd)
        assertTrue(session.inputText("か"))

        assertEquals("アイパッ かい", connection.text)
        assertEquals(1, session.compositionSnapshot().selectionEnd)
    }

    /** TYPE_NULLではcompositionを作らず、候補も返さず直接確定する。 */
    @Test
    fun typeNullCommitsDirectlyWithoutCandidates() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy(isTypeNull = true))

        assertTrue(session.inputText("A"))
        assertTrue(session.candidateOptions().isEmpty())
        assertTrue(connection.composingTexts.isEmpty())
        assertEquals(listOf("A"), connection.committedTexts)
    }

    /** 単一行では確定後にeditor actionを一度だけ送る。 */
    @Test
    fun enterCommitsThenPerformsEditorActionOnce() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy(imeAction = EditorInfo.IME_ACTION_SEND))
        session.inputText("あ")

        assertTrue(session.handleEnter())
        assertEquals(listOf("あ"), connection.committedTexts)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), connection.editorActions)
    }

    /** 「小゛゜」操作を直前のかなへ順に適用する。 */
    @Test
    fun transformKanaCyclesVoicedAndSemiVoicedForms() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy())
        session.inputText("は")

        assertTrue(session.transformKana())
        assertTrue(session.transformKana())
        assertTrue(session.transformKana())

        assertEquals(listOf("は", "ば", "ぱ", "は"), connection.composingTexts)
    }

    /** 「あ」のcomposition確定通知を送る。 */
    private fun notifyCharacterCommitted(session: EditorSession) {
        session.updateSelection(1, 1, 1, 1, -1, -1)
    }

    /** 空白の確定通知を送る。 */
    private fun notifySpaceCommitted(session: EditorSession) {
        session.updateSelection(1, 1, 2, 2, -1, -1)
    }

    /** 「あ」と空白の確定通知を操作順に送る。 */
    private fun notifyCharacterAndSpaceCommitted(session: EditorSession) {
        notifyCharacterCommitted(session)
        notifySpaceCommitted(session)
    }

    /** テスト対象に必要な通常欄の方針を作る。 */
    private fun normalPolicy(
        isTypeNull: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_DONE,
    ): InputFieldPolicy {
        return InputFieldPolicy(
            isPassword = false,
            noPersonalizedLearning = false,
            isTypeNull = isTypeNull,
            isNumeric = false,
            isMultiLine = false,
            noEnterAction = false,
            imeAction = imeAction,
            actionLabel = "完了",
        )
    }
}

/** 確定通知が次の入力に対して届く時点を表す。 */
private enum class CommitNotificationTiming {
    BEFORE_INPUT,
    BETWEEN_INPUTS,
    AFTER_INPUT,
}

/** EditorSessionが送った操作と失敗条件をメモリ上で記録する。 */
private class FakeEditorConnection : EditorConnectionPort {
    var composingAccepted = true
    var selectionAccepted = true
    var finishAccepted = true
    var beforeCursor: CharSequence? = ""
    var afterCursor: CharSequence? = ""
    var invalidated = false
    val composingTexts = mutableListOf<String>()
    val committedTexts = mutableListOf<String>()
    val deleteCalls = mutableListOf<Pair<Int, Int>>()
    val selectionCalls = mutableListOf<Pair<Int, Int>>()
    val editorActions = mutableListOf<Int>()

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composingTexts += text.toString()
        return !invalidated && composingAccepted
    }

    override fun finishComposingText(): Boolean = !invalidated && finishAccepted

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (invalidated) return false
        committedTexts += text.toString()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (invalidated) return false
        deleteCalls += beforeLength to afterLength
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return !invalidated
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        selectionCalls += start to end
        return !invalidated && selectionAccepted
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (invalidated) return false
        editorActions += actionCode
        return true
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? = if (invalidated) null else beforeCursor

    override fun textAfterCursor(maxChars: Int): CharSequence? = if (invalidated) null else afterCursor

    override fun invalidate() {
        invalidated = true
    }
}

/** InputConnectionの置換規則を再現し、最終本文を検証できる接続を提供する。 */
private class ModelEditorConnection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
) : EditorConnectionPort {
    var text: String = text
        private set
    var finishComposingCalls = 0
        private set
    private var selectionStart = selectionStart
    private var selectionEnd = selectionEnd
    private var composingStart: Int? = null
    private var composingEnd: Int? = null
    private var active = true

    /** host側だけでcomposing spanを終了する。 */
    fun hostFinishComposition() {
        composingStart = null
        composingEnd = null
    }

    /** host側のselectionを、composing spanを残したまま変更する。 */
    fun hostSetSelection(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!active) return false
        val start = composingStart ?: minOf(selectionStart, selectionEnd)
        val end = composingEnd ?: maxOf(selectionStart, selectionEnd)
        replace(start, end, text.toString())
        composingStart = start
        composingEnd = start + text.length
        moveCursorAfterReplacement(start, text.length, newCursorPosition)
        return true
    }

    override fun finishComposingText(): Boolean {
        if (!active) return false
        finishComposingCalls += 1
        hostFinishComposition()
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!active) return false
        val start = composingStart ?: minOf(selectionStart, selectionEnd)
        val end = composingEnd ?: maxOf(selectionStart, selectionEnd)
        replace(start, end, text.toString())
        hostFinishComposition()
        moveCursorAfterReplacement(start, text.length, newCursorPosition)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!active) return false
        val start = (selectionStart - beforeLength).coerceAtLeast(0)
        val end = (selectionEnd + afterLength).coerceAtMost(text.length)
        replace(start, end, "")
        selectionStart = start
        selectionEnd = start
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (!active || start !in 0..text.length || end !in 0..text.length) return false
        selectionStart = start
        selectionEnd = end
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean = active

    override fun textBeforeCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        val end = minOf(selectionStart, selectionEnd)
        return text.substring((end - maxChars).coerceAtLeast(0), end)
    }

    override fun textAfterCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        val start = maxOf(selectionStart, selectionEnd)
        return text.substring(start, (start + maxChars).coerceAtMost(text.length))
    }

    override fun invalidate() {
        active = false
    }

    /** 指定範囲を置換し、本文長に合わせてspan位置を保つ。 */
    private fun replace(start: Int, end: Int, replacement: String) {
        text = text.replaceRange(start, end, replacement)
    }

    /** InputConnectionのnewCursorPosition規則で置換後cursorを更新する。 */
    private fun moveCursorAfterReplacement(start: Int, length: Int, newCursorPosition: Int) {
        val replacementEnd = start + length
        val cursor = if (newCursorPosition > 0) {
            replacementEnd + newCursorPosition - 1
        } else {
            start + newCursorPosition
        }.coerceIn(0, text.length)
        selectionStart = cursor
        selectionEnd = cursor
    }
}
