package dev.uzumi.ime.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ライブ変換コアを、試験用の変換器と決まったイベント列で検証する。
 * 設計文書の「反証する試験」と、安定化・境界・Undo/Redoの規則を対象にする。
 */
class LiveConversionCoreTest {
    private val sentence = "きょうはてんきがいいですね"

    /** 保留中の要求を作るため、結果を自動で返さない補助を作る。 */
    private fun heldDriver(): LiveSessionDriver = LiveSessionDriver(deliverImmediately = false)

    /** 「今日は｜天気が｜いいですね」を、各打鍵の結果をすぐ返しながら入力した状態を作る。 */
    private fun typedSentence(): LiveSessionDriver = LiveSessionDriver().type(sentence)

    /** segmentの表示を並べる。 */
    private fun surfaces(core: LiveConversionCore): List<String> = core.segments.map { it.surface }

    /** segmentの状態を並べる。 */
    private fun states(core: LiveConversionCore): List<SegmentState> = core.segments.map { it.state }

    /** 入力Aの結果が入力Bの結果より後に届いても、Bの表示を上書きしない。 */
    @Test
    fun olderResultArrivingLaterDoesNotOverwriteNewerInput() {
        val driver = heldDriver().type("よ")
        val requestA = driver.pending.last()
        driver.type("い")
        val requestB = driver.pending.last()

        assertNull(driver.deliver(requestB).rejection)
        assertEquals("良い", driver.editor.composing)

        val late = driver.deliver(requestA)
        assertEquals(RejectReason.REVISION_MISMATCH, late.rejection)
        assertTrue(late.commands.isEmpty())
        assertEquals("良い", driver.editor.composing)
        assertEquals("良い", driver.core.display)
    }

    /** 要求の識別情報のどれか一つでも現在値と違えば、その理由で捨てる。 */
    @Test
    fun resultIsRejectedWhenAnyIdentityFieldDiffers() {
        val driver = heldDriver().type("よい")
        val request = driver.pending.last()
        val valid = driver.converter.convert(request)!!
        val cases = mapOf(
            RejectReason.EPOCH_MISMATCH to request.identity.copy(sessionEpoch = request.identity.sessionEpoch - 1),
            RejectReason.GENERATION_MISMATCH to request.identity.copy(converterGeneration = 99),
            RejectReason.REVISION_MISMATCH to request.identity.copy(revision = request.identity.revision - 1),
            RejectReason.READING_MISMATCH to request.identity.copy(reading = "よう"),
            RejectReason.TARGET_MISMATCH to request.identity.copy(targetStart = 1),
            RejectReason.PROTECTED_MISMATCH to request.identity.copy(
                protectedRanges = listOf(ProtectedRange(0, 1, "よ")),
            ),
        )
        for ((reason, identity) in cases) {
            val update = driver.core.onConversionResult(valid.copy(identity = identity))
            assertEquals(reason, update.rejection)
            assertTrue(update.commands.isEmpty())
        }
        assertEquals("よい", driver.core.display)
        assertNull(driver.core.onConversionResult(valid).rejection)
        assertEquals("良い", driver.core.display)
    }

    /** 読みの欠落・重複や書記素の途中での分割を含む結果は適用しない。 */
    @Test
    fun malformedSegmentsAreRejected() {
        val driver = heldDriver().type("よい")
        val identity = driver.pending.last().identity
        val malformed = listOf(
            listOf(ResultSegment("よ", "世")),
            listOf(ResultSegment("よい", "良い"), ResultSegment("い", "い")),
            listOf(ResultSegment("よい", "")),
            listOf(ResultSegment("いよ", "良い")),
        )
        for (segments in malformed) {
            val update = driver.core.onConversionResult(ConversionResult(identity, segments))
            assertEquals(RejectReason.MALFORMED_SEGMENTS, update.rejection)
        }
        assertEquals("よい", driver.core.display)
    }

    /** 辞書更新前の世代で作られた結果は捨て、新しい世代の要求だけを適用する。 */
    @Test
    fun resultFromOldConverterGenerationIsRejected() {
        val driver = heldDriver().type("よい")
        val beforeUpdate = driver.pending.last()

        val regenerated = driver.core.setConverterGeneration(2)
        val afterUpdate = assertNotNullAndGet(regenerated.request)
        assertEquals(2, afterUpdate.identity.converterGeneration)

        assertEquals(RejectReason.GENERATION_MISMATCH, driver.deliver(beforeUpdate).rejection)
        assertEquals("よい", driver.core.display)
        assertNull(driver.deliver(afterUpdate).rejection)
        assertEquals("良い", driver.core.display)
    }

