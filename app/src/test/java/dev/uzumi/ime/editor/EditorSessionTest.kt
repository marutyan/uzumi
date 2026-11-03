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
import dev.uzumi.ime.conversion.PredictionCandidate
import dev.uzumi.ime.conversion.PredictionClient
import dev.uzumi.ime.conversion.PredictionRequest
import dev.uzumi.ime.conversion.PredictionResult
import dev.uzumi.ime.evaluation.EvaluationCounter
import dev.uzumi.ime.live.CandidateRequest
import dev.uzumi.ime.live.CandidateResult
import dev.uzumi.ime.live.DisplaySpan
import dev.uzumi.ime.live.FakeLiveConverter
import dev.uzumi.ime.live.FixedRange
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.ConversionRequest as LiveRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** InputConnectionの成功・失敗と編集状態の同期境界を検証する。 */
class EditorSessionTest {
    /** 予測応答は読みの世代が一致するときだけ表示し、選択すると未確定全体を確定する。 */
    @Test
    fun typedPredictionReplacesWholeCompositionAndRejectsStaleResponse() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakePredictionClient()
        val session = EditorSession(connection, normalPolicy(), sessionEpoch = 4, predictionClient = client)
        assertTrue(session.inputText("ありがと"))

        assertTrue(session.refreshPredictions())
        val oldRequest = client.requests.single()
        assertEquals("ありがと", oldRequest.reading)
        assertTrue(session.inputText("う"))
        assertFalse(session.applyPredictionResult(PredictionResult(oldRequest, listOf(PredictionCandidate(1, "ありがとう")))))
        assertTrue(session.predictionCandidates().isEmpty())

