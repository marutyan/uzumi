package dev.uzumi.ime.evaluation

import dev.uzumi.ime.keyboard.KeyboardAction
import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.RejectReason
import dev.uzumi.ime.live.SegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationCounterTest {
    /** 評価条件の「指標」に合わせた操作の分類を固定する。変える場合は計数の版を上げる。 */
    @Test
    fun keyboardActionsFollowProtocolClassification() {
        fun kind(action: KeyboardAction, live: Boolean = false) = OperationClassifier.keyboardAction(action, live)

        assertEquals(OperationKind.INPUT, kind(KeyboardAction.Text("あ")))
        assertEquals(OperationKind.INPUT, kind(KeyboardAction.Text("、")))
        assertEquals(OperationKind.INPUT, kind(KeyboardAction.Text(".")))
        assertEquals(OperationKind.INPUT, kind(KeyboardAction.Space))
        assertEquals(OperationKind.INPUT, kind(KeyboardAction.TransformKana))
        // 句点とEnterは本来の終端操作
        assertEquals(OperationKind.TERMINATOR, kind(KeyboardAction.Text("。")))
        assertEquals(OperationKind.TERMINATOR, kind(KeyboardAction.Enter))
        assertEquals(OperationKind.TERMINATOR, kind(KeyboardAction.Enter, live = true))
        // 変換キーは明示変換では確定操作、ライブ変換では次の候補へ切り替える訂正操作
        assertEquals(OperationKind.COMMIT, kind(KeyboardAction.Convert))
        assertEquals(OperationKind.CORRECTION, kind(KeyboardAction.Convert, live = true))
        assertEquals(OperationKind.CORRECTION, kind(KeyboardAction.Delete))
        assertEquals(OperationKind.CORRECTION, kind(KeyboardAction.DeleteToLineStart))
        assertEquals(OperationKind.CORRECTION, kind(KeyboardAction.MoveCursor(-1), live = true))
        assertEquals(OperationKind.CORRECTION, kind(KeyboardAction.ToKatakana))
        // 第一候補の選択だけが確定操作
        assertEquals(OperationKind.COMMIT, OperationClassifier.candidatePick(0))
        assertEquals(OperationKind.CORRECTION, OperationClassifier.candidatePick(1))
    }

    /** キー操作数は終端操作を除くすべての押下で、確定・訂正はその内訳になる。 */
    @Test
    fun countsExcludeTerminatorsFromKeys() {
        val counts = listOf(
            OperationKind.INPUT,
            OperationKind.OTHER,
            OperationKind.COMMIT,
            OperationKind.CORRECTION,
            OperationKind.TERMINATOR,
        ).fold(OperationCounts()) { total, kind -> total + kind }
        assertEquals(OperationCounts(keys = 4, commits = 1, corrections = 1, terminators = 1), counts)
    }

    /** 開始するまでは数えず、終えると課題IDと件数だけを返して止まる。 */
    @Test
    fun countsOnlyBetweenStartAndFinish() {
        val counter = EvaluationCounter(clock = { 5L })
        counter.record(OperationKind.INPUT)
        assertNull(counter.finish())

        assertTrue(counter.start("N01"))
        counter.record(OperationKind.INPUT)
        counter.record(OperationKind.TERMINATOR)
        val result = counter.finish()
        assertEquals(OperationCounts(keys = 1, terminators = 1), result?.counts)
        assertEquals("2\tN01\t1\t0\t0\t1\t0\t0\t0\t0\t0\t0\t0\t0\t5\t5\t0", result?.toTsvRow())
        assertEquals(TaskOperationCounts.TSV_HEADER.split('\t').size, result?.toTsvRow()?.split('\t')?.size)

        // 終えた後の押下は数えない
        counter.record(OperationKind.INPUT)
        assertNull(counter.activeTaskId)
        assertNull(counter.finish())
    }

    /** 課題IDの欄へ本文を入れられない。文や長い文字列、区切り文字を含むIDでは始めない。 */
    @Test
    fun rejectsTaskIdsThatCouldCarryText() {
        val counter = EvaluationCounter()
        assertFalse(counter.start("今日は雨が降っています"))
        assertFalse(counter.start("N01\tきょう"))
        assertFalse(counter.start("a".repeat(17)))
        assertFalse(counter.start(""))
        assertNull(counter.activeTaskId)
        assertTrue(counter.start("R06"))
    }

    /** 開始・終了の時刻と、最初の押下から最後の終端操作までの時間を残す。 */
    @Test
    fun recordsTaskTimes() {
        var now = 1_000L
        val counter = EvaluationCounter(clock = { now })
        assertTrue(counter.start("N02"))
        now = 1_500L
        counter.record(OperationKind.INPUT)
        now = 2_000L
        counter.record(OperationKind.INPUT)
        now = 3_200L
        counter.record(OperationKind.TERMINATOR)
        now = 4_000L
        val timing = counter.finish()!!.timing
        assertEquals(TaskTiming(startedMs = 1_000, firstInputMs = 1_500, lastTerminatorMs = 3_200, finishedMs = 4_000), timing)
        assertEquals(1_700L, timing.elapsedMs)

        // 終端操作が無ければ完了時間は欠けた値（-1）にする
        assertTrue(counter.start("N03"))
        counter.record(OperationKind.INPUT)
        assertEquals(-1L, counter.finish()!!.timing.elapsedMs)
    }

    /** 評価モードの間だけ、ライブ変換の計数を足す。終えるとTSVの列へ出る。 */
    @Test
    fun liveCountsAreAddedOnlyWhileRecordingAndWrittenToTsv() {
        val counter = EvaluationCounter(clock = { 10L })
        counter.recordLive(LiveDisplayCounts(displayChanges = 1))
        assertTrue(counter.start("N04"))
        counter.recordLive(LiveDisplayCounts(displayChanges = 1, flicker = 1, toStable = 2))
        counter.recordLive(LiveChangeClassifier.STALE_RESULT_DISCARDED)
        counter.record(OperationKind.TERMINATOR)
        val result = counter.finish()!!
        assertEquals(LiveDisplayCounts(displayChanges = 1, flicker = 1, staleResultsDiscarded = 1, toStable = 2), result.live)
        assertEquals("2\tN04\t0\t0\t0\t1\t1\t1\t0\t0\t1\t2\t0\t0\t10\t10\t0", result.toTsvRow())
        assertEquals(TaskOperationCounts.TSV_HEADER.split('\t').size, result.toTsvRow().split('\t').size)
    }

    /** 未変換の読みが初めて変換された表示はflickerに数えず、変換済みの表示が変わった場合だけ数える。 */
    @Test
    fun flickerExcludesFirstDisplayOfRawReading() {
        // 「きょうは」（未変換）→「今日は」：表示は変わるが初回の表示
        val first = LiveChangeClassifier.resultApplied(
            listOf(segment(1, 0, 4, "きょうは", converted = false)),
            listOf(segment(1, 0, 4, "今日は")),
        )
        assertEquals(LiveDisplayCounts(displayChanges = 1), first)

        // 「今日は」＋未変換「いい」→「今日は」「良い」：変わったのは未変換の部分だけ
        val tail = LiveChangeClassifier.resultApplied(
            listOf(segment(1, 0, 4, "今日は"), segment(2, 4, 6, "いい", converted = false)),
            listOf(segment(1, 0, 4, "今日は"), segment(3, 4, 6, "良い")),
        )
        assertEquals(LiveDisplayCounts(displayChanges = 1), tail)

        // 変換済みの「世」＋未変換「い」→「良い」：変換済みの表示が自動で変わった
        val changed = LiveChangeClassifier.resultApplied(
            listOf(segment(1, 0, 1, "世"), segment(2, 1, 2, "い", converted = false)),
            listOf(segment(3, 0, 2, "良い")),
        )
        assertEquals(LiveDisplayCounts(displayChanges = 1, flicker = 1), changed)

        // 同じ表示の再送（分け方だけの変化を含む）は数えない
        val same = LiveChangeClassifier.resultApplied(
            listOf(segment(1, 0, 4, "今日は")),
            listOf(segment(2, 0, 3, "今日"), segment(3, 3, 4, "は")),
        )
        assertEquals(LiveDisplayCounts(), same)
    }

    /** 変換結果の適用でstable・chosenの文節が変わったら、それぞれ誤書換えの候補として数える。 */
    @Test
    fun protectedSegmentChangesAreCountedSeparately() {
        val before = listOf(
            segment(1, 0, 4, "今日は", SegmentState.CHOSEN),
            segment(2, 4, 7, "天気が", SegmentState.STABLE),
            segment(3, 7, 9, "いい"),
        )
        // 保護された文節がそのまま残る通常の結果
        val kept = LiveChangeClassifier.resultApplied(before, before.take(2) + segment(3, 7, 9, "良い"))
        assertEquals(LiveDisplayCounts(displayChanges = 1, flicker = 1), kept)

        // 規則に反してchosenとstableを書き換えた結果
        val overwritten = LiveChangeClassifier.resultApplied(
            before,
            listOf(segment(4, 0, 4, "京は"), segment(5, 4, 7, "転機が"), segment(3, 7, 9, "いい")),
        )
        assertEquals(1, overwritten.chosenOverwrites)
        assertEquals(1, overwritten.stableOverwrites)
    }

    /** 文節状態の変化を、結果による昇格とユーザーの操作による選択・取り消しで数える。 */
    @Test
    fun countsSegmentStateTransitions() {
        val provisional = listOf(segment(1, 0, 4, "今日は"), segment(2, 4, 7, "天気が"))
        // 結果で既存の文節が昇格し、新しく作られた文節もその場で昇格した
        val promoted = LiveChangeClassifier.resultApplied(
            provisional,
            listOf(segment(1, 0, 4, "今日は", SegmentState.STABLE), segment(3, 4, 6, "天気", SegmentState.STABLE)),
        )
        assertEquals(2, promoted.toStable)

        // 候補の選択でchosenに、Undoでprovisionalへ戻る
        val chosen = listOf(segment(1, 0, 4, "京は", SegmentState.CHOSEN), provisional[1])
        assertEquals(LiveDisplayCounts(toChosen = 1), LiveChangeClassifier.userOperation(provisional, chosen))
        assertEquals(LiveDisplayCounts(toProvisional = 1), LiveChangeClassifier.userOperation(chosen, provisional))

        // ユーザーが入れた句読点の文節は、作られた時からstableなので状態の変化に数えない
        val withComma = provisional + segment(4, 7, 8, "、", SegmentState.STABLE)
        assertEquals(LiveDisplayCounts(), LiveChangeClassifier.userOperation(provisional, withComma))
    }

    /** 捨てた結果のうち、要求が古いためのものだけを古い結果として数える。 */
    @Test
    fun onlyIdentityMismatchesCountAsStale() {
        assertTrue(LiveChangeClassifier.isStale(RejectReason.REVISION_MISMATCH))
        assertTrue(LiveChangeClassifier.isStale(RejectReason.EPOCH_MISMATCH))
        assertFalse(LiveChangeClassifier.isStale(RejectReason.MALFORMED_SEGMENTS))
        assertFalse(LiveChangeClassifier.isStale(RejectReason.CROSSES_PROTECTED))
    }

    /** 計数の結果は件数・時刻と課題IDだけを持ち、本文・読み・候補を入れる欄が無い。 */
    @Test
    fun resultsHoldNoTextFields() {
        fun fields(type: Class<*>) = type.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }

        for ((type, fieldType) in listOf(
            OperationCounts::class.java to Int::class.javaPrimitiveType,
            LiveDisplayCounts::class.java to Int::class.javaPrimitiveType,
            TaskTiming::class.java to Long::class.javaPrimitiveType,
        )) {
            val declared = fields(type)
            assertTrue(declared.isNotEmpty())
            assertTrue("$type", declared.all { it.type == fieldType })
        }

        val resultFields = fields(TaskOperationCounts::class.java).associate { it.name to it.type }
        assertEquals(
            mapOf(
                "taskId" to String::class.java,
                "counts" to OperationCounts::class.java,
                "live" to LiveDisplayCounts::class.java,
                "timing" to TaskTiming::class.java,
            ),
            resultFields,
        )

        // 計数器が保持する値も、課題ID・件数・時刻と時計だけ
        val counterFields = fields(EvaluationCounter::class.java).associate { it.name to it.type }
        assertEquals(
            mapOf(
                "clock" to Function0::class.java,
                "taskId" to String::class.java,
                "counts" to OperationCounts::class.java,
                "live" to LiveDisplayCounts::class.java,
                "timing" to TaskTiming::class.java,
            ),
            counterFields,
        )
    }

    /** 読み範囲[start, end)・表記・状態を指定した試験用の文節。読みの中身は計数に使わない。 */
    private fun segment(
        id: Long,
        start: Int,
        end: Int,
        surface: String,
        state: SegmentState = SegmentState.PROVISIONAL,
        converted: Boolean = true,
    ) = LiveSegment(
        id = id,
        readingStart = start,
        readingEnd = end,
        reading = "あ".repeat(end - start),
        surface = surface,
        state = state,
        candidates = listOf(surface),
        converted = converted,
        observations = 1,
        lastObservedReadingVersion = 0,
    )
}