    /** Send直後に古い結果が届いても、Editorへ書き込まない。確定とactionは一度だけ送る。 */
    @Test
    fun resultAfterSendIsNotWritten() {
        val driver = heldDriver().type("きょうは")
        val inFlight = driver.pending.last()

        val sent = driver.enter(EnterKind.EditorAction(actionCode = 4))
        assertEquals(
            listOf(EditorCommand.Commit("きょうは"), EditorCommand.PerformEditorAction(4)),
            sent.commands,
        )

        val late = driver.deliver(inFlight)
        assertNotNull(late.rejection)
        assertTrue(late.commands.isEmpty())
        assertEquals("きょうは", driver.editor.text)
        assertEquals(listOf(4), driver.editor.actions)
    }

    /** 別の入力欄へ移った直後に結果が届いても、新しい欄へ書き込まない。 */
    @Test
    fun resultAfterFieldChangeIsNotWritten() {
        val driver = heldDriver().type("きょうは")
        val inFlight = driver.pending.last()

        val moved = driver.core.startField(LiveFieldPolicy.NORMAL)
        assertTrue(moved.commands.isEmpty())
        assertEquals("", driver.core.display)

        val late = driver.deliver(inFlight)
        assertEquals(RejectReason.EPOCH_MISMATCH, late.rejection)
        assertTrue(late.commands.isEmpty())
    }

    /** password欄へ移った直後の結果を書かず、password欄では変換要求を出さない。 */
    @Test
    fun resultAfterMovingToPasswordIsNotWrittenAndNoRequestIsSent() {
        val driver = heldDriver().type("きょうは")
        val inFlight = driver.pending.last()

        driver.core.startField(LiveFieldPolicy(conversionAllowed = false, learningAllowed = false))
        val late = driver.deliver(inFlight)
        assertNotNull(late.rejection)
        assertTrue(late.commands.isEmpty())

        val typed = driver.core.inputText("あ")
        assertEquals(listOf(EditorCommand.SetComposition("あ", 1)), typed.commands)
        assertNull(typed.request)
    }

    /** 学習禁止欄では、要求を送る前から学習禁止のflagが付く。 */
    @Test
    fun noPersonalizedLearningFieldMarksRequestBeforeSending() {
        val core = LiveConversionCore()
        core.startField(LiveFieldPolicy(conversionAllowed = true, learningAllowed = false))
        val request = assertNotNullAndGet(core.inputText("よ").request)
        assertFalse(request.learningAllowed)

        core.startField(LiveFieldPolicy.NORMAL)
        assertTrue(assertNotNullAndGet(core.inputText("よ").request).learningAllowed)
    }

    /** ライブ変換OFFでは表示を一度確定し、以後の入力は明示変換へ渡し、古い結果を捨てる。 */
    @Test
    fun turningLiveOffCommitsOnceAndHandsInputBack() {
        val driver = heldDriver().type("よい")
        val inFlight = driver.pending.last()

        val off = driver.core.setLiveEnabled(false)
        assertEquals(listOf(EditorCommand.Commit("よい")), off.commands)
        assertEquals(RejectReason.LIVE_DISABLED, driver.deliver(inFlight).rejection)
        assertFalse(driver.core.inputText("か").handled)
        assertFalse(driver.core.deleteBackward().handled)
        assertTrue(driver.core.setLiveEnabled(false).commands.isEmpty())

        driver.core.setLiveEnabled(true)
        assertTrue(driver.core.inputText("か").handled)
    }

