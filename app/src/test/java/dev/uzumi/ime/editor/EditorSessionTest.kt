package dev.uzumi.ime.editor

import android.view.inputmethod.EditorInfo
import dev.uzumi.ime.conversion.ConversionCandidate
import dev.uzumi.ime.conversion.ConversionClient
import dev.uzumi.ime.conversion.ConversionOutcome
import dev.uzumi.ime.conversion.ConversionRequest
import dev.uzumi.ime.conversion.ConversionResult
import dev.uzumi.ime.conversion.ConversionSegment
import dev.uzumi.ime.conversion.LearnedSegment
import dev.uzumi.ime.conversion.LiveConversionClient
import dev.uzumi.ime.live.DisplaySpan
import dev.uzumi.ime.live.FakeLiveConverter
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
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
    /** 変換は結果を待たずに依頼し、一致する応答だけを表示と候補へ反映して確定する。 */
    @Test
    fun conversionResultIsAppliedAndCommitted() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, sessionEpoch = 3, conversionClient = client)
        assertTrue(session.inputText("かんじ"))

        assertTrue(session.convert())
        val request = client.requests.single()
        assertEquals(ConversionRequest(3, request.revision, "かんじ", incognito = false), request)
        assertEquals("かんじ", connection.text)

        assertTrue(session.applyConversionOutcome(converted(request, "漢字", "感じ")))
        assertEquals("漢字", connection.text)
        val options = session.candidateOptions()
        assertEquals(listOf("漢字", "感じ"), options.map(CandidateOption::value))

        assertTrue(session.selectConversionCandidate(options[1].conversionChoice!!))
        assertEquals("感じ", connection.text)
        assertTrue(session.compositionSnapshot().reading.isEmpty())
        assertEquals(listOf(request to 1), client.committedCandidates)
    }

    /** 応答の前に読みが変わったら、古い応答で新しい入力を上書きしない。 */
    @Test
    fun staleConversionAfterTypingIsDiscarded() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val stale = client.requests.single()

        assertTrue(session.inputText("を"))
        assertFalse(session.applyConversionOutcome(converted(stale, "漢字")))

        assertEquals("かんじを", connection.text)
        assertEquals(listOf("かんじを", "カンジヲ"), session.candidateOptions().map(CandidateOption::value))
    }

    /** 別のフィールドの要求に対する応答は、同じ読み・revisionでも適用しない。 */
    @Test
    fun conversionFromOtherEpochIsDiscarded() {
        val client = FakeConversionClient()
        val oldSession = EditorSession(FakeEditorConnection(), normalPolicy(), sessionEpoch = 1, conversionClient = client)
        oldSession.inputText("かんじ")
        oldSession.convert()
        val oldRequest = client.requests.single()
        oldSession.close()

        val connection = FakeEditorConnection()
        val newSession = EditorSession(connection, normalPolicy(), sessionEpoch = 2, conversionClient = client)
        newSession.inputText("かんじ")
        newSession.convert()

        assertFalse(newSession.applyConversionOutcome(converted(oldRequest, "漢字")))
        assertFalse(oldSession.applyConversionOutcome(converted(oldRequest, "漢字")))
        assertEquals(listOf("かんじ"), connection.composingTexts)
    }

    /** password欄では変換要求を送らず、学習禁止欄ではincognitoを指定した要求にする。 */
    @Test
    fun sensitiveFieldsControlRequestsBeforeSending() {
        val passwordClient = FakeConversionClient()
        val password = EditorSession(
            FakeEditorConnection(),
            normalPolicy().copy(isPassword = true),
            conversionClient = passwordClient,
        )
        password.inputText("かんじ")
        password.convert()
        assertTrue(passwordClient.requests.isEmpty())

        val noLearningClient = FakeConversionClient()
        val noLearning = EditorSession(
            FakeEditorConnection(),
            normalPolicy().copy(noPersonalizedLearning = true),
            conversionClient = noLearningClient,
        )
        noLearning.inputText("かんじ")
        noLearning.convert()
        assertTrue(noLearningClient.requests.single().incognito)
    }

    /** 時間超過後に届いた応答は捨て、次の変換操作では読みのカナ候補へ戻す。 */
    @Test
    fun timedOutConversionFallsBackToKana() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val request = client.requests.single()

        assertTrue(session.abandonConversion(request))
        assertFalse(session.applyConversionOutcome(converted(request, "漢字")))
        assertEquals(listOf("かんじ", "カンジ"), session.candidateOptions().map(CandidateOption::value))

        assertTrue(session.convert())
        assertEquals(1, client.requests.size)
        assertEquals("カンジ", connection.composingTexts.last())
    }

    /** 失敗応答や読みと一致しない文節は適用せず、読みの表示を保つ。 */
    @Test
    fun failedOrInconsistentConversionKeepsReading() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val request = client.requests.single()
        val inconsistent = ConversionOutcome.Converted(
            ConversionResult(request, listOf(ConversionSegment("かん", "漢")), listOf(ConversionCandidate(0, "漢", "かん"))),
        )

        assertFalse(session.applyConversionOutcome(inconsistent))
        assertEquals(listOf("かんじ"), connection.composingTexts)

        session.inputText("を")
        session.convert()
        assertFalse(session.applyConversionOutcome(ConversionOutcome.Failed(client.requests.last())))
        assertEquals("かんじを", connection.composingTexts.last())
    }

    /** 先頭文節の候補を選ぶと、その文節だけ確定し、残りの読みを新しい要求として変換する。 */
    @Test
    fun selectingHeadCandidateKeepsRemainingReading() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("きょうはてんき")
        session.convert()
        val first = client.requests.single()
        val result = ConversionResult(
            first,
            listOf(ConversionSegment("きょうは", "今日は"), ConversionSegment("てんき", "天気")),
            listOf(ConversionCandidate(0, "今日は", "きょうは"), ConversionCandidate(1, "京は", "きょうは")),
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))
        assertEquals("今日は天気", connection.text)

        assertTrue(session.selectConversionCandidate(ConversionChoice(first, 1)))

        assertEquals("京はてんき", connection.text)
        assertEquals("てんき", session.compositionSnapshot().reading)
        val second = client.requests.last()
        assertEquals("てんき", second.reading)
        assertTrue(session.applyConversionOutcome(converted(second, "天気")))
        assertEquals("京は天気", connection.text)
    }

    /** 複数の文節をまとめる候補を選ぶと、その候補が覆う読みを確定し、残りだけを再入力する。 */
    @Test
    fun selectingMultiSegmentCandidateDoesNotDuplicateReading() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("きょうはいいてんき")
        session.convert()
        val first = client.requests.single()
        val result = ConversionResult(
            first,
            listOf(
                ConversionSegment("きょうは", "今日は"),
                ConversionSegment("いい", "いい"),
                ConversionSegment("てんき", "天気"),
            ),
            listOf(
                ConversionCandidate(0, "今日は", "きょうは"),
                ConversionCandidate(5, "今日はいい", "きょうはいい"),
                ConversionCandidate(6, "今日はいい天気", "きょうはいいてんき"),
            ),
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))

        assertTrue(session.selectConversionCandidate(ConversionChoice(first, 5)))
        assertEquals("今日はいいてんき", connection.text)
        assertEquals("てんき", client.requests.last().reading)
        assertTrue(session.applyConversionOutcome(converted(client.requests.last(), "天気")))
        assertTrue(session.commitComposition())
        assertEquals("今日はいい天気", connection.text)

        session.inputText("きょうはいいてんき")
        session.convert()
        val full = client.requests.last()
        assertTrue(
            session.applyConversionOutcome(
                ConversionOutcome.Converted(result.copy(request = full)),
            ),
        )
        assertTrue(session.selectConversionCandidate(ConversionChoice(full, 6)))
        assertEquals("今日はいい天気今日はいい天気", connection.text)
        assertTrue(session.compositionSnapshot().reading.isEmpty())
    }

    /** 変換結果の表示中に次の文字を入力すると、表示中の変換を確定して新しい読みを始める。 */
    @Test
    fun typingAfterConversionCommitsConvertedText() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val request = client.requests.single()
        session.applyConversionOutcome(converted(request, "漢字"))
        session.updateSelection(0, 0, 2, 2, 0, 2)

        assertTrue(session.inputText("を"))

        assertEquals("漢字を", connection.text)
        assertEquals("を", session.compositionSnapshot().reading)
        assertEquals(listOf(request), client.committedAll)
    }

    /** 古い変換結果の候補をタップしても、現在の変換へ適用しない。 */
    @Test
    fun staleCandidateChoiceIsRejected() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val first = client.requests.single()
        session.applyConversionOutcome(converted(first, "漢字", "感じ"))
        val staleChoice = session.candidateOptions()[1].conversionChoice!!
        session.deleteBackward()
        session.inputText("じ")
        session.convert()
        session.applyConversionOutcome(converted(client.requests.last(), "漢字", "幹事"))

        assertFalse(session.selectConversionCandidate(staleChoice))
        assertTrue(connection.committedTexts.isEmpty())
    }

    /** 読み全体を一文節とする変換応答を作る。 */
    private fun converted(request: ConversionRequest, vararg values: String): ConversionOutcome {
        return ConversionOutcome.Converted(
            ConversionResult(
                request,
                listOf(ConversionSegment(request.reading, values.first())),
                values.mapIndexed { index, value -> ConversionCandidate(index, value, request.reading) },
            ),
        )
    }

    /** ライブ変換では、打つたびに変換結果が表示され、句点で確定操作なしに確定して学習へ回る。 */
    @Test
    fun liveTypingShowsConversionAndPeriodCommits() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)

        typeLive(session, client, "きょうは")
        assertEquals("今日は", connection.text)
        assertTrue(client.requests.all { it.first == 5L && it.second.learningAllowed })

        assertTrue(session.inputText("。"))
        assertEquals("今日は。", connection.text)
        assertEquals(listOf(listOf(LearnedSegment("きょうは", "今日は"))), client.learned)
        typeLive(session, client, "よい")
        assertEquals("今日は。良い", connection.text)
    }

    /** 「良い」の末尾で削除すると、読み末尾の「い」だけを消して「よ」を再変換する。 */
    @Test
    fun liveBackspaceOnConvertedSegmentReconvertsShortenedReading() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "よい")
        assertEquals("良い", connection.text)

        assertTrue(session.deleteBackward())
        assertEquals("よ", connection.text)
        assertEquals("よ", client.requests.last().second.targetReading)
        client.deliverLatest(session)
        assertEquals("世", connection.text)
    }

    /** 過去segmentへ移って候補を選び直し、取り消し、末尾へ戻って入力を続けられる。 */
    @Test
    fun livePastSegmentCorrectionUndoAndReturn() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val spans = mutableListOf<DisplaySpan?>()
        val session = liveSession(connection, client, styler = { text, span -> spans += span; text })
        typeLive(session, client, "きょうはてんきがいいですね")
        assertEquals("今日は天気がいいですね", connection.text)
        assertTrue(session.liveCandidateState()!!.focusAtInput)

        assertTrue(session.moveLiveFocus(-1))
        val state = session.liveCandidateState()!!
        assertEquals("天気が", state.currentValue)
        assertFalse(state.focusAtInput)
        // 候補バーに、どの文節を直しているかを読みで示す
        assertEquals("てんきが", state.focusedReading)
        assertEquals(DisplaySpan(spans.last()!!.segmentId, 3, 6, spans.last()!!.state, true, true), spans.last())

        assertTrue(session.selectLiveCandidate(state.choices.first { it.value == "転機が" }))
        assertEquals("今日は転機がいいですね", connection.text)
        assertTrue(session.liveCandidateState()!!.canUndo)
        assertTrue(session.undoLive())
        assertEquals("今日は天気がいいですね", connection.text)

        assertTrue(session.returnLiveFocusToInput())
        assertTrue(session.liveCandidateState()!!.focusAtInput)
        typeLive(session, client, "よ")
        assertEquals("今日は天気がいいですね世", connection.text)
    }

    /** 変換済み表示の内部をEditorでタップすると、そのsegmentを候補バーの対象にする。 */
    @Test
    fun liveTapInsideConvertedSegmentFocusesIt() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "きょうはてんきがいいですね")

        session.updateSelection(11, 11, 4, 4, 0, 11)

        assertEquals("天気が", session.liveCandidateState()!!.currentValue)
        assertEquals("今日は天気がいいですね", connection.text)
    }

    /** Enterは表示を確定してからEditor actionを一度だけ送り、改行欄では改行を一度だけ入れる。 */
    @Test
    fun liveEnterCommitsThenActsOnce() {
        val sendConnection = FakeEditorConnection()
        val client = FakeLiveClient()
        val send = liveSession(sendConnection, client, normalPolicy(imeAction = EditorInfo.IME_ACTION_SEND))
        typeLive(send, client, "よい")
        assertTrue(send.handleEnter())
        assertEquals(listOf("良い"), sendConnection.committedTexts)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), sendConnection.editorActions)

        val newlineConnection = FakeEditorConnection()
        val newlineClient = FakeLiveClient()
        val newline = liveSession(newlineConnection, newlineClient, normalPolicy().copy(isMultiLine = true))
        typeLive(newline, newlineClient, "よい")
        assertTrue(newline.handleEnter())
        assertEquals(listOf("良い", "\n"), newlineConnection.committedTexts)
        assertTrue(newlineConnection.editorActions.isEmpty())
    }

    /** password欄ではライブ変換の要求を出さず、学習禁止欄では要求へ学習禁止を付けて確定を学習させない。 */
    @Test
    fun liveSensitiveFieldsControlRequestsAndLearning() {
        val passwordClient = FakeLiveClient()
        val password = liveSession(FakeEditorConnection(), passwordClient, normalPolicy().copy(isPassword = true))
        typeLive(password, passwordClient, "abc")
        assertTrue(passwordClient.requests.isEmpty())

        val noLearningClient = FakeLiveClient()
        val noLearning = liveSession(
            FakeEditorConnection(),
            noLearningClient,
            normalPolicy().copy(noPersonalizedLearning = true),
        )
        typeLive(noLearning, noLearningClient, "よい。")
        assertTrue(noLearningClient.requests.isNotEmpty())
        assertTrue(noLearningClient.requests.none { it.second.learningAllowed })
        assertTrue(noLearningClient.learned.isEmpty())
    }

    /** 変換エンジンが使えない間は要求を送らず、かな・カナを候補にする。 */
    @Test
    fun liveWithoutEngineOffersKanaCandidates() {
        val client = FakeLiveClient().apply { available = false }
        val session = liveSession(FakeEditorConnection(), client)

        assertTrue(session.inputText("かな"))

        assertTrue(client.requests.isEmpty())
        assertEquals(listOf("かな", "カナ"), session.liveCandidateState()!!.choices.map { it.value })
    }

    /** 後から入力した読みの結果より前に出した要求の結果は、表示へ反映しない。 */
    @Test
    fun liveStaleResultIsNotApplied() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        session.inputText("よ")
        val older = client.result(0)
        session.inputText("い")

        assertFalse(session.applyLiveResult(older))
        assertEquals("よい", connection.text)
        client.deliverLatest(session)
        assertEquals("良い", connection.text)
    }

    /** 明示変換で登録語の候補を確定しても、エンジンの候補ではないため確定を通知しない。 */
    @Test
    fun explicitUserDictionaryCandidateIsNotReportedToEngine() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val request = client.requests.single()
        val result = ConversionResult(
            request,
            listOf(ConversionSegment("かんじ", "漢字")),
            listOf(
                ConversionCandidate(Int.MIN_VALUE, "幹事", "かんじ", fromUserDictionary = true),
                ConversionCandidate(0, "漢字", "かんじ"),
            ),
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))

        val option = session.candidateOptions().first()
        assertEquals("幹事", option.value)
        assertTrue(session.selectConversionCandidate(option.conversionChoice!!))
        assertEquals(listOf("幹事"), connection.committedTexts)
        assertTrue(client.committedCandidates.isEmpty())
    }

    /**
     * 明示変換の表示中に削除すると読みへ戻り、自動では再変換しない。続けて入力しても、
     * 削除前の変換や別の変換結果を確定せず、読みへ文字を足す（「良い」→削除→「よ」→「る」で「よる」）。
     */
    @Test
    fun explicitDeleteDuringConversionReturnsToReadingWithoutReconverting() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("よい")
        session.convert()
        assertTrue(session.applyConversionOutcome(converted(client.requests.single(), "良い")))
        assertEquals("良い", connection.text)

        assertTrue(session.deleteBackward())
        assertEquals("よ", connection.text)
        assertEquals(1, client.requests.size)

        assertTrue(session.inputText("る"))
        assertEquals("よる", connection.text)
        assertEquals("よる", session.compositionSnapshot().reading)
        assertTrue(client.committedAll.isEmpty())
    }

    /** 明示変換で読み全体の登録語を表示したまま確定しても、エンジンへ確定を学習させない。 */
    @Test
    fun explicitCommitOfUserDictionaryDisplayIsNotReportedToEngine() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("うずみ")
        session.convert()
        val result = ConversionResult(
            client.requests.single(),
            listOf(ConversionSegment("うずみ", "Uzumi", fromUserDictionary = true)),
            listOf(ConversionCandidate(Int.MIN_VALUE, "Uzumi", "うずみ", fromUserDictionary = true)),
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))
        assertEquals("Uzumi", connection.composingTexts.last())

        assertTrue(session.commitComposition())
        assertEquals(listOf("Uzumi"), connection.committedTexts)
        assertTrue(client.committedAll.isEmpty())
    }

    /** 12キーの「カナ」は、変換前の読みをカタカナにし、入力中の状態をキーボードへ伝えられる。 */
    @Test
    fun katakanaKeyReplacesReadingAndCompositionStateIsReported() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy())
        assertFalse(session.hasComposition)
        assertFalse(session.toKatakana())

        assertTrue(session.inputText("かな"))
        assertTrue(session.hasComposition)
        assertTrue(session.toKatakana())
        assertEquals("カナ", session.compositionSnapshot().display)
        assertTrue(session.commitComposition())
        assertFalse(session.hasComposition)
    }

    /** ライブ変換の「カナ」は、カタカナ表記の候補が無い文節では表示を変えない。 */
    @Test
    fun liveKatakanaKeyKeepsDisplayWithoutKatakanaCandidate() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        assertFalse(session.hasComposition)
        typeLive(session, client, "きょうは")
        assertTrue(session.hasComposition)

        assertFalse(session.toKatakana())
        assertEquals("今日は", connection.text)
    }

    /** 左ドラッグで消す範囲は同じ行の行頭までで、行頭では直前の改行一つになる。 */
    @Test
    fun lineDeleteTargetStopsAtLineStart() {
        assertEquals("持ち物は水筒", lineDeleteTarget("明日は駅前\n持ち物は水筒"))
        assertEquals("\n", lineDeleteTarget("明日は駅前\n"))
        assertEquals("明日は駅前", lineDeleteTarget("明日は駅前"))
        assertEquals("", lineDeleteTarget(""))
    }

    /** 確定済みの行を消して元に戻せる。前後の文字列が変わっていれば戻さない。 */
    @Test
    fun deleteToLineStartAndUndoOnlyWhenSurroundingTextMatches() {
        val text = "明日は駅前\n持ち物は水筒"
        val connection = ModelEditorConnection(text = text, selectionStart = text.length, selectionEnd = text.length)
        val session = EditorSession(connection, normalPolicy(), initialSelectionStart = text.length, initialSelectionEnd = text.length)
        assertEquals(6, session.lineDeleteLength())

        assertTrue(session.deleteToLineStart())
        assertEquals("明日は駅前\n", connection.text)
        assertTrue(session.canUndoLineDelete)
        assertTrue(session.undoLineDelete())
        assertEquals(text, connection.text)
        assertFalse(session.canUndoLineDelete)

        // 消した後に別の文字を入れた場合は、周りの文字列が一致しないため戻さない
        assertTrue(session.deleteToLineStart())
        connection.commitText("雨", 1)
        assertFalse(session.undoLineDelete())
        assertEquals("明日は駅前\n雨", connection.text)
    }

    /** 機密欄では消した文字列を覚えず、元に戻す入口を出さない。 */
    @Test
    fun deleteToLineStartKeepsNothingInSensitiveField() {
        val connection = ModelEditorConnection(text = "secret", selectionStart = 6, selectionEnd = 6)
        val policy = normalPolicy().copy(isPassword = true)
        val session = EditorSession(connection, policy, initialSelectionStart = 6, initialSelectionEnd = 6)

        assertTrue(session.deleteToLineStart())
        assertEquals("", connection.text)
        assertFalse(session.canUndoLineDelete)
        assertFalse(session.undoLineDelete())
    }

    /** 設定で学習を止めても欄は機密ではないため、変換は学習禁止で送り、削除の「元に戻す」は使える。 */
    @Test
    fun learningDisabledBySettingIsNotTreatedAsSensitive() {
        val policy = normalPolicy().copy(learningDisabledBySetting = true)
        assertTrue(policy.suppressLearning)
        assertFalse(policy.isSensitive)
        val connection = ModelEditorConnection(text = "明日", selectionStart = 2, selectionEnd = 2)
        val session = EditorSession(connection, policy, initialSelectionStart = 2, initialSelectionEnd = 2)
        assertTrue(session.deleteToLineStart())
        assertTrue(session.canUndoLineDelete)

        val client = FakeLiveClient()
        val live = liveSession(ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0), client, policy)
        typeLive(live, client, "きょうは")
        assertTrue(client.requests.none { it.second.learningAllowed })
    }

    /** 入力中の左ドラッグは未確定の読みだけを消し、元に戻すと読みを入力し直す。 */
    @Test
    fun deleteToLineStartClearsOnlyCompositionAndUndoRetypesReading() {
        val connection = ModelEditorConnection(text = "明日\n", selectionStart = 3, selectionEnd = 3)
        val session = EditorSession(connection, normalPolicy(), initialSelectionStart = 3, initialSelectionEnd = 3)
        assertTrue(session.inputText("かさ"))
        assertEquals("明日\nかさ", connection.text)
        assertEquals(2, session.lineDeleteLength())

        assertTrue(session.deleteToLineStart())
        assertEquals("明日\n", connection.text)
        assertFalse(session.hasComposition)
        assertTrue(session.undoLineDelete())
        assertEquals("明日\nかさ", connection.text)
        assertTrue(session.hasComposition)
    }

    /** ライブ変換の入力中も、変換中の表示だけを消す。 */
    @Test
    fun liveDeleteToLineStartClearsConvertedComposition() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "きょうは")
        assertEquals("今日は", connection.text)

        assertTrue(session.deleteToLineStart())
        assertEquals("", connection.text)
        assertFalse(session.hasComposition)
        assertTrue(session.canUndoLineDelete)
    }

    /** ライブ変換の編集セッションを作る。変換要求はclientへ記録され、テストが結果を返す。 */
    private fun liveSession(
        connection: EditorConnectionPort,
        client: FakeLiveClient,
        policy: InputFieldPolicy = normalPolicy(),
        styler: (String, DisplaySpan?) -> CharSequence = { text, _ -> text },
    ): EditorSession {
        return EditorSession(
            connection = connection,
            policy = policy,
            initialSelectionStart = 0,
            initialSelectionEnd = 0,
            sessionEpoch = 5,
            liveCore = LiveConversionCore(),
            liveClient = client,
            compositionStyler = styler,
        )
    }

    /** 一書記素ずつ入力し、そのたびに新しい要求があれば結果を返す。 */
    private fun typeLive(session: EditorSession, client: FakeLiveClient, text: String) {
        for (cluster in GraphemeClusters.split(text)) {
            session.inputText(cluster)
            client.deliverLatest(session)
        }
    }

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

