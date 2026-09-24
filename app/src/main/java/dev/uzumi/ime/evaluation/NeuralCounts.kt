package dev.uzumi.ime.evaluation

import dev.uzumi.ime.conversion.NeuralCallOutcome
import dev.uzumi.ime.live.RequestIdentity

/**
 * Phase 3aの評価条件が求める、ニューラル側とH3の計数（件数だけ）。各項目の定義は
 * `docs/phase3a-model-selection-protocol.md`の「ニューラル側の計数」と「H3の数え方」に従う。
 * Mozcだけの条件（M）でも同じ列を出し、ニューラルの項目が0であることを確かめる。
 */
data class NeuralCounts(
    /** ライブ変換で変換した部分範囲の数（かなを含み、ASCIIだけでないもの）。分割の数の分母。 */
    val ranges: Int = 0,
    /** モデルへ渡したかなの連なりの数（長い入力を区切った後の数）。 */
    val kanaRuns: Int = 0,
    /** 30文字を超えたため区切った回数。 */
    val longSplits: Int = 0,
    /** モデルへ実際に送った要求の数（cache hitを除く）。 */
    val modelRequests: Int = 0,
    val cacheHits: Int = 0,
    /** 新しい入力で推論を中断した数。 */
    val cancelled: Int = 0,
    /** 時間超過でモデルを待たずにMozcの結果を使った数。 */
    val timeouts: Int = 0,
    /** モデルが使えなかった数（未準備、別プロセスの終了、推論の失敗）。時間超過は含めない。 */
    val unavailable: Int = 0,
    val check1Rejected: Int = 0,
    val check2Rejected: Int = 0,
    val check3Rejected: Int = 0,
    /** 検査4で捨てた数。H3の拒否件数でもある。 */
    val check4Rejected: Int = 0,
    /** モデルの結果を使わず、かなの連なりにMozcの結果を表示した数。 */
    val mozcFallbacks: Int = 0,
    /** モデルもMozcも使えず、かなの連なりを読みのまま表示した数。 */
    val readingFallbacks: Int = 0,
    /** H3の生成件数（検査の前に数える）。 */
    val h3Generated: Int = 0,
    /** 生成件数に当たる出力のうち、検査1〜4のどれでも捨てられなかったもの。1件でもあれば試験を止める。 */
    val h3Unrejected: Int = 0,
    /** H3の適用後の違反件数。 */
    val h3AppliedViolations: Int = 0,
    /** 入力した数字・英字を改変したsegmentの数（適用した結果だけ）。 */
    val inputModifications: Int = 0,
    /** 正→誤の遷移の回数。 */
    val correctToWrong: Int = 0,
) {
    /** 二つの計数を項目ごとに足す。 */
    operator fun plus(o: NeuralCounts): NeuralCounts = NeuralCounts(
        ranges + o.ranges, kanaRuns + o.kanaRuns, longSplits + o.longSplits, modelRequests + o.modelRequests,
        cacheHits + o.cacheHits, cancelled + o.cancelled, timeouts + o.timeouts, unavailable + o.unavailable,
        check1Rejected + o.check1Rejected, check2Rejected + o.check2Rejected, check3Rejected + o.check3Rejected,
        check4Rejected + o.check4Rejected, mozcFallbacks + o.mozcFallbacks, readingFallbacks + o.readingFallbacks,
        h3Generated + o.h3Generated, h3Unrejected + o.h3Unrejected, h3AppliedViolations + o.h3AppliedViolations,
        inputModifications + o.inputModifications, correctToWrong + o.correctToWrong,
    )

    /** [TSV_COLUMNS]の順の値。 */
    fun values(): List<Int> = listOf(
        ranges, kanaRuns, longSplits, modelRequests, cacheHits, cancelled, timeouts, unavailable,
        check1Rejected, check2Rejected, check3Rejected, check4Rejected, mozcFallbacks, readingFallbacks,
        h3Generated, h3Unrejected, h3AppliedViolations, inputModifications, correctToWrong,
    )

    companion object {
        /** 計数の行へ足す列名。 */
        val TSV_COLUMNS = listOf(
            "neural_ranges", "neural_kana_runs", "neural_long_splits", "neural_requests", "neural_cache_hits",
            "neural_cancelled", "neural_timeouts", "neural_unavailable", "check1_rejected", "check2_rejected",
            "check3_rejected", "check4_rejected", "mozc_fallbacks", "reading_fallbacks", "h3_generated",
            "h3_unrejected", "h3_applied_violations", "input_modifications", "correct_to_wrong",
        )
    }
}

/** 時間の記録の種類。 */
enum class TimingKind(val label: String) {
    /** モデルへ送った要求の推論時間。時間超過は300 msで打ち切った値にする。 */
    INFERENCE("inference"),

    /** 新しい入力で中断した要求の、中断までの時間。推論時間の母集団には入れない。 */
    CANCELLED("cancelled"),

    /** 変換要求を作った入力の受理から、結果の適用までの時間。 */
    APPLY("apply"),
}

/** 時間の記録1件。どの読みの要求かは持たず、数値と種類（列挙）だけを持つ。適用までの時間ではoutcomeは無い。 */
data class TimingSample(val kind: TimingKind, val millis: Double, val outcome: NeuralCallOutcome? = null)

/**
 * workerが一つのライブ変換要求を処理した後に返す、評価用の報告。評価モードの間だけ作る。
 * identityは結果を返した要求の識別（結果を返さなかった場合はnull）で、UIスレッドで適用されたかの照合にだけ使う。
 * [appliedOnly]は結果が適用された場合だけ数える項目（H3の適用後の違反件数、入力した数字・英字の改変）。
 */
data class LiveRequestReport(
    val identity: RequestIdentity?,
    val requestedAtNanos: Long,
    val counts: NeuralCounts,
    val appliedOnly: NeuralCounts,
    val samples: List<TimingSample>,
)