    /** 後続segmentへ進み、独立した3回の読みrevisionで同じ表記を保ったsegmentだけをstableにする。 */
    @Test
    fun segmentBecomesStableAfterThreeReadingRevisionsWithFollower() {
        val driver = heldDriver()
        // 同じ要求へ二つの結果（辞書とニューラルの想定）を返し、二つ目の結果の拒否理由を返す。
        fun typeAndDeliverTwice(text: String): RejectReason? {
            driver.type(text)
            val request = driver.pending.last()
            assertNull(driver.deliver(request).rejection)
            return driver.deliver(request).rejection
        }

        assertNull(typeAndDeliverTwice("きょうは"))
        assertEquals(listOf("今日は"), surfaces(driver.core))
        assertEquals(1, driver.core.segments[0].observations)

        assertNull(typeAndDeliverTwice("て"))
        assertEquals(listOf("今日は", "て"), surfaces(driver.core))
        // 二つ目の結果は同じ読みrevisionなので、観測回数を増やさない。
        assertEquals(2, driver.core.segments[0].observations)
        assertEquals(SegmentState.PROVISIONAL, driver.core.segments[0].state)

        // 3回目の読みrevisionでstableになり、対象範囲が変わるため二つ目の結果は捨てる。
        assertEquals(RejectReason.TARGET_MISMATCH, typeAndDeliverTwice("ん"))
        assertEquals(SegmentState.STABLE, driver.core.segments[0].state)

        driver.type("き")
        val next = driver.pending.last().identity
        assertEquals(4, next.targetStart)
        assertEquals(listOf(ProtectedRange(0, 4, "今日は")), next.protectedRanges)
    }

    /** 最後のsegmentは同じ表記が続いても、後続segmentがなければstableにしない。 */
    @Test
    fun lastSegmentStaysProvisionalWithoutFollower() {
        val driver = typedSentence()
        assertEquals(listOf("今日は", "天気が", "いいですね"), surfaces(driver.core))
        assertEquals(
            listOf(SegmentState.STABLE, SegmentState.STABLE, SegmentState.PROVISIONAL),
            states(driver.core),
        )
        val refreshed = assertNotNullAndGet(driver.core.setConverterGeneration(1).request)
        repeat(3) {
            driver.deliver(refreshed)
        }
        assertEquals(SegmentState.PROVISIONAL, driver.core.segments.last().state)
    }

    /** 候補を選んだsegmentはchosenになり、文全体は確定しない。表示時と違う候補のタップは捨てる。 */
    @Test
    fun candidateSelectionChoosesSegmentWithoutCommitting() {
        val driver = LiveSessionDriver().type("よい")
        val bar = assertNotNullAndGet(driver.core.candidateBar())
        assertEquals(listOf("良い", "酔い", "よい"), bar.choices.map { it.value })

        driver.type("か")
        val stale = driver.core.selectCandidate(bar.choices[1])
        assertEquals(RejectReason.STALE_CANDIDATE, stale.rejection)
        assertTrue(stale.commands.isEmpty())

        driver.backspace()
        driver.focus(0)
        val selected = driver.select("酔い")
        assertTrue(driver.editor.committed.isEmpty())
        assertEquals("酔い", driver.editor.composing)
        assertEquals(SegmentState.CHOSEN, driver.core.segments[0].state)
        assertTrue(selected.commands.none { it is EditorCommand.Commit })

        val otherSegment = bar.choices[0].copy(segmentId = 9_999, revision = driver.core.revision)
        assertEquals(RejectReason.STALE_CANDIDATE, driver.core.selectCandidate(otherSegment).rejection)
    }

    /** 候補選択後は、選択前の結果も、chosenの境界をまたぐ再分割も捨てる。 */
    @Test
    fun boundaryChangeAfterCandidateSelectionIsRejected() {
        val driver = heldDriver().type("きょうはてんきがいい")
        val beforeSelection = driver.pending.last()
        val earlier = driver.pending[driver.pending.size - 3]
        assertNull(driver.deliver(beforeSelection).rejection)
        assertEquals(listOf("今日は", "天気が", "いい"), surfaces(driver.core))

        driver.focus(1)
        driver.select("転機が")
        val afterSelection = driver.pending.last()
        assertEquals(listOf(ProtectedRange(4, 8, "転機が")), afterSelection.identity.protectedRanges)

        assertEquals(RejectReason.REVISION_MISMATCH, driver.deliver(earlier).rejection)
        assertEquals(RejectReason.REVISION_MISMATCH, driver.deliver(beforeSelection).rejection)

        val crossing = ConversionResult(
            afterSelection.identity,
            listOf(ResultSegment("きょうはて", "今日はて"), ResultSegment("んきがいい", "ん期がいい")),
        )
        assertEquals(RejectReason.CROSSES_PROTECTED, driver.core.onConversionResult(crossing).rejection)
        assertEquals("今日は転機がいい", driver.core.display)

        // 保護範囲の表記を変えようとする結果は、境界が合っていても保護範囲を書き換えない。
        val overwriting = ConversionResult(
            afterSelection.identity,
            listOf(
                ResultSegment("きょうは", "京は"),
                ResultSegment("てんきが", "天気が"),
                ResultSegment("いい", "良い"),
            ),
        )
        assertNull(driver.core.onConversionResult(overwriting).rejection)
        assertEquals(listOf("京は", "転機が", "良い"), surfaces(driver.core))
        assertEquals(SegmentState.CHOSEN, driver.core.segments[1].state)
    }

