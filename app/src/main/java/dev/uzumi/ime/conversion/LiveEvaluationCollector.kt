package dev.uzumi.ime.conversion

import dev.uzumi.ime.editor.GraphemeClusters
import dev.uzumi.ime.evaluation.LiveRequestReport
import dev.uzumi.ime.evaluation.NeuralAudit
import dev.uzumi.ime.evaluation.NeuralCounts
import dev.uzumi.ime.evaluation.TimingKind
import dev.uzumi.ime.evaluation.TimingSample
import dev.uzumi.ime.live.ResultSegment
import dev.uzumi.ime.live.ConversionResult as LiveResult

/**
 * 評価モードの間、一つのライブ変換要求の処理から評価用の件数と時間を集める。変換workerのthreadだけで使う。
 * H3の生成件数と適用後の違反件数は、変換器の検査を使わず[NeuralAudit]で数え、照合に使うMozcの文節は
 * [mozcDirect]（変換器を通さずMozcを直接呼ぶ関数）から取る。文字列は件数を数える間だけ使い、残さない。
 */
class LiveEvaluationCollector(
    private val mozcDirect: (String) -> List<ResultSegment>?,
) : NeuralConversionObserver {
    private var counts = NeuralCounts()
    private val samples = mutableListOf<TimingSample>()

    /** 部分範囲を一つ変換し始めた（かなを含み、ASCIIだけでないもの）。 */
    fun onRange() {
        counts += NeuralCounts(ranges = 1)
    }

    override fun onKanaRun(reading: String, split: Boolean) {
        counts += NeuralCounts(kanaRuns = 1, longSplits = if (split) 1 else 0)
    }

    override fun onModelOutput(reading: String, output: String, verdict: NeuralVerdict) {
        val generated = NeuralAudit.generatedDigitLetterRuns(reading, output, mozcDirect(reading))
        counts += NeuralCounts(
            check1Rejected = if (verdict == NeuralVerdict.CHECK1_INVALID_TEXT) 1 else 0,
            check2Rejected = if (verdict == NeuralVerdict.CHECK2_TRUNCATED) 1 else 0,
            check3Rejected = if (verdict == NeuralVerdict.CHECK3_KANA_MISMATCH) 1 else 0,
            check4Rejected = if (verdict == NeuralVerdict.CHECK4_UNBACKED_DIGITS) 1 else 0,
            h3Generated = generated,
            h3Unrejected = if (generated > 0 && verdict == NeuralVerdict.ACCEPTED) 1 else 0,
        )
    }

    override fun onModelFailed(reason: NeuralFailure) {
        counts += when (reason) {
            NeuralFailure.TIMEOUT -> NeuralCounts(timeouts = 1)
            NeuralFailure.UNAVAILABLE, NeuralFailure.ERROR -> NeuralCounts(unavailable = 1)
        }
    }

    override fun onFallback(usedReading: Boolean) {
        counts += if (usedReading) NeuralCounts(readingFallbacks = 1) else NeuralCounts(mozcFallbacks = 1)
    }

    /**
     * モデルへの一回の呼び出しを数える。推論時間の母集団は送った要求（cache hitを除く）で、時間超過は評価条件どおり
     * 時間超過の値で打ち切った値にする。中断した要求は推論時間に入れず、中断までの時間を別に残す。
     */
    fun onCall(record: NeuralCallRecord) {
        if (record.cacheHit) {
            counts += NeuralCounts(cacheHits = 1)
            return
        }
        if (record.outcome == NeuralCallOutcome.CANCELLED) {
            counts += NeuralCounts(cancelled = 1)
            if (record.sent) samples += TimingSample(TimingKind.CANCELLED, record.millis, record.outcome)
            return
        }
        if (!record.sent) return
        counts += NeuralCounts(modelRequests = 1)
        val millis = if (record.outcome == NeuralCallOutcome.TIMEOUT) {
            NeuralInferenceSettings.TIMEOUT_MILLIS.toDouble()
        } else {
            record.millis
        }
        samples += TimingSample(TimingKind.INFERENCE, millis, record.outcome)
    }

    /**
     * 要求の処理を終えて報告を作る。[result]は返した結果（返さなければnull）で、保護範囲以外のsegmentについて、
     * 適用された場合だけ数える項目（H3の適用後の違反件数、入力した数字・英字の改変）を求める。
     */
    fun report(result: LiveResult?, requestedAtNanos: Long): LiveRequestReport {
        val applied = result?.let { converted ->
            val identity = converted.identity
            var position = identity.targetStart
            val free = converted.segments.filter { segment ->
                val start = position
                position += GraphemeClusters.split(segment.reading).size
                identity.protectedRanges.none { it.readingStart == start && it.readingEnd == position }
            }
            NeuralCounts(
                h3AppliedViolations = NeuralAudit.appliedViolations(free, mozcDirect),
                inputModifications = NeuralAudit.inputModifications(free),
            )
        } ?: NeuralCounts()
        return LiveRequestReport(result?.identity, requestedAtNanos, counts, applied, samples.toList())
    }
}