        assertTrue(session.refreshPredictions())
        val request = client.requests.last()
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(PredictionCandidate(2, "ありがとうございます")))))
        assertTrue(session.selectPrediction(request, candidateId = 2))

        assertEquals("ありがとうございます", connection.text)
        assertFalse(session.hasComposition)
        assertEquals(listOf(request to PredictionCandidate(2, "ありがとうございます")), client.committed)
    }

    /** 入力途中のローマ字は予測の読みへ含めず、予測を選ぶと保留ごと未確定全体を置き換える。 */
    @Test
    fun predictionExcludesPendingRomajiAndDiscardsItOnSelection() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakePredictionClient()
        val session = EditorSession(connection, normalPolicy(), predictionClient = client, romajiInputEnabled = true)
        typeRomaji(session, "arigak")
        assertEquals("ありがk", connection.text)

        assertTrue(session.refreshPredictions())
        val request = client.requests.single()
        assertEquals("ありが", request.reading)
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(PredictionCandidate(5, "ありがとう")))))
        assertTrue(session.selectPrediction(request, candidateId = 5))

        assertEquals("ありがとう", connection.text)
        assertFalse(session.hasComposition)
        // 捨てた保留が次の入力へ残らない。
        typeRomaji(session, "a")
        assertEquals("ありがとうあ", connection.text)
    }

    /** IDを持たない複数の英語候補を表記で区別し、選んだ表記をそのまま確定する。 */
    @Test
    fun selectsExactIdlessEnglishPredictionAmongSameNullIds() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakePredictionClient()
        val session = EditorSession(connection, normalPolicy(), predictionClient = client)
        assertTrue(session.inputText("いーめーる"))
        assertTrue(session.refreshPredictions())
        val request = client.requests.single()
        val candidates = listOf(
            PredictionCandidate(4, "メール", "いーめーる"),
            PredictionCandidate(null, "email", "いーめーる", englishVariant = true),
            PredictionCandidate(null, "Email", "いーめーる", englishVariant = true),
            PredictionCandidate(null, "E-mail", "いーめーる", englishVariant = true),
        )
        assertTrue(session.applyPredictionResult(PredictionResult(request, candidates)))

        assertTrue(session.selectPrediction(request, candidates[2]))

        assertEquals("Email", connection.text)
        assertEquals(listOf(request to candidates[2]), client.committed)
    }

    /** Mozc候補が無い、または表示中の値と重なって除外されたとき、英語候補だけを先頭へ出さない。 */
    @Test
    fun hidesEnglishPredictionsWithoutVisibleMozcCandidateBeforeThem() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakePredictionClient()
        val session = EditorSession(connection, normalPolicy(), predictionClient = client)
        assertTrue(session.inputText("いーめーる"))
        assertTrue(session.refreshPredictions())
        val request = client.requests.single()
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(
            PredictionCandidate(1, "いーめーる", "いーめーる"),
            PredictionCandidate(null, "Email", "いーめーる", englishVariant = true),
        ))))

        assertTrue(session.predictionResult()!!.candidates.isEmpty())
    }

    /** 入力全体の候補から、本文に表示中のライブ変換の表記と注目segmentの候補と同じ表記を除く。全部重なれば0件にする。 */
    @Test
    fun livePredictionsExcludeDisplayedValueAndRegularCandidates() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val liveClient = FakeLiveClient()
        val predictionClient = FakePredictionClient()
        val session = EditorSession(
            connection = connection,
            policy = normalPolicy(),
            sessionEpoch = 7,
            liveCore = LiveConversionCore(),
            liveClient = liveClient,
            predictionClient = predictionClient,
        )
        assertTrue(session.inputText("きょうは"))
        liveClient.deliverLatest(session)
        assertEquals("今日は", connection.text)
        assertTrue(session.refreshPredictions())
        val request = predictionClient.requests.single()
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(
            PredictionCandidate(1, "今日は"),
            PredictionCandidate(2, "京は"),
            PredictionCandidate(3, "今日はいい天気"),
            PredictionCandidate(4, "キョウハ"),
        ))))

        assertEquals(listOf("今日はいい天気", "キョウハ"), session.predictionResult()!!.candidates.map { it.value })
        // 除いた候補が無くても、残った候補は元の候補IDで選べる。
        assertTrue(session.selectPrediction(request, candidateId = 3))
        assertEquals("今日はいい天気", connection.text)
    }

    /** 変換前の入力では、表示中の読みと基本候補（カタカナ）と同じ表記を除き、重複だけなら予測を0件にする。 */
    @Test
    fun predictionsExcludeBasicCandidatesBeforeConversion() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakePredictionClient()
        val session = EditorSession(connection, normalPolicy(), sessionEpoch = 8, predictionClient = client)
        assertTrue(session.inputText("ありがと"))
        assertTrue(session.refreshPredictions())
        val request = client.requests.single()
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(
            PredictionCandidate(1, "ありがと"),
            PredictionCandidate(2, "アリガト"),
        ))))

        assertTrue(session.predictionResult()!!.candidates.isEmpty())
        assertTrue(session.predictionCandidates().isEmpty())
    }

    /** ライブ変換の読みとsegmentを予測語の確定後に捨て、二重確定と旧segment学習を防ぐ。 */
    @Test
    fun livePredictionClearsCoreWithoutLearningOldSegments() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val liveClient = FakeLiveClient()
        val predictionClient = FakePredictionClient()
        val core = LiveConversionCore()
        val session = EditorSession(
            connection = connection,
            policy = normalPolicy(),
            sessionEpoch = 6,
            liveCore = core,
            liveClient = liveClient,
            predictionClient = predictionClient,
        )
        assertTrue(session.inputText("ありがと"))
        assertTrue(session.refreshPredictions())
        val request = predictionClient.requests.single()
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(PredictionCandidate(4, "ありがとう")))))

        assertTrue(session.selectPrediction(request, candidateId = 4))

        assertEquals("ありがとう", connection.text)
        assertEquals("", core.display)
        assertFalse(session.hasComposition)
        assertTrue(liveClient.learned.isEmpty())
        assertEquals(listOf(request to PredictionCandidate(4, "ありがとう")), predictionClient.committed)
    }

    /** ゼロクエリは直前の確定文脈だけを使い、選択語をカーソル位置へ直接確定する。 */
    @Test
    fun zeroQueryPredictionUsesCommittedContextAndCommitsAtCursor() {
        val connection = ModelEditorConnection(text = "きょうは晴れ", selectionStart = 4, selectionEnd = 4)
        val client = FakePredictionClient()
        val session = EditorSession(connection, normalPolicy(), initialSelectionStart = 4, sessionEpoch = 5, predictionClient = client)

        assertTrue(session.refreshPredictions())
        val request = client.requests.single()
        assertTrue(request.isZeroQuery)
        assertEquals("きょうは", request.precedingText)
        assertTrue(session.applyPredictionResult(PredictionResult(request, listOf(PredictionCandidate(3, "天気")))))
        assertTrue(session.selectPrediction(request, candidateId = 3))

        assertEquals("きょうは天気晴れ", connection.text)
        assertEquals(listOf(request to PredictionCandidate(3, "天気")), client.committed)
    }

    /** ゼロクエリは機密・学習禁止・数値・URLやメールの欄、または選択範囲がある状態から本文を読まずに抑止する。 */
    @Test
    fun zeroQueryPredictionDoesNotReadProtectedOrSelectedText() {
        val policies = listOf(
            normalPolicy().copy(isPassword = true),
            normalPolicy().copy(noPersonalizedLearning = true),
            normalPolicy().copy(learningDisabledBySetting = true),
            normalPolicy().copy(isNumeric = true),
            normalPolicy().copy(isTypeNull = true),
            normalPolicy().copy(isAddressLike = true),
        )
        policies.forEach { policy ->
            val client = FakePredictionClient()
            val connection = ModelEditorConnection(text = "secret", selectionStart = 6, selectionEnd = 6)
            val session = EditorSession(
                connection,
                policy,
                initialSelectionStart = 6,
                predictionClient = client,
            )
            assertFalse(session.refreshPredictions())
            assertTrue(client.requests.isEmpty())
            assertEquals(0, connection.textBeforeCursorCalls)
        }

        val selectedClient = FakePredictionClient()
        val selectedConnection = ModelEditorConnection(text = "本文", selectionStart = 0, selectionEnd = 2)
        val selected = EditorSession(
            selectedConnection,
            normalPolicy(),
            initialSelectionStart = 0,
            initialSelectionEnd = 2,
            predictionClient = selectedClient,
        )
        assertFalse(selected.refreshPredictions())
        assertTrue(selectedClient.requests.isEmpty())
        assertEquals(0, selectedConnection.textBeforeCursorCalls)
    }

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

    /** 貼り付けでは既存のcompositionを先に確定し、空白・改行を変換せずそのまま送る。 */
    @Test
    fun pasteTextKeepsWhitespaceAndFinishesCompositionFirst() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        val pasted = "  line one\n\tline two  "

        assertTrue(session.inputText("かな"))
        assertTrue(session.pasteText(pasted))

        assertEquals(listOf("かな", pasted), connection.committedTexts)
        assertTrue(session.compositionSnapshot().reading.isEmpty())
        assertTrue(client.requests.isEmpty())
        assertTrue(client.committedAll.isEmpty())
    }

    /** 一覧で選んだ絵文字列も未確定の読みを先に確定し、変換・学習へ渡さずそのまま入力する。 */
    @Test
    fun emojiPickerSelectionFinishesCompositionAndKeepsTheSequence() {
        val connection = FakeEditorConnection()
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        val emoji = "👩‍💻"

        assertTrue(session.inputText("かな"))
        assertTrue(session.pasteText(emoji))

        assertEquals(listOf("かな", emoji), connection.committedTexts)
        assertTrue(session.compositionSnapshot().reading.isEmpty())
        assertTrue(client.requests.isEmpty())
        assertTrue(client.committedAll.isEmpty())
    }

    /** 貼り付け本文はライブ変換要求と個人学習へ渡さない。 */
    @Test
    fun pasteTextDoesNotEnterLiveConversionOrLearning() {
        val connection = FakeEditorConnection()
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        val pasted = "one\n two\t"

        assertTrue(session.pasteText(pasted))

        assertEquals(listOf(pasted), connection.committedTexts)
        assertTrue(client.requests.isEmpty())
        assertTrue(client.learned.isEmpty())
    }

    /** 貼り付けの書込み失敗後に、同じ内容を二重送信しない。 */
    @Test
    fun failedPasteInvalidatesSessionWithoutRetry() {
        val connection = FakeEditorConnection().apply { commitAccepted = false }
        val session = EditorSession(connection, normalPolicy())

        assertFalse(session.pasteText("single"))
        assertFalse(session.pasteText("single"))

        assertEquals(listOf("single"), connection.committedTexts)
        assertFalse(session.isActive)
        assertTrue(connection.invalidated)
    }

    /** compositionの確定に失敗した場合は、貼り付け本文を送らない。 */
    @Test
    fun pasteDoesNotContinueWhenCompositionCommitFails() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy())
        assertTrue(session.inputText("かな"))
        connection.commitAccepted = false

        assertFalse(session.pasteText("clipboard"))

        assertEquals(listOf("かな"), connection.committedTexts)
        assertFalse(session.isActive)
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

    /** 範囲選択中の←→は、Android標準と同じく←で選択の開始、→で終了へ畳む。全選択でも動く。 */
    @Test
    fun arrowsCollapseExternalSelectionToItsEdge() {
        val left = ModelEditorConnection(text = "abcdef", selectionStart = 0, selectionEnd = 6)
        val leftSession = EditorSession(left, normalPolicy(), 0, 6)
        assertTrue(leftSession.moveCursor(-1))
        assertEquals("", left.selectedText())
        assertEquals("", left.textBeforeCursor(10))

        val right = ModelEditorConnection(text = "abcdef", selectionStart = 2, selectionEnd = 4)
        val rightSession = EditorSession(right, normalPolicy(), 2, 4)
        assertTrue(rightSession.moveCursor(1))
        assertEquals("abcd", right.textBeforeCursor(10))
        assertEquals("", right.selectedText())

        // 畳んだ後は、通常の書記素単位の移動に戻る。
        assertTrue(rightSession.moveCursor(1))
        assertEquals("abcde", right.textBeforeCursor(10))
    }

    /**
     * 未確定文字の先頭にカーソルがあるBackspaceは、直前の確定済みの一書記素を消し、未確定はそのまま残す。
     * ずれたcompositionの選択通知を外部の変更と誤らず、続けて入力できる。
     */
    @Test
    fun backspaceAtCompositionStartDeletesCommittedTextBefore() {
        val connection = ModelEditorConnection(text = "漢字", selectionStart = 2, selectionEnd = 2)
        val session = EditorSession(connection, normalPolicy(), 2, 2)
        assertTrue(session.inputText("あ"))
        assertTrue(session.inputText("い"))
        assertTrue(session.moveCursor(-2))

        assertTrue(session.deleteBackward())
        assertEquals("漢あい", connection.text)
        assertEquals("あい", session.compositionSnapshot().reading)
        assertFalse(session.canUndoDeletion)
        session.updateSelection(2, 2, 1, 1, 1, 3)
        assertTrue(session.hasComposition)

        assertTrue(session.inputText("か"))
        assertEquals("漢かあい", connection.text)
        assertTrue(session.commitComposition())
        assertEquals("漢かあい", connection.text)
    }

    /** ライブ変換でも、未確定文字の先頭でのBackspaceは前の確定済み文字を消し、未確定の表示を保つ。 */
    @Test
    fun liveBackspaceAtCompositionStartDeletesCommittedTextBefore() {
        val connection = ModelEditorConnection(text = "漢", selectionStart = 1, selectionEnd = 1)
        val client = FakeLiveClient()
        val session = EditorSession(
            connection = connection,
            policy = normalPolicy(),
            initialSelectionStart = 1,
            initialSelectionEnd = 1,
            liveCore = LiveConversionCore(),
            liveClient = client,
        )
        typeLive(session, client, "よい")
        val displayed = connection.text.substring(1)
        assertTrue(session.moveCursor(-2))

        assertTrue(session.deleteBackward())
        assertEquals(displayed, connection.text)
        session.updateSelection(1, 1, 0, 0, 0, displayed.length)
        assertTrue(session.hasComposition)

        assertTrue(session.commitComposition())
        assertEquals(displayed, connection.text)
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

    /**
     * TYPE_NULLの欄では周辺文字列を読めなくても、BackspaceをDeleteキーのイベントとして送る。
     * 通常の欄で周辺文字列の取得に一時的に失敗した場合は、キーイベントを送らず何もしない。
     */
    @Test
    fun typeNullBackspaceSendsDeleteKeyButNormalFieldDoesNot() {
        val typeNull = FakeEditorConnection().apply { beforeCursor = null }
        val terminal = EditorSession(typeNull, normalPolicy(isTypeNull = true))
        assertTrue(terminal.inputText("a"))

        assertTrue(terminal.deleteBackward())
        assertEquals(1, typeNull.deleteKeys)
        assertTrue(typeNull.deleteCalls.isEmpty())
        assertFalse(terminal.canUndoDeletion)

        val normal = FakeEditorConnection().apply { beforeCursor = null }
        val session = EditorSession(normal, normalPolicy())
        assertFalse(session.deleteBackward())
        assertEquals(0, normal.deleteKeys)
    }

    /**
     * パスワード・数値の欄ではcompositionを作らず一文字ずつ確定し、最初のEnterでeditor actionを送る。
     * パスワード欄で入力した文字を消しても、「元に戻す」のために本文を覚えない。
     */
    @Test
    fun passwordAndNumericFieldsCommitDirectlyAndEnterActsOnce() {
        listOf(
            normalPolicy(imeAction = EditorInfo.IME_ACTION_DONE).copy(isPassword = true),
            normalPolicy(imeAction = EditorInfo.IME_ACTION_NEXT).copy(isNumeric = true),
        ).forEach { policy ->
            val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
            val session = EditorSession(connection, policy, initialSelectionStart = 0)

            assertTrue(session.inputText("1"))
            assertTrue(session.inputText("2"))
            assertFalse(session.hasComposition)
            assertEquals("12", connection.text)

            assertTrue(session.deleteBackward())
            assertEquals("1", connection.text)
            assertEquals(policy.isPassword, !session.canUndoDeletion)

            val actions = FakeEditorConnection()
            val enterSession = EditorSession(actions, policy)
            assertTrue(enterSession.inputText("1"))
            assertTrue(enterSession.handleEnter())
            assertEquals(listOf("1"), actions.committedTexts)
            assertEquals(listOf(policy.imeAction), actions.editorActions)
        }
    }

    /** 未確定文字がある間のEnterは確定だけを行い、次のEnterでeditor actionを一度だけ送る。 */
    @Test
    fun enterCommitsFirstThenPerformsEditorActionOnSecondPress() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy(imeAction = EditorInfo.IME_ACTION_SEND))
        session.inputText("あ")

        assertTrue(session.handleEnter())
        assertEquals(listOf("あ"), connection.committedTexts)
        assertTrue(connection.editorActions.isEmpty())

        assertTrue(session.handleEnter())
        assertEquals(listOf("あ"), connection.committedTexts)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), connection.editorActions)
    }

    /** actionの指定がない単一行欄では、確定後のEnterでEnterキーのイベントを送り、複数行の検索欄では検索actionを送る。 */
    @Test
    fun enterSendsKeyEventOrSearchActionByField() {
        val keyConnection = FakeEditorConnection()
        val keySession = EditorSession(keyConnection, normalPolicy(imeAction = EditorInfo.IME_ACTION_UNSPECIFIED))
        keySession.inputText("あ")
        assertTrue(keySession.handleEnter())
        assertEquals(0, keyConnection.enterKeys)
        assertTrue(keySession.handleEnter())
        assertEquals(1, keyConnection.enterKeys)
        assertTrue(keyConnection.editorActions.isEmpty())

        val searchConnection = FakeEditorConnection()
        val search = EditorSession(
            searchConnection,
            normalPolicy(imeAction = EditorInfo.IME_ACTION_SEARCH).copy(isMultiLine = true),
        )
        assertTrue(search.handleEnter())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), searchConnection.editorActions)
        assertTrue(searchConnection.committedTexts.isEmpty())
    }

    /**
     * 同じ欄の再開始（画面回転など）では、表示中の未確定文字列をそのまま確定してから閉じる。
     * 確定しないと、次のセッションの最初の入力が以前の未確定本文全体を置き換える。
     */
    @Test
    fun restartFinishesDisplayedCompositionBeforeNextSession() {
        val editor = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        // 回転では同じInputConnectionで再開始する。旧セッションの窓口だけを無効化し、Editorは生かしておく。
        val oldPort = object : EditorConnectionPort by editor {
            override fun invalidate() = Unit
        }
        val old = EditorSession(oldPort, normalPolicy())
        assertTrue(old.inputText("かな"))
        assertTrue(old.applyCandidate("カナ"))

        old.closeForRestart(editor)

        assertFalse(old.isActive)
        assertEquals(1, editor.finishComposingCalls)
        val restarted = EditorSession(editor, normalPolicy(), initialSelectionStart = 2)
        assertTrue(restarted.inputText("あ"))
        assertEquals("カナあ", editor.text)
    }

    /** 未確定文字がなければ、再開始でも接続へ何も送らない。 */
    @Test
    fun restartWithoutCompositionSendsNothing() {
        val editor = ModelEditorConnection(text = "済", selectionStart = 1, selectionEnd = 1)
        val session = EditorSession(FakeEditorConnection(), normalPolicy(), initialSelectionStart = 1)

        session.closeForRestart(editor)

        assertEquals(0, editor.finishComposingCalls)
        assertEquals("済", editor.text)
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
    /** 変換は結果を待たずに依頼し、候補選択後も全文節を未確定のまま保って確定する。 */
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
        assertTrue(options.first().selected)

        assertTrue(session.selectConversionCandidate(options[1].conversionChoice!!))
        assertEquals("感じ", connection.text)
        assertEquals("かんじ", session.compositionSnapshot().reading)
        assertTrue(session.commitComposition())
        assertTrue(session.compositionSnapshot().reading.isEmpty())
        assertEquals(listOf(request), client.committedAll)
        assertEquals(listOf("感じ"), client.committedAllSegments.single().second.map(ConversionSegment::value))
    }

    /** 明示変換で選んだ英語候補の読みと種別を保ち、Mozcへ候補を再学習させない。 */
    @Test
    fun chosenEnglishConversionCandidateKeepsLearningMetadata() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        assertTrue(session.inputText("ぐーぐる"))
        assertTrue(session.convert())
        val request = client.requests.single()
        val engine = ConversionCandidate(0, "ぐーぐる", "ぐーぐる")
        val english = ConversionCandidate(
            id = Int.MIN_VALUE + 0x20000,
            value = "Google",
            reading = "ぐーぐる",
            learnedReading = "ぐーぐる",
            englishVariant = true,
        )
        val segment = ConversionSegment("ぐーぐる", "ぐーぐる", candidates = listOf(engine, english))
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(ConversionResult(request, listOf(segment), listOf(engine, english)))))
        assertTrue(session.selectConversionCandidate(session.candidateOptions().last().conversionChoice!!))
        assertTrue(session.commitComposition())

        val committed = client.committedAllSegments.single().second.single()
        assertEquals("Google", committed.value)
        assertEquals("ぐーぐる", committed.learnedReading)
        assertTrue(committed.englishVariant)
        assertFalse(committed.engineCandidate)
    }

    /** 変換キーを押すたび注目文節の候補を循環し、候補バーの選択表示も追従する。 */
    @Test
    fun explicitConvertCyclesCandidatesAndWrapsWithoutCommitting() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        val request = client.requests.single()
        assertTrue(session.applyConversionOutcome(converted(request, "漢字", "感じ")))

        assertTrue(session.candidateOptions().first().selected)
        assertTrue(session.convert())
        assertEquals("感じ", session.compositionSnapshot().display)
        assertTrue(session.candidateOptions().last().selected)
        assertTrue(session.convert())
        assertEquals("漢字", session.compositionSnapshot().display)
        assertTrue(session.candidateOptions().first().selected)
        assertEquals("かんじ", session.compositionSnapshot().reading)
        assertTrue(client.committedAll.isEmpty())
    }

    /** 候補選択で表示幅が変わった後に旧compositionの遅延通知が届いても、現在の変換を保つ。 */
    @Test
    fun delayedSelectionForPreviousConversionDisplayDoesNotDiscardCurrentChoices() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("きょうはてんき")
        session.convert()
        val request = client.requests.single()
        val head = listOf(ConversionCandidate(0, "今日は", "きょうは"), ConversionCandidate(1, "京", "きょうは"))
        val tail = listOf(ConversionCandidate(2, "天気", "てんき"))
        assertTrue(
            session.applyConversionOutcome(
                ConversionOutcome.Converted(
                    ConversionResult(
                        request,
                        listOf(
                            ConversionSegment("きょうは", "今日は", candidates = head),
                            ConversionSegment("てんき", "天気", candidates = tail),
                        ),
                        head,
                    ),
                ),
            ),
        )

        assertTrue(session.selectConversionCandidate(session.candidateOptions().last().conversionChoice!!))
        assertEquals("京天気", connection.text)
        session.updateSelection(0, 0, 5, 5, 0, 5)

        assertTrue(session.hasExplicitConversion)
        assertEquals("きょうはてんき", session.compositionSnapshot().reading)
        assertTrue(session.candidateOptions().first { it.value == "京" }.selected)
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

    /** 先頭文節の候補を選ぶとその表示だけ差し替わり、全文節の読みをcompositionに残す。 */
    @Test
    fun selectingHeadCandidateKeepsWholeCompositionUncommitted() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("きょうはてんき")
        session.convert()
        val first = client.requests.single()
        val firstCandidates = listOf(ConversionCandidate(0, "今日は", "きょうは"), ConversionCandidate(1, "京は", "きょうは"))
        val secondCandidates = listOf(ConversionCandidate(2, "天気", "てんき"), ConversionCandidate(3, "転機", "てんき"))
        val result = ConversionResult(
            first,
            listOf(
                ConversionSegment("きょうは", "今日は", candidates = firstCandidates),
                ConversionSegment("てんき", "天気", candidates = secondCandidates),
            ),
            firstCandidates,
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))
        assertEquals("今日は天気", connection.text)

        assertTrue(session.selectConversionCandidate(session.candidateOptions()[1].conversionChoice!!))

        assertEquals("京は天気", connection.text)
        assertEquals("きょうはてんき", session.compositionSnapshot().reading)
        assertEquals(1, client.requests.size)
        assertTrue(client.committedAll.isEmpty())
        assertTrue(session.commitComposition())
        assertEquals(listOf(first), client.committedAll)
        assertEquals(listOf("京は", "天気"), client.committedAllSegments.single().second.map(ConversionSegment::value))
    }

    /** 左右で注目文節を移し、候補タップはその文節だけを置き換え、境界候補は表示しない。 */
    @Test
    fun explicitArrowFocusAndCandidateSelectionStayWithinSegment() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("きょうはいいてんき")
        session.convert()
        val first = client.requests.single()
        val head = listOf(ConversionCandidate(0, "今日は", "きょうは"), ConversionCandidate(1, "京は", "きょうは"))
        val middle = listOf(ConversionCandidate(2, "いい", "いい"), ConversionCandidate(3, "良い", "いい"))
        val tail = listOf(ConversionCandidate(4, "天気", "てんき"), ConversionCandidate(5, "転機", "てんき"))
        val result = ConversionResult(
            first,
            listOf(
                ConversionSegment("きょうは", "今日は", candidates = head),
                ConversionSegment("いい", "いい", candidates = middle),
                ConversionSegment("てんき", "天気", candidates = tail),
            ),
            head,
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))

        val staleHeadChoice = session.candidateOptions().first().conversionChoice!!
        assertTrue(session.moveConversionFocus(1))
        assertEquals(listOf("いい", "良い"), session.candidateOptions().map(CandidateOption::value))
        assertFalse(session.selectConversionCandidate(staleHeadChoice))
        val middleChoice = session.candidateOptions().last().conversionChoice!!
        assertTrue(session.selectConversionCandidate(middleChoice))
        assertEquals("今日は良い天気", connection.text)
        assertEquals("きょうはいいてんき", session.compositionSnapshot().reading)
        assertTrue(session.moveConversionFocus(1))
        assertEquals(listOf("天気", "転機"), session.candidateOptions().map(CandidateOption::value))
        assertTrue(session.selectConversionCandidate(session.candidateOptions().last().conversionChoice!!))
        assertEquals("今日は良い転機", connection.text)
        assertTrue(session.moveConversionFocus(-1))
        assertTrue(session.candidateOptions().first { it.value == "良い" }.selected)
        assertTrue(session.moveConversionFocus(1))
        assertFalse(session.moveConversionFocus(1))
        assertTrue(session.commitComposition())
        assertEquals("今日は良い転機", connection.text)
        assertTrue(session.compositionSnapshot().reading.isEmpty())
        assertEquals(listOf(first), client.committedAll)
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
        assertEquals("かんじ", session.compositionSnapshot().reading)
        session.deleteBackward()
        assertEquals("かん", session.compositionSnapshot().reading)
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

    /** ライブ変換中の実カーソル移動後は、次の文字を移動先へ挿入する。 */
    @Test
    fun liveCursorMoveChangesInputPositionInsteadOfCandidateFocus() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "あいう")

        assertTrue(session.moveCursor(-1))
        assertTrue(session.inputText("え"))

        assertEquals("あいえう", connection.text)
    }

    /** 明示変換の循環処理を加えても、ライブ変換キーは候補バーの候補を従来どおり循環する。 */
    @Test
    fun liveConvertKeyStillCyclesFocusedCandidates() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "よい")

        assertEquals("良い", session.liveCandidateState()!!.currentValue)
        assertTrue(session.convert())
        assertEquals("酔い", session.liveCandidateState()!!.currentValue)
        assertTrue(session.convert())
        assertEquals("よい", session.liveCandidateState()!!.currentValue)
        assertFalse(session.hasExplicitConversion)
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

    /** ライブ変換でも1回目のEnterは表示を確定するだけで、2回目でEditor actionまたは改行を一度だけ行う。 */
    @Test
    fun liveEnterCommitsFirstThenActsOnSecondPress() {
        val sendConnection = FakeEditorConnection()
        val client = FakeLiveClient()
        val send = liveSession(sendConnection, client, normalPolicy(imeAction = EditorInfo.IME_ACTION_SEND))
        typeLive(send, client, "よい")
        assertTrue(send.handleEnter())
        assertEquals(listOf("良い"), sendConnection.committedTexts)
        assertTrue(sendConnection.editorActions.isEmpty())
        assertTrue(send.handleEnter())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), sendConnection.editorActions)

        val newlineConnection = FakeEditorConnection()
        val newlineClient = FakeLiveClient()
        // AndroidのTextViewは改行させたい複数行欄へNO_ENTER_ACTIONを自動で付けるため、その状態を再現する。
        val newline = liveSession(newlineConnection, newlineClient, normalPolicy().copy(isMultiLine = true, noEnterAction = true))
        typeLive(newline, newlineClient, "よい")
        assertTrue(newline.handleEnter())
        assertEquals(listOf("良い"), newlineConnection.committedTexts)
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

    /** 評価モードでは、結果による表示の変化・古い結果・候補の選択を評価用計数へ件数として渡す。 */
    @Test
    fun liveChangesAreReportedToEvaluationCounter() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val counter = EvaluationCounter()
        val session = liveSession(connection, client, evaluation = counter)
        assertTrue(counter.start("N01"))

        // 「よ」→「世」は初回の表示、「世い」→「良い」は変換済みの表示の自動変更
        session.inputText("よ")
        val older = client.result(0)
        client.deliverLatest(session)
        typeLive(session, client, "い")
        assertFalse(session.applyLiveResult(older))
        assertEquals("良い", connection.text)

        val choice = session.liveCandidateState()!!.choices.first { it.value == "酔い" }
        assertTrue(session.selectLiveCandidate(choice))

        val live = counter.finish()!!.live
        assertEquals(2, live.displayChanges)
        assertEquals(1, live.flicker)
        assertEquals(1, live.staleResultsDiscarded)
        assertEquals(1, live.toChosen)
        assertEquals(0, live.stableOverwrites + live.chosenOverwrites)
    }

    /**
     * 明示変換で登録語の候補を選んでもcompositionに残す。確定はworkerへ渡し、登録語の文節であることを示して
     * エンジンへの学習はworkerに止めさせる。
     */
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
        assertEquals("幹事", connection.composingTexts.last())
        assertEquals("かんじ", session.compositionSnapshot().reading)
        assertTrue(connection.committedTexts.isEmpty())
        assertTrue(session.commitComposition())
        assertEquals(listOf("幹事"), connection.committedTexts)
        val committed = client.committedAllSegments.single().second.single()
        assertEquals("幹事", committed.value)
        assertTrue(committed.fromUserDictionary)
        assertFalse(committed.engineCandidate)
    }

    /** 明示変換中の一回目Backspaceは読みへ戻し、二回目は一字削除して読みをUndo可能にする。 */
    @Test
    fun explicitBackspaceCancelsThenDeletesAndAllowsReconversion() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, conversionClient = client)
        session.inputText("よい")
        session.convert()
        assertTrue(session.applyConversionOutcome(converted(client.requests.single(), "良い")))
        assertEquals("良い", connection.text)

        assertTrue(session.deleteBackward())
        assertEquals("よい", connection.text)
        assertEquals("よい", session.compositionSnapshot().reading)
        assertFalse(session.canUndoDeletion)

        assertTrue(session.deleteBackward())
        assertEquals("よ", connection.text)
        assertTrue(session.canUndoDeletion)
        assertTrue(session.undoDeletion())
        assertEquals("よい", connection.text)
        assertEquals("よい", session.compositionSnapshot().reading)
        assertEquals(1, client.requests.size)

        assertTrue(session.convert())
        val nextRequest = client.requests.last()
        assertTrue(nextRequest.revision > client.requests.first().revision)
        assertFalse(session.applyConversionOutcome(converted(client.requests.first(), "古い")))
        assertTrue(session.applyConversionOutcome(converted(nextRequest, "夜")))
        assertEquals("夜", session.compositionSnapshot().display)
        assertTrue(client.committedAll.isEmpty())
    }

    /** 変換表記が読みと同じ場合も、最初のBackspaceでは読みを消さず変換状態だけを解除する。 */
    @Test
    fun backspaceCancelsSameValueExplicitConversionBeforeDeleting() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        session.inputText("かんじ")
        session.convert()
        assertTrue(session.applyConversionOutcome(converted(client.requests.single(), "かんじ")))
        assertTrue(session.hasExplicitConversion)

        assertTrue(session.deleteBackward())
        assertEquals("かんじ", connection.text)
        assertEquals("かんじ", session.compositionSnapshot().reading)
        assertFalse(session.hasExplicitConversion)
        assertFalse(session.canUndoDeletion)
        assertTrue(session.deleteBackward())
        assertEquals("かん", session.compositionSnapshot().reading)
    }

    /** エンジンなしの変換キーで出したカタカナも、一回目は読みへ戻し、次で一字を削除する。 */
    @Test
    fun backspaceCancelsKatakanaFallbackBeforeDeleting() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy())
        assertTrue(session.inputText("よい"))
        assertTrue(session.convert())
        assertEquals("ヨイ", session.compositionSnapshot().display)

        assertTrue(session.deleteBackward())
        assertEquals("よい", connection.text)
        assertEquals("よい", session.compositionSnapshot().reading)
        assertFalse(session.canUndoDeletion)
        assertTrue(session.deleteBackward())
        assertEquals("よ", session.compositionSnapshot().reading)
        assertTrue(session.canUndoDeletion)
    }

    /**
     * 明示変換で読み全体の登録語を表示したまま確定すると、文節をそのままworkerへ渡す。
     * 登録語の文節はエンジンの候補ではないため、エンジンへの学習はworkerが止める。
     */
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
        val committed = client.committedAllSegments.single().second.single()
        assertTrue(committed.fromUserDictionary)
        assertFalse(committed.engineCandidate)
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
        assertTrue(session.canUndoDeletion)
        assertTrue(session.undoDeletion())
        assertEquals(text, connection.text)
        assertFalse(session.canUndoDeletion)

        // 消した後に別の文字を入れた場合は、周りの文字列が一致しないため戻さない
        assertTrue(session.deleteToLineStart())
        connection.commitText("雨", 1)
        assertFalse(session.undoDeletion())
        assertEquals("明日は駅前\n雨", connection.text)
    }

    /** 空の欄では消す対象が0文字で、左ドラッグで離しても何も起きない（案内も出さない）。 */
    @Test
    fun deleteToLineStartInEmptyFieldReportsZeroAndDoesNothing() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), initialSelectionStart = 0, initialSelectionEnd = 0)
        assertEquals(0, session.lineDeleteLength())

        assertFalse(session.deleteToLineStart())
        assertEquals("", connection.text)
        assertFalse(session.canUndoDeletion)
    }

    /** 機密欄では消した文字列を覚えず、元に戻す入口を出さない。 */
    @Test
    fun deleteToLineStartKeepsNothingInSensitiveField() {
        val connection = ModelEditorConnection(text = "secret", selectionStart = 6, selectionEnd = 6)
        val policy = normalPolicy().copy(isPassword = true)
        val session = EditorSession(connection, policy, initialSelectionStart = 6, initialSelectionEnd = 6)

        assertTrue(session.deleteToLineStart())
        assertEquals("", connection.text)
        assertFalse(session.canUndoDeletion)
        assertFalse(session.undoDeletion())
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
        assertTrue(session.canUndoDeletion)

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
        assertTrue(session.undoDeletion())
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
        assertTrue(session.canUndoDeletion)
    }

    /** 連続Backspaceは改行・絵文字を含めて一度に戻り、空欄での追加削除も記録を消さない。 */
    @Test
    fun consecutiveBackspacesRestoreGraphemesAndKeepUndoAfterEmptyDelete() {
        val original = "a👩‍💻\n"
        val connection = ModelEditorConnection(text = original, selectionStart = original.length, selectionEnd = original.length)
        val session = EditorSession(connection, normalPolicy(), original.length, original.length)

        assertTrue(session.deleteBackward())
        assertTrue(session.deleteBackward())
        assertTrue(session.deleteBackward())
        assertEquals("", connection.text)
        assertTrue(session.canUndoDeletion)
        assertFalse(session.deleteBackward())
        assertTrue(session.canUndoDeletion)

        assertTrue(session.undoDeletion())
        assertEquals(original, connection.text)
    }

    /** 連続Backspaceの遅延selection通知がまとめられても、Undo位置を最新のカーソルに保つ。 */
    @Test
    fun coalescedBackspaceSelectionNotificationKeepsUndoAtLatestCursor() {
        val connection = ModelEditorConnection(text = "abcd", selectionStart = 4, selectionEnd = 4)
        val session = EditorSession(connection, normalPolicy(), 4, 4)
        repeat(3) { assertTrue(session.deleteBackward()) }
        assertEquals("a", connection.text)

        // 先の2回分だけをまとめた通知が、3回目の削除より後に届く。
        session.updateSelection(4, 4, 2, 2, -1, -1)

        assertTrue(session.canUndoDeletion)
        assertTrue(session.undoDeletion())
        assertEquals("abcd", connection.text)
    }

    /** 遅延通知の移動先が自己遷移の連続経路に無ければ、Undoを無効化する。 */
    @Test
    fun unrelatedSelectionNotificationDoesNotMatchBackspaceGroup() {
        val connection = ModelEditorConnection(text = "abcd", selectionStart = 4, selectionEnd = 4)
        val session = EditorSession(connection, normalPolicy(), 4, 4)
        repeat(3) { assertTrue(session.deleteBackward()) }

        session.updateSelection(4, 4, 0, 0, -1, -1)

        assertFalse(session.canUndoDeletion)
        assertFalse(session.undoDeletion())
        assertEquals("a", connection.text)
    }

    /** 全選択を逆向きにBackspaceしても、絵文字と改行を含む選択文字列を戻す。 */
    @Test
    fun backspaceRestoresReverseSelectedText() {
        val prefix = "前"
        val selected = "今日は👩‍💻\n晴れ"
        val suffix = "後"
        val original = prefix + selected + suffix
        val start = prefix.length
        val end = start + selected.length
        val connection = ModelEditorConnection(text = original, selectionStart = end, selectionEnd = start)
        val session = EditorSession(connection, normalPolicy(), end, start)

        assertTrue(session.deleteBackward())
        assertEquals(prefix + suffix, connection.text)
        assertTrue(session.canUndoDeletion)
        assertTrue(session.undoDeletion())
        assertEquals(original, connection.text)
    }

    /** 選択文字列を読めないEditorでも削除は実行し、復元だけを無効にする。 */
    @Test
    fun selectedTextUnavailableKeepsBackspaceButDisablesUndo() {
        val connection = ModelEditorConnection(text = "文章", selectionStart = 0, selectionEnd = 2).apply {
            selectedTextSupported = false
        }
        val session = EditorSession(connection, normalPolicy(), 0, 2)

        assertTrue(session.deleteBackward())
        assertEquals("", connection.text)
        assertFalse(session.canUndoDeletion)
    }

    /** 明示変換を読みへ戻してから連続削除し、Undoで削除した読みだけを復元する。 */
    @Test
    fun consecutiveBackspacesRestoreExplicitConvertedComposition() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        assertTrue(session.inputText("かんじ"))
        assertTrue(session.convert())
        assertTrue(session.applyConversionOutcome(converted(client.requests.single(), "漢字", "感じ")))

        assertTrue(session.deleteBackward())
        assertEquals("かんじ", connection.text)
        assertFalse(session.canUndoDeletion)
        repeat(3) { assertTrue(session.deleteBackward()) }
        assertEquals("", connection.text)
        assertTrue(session.undoDeletion())

        assertEquals("かんじ", connection.text)
        assertEquals("かんじ", session.compositionSnapshot().reading)
        assertEquals(listOf("かんじ", "カンジ"), session.candidateOptions().map(CandidateOption::value))
    }

    /** 候補選択でUI revisionが進んでも、確定時は変換時の元要求と更新後の表記を使う。 */
    @Test
    fun selectedExplicitCandidateCommitsWithOriginalRequest() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client)
        assertTrue(session.inputText("かんじ"))
        assertTrue(session.convert())
        val engineRequest = client.requests.single()
        assertTrue(session.applyConversionOutcome(converted(engineRequest, "漢字", "感じ")))

        val choice = session.candidateOptions().last().conversionChoice!!
        assertTrue(session.selectConversionCandidate(choice))
        assertTrue(session.candidateOptions().first { it.value == "感じ" }.conversionChoice!!.request.revision != engineRequest.revision)
        assertTrue(session.commitComposition())

        assertEquals("感じ", connection.text)
        assertEquals(listOf(engineRequest), client.committedAll)
        assertEquals(listOf("感じ"), client.committedAllSegments.single().second.map(ConversionSegment::value))
    }

    /** ライブ変換のBackspace undoはLiveCoreの履歴をまとめて戻し、変換済み表記を保つ。 */
    @Test
    fun consecutiveBackspacesRestoreLiveConvertedComposition() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "きょうは")
        assertEquals("今日は", connection.text)

        repeat(4) { assertTrue(session.deleteBackward()) }
        assertEquals("", connection.text)
        assertTrue(session.undoDeletion())

        assertEquals("今日は", connection.text)
        assertTrue(session.hasComposition)
    }

    /** ライブ変換を消し切った後、確定済み文字も消した連続Backspaceを元の表記へ一度で戻す。 */
    @Test
    fun backspaceUndoRestoresLiveCompositionAndCommittedTextAsOneGroup() {
        val committed = "old"
        val connection = ModelEditorConnection(text = committed, selectionStart = committed.length, selectionEnd = committed.length)
        val client = FakeLiveClient()
        val session = EditorSession(
            connection = connection,
            policy = normalPolicy(),
            initialSelectionStart = committed.length,
            initialSelectionEnd = committed.length,
            sessionEpoch = 5,
            liveCore = LiveConversionCore(),
            liveClient = client,
        )
        typeLive(session, client, "きょうは")
        assertEquals("old今日は", connection.text)

        repeat(4) { assertTrue(session.deleteBackward()) }
        assertEquals(committed, connection.text)
        assertFalse(session.hasComposition)
        assertTrue(session.deleteBackward())
        assertEquals("ol", connection.text)
        assertTrue(session.canUndoDeletion)

        assertTrue(session.undoDeletion())

        assertEquals("old今日は", connection.text)
        assertTrue(session.hasComposition)
    }

    /** Backspaceでcompositionを消し切ってから確定文字も消した後、一度のUndoで両方戻す。 */
    @Test
    fun backspaceUndoRestoresCompositionAndCommittedTextAsOneGroup() {
        val committed = "old"
        val connection = ModelEditorConnection(text = committed, selectionStart = committed.length, selectionEnd = committed.length)
        val session = EditorSession(connection, normalPolicy(), committed.length, committed.length)
        assertTrue(session.inputText("かな"))

        repeat(2) { assertTrue(session.deleteBackward()) }
        assertEquals(committed, connection.text)
        repeat(3) { assertTrue(session.deleteBackward()) }
        assertEquals("", connection.text)
        assertFalse(session.deleteBackward())
        assertTrue(session.canUndoDeletion)

        assertTrue(session.undoDeletion())
        assertEquals("oldかな", connection.text)
        assertEquals("かな", session.compositionSnapshot().reading)
    }

    /** カーソル移動、外部編集、欄切替の後は古い削除を元の位置へ戻さない。 */
    @Test
    fun deletionUndoIsRejectedAfterSelectionChangeExternalEditOrClose() {
        val movedConnection = ModelEditorConnection(text = "abc", selectionStart = 3, selectionEnd = 3)
        val moved = EditorSession(movedConnection, normalPolicy(), 3, 3)
        assertTrue(moved.deleteBackward())
        movedConnection.hostSetSelection(0, 0)
        moved.updateSelection(2, 2, 0, 0, -1, -1)
        assertFalse(moved.canUndoDeletion)
        assertFalse(moved.undoDeletion())
        assertEquals("ab", movedConnection.text)

        val editedConnection = ModelEditorConnection(text = "abc", selectionStart = 3, selectionEnd = 3)
        val edited = EditorSession(editedConnection, normalPolicy(), 3, 3)
        assertTrue(edited.deleteBackward())
        editedConnection.commitText("X", 1)
        assertFalse(edited.undoDeletion())
        assertEquals("abX", editedConnection.text)

        val closedConnection = ModelEditorConnection(text = "abc", selectionStart = 3, selectionEnd = 3)
        val closed = EditorSession(closedConnection, normalPolicy(), 3, 3)
        assertTrue(closed.deleteBackward())
        closed.close()
        assertFalse(closed.canUndoDeletion)
        assertFalse(closed.undoDeletion())
        assertEquals("ab", closedConnection.text)
    }

    /** パスワード欄と個人化禁止欄では、Backspace本文をUndo用に保持しない。 */
    @Test
    fun backspaceDoesNotRecordSensitiveOrNoPersonalizedLearningText() {
        val policies = listOf(
            normalPolicy().copy(isPassword = true),
            normalPolicy().copy(noPersonalizedLearning = true),
        )
        policies.forEach { policy ->
            val connection = ModelEditorConnection(text = "secret", selectionStart = 6, selectionEnd = 6)
            val session = EditorSession(connection, policy, 6, 6)
            assertTrue(session.deleteBackward())
            assertEquals("secre", connection.text)
            assertFalse(session.canUndoDeletion)
        }
    }

    /** 確定本文の復元後にcomposition書込みが失敗したら、接続を閉じて再送を防ぐ。 */
    @Test
    fun partialBackspaceUndoFailureClosesConnectionWithoutDuplicateRestore() {
        val connection = ModelEditorConnection(text = "old", selectionStart = 3, selectionEnd = 3)
        val session = EditorSession(connection, normalPolicy(), 3, 3)
        assertTrue(session.inputText("かな"))
        repeat(2) { assertTrue(session.deleteBackward()) }
        assertTrue(session.deleteBackward())
        assertEquals("ol", connection.text)
        connection.composingAccepted = false

        assertFalse(session.undoDeletion())
        assertEquals("old", connection.text)
        assertFalse(session.isActive)
        assertFalse(session.undoDeletion())
        assertFalse(session.inputText("か"))
        assertEquals("old", connection.text)
    }

    /**
     * ←で過去の文節へ移ると、その文節の候補の取り直しを依頼し、届いた候補を候補バーに加える。
     * 候補一覧を開くときは末尾入力位置の文節の候補も依頼する。
     */
    @Test
    fun liveFocusRequestsSegmentCandidatesAndShowsThem() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        typeLive(session, client, "きょうはてんきが")
        assertTrue(client.candidateRequests.isEmpty())

        assertTrue(session.requestLiveCandidates())
        assertEquals("てんきが", client.candidateRequests.single().reading)

        assertTrue(session.moveLiveFocus(-1))
        val request = client.candidateRequests.last()
        assertEquals("きょうは", request.reading)
        assertEquals("てんきが", request.following)
        assertTrue(session.applyLiveCandidates(CandidateResult(request, listOf("今日は", "教派"))))
        val choices = session.liveCandidateState()!!.choices
        assertTrue("教派" in choices.map { it.value })

        assertTrue(session.selectLiveCandidate(choices.first { it.value == "教派" }))
        assertEquals("教派天気が", connection.text)
    }

    /**
     * 評価用の状態の確認口が読む変換の進み具合。ライブ変換の要求を送ると結果のrevisionが追い付くまで差があり、
     * 候補の取り直しは結果を受けるまで未処理として残る。自動測定の道具はこれで変換の届き終わりを確かめる。
     */
    @Test
    fun conversionProgressTracksLiveRequestsAndCandidateRefreshes() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        assertEquals(-1L, session.conversionProgress().liveRequestedRevision)

        assertTrue(session.inputText("き"))
        val waiting = session.conversionProgress()
        assertEquals(client.requests.last().second.identity.revision, waiting.liveRequestedRevision)
        assertTrue(waiting.liveResultRevision < waiting.liveRequestedRevision)
        client.deliverLatest(session)
        val settled = session.conversionProgress()
        assertEquals(settled.liveRequestedRevision, settled.liveResultRevision)
        assertEquals(settled.revision, settled.liveResultRevision)
        assertFalse(settled.conversionPending)

        typeLive(session, client, "ょうは")
        assertFalse(session.conversionProgress().candidatesOutstanding)
        assertTrue(session.requestLiveCandidates())
        assertTrue(session.conversionProgress().candidatesOutstanding)
        session.applyLiveCandidates(CandidateResult(client.candidateRequests.last(), listOf("今日は")))
        assertFalse(session.conversionProgress().candidatesOutstanding)
    }

    /** ライブ変換の入力中は文節を伸縮でき、縮めた範囲を区切りに決めた変換を依頼して表示へ反映する。 */
    @Test
    fun liveResizeRequestsFixedRangeAndShowsResult() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)
        assertFalse(session.canResizeSegment)
        typeLive(session, client, "きょうはてんきが")
        assertTrue(session.canResizeSegment)

        assertTrue(session.resizeSegment(-1))
        assertEquals("今日はてんきが", connection.text)
        assertEquals(listOf(FixedRange(4, 7)), client.requests.last().second.identity.fixedRanges)
        client.deliverLatest(session)
        assertEquals("今日は天気が", connection.text)
    }

    /** 明示変換では注目文節を伸縮でき、続けて押すと応答待ちの区切りから数える。 */
    @Test
    fun explicitResizeRequestsConversionWithSegmentLength() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), 0, 0, sessionEpoch = 3, conversionClient = client)
        session.inputText("かんじ")
        assertFalse(session.canResizeSegment)
        assertFalse(session.resizeSegment(-1))
        session.convert()
        session.applyConversionOutcome(converted(client.requests.single(), "漢字", "感じ"))
        assertTrue(session.canResizeSegment)

        assertTrue(session.resizeSegment(-1))
        assertEquals(listOf(2), client.requests.last().segmentLengths)
        assertTrue(session.resizeSegment(-1))
        assertEquals(listOf(1), client.requests.last().segmentLengths)
        assertFalse(session.resizeSegment(-1))
        assertFalse(session.resizeSegment(0))

        val resized = client.requests.last()
        val outcome = ConversionOutcome.Converted(
            ConversionResult(
                resized,
                listOf(ConversionSegment("か", "蚊"), ConversionSegment("んじ", "んじ")),
                listOf(ConversionCandidate(0, "蚊", "か"), ConversionCandidate(1, "か", "か")),
            ),
        )
        assertTrue(session.applyConversionOutcome(outcome))
        assertEquals("蚊んじ", connection.text)
        assertEquals(listOf("蚊", "か"), session.candidateOptions().map(CandidateOption::value))
    }

    /**
     * 小書き・濁点を区別しない変換は、12キー（フリック・トグル）だけで入れた読みの変換・予測要求にだけ付ける。
     * 英字配列やローマ字が一度でも混ざった未確定では付けず、確定後の新しい未確定は入力元で決め直す。
     */
    @Test
    fun kanaModifierInsensitiveFollowsTwelveKeyOnlyComposition() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val prediction = FakePredictionClient()
        val session = EditorSession(
            connection, normalPolicy(), conversionClient = client, predictionClient = prediction, romajiInputEnabled = true,
        )
        assertTrue(session.inputText("り", fromTwelveKey = true))
        assertTrue(session.inputKanaTap(listOf("や", "ゆ", "よ"), 0))
        assertTrue(session.refreshPredictions())
        assertTrue(prediction.requests.last().kanaModifierInsensitive)
        assertTrue(session.convert())
        assertTrue(client.requests.last().kanaModifierInsensitive)
        assertTrue(session.commitComposition())

        // 英字配列から入れた読みには使わない。
        assertTrue(session.inputText("か"))
        assertTrue(session.convert())
        assertFalse(client.requests.last().kanaModifierInsensitive)
        assertTrue(session.commitComposition())

        // 12キーで始めた未確定にローマ字が混ざれば、以後は使わない（安全側）。
        assertTrue(session.inputText("か", fromTwelveKey = true))
        typeRomaji(session, "ki")
        assertTrue(session.inputText("く", fromTwelveKey = true))
        assertTrue(session.convert())
        assertFalse(client.requests.last().kanaModifierInsensitive)
    }

    /** ライブ変換でも、12キーだけで入れた読みの変換要求と確定時の学習に表記ゆれ変換の指定を付ける。 */
    @Test
    fun liveRequestsCarryTwelveKeyOnlySource() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val liveClient = FakeLiveClient()
        val session = EditorSession(
            connection = connection,
            policy = normalPolicy(),
            sessionEpoch = 9,
            liveCore = LiveConversionCore(),
            liveClient = liveClient,
            romajiInputEnabled = true,
        )
        assertTrue(session.inputText("きょうは", fromTwelveKey = true))
        assertTrue(liveClient.requests.last().second.kanaModifierInsensitive)
        typeRomaji(session, "ka")
        assertFalse(liveClient.requests.last().second.kanaModifierInsensitive)
    }

    /** 明示変換で同じキーを続けてタップすると、末尾の一文字だけが行の次の文字へ替わり、一周すると戻る。 */
    @Test
    fun kanaTapTogglesLastCharacterWithinWindow() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy())

        assertTrue(session.inputKanaTap(A_ROW, 0))
        assertTrue(session.inputKanaTap(A_ROW, 300))
        assertTrue(session.inputKanaTap(A_ROW, 600))
        assertEquals("う", connection.text)
        assertEquals("う", session.compositionSnapshot().reading)
        // 一周すると先頭へ戻る
        session.inputKanaTap(A_ROW, 900)
        session.inputKanaTap(A_ROW, 1_200)
        session.inputKanaTap(A_ROW, 1_500)
        assertEquals("あ", connection.text)

        // 猶予時間を過ぎたタップ、別のキーのタップは新しい文字になる
        assertTrue(session.inputKanaTap(A_ROW, 1_500 + KanaKeyToggle.WINDOW_MILLIS + 1))
        assertTrue(session.inputKanaTap(KA_ROW, 2_600))
        assertTrue(session.inputKanaTap(A_ROW, 2_700))
        assertEquals("ああかあ", connection.text)
        assertTrue(session.commitComposition())
        assertEquals("ああかあ", connection.text)
    }

    /** →は連続タップの途中だけ区切りとして使われ、フリック・小゛゜・削除の後のタップも新しい文字になる。 */
    @Test
    fun kanaToggleEndsAtArrowFlickTransformAndDelete() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy())

        assertFalse(session.endKanaToggle(0))
        session.inputKanaTap(A_ROW, 0)
        assertTrue(session.endKanaToggle(100))
        assertFalse(session.endKanaToggle(150))
        session.inputKanaTap(A_ROW, 200)
        assertEquals("ああ", connection.text)

        // フリックで入れた文字の後
        session.inputText("い")
        session.inputKanaTap(A_ROW, 300)
        assertEquals("ああいあ", connection.text)

        // 「つ」を小゛゜で「っ」にした後
        repeat(3) { session.inputKanaTap(TA_ROW, 400L + it) }
        assertTrue(session.transformKana())
        session.inputKanaTap(TA_ROW, 500)
        assertEquals("ああいあった", connection.text)

        // 削除の後
        assertTrue(session.deleteBackward())
        session.inputKanaTap(TA_ROW, 600)
        assertEquals("ああいあった", connection.text)
        assertTrue(session.endKanaToggle(700))
        assertFalse(session.endKanaToggle(700 + KanaKeyToggle.WINDOW_MILLIS + 1))
    }

    /** 外部のカーソル移動の後は、同じキーを続けてタップしても前の文字を差し替えない。 */
    @Test
    fun kanaToggleEndsWhenCursorMovesInsideComposition() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy())
        session.inputKanaTap(A_ROW, 0)
        session.inputKanaTap(KA_ROW, 100)
        assertTrue(session.moveCursor(-1))
        session.inputKanaTap(KA_ROW, 200)
        assertEquals("あかか", connection.text)
    }

    /** 直接確定の欄では差し替えず、タップごとに先頭の文字を確定する。 */
    @Test
    fun kanaTapCommitsDirectlyInPasswordField() {
        val connection = FakeEditorConnection()
        val session = EditorSession(connection, normalPolicy().copy(isPassword = true))
        assertTrue(session.inputKanaTap(A_ROW, 0))
        assertTrue(session.inputKanaTap(A_ROW, 100))
        assertEquals(listOf("あ", "あ"), connection.committedTexts)
        assertTrue(connection.composingTexts.isEmpty())
    }

    /** ライブ変換では差し替えた読みで変換を依頼し直し、「、」から「。」への切り替えは句点として確定する。 */
    @Test
    fun liveKanaToggleReconvertsAndPeriodCommits() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = liveSession(connection, client)

        session.inputKanaTap(listOf("よ", "い"), 0)
        client.deliverLatest(session)
        assertEquals("世", connection.text)
        session.inputKanaTap(A_ROW, 100)
        session.inputKanaTap(A_ROW, 200)
        client.deliverLatest(session)
        assertEquals("よい", client.requests.last().second.identity.reading)
        assertEquals("良い", connection.text)

        session.inputKanaTap(PUNCT_ROW, 300)
        assertEquals("良い、", connection.text)
        assertTrue(session.hasComposition)
        session.inputKanaTap(PUNCT_ROW, 400)
        assertEquals("良い。", connection.text)
        assertFalse(session.hasComposition)
        assertEquals(listOf(listOf(LearnedSegment("よい", "良い"))), client.learned)

        // 確定後のタップは差し替える相手が無いため、新しい文字として入る
        session.inputKanaTap(PUNCT_ROW, 500)
        assertEquals("良い。、", connection.text)
    }

    /** ローマ字かな入力では、入力途中の子音を表示しながら、Mozcの表のとおりにかなへ組み立てる。 */
    @Test
    fun romajiInputComposesKanaWithPendingLetters() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), romajiInputEnabled = true)

        assertTrue(session.inputRomajiText("k"))
        assertEquals("k", connection.text)
        assertTrue(session.inputRomajiText("y"))
        assertEquals("ky", connection.text)
        assertTrue(session.inputRomajiText("o"))
        assertEquals("きょ", connection.text)

        typeRomaji(session, "uhanndatta")
        assertEquals("きょうはんだった", connection.text)
        typeRomaji(session, "nkan'ixtu-,.")
        assertEquals("きょうはんだったんかんいっー、。", connection.text)
        typeRomaji(session, "tchiwwwa")
        assertEquals("きょうはんだったんかんいっー、。っちwっわ", connection.text)

        // 末尾の「n」は確定時に「ん」にする
        typeRomaji(session, "kan")
        assertEquals("きょうはんだったんかんいっー、。っちwっわかn", connection.text)
        assertTrue(session.commitComposition())
        assertEquals("きょうはんだったんかんいっー、。っちwっわかん", connection.text)
        assertFalse(session.hasComposition)
    }

    /** 大文字・数字・表に無い記号は英字のまま入れ、入力途中の子音はその場で区切る。 */
    @Test
    fun romajiInputKeepsUppercaseAndDigitsAsLetters() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), romajiInputEnabled = true)
        typeRomaji(session, "Aika2kA?")
        assertEquals("Aいか2kA？", connection.text)
    }

    /** ローマ字かな入力が無効なら、英字配列の文字は従来どおり英字として入る。 */
    @Test
    fun romajiInputDisabledKeepsLetters() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy())
        assertFalse(session.isRomajiInputEnabled)
        assertTrue(session.inputRomajiText("ka"))
        assertEquals("ka", connection.text)
    }

    /** Backspaceは入力途中のローマ字を一文字ずつ消し、残った子音から入力を続けられる。元に戻すと保留も戻る。 */
    @Test
    fun romajiBackspaceDeletesPendingLettersAndUndoRestoresThem() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val session = EditorSession(connection, normalPolicy(), initialSelectionStart = 0, initialSelectionEnd = 0, romajiInputEnabled = true)

        typeRomaji(session, "aky")
        assertTrue(session.deleteBackward())
        assertEquals("あk", connection.text)
        typeRomaji(session, "a")
        assertEquals("あか", connection.text)

        typeRomaji(session, "ky")
        assertTrue(session.deleteBackward())
        assertTrue(session.deleteBackward())
        assertTrue(session.deleteBackward())
        assertEquals("あ", connection.text)
        assertTrue(session.undoDeletion())
        assertEquals("あかky", connection.text)
        typeRomaji(session, "a")
        assertEquals("あかきゃ", connection.text)
    }

    /** 明示変換の結果を表示中にローマ字を打つと、表示中の変換を確定してから新しい読みを始める。 */
    @Test
    fun romajiInputAfterExplicitConversionCommitsItFirst() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val session = EditorSession(connection, normalPolicy(), conversionClient = client, romajiInputEnabled = true)
        typeRomaji(session, "kan")
        assertTrue(session.convert())
        // 変換前に末尾の「n」を「ん」にしてから読みを送る
        assertEquals("かん", client.requests.single().reading)
        val request = client.requests.single()
        val outcome = ConversionOutcome.Converted(
            ConversionResult(
                request = request,
                segments = listOf(ConversionSegment("かん", "缶")),
                headCandidates = listOf(ConversionCandidate(0, "缶", "かん")),
            ),
        )
        assertTrue(session.applyConversionOutcome(outcome))
        assertEquals("缶", connection.text)
        typeRomaji(session, "ga")
        assertEquals("缶が", connection.text)
        assertEquals("が", session.compositionSnapshot().reading)
    }

    /** ライブ変換でもローマ字の句点で確定し、末尾の「n」は句点の前で「ん」にする。 */
    @Test
    fun liveRomajiInputCommitsAtPeriod() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeLiveClient()
        val session = EditorSession(
            connection = connection,
            policy = normalPolicy(),
            initialSelectionStart = 0,
            initialSelectionEnd = 0,
            sessionEpoch = 5,
            liveCore = LiveConversionCore(),
            liveClient = client,
            romajiInputEnabled = true,
        )
        for (char in "yoi.") {
            session.inputRomajiText(char.toString())
            client.deliverLatest(session)
        }
        assertEquals("良い。", connection.text)
        assertEquals(listOf(listOf(LearnedSegment("よい", "良い"))), client.learned)

        for (char in "kan.") {
            session.inputRomajiText(char.toString())
            client.deliverLatest(session)
        }
        assertEquals("良い。蚊ん。", connection.text)
        assertFalse(session.hasComposition)
    }

    /** ローマ字を一文字ずつ英字配列のキーとして入れる。 */
    private fun typeRomaji(session: EditorSession, text: String) {
        text.forEach { assertTrue(session.inputRomajiText(it.toString())) }
    }

    /**
     * 明示変換の文節伸縮では、注目文節より前の区切りと伸縮後の長さを要求へ渡す。応答後も伸縮した文節を候補バーの対象に保ち、
     * 前の文節で選んだ候補は引き継ぐ。複数文節の表示では注目文節をEditor上で強調する。
     */
    @Test
    fun explicitResizeTargetsFocusedSegmentAndKeepsFocus() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val spans = mutableListOf<DisplaySpan?>()
        val session = EditorSession(
            connection,
            normalPolicy(),
            conversionClient = client,
            compositionStyler = { text, span -> spans += span; text },
        )
        session.inputText("きょうはてんき")
        session.convert()
        val first = client.requests.single()
        val head = listOf(ConversionCandidate(0, "今日は", "きょうは"), ConversionCandidate(1, "京は", "きょうは"))
        val tail = listOf(ConversionCandidate(1, "天気", "てんき"))
        val result = ConversionResult(
            first,
            listOf(
                ConversionSegment("きょうは", "今日は", candidates = head),
                ConversionSegment("てんき", "天気", candidates = tail),
            ),
            head,
        )
        assertTrue(session.applyConversionOutcome(ConversionOutcome.Converted(result)))
        assertEquals(0 to 3, spans.last()!!.start to spans.last()!!.end)
        assertTrue(session.convert())
        assertEquals("京は天気", connection.text)
        assertTrue(session.moveConversionFocus(1))
        assertEquals(2 to 4, spans.last()!!.start to spans.last()!!.end)

        assertTrue(session.resizeSegment(-1))
        val resized = client.requests.last()
        assertEquals(listOf(4, 2), resized.segmentLengths)
        assertEquals(1, resized.focusedSegmentIndex)
        assertTrue(
            session.applyConversionOutcome(
                ConversionOutcome.Converted(
                    ConversionResult(
                        resized,
                        listOf(
                            ConversionSegment("きょうは", "今日は", candidates = head),
                            ConversionSegment("てん", "天", candidates = listOf(ConversionCandidate(2, "天", "てん"))),
                            ConversionSegment("き", "き", candidates = listOf(ConversionCandidate(3, "き", "き"))),
                        ),
                        head,
                    ),
                ),
            ),
        )
        assertEquals("京は天き", connection.text)
        assertEquals(listOf("天"), session.candidateOptions().map(CandidateOption::value))
        assertTrue(session.candidateOptions().single().selected)
        assertEquals(2 to 3, spans.last()!!.start to spans.last()!!.end)

        assertTrue(session.commitComposition())
        assertEquals(listOf("京は", "天", "き"), client.committedAllSegments.single().second.map(ConversionSegment::value))
        assertEquals(listOf(true, false, false), client.committedAllSegments.single().second.map(ConversionSegment::chosen))
    }

    /** 一文節だけの明示変換は、文節の区別が要らないため強調しない。 */
    @Test
    fun singleSegmentExplicitConversionIsNotHighlighted() {
        val connection = ModelEditorConnection(text = "", selectionStart = 0, selectionEnd = 0)
        val client = FakeConversionClient()
        val spans = mutableListOf<DisplaySpan?>()
        val session = EditorSession(
            connection,
            normalPolicy(),
            conversionClient = client,
            compositionStyler = { text, span -> spans += span; text },
        )
        session.inputText("かんじ")
        session.convert()
        assertTrue(session.applyConversionOutcome(converted(client.requests.single(), "漢字", "感じ")))
        assertTrue(session.convert())

        assertEquals("感じ", connection.text)
        assertTrue(spans.isEmpty())
    }

    /** ライブ変換の編集セッションを作る。変換要求はclientへ記録され、テストが結果を返す。 */
    private fun liveSession(
        connection: EditorConnectionPort,
        client: FakeLiveClient,
        policy: InputFieldPolicy = normalPolicy(),
        styler: (String, DisplaySpan?) -> CharSequence = { text, _ -> text },
        evaluation: EvaluationCounter? = null,
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
            evaluation = evaluation,
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

    private companion object {
        /** 12キーの連続タップで使う、各キーの切り替え順。 */
        val A_ROW = listOf("あ", "い", "う", "え", "お")
        val KA_ROW = listOf("か", "き", "く", "け", "こ")
        val TA_ROW = listOf("た", "ち", "つ", "て", "と")
        val PUNCT_ROW = listOf("、", "。", "？", "！", "…")
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
    val committedAll = mutableListOf<ConversionRequest>()
    val committedAllSegments = mutableListOf<Pair<ConversionRequest, List<ConversionSegment>>>()

    override val isAvailable: Boolean = true

    override fun requestConversion(request: ConversionRequest) {
        requests += request
    }

    override fun commitAll(request: ConversionRequest, segments: List<ConversionSegment>) {
        committedAll += request
        committedAllSegments += request to segments
    }
}

/** 予測要求と、選んだ候補の確定を記録する偽の窓口。 */
private class FakePredictionClient : PredictionClient {
    val requests = mutableListOf<PredictionRequest>()
    val committed = mutableListOf<Pair<PredictionRequest, PredictionCandidate>>()

    override val isPredictionAvailable: Boolean = true

    /** 予測要求を記録し、世代と文脈の検証に使う。 */
    override fun requestPrediction(request: PredictionRequest) {
        requests += request
    }

    /** 選択候補の確定通知を記録する。 */
    override fun commitPrediction(request: PredictionRequest, candidate: PredictionCandidate) {
        committed += request to candidate
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

    override fun learnCommitted(sessionEpoch: Long, units: List<List<LearnedSegment>>, kanaModifierInsensitive: Boolean) {
        learned += units
    }

    // 受け取った候補の取り直し要求。
    val candidateRequests = mutableListOf<CandidateRequest>()

    override fun requestSegmentCandidates(sessionEpoch: Long, request: CandidateRequest) {
        candidateRequests += request
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
    var commitAccepted = true
    var beforeCursor: CharSequence? = ""
    var afterCursor: CharSequence? = ""
    var invalidated = false
    val composingTexts = mutableListOf<String>()
    val committedTexts = mutableListOf<String>()
    val deleteCalls = mutableListOf<Pair<Int, Int>>()
    val selectionCalls = mutableListOf<Pair<Int, Int>>()
    val editorActions = mutableListOf<Int>()
    var enterKeys = 0
    var deleteKeys = 0
    var selectedTextValue: CharSequence? = null

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composingTexts += text.toString()
        return !invalidated && composingAccepted
    }

    override fun finishComposingText(): Boolean = !invalidated && finishAccepted

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (invalidated) return false
        committedTexts += text.toString()
        return commitAccepted
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

    override fun sendEnterKey(): Boolean {
        if (invalidated) return false
        enterKeys += 1
        return true
    }

    override fun sendDeleteKey(): Boolean {
        if (invalidated) return false
        deleteKeys += 1
        return true
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? = if (invalidated) null else beforeCursor

    override fun textAfterCursor(maxChars: Int): CharSequence? = if (invalidated) null else afterCursor

    override fun selectedText(): CharSequence? = if (invalidated) null else selectedTextValue

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
    var textBeforeCursorCalls = 0
        private set
    private var selectionStart = selectionStart
    private var selectionEnd = selectionEnd
    private var composingStart: Int? = null
    private var composingEnd: Int? = null
    private var active = true
    var selectedTextSupported = true
    var composingAccepted = true
    var commitAccepted = true
    var finishAccepted = true

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
        if (!active || !composingAccepted) return false
        val start = composingStart ?: minOf(selectionStart, selectionEnd)
        val end = composingEnd ?: maxOf(selectionStart, selectionEnd)
        replace(start, end, text.toString())
        composingStart = start
        composingEnd = start + text.length
        moveCursorAfterReplacement(start, text.length, newCursorPosition)
        return true
    }

    override fun finishComposingText(): Boolean {
        if (!active || !finishAccepted) return false
        finishComposingCalls += 1
        hostFinishComposition()
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!active || !commitAccepted) return false
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
        // BaseInputConnectionと同じく、カーソル位置から始まるcomposing spanは消さず、削除幅だけ左へずらす。
        val spanStart = composingStart
        if (spanStart != null && afterLength == 0 && spanStart >= selectionStart) {
            val removed = selectionStart - start
            composingStart = spanStart - removed
            composingEnd = composingEnd?.minus(removed)
        }
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

    override fun sendEnterKey(): Boolean = active

    override fun sendDeleteKey(): Boolean = active

    override fun textBeforeCursor(maxChars: Int): CharSequence? {
        textBeforeCursorCalls += 1
        if (!active) return null
        val end = minOf(selectionStart, selectionEnd)
        return text.substring((end - maxChars).coerceAtLeast(0), end)
    }

    override fun textAfterCursor(maxChars: Int): CharSequence? {
        if (!active) return null
        val start = maxOf(selectionStart, selectionEnd)
        return text.substring(start, (start + maxChars).coerceAtMost(text.length))
    }

    override fun selectedText(): CharSequence? {
        if (!active || !selectedTextSupported) return null
        return text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd))
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