    /** 「今日は｜天気が｜いいですね」で「転機が」へ直しても、他segmentと末尾入力位置を壊さない。 */
    @Test
    fun correctingMiddleSegmentKeepsOtherSegmentsAndInputPosition() {
        val driver = typedSentence()
        val first = driver.core.segments[0]
        val last = driver.core.segments[2]
        val cursorBefore = driver.core.inputCursor
        assertEquals(sentence.length, cursorBefore)

        driver.focus(1)
        assertEquals("天気が", driver.core.focusedSegment?.surface)
        driver.select("転機が")

        assertEquals(first, driver.core.segments[0])
        assertEquals(last.surface, driver.core.segments[2].surface)
        assertEquals(last.id, driver.core.segments[2].id)
        assertEquals(cursorBefore, driver.core.inputCursor)
        assertEquals("今日は転機がいいですね", driver.editor.composing)
        assertEquals("今日は転機がいいですね".length, driver.editor.cursorInComposition)

        driver.returnToInput()
        assertEquals(driver.core.segments.last().id, driver.core.focusedSegment?.id)
        driver.type("よ")
        assertEquals("今日は転機がいいですね世", driver.editor.composing)
        assertEquals(SegmentState.CHOSEN, driver.core.segments[1].state)
        assertEquals("転機が", driver.core.segments[1].surface)

        driver.type("。")
        assertEquals("今日は転機がいいですね世。", driver.editor.committed.toString())
        assertEquals("", driver.core.display)
        assertFalse(driver.core.canUndo)
    }

    /** 「良い」の末尾Backspaceは読み末尾の「い」を削って「よ」を再変換し、Undoで戻せる。 */
    @Test
    fun backspaceOnConvertedSegmentDeletesReadingGrapheme() {
        val driver = heldDriver().type("よい")
        driver.deliver(driver.pending.last())
        driver.select("良い")
        assertEquals("良い", driver.editor.composing)

        val deleted = driver.backspace()
        assertEquals(listOf(EditorCommand.SetComposition("よ", 1)), deleted.commands)
        assertEquals("よ", assertNotNullAndGet(deleted.request).targetReading)
        assertEquals(SegmentState.PROVISIONAL, driver.core.segments[0].state)

        driver.deliver(driver.pending.last())
        assertEquals("世", driver.editor.composing)

        driver.undo()
        assertEquals("良い", driver.editor.composing)
        assertEquals(SegmentState.CHOSEN, driver.core.segments[0].state)
        driver.redo()
        assertEquals("世", driver.editor.composing)
    }

    /** 候補の誤選択は一回のUndoで戻り、Redoで再び選べる。新しい入力でRedoを消す。 */
    @Test
    fun wrongCandidateSelectionIsUndoneAsOneOperation() {
        val driver = typedSentence()
        val depthBefore = driver.core.undoDepth
        val segmentsBefore = driver.core.segments

        driver.focus(1)
        driver.select("転機が")
        assertEquals(depthBefore + 1, driver.core.undoDepth)

        val revisionBeforeUndo = driver.core.revision
        driver.undo()
        assertTrue(driver.core.revision > revisionBeforeUndo)
        assertEquals(segmentsBefore, driver.core.segments)
        assertEquals("今日は天気がいいですね", driver.editor.composing)
        assertEquals(depthBefore, driver.core.undoDepth)

        driver.redo()
        assertEquals("今日は転機がいいですね", driver.editor.composing)
        driver.undo()
        assertTrue(driver.core.canRedo)
        driver.type("よ")
        assertFalse(driver.core.canRedo)
    }

    /** 自動変換はUndo stackを埋めず、打鍵の数だけ戻せば空になる。 */
    @Test
    fun automaticConversionDoesNotFillUndoStack() {
        val driver = LiveSessionDriver().type("きょうは")
        assertEquals("今日は", driver.core.display)
        assertEquals(4, driver.core.undoDepth)
        repeat(4) { driver.undo() }
        assertEquals("", driver.core.display)
        assertFalse(driver.core.canUndo)
    }