/** 変換要求と学習通知を記録するだけの、常に利用可能な偽の変換窓口。 */
private class FakeConversionClient : ConversionClient {
    val requests = mutableListOf<ConversionRequest>()
    val committedCandidates = mutableListOf<Pair<ConversionRequest, Int>>()
    val committedAll = mutableListOf<ConversionRequest>()

    override val isAvailable: Boolean = true

    override fun requestConversion(request: ConversionRequest) {
        requests += request
    }

    override fun commitCandidate(request: ConversionRequest, candidateId: Int) {
        committedCandidates += request to candidateId
    }

    override fun commitAll(request: ConversionRequest) {
        committedAll += request
    }
}

/** ライブ変換の要求と学習を記録し、試験用の語彙で結果を作る偽の窓口。 */
private class FakeLiveClient : LiveConversionClient {
    var available = true
    val requests = mutableListOf<Pair<Long, LiveRequest>>()
    val learned = mutableListOf<List<LearnedSegment>>()
    private val converter = FakeLiveConverter(FakeLiveConverter.LEXICON)
    // 結果を返し終えた要求の数。新しい要求だけを返すために使う。
    private var delivered = 0

    override val isAvailable: Boolean
        get() = available

    override fun requestLiveConversion(sessionEpoch: Long, request: LiveRequest) {
        requests += sessionEpoch to request
    }

    override fun learnCommitted(sessionEpoch: Long, units: List<List<LearnedSegment>>) {
        learned += units
    }

    /** index番目の要求を変換した結果。 */
    fun result(index: Int) = converter.convert(requests[index].second)

    /** まだ結果を返していない要求があれば、最新の要求の結果を返す。 */
    fun deliverLatest(session: EditorSession) {
        if (requests.size == delivered) return
        delivered = requests.size
        session.applyLiveResult(result(requests.lastIndex))
    }
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
