package dev.uzumi.ime.conversion

import dev.uzumi.ime.evaluation.TimingKind
import dev.uzumi.ime.live.ConversionRequest
import dev.uzumi.ime.live.RequestIdentity
import dev.uzumi.ime.live.ResultSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 評価用の件数（H3の3つの件数、検査ごとの不採用、分割とfallback、時間）を、変換器と組み合わせて検証する。
 * 評価条件どおり、検査4を外した変換器の出力に対しても、計数が独立に違反を数えることを確かめる。
 */
class LiveEvaluationCollectorTest {
    // 「じゅう」「じ」を別の文節にし、候補に「10」「時」を持つMozcの代わり。
    private val mozc = { reading: String ->
        when (reading) {
            "じゅうじ" -> listOf(ResultSegment("じゅう", "十", listOf("十", "10")), ResultSegment("じ", "時", listOf("時", "字")))
            else -> listOf(ResultSegment(reading, reading))
        }
    }

    /** 検査を通して変換し、報告を作る。checkDigitsがfalseなら検査4を外す。 */
    private fun convert(output: String, checkDigits: Boolean): Pair<List<ResultSegment>, dev.uzumi.ime.evaluation.LiveRequestReport> {
        val collector = LiveEvaluationCollector(mozcDirect = mozc)
        val converter = NeuralRangeConverter(
            model = { _, _ -> NeuralModelOutput.Completed(output) },
            dictionary = mozc,
            observer = collector,
            checkDigitsWithDictionary = checkDigits,
        )
        val live = SegmentedLiveConverter(convertRange = { chunk, context ->
            collector.onRange()
            converter.convert(chunk, context)
        })
        val identity = RequestIdentity(1, 1, "じゅうじ", 0, 4, emptyList(), 4, 0)
        val result = live.convert(ConversionRequest(identity, "じゅうじ", learningAllowed = true))!!
        return result.segments to collector.report(result, requestedAtNanos = 0)
    }

    /** 検査4がある場合、読みに合わない数字（11時）は生成件数と拒否件数に数え、適用後の違反は0になる。 */
    @Test
    fun checkFourRejectsGeneratedDigits() {
        val (segments, report) = convert("11時", checkDigits = true)

        assertEquals(listOf("十", "時"), segments.map { it.surface })
        assertEquals(1, report.counts.h3Generated)
        assertEquals(1, report.counts.check4Rejected)
        assertEquals(0, report.counts.h3Unrejected)
        assertEquals(1, report.counts.mozcFallbacks)
        assertEquals(0, report.appliedOnly.h3AppliedViolations)
    }

    /** 検査4を外すと「11時」が表示に残り、計数は検査と独立に、捨てられなかった生成と適用後の違反を数える。 */
    @Test
    fun countsViolationsEvenWhenCheckFourIsDisabled() {
        val (segments, report) = convert("11時", checkDigits = false)

        assertEquals(listOf("11時"), segments.map { it.surface })
        assertEquals(1, report.counts.h3Generated)
        assertEquals(0, report.counts.check4Rejected)
        assertEquals(1, report.counts.h3Unrejected)
        assertEquals(1, report.appliedOnly.h3AppliedViolations)
    }

    /** 辞書の候補と一致する数字（10時）は、どの計数にも当たらない。 */
    @Test
    fun dictionaryBackedDigitsAreNotViolations() {
        val (_, report) = convert("10時", checkDigits = true)

        assertEquals(0, report.counts.h3Generated)
        assertEquals(0, report.appliedOnly.h3AppliedViolations)
        assertEquals(1, report.counts.ranges)
        assertEquals(1, report.counts.kanaRuns)
    }

    /** 推論時間の母集団は送った要求だけで、cache hitを除き、時間超過は300 msで打ち切る。中断は別の種類で残す。 */
    @Test
    fun callRecordsBecomeCountsAndTimings() {
        val collector = LiveEvaluationCollector(mozcDirect = mozc)
        collector.onCall(NeuralCallRecord(sent = true, cacheHit = false, millis = 12.5, outcome = NeuralCallOutcome.COMPLETED))
        collector.onCall(NeuralCallRecord(sent = false, cacheHit = true, millis = 0.0, outcome = NeuralCallOutcome.COMPLETED))
        collector.onCall(NeuralCallRecord(sent = true, cacheHit = false, millis = 280.0, outcome = NeuralCallOutcome.TIMEOUT))
        collector.onCall(NeuralCallRecord(sent = true, cacheHit = false, millis = 3.0, outcome = NeuralCallOutcome.CANCELLED))

        val report = collector.report(null, requestedAtNanos = 0)

        assertEquals(2, report.counts.modelRequests)
        assertEquals(1, report.counts.cacheHits)
        assertEquals(1, report.counts.cancelled)
        assertEquals(listOf(TimingKind.INFERENCE to 12.5, TimingKind.INFERENCE to 300.0, TimingKind.CANCELLED to 3.0), report.samples.map { it.kind to it.millis })
    }
}