    /** Undo後の古い結果を捨て、復元した範囲は次の読み編集まで自動変換しない。 */
    @Test
    fun undoRejectsInFlightResultAndGuardsRestoredRange() {
        val driver = heldDriver().type("よい")
        driver.deliver(driver.pending.last())
        driver.type("か")
        val inFlight = driver.pending.last()

        driver.undo()
        assertEquals("良い", driver.editor.composing)
        assertEquals(RejectReason.REVISION_MISMATCH, driver.deliver(inFlight).rejection)
        assertEquals("良い", driver.editor.composing)

        // 復元したprovisional範囲は保護されるため、辞書を更新しても要求が出ない。
        assertNull(driver.core.setConverterGeneration(5).request)

        val edited = driver.core.inputText("か")
        assertEquals(0, assertNotNullAndGet(edited.request).identity.targetStart)
    }

    /** 過去segmentの読みを編集すると、その範囲だけprovisionalへ戻る。 */
    @Test
    fun editingPastReadingRevertsOnlyThatRange() {
        val driver = typedSentence()
        val first = driver.core.segments[0]
        val last = driver.core.segments[2]

        val moved = driver.core.moveCursorTo(6)
        assertEquals(listOf("今日は", "てんきが", "いいですね"), surfaces(driver.core))
        assertEquals(listOf(EditorCommand.SetComposition("今日はてんきがいいですね", 5)), moved.commands)

        driver.handle(driver.core.deleteBackward())
        assertEquals(listOf("今日は", "てきが", "いいですね"), surfaces(driver.core))
        assertEquals(first, driver.core.segments[0])
        val shifted = driver.core.segments[2]
        assertEquals(listOf(last.id, 7L, 12L), listOf(shifted.id, shifted.readingStart.toLong(), shifted.readingEnd.toLong()))
        assertEquals(last.surface, shifted.surface)
        assertEquals(SegmentState.PROVISIONAL, driver.core.segments[1].state)
        assertEquals(5, driver.core.inputCursor)

        driver.undo()
        driver.undo()
        assertEquals("今日は天気がいいですね", driver.core.display)
        assertEquals(SegmentState.STABLE, driver.core.segments[1].state)
    }

    /** `、`は直前をstableにし、未変換の範囲は変換結果が届いた時点でstableにする。 */
    @Test
    fun readingCommaStabilizesPrecedingSegments() {
        val driver = LiveSessionDriver().type("きょうは")
        assertEquals(SegmentState.PROVISIONAL, driver.core.segments[0].state)
        driver.type("、")
        assertEquals(listOf("今日は", "、"), surfaces(driver.core))
        assertEquals(listOf(SegmentState.STABLE, SegmentState.STABLE), states(driver.core))

        driver.deliverImmediately = false
        driver.type("てんき")
        driver.type("、")
        assertEquals(SegmentState.PROVISIONAL, driver.core.segments[2].state)
        driver.deliver(driver.pending.last())
        assertEquals(listOf("今日は", "、", "天気", "、"), surfaces(driver.core))
        assertTrue(driver.core.segments.all { it.state == SegmentState.STABLE })
        assertEquals("", driver.editor.committed.toString())
    }

    /** `。！？`と改行は現在の表示を確定し、改行は一度だけ入れる。 */
    @Test
    fun terminalPunctuationAndNewlineCommit() {
        val driver = LiveSessionDriver().type("よい！")
        assertEquals("良い！", driver.editor.committed.toString())
        driver.type("よい？")
        assertEquals("良い！良い？", driver.editor.committed.toString())

        driver.type("よい")
        val newline = driver.enter(EnterKind.Newline)
        assertEquals(listOf(EditorCommand.Commit("良い"), EditorCommand.Commit("\n")), newline.commands)
        assertEquals("良い！良い？良い\n", driver.editor.text)
        assertFalse(driver.core.canUndo)
    }

    /** 結合濁点は直前のかなと一つの書記素になり、途中で区切った結果を拒否し、一回の削除で消える。 */
    @Test
    fun combiningVoicedMarkStaysInOneGrapheme() {
        val driver = heldDriver().type("か")
        driver.deliver(driver.pending.last())
        assertEquals("蚊", driver.core.display)

        val joined = driver.core.inputText("゙")
        driver.handle(joined)
        assertEquals(1, driver.core.segments.size)
        assertEquals("が", driver.core.segments[0].surface)
        assertEquals(1, driver.core.inputCursor)
        assertEquals(EditorCommand.SetComposition("が", 2), joined.commands.single())

        val request = driver.pending.last()
        assertEquals("が", request.targetReading)
        val split = ConversionResult(request.identity, listOf(ResultSegment("か", "蚊"), ResultSegment("゙", "゛")))
        assertEquals(RejectReason.MALFORMED_SEGMENTS, driver.core.onConversionResult(split).rejection)

        driver.backspace()
        assertEquals("", driver.core.display)
        assertEquals("", driver.editor.composing)
    }

    /** 濁点・小文字の変換は直前の書記素だけを置き換え、そのsegmentを読みへ戻す一操作になる。 */
    @Test
    fun kanaTransformEditsOnlyPrecedingGrapheme() {
        val driver = heldDriver().type("よい")
        driver.deliver(driver.pending.last())
        assertEquals("良い", driver.core.display)

        val transformed = driver.core.transformBeforeCursor { if (it == "い") "ぃ" else null }
        assertEquals(listOf(EditorCommand.SetComposition("よぃ", 2)), transformed.commands)
        assertEquals("よぃ", assertNotNullAndGet(transformed.request).targetReading)
        assertFalse(driver.core.transformBeforeCursor { null }.handled)

        driver.undo()
        assertEquals("良い", driver.core.display)
    }

    /** サロゲートペアの文字はUTF-16で2単位でも一書記素として入力・削除する。 */
    @Test
    fun surrogatePairIsOneGrapheme() {
        val driver = LiveSessionDriver().type("😀あ")
        assertEquals(2, driver.core.inputCursor)
        assertEquals(3, driver.editor.cursorInComposition)
        assertEquals(0, driver.core.clusterIndexForDisplayOffset(0))
        assertEquals(1, driver.core.clusterIndexForDisplayOffset(2))
        assertNull(driver.core.clusterIndexForDisplayOffset(1))

        driver.backspace()
        assertEquals("😀", driver.editor.composing)
        assertEquals(2, driver.editor.cursorInComposition)
        driver.backspace()
        assertEquals("", driver.editor.composing)
        assertEquals(0, driver.core.segments.size)
    }

    /** ZWJ絵文字を部品ごとに入力しても一書記素にまとまり、一回の削除で消える。 */
    @Test
    fun zwjEmojiTypedInPartsBecomesOneGrapheme() {
        val driver = LiveSessionDriver()
        driver.handle(driver.core.inputText("👩"))
        driver.handle(driver.core.inputText("‍"))
        driver.handle(driver.core.inputText("💻"))
        assertEquals("👩‍💻", driver.core.display)
        assertEquals(1, driver.core.segments.size)
        assertEquals(1, driver.core.inputCursor)
        assertEquals("👩‍💻".length, driver.editor.cursorInComposition)

        driver.backspace()
        assertEquals("", driver.core.display)
    }

    /** 正しく変換される文は、文字キーだけで確定操作と訂正操作なしに入力できる。 */
    @Test
    fun operationCountsForSentenceWithoutCorrection() {
        val driver = LiveSessionDriver().type("${sentence}。")
        assertEquals("今日は天気がいいですね。", driver.editor.committed.toString())
        assertEquals(OperationCounts(keys = 14, commits = 0, corrections = 0, terminators = 0), driver.counts)
        assertTrue(driver.rejections.isEmpty())
    }

    /** 過去segmentを一つ直す文は、選択・候補・末尾復帰の3操作の訂正で入力できる。 */
    @Test
    fun operationCountsForSentenceWithOneCorrection() {
        val driver = typedSentence()
        driver.focus(1)
        driver.select("転機が")
        driver.returnToInput()
        driver.type("。")
        assertEquals("今日は転機がいいですね。", driver.editor.committed.toString())
        assertEquals(OperationCounts(keys = 14, commits = 0, corrections = 3, terminators = 0), driver.counts)
    }

    /** 確定キーは確定操作として数え、Enterは終端操作として分けて数える。 */
    @Test
    fun operationCountsSeparateCommitFromTerminator() {
        val driver = LiveSessionDriver().type("よい")
        driver.commit()
        driver.type("よい")
        driver.enter(EnterKind.EditorAction(actionCode = 3))
        assertEquals("良い良い", driver.editor.committed.toString())
        assertEquals(OperationCounts(keys = 4, commits = 1, corrections = 0, terminators = 1), driver.counts)
    }

    /** nullでないことを確かめ、その値を返す。 */
    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
