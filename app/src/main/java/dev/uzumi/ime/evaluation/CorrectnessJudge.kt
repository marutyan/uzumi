package dev.uzumi.ime.evaluation

import dev.uzumi.ime.live.LiveSegment

/**
 * Phase 3aの評価で、課題の許容表記を使う二つの判定（正→誤の遷移、最終文の数字の並び）を端末内で行う。
 * 許容表記は課題の間だけメモリに持ち、判定の結果（真偽と件数）だけを計数器へ渡す。本文を持たないことを
 * テストで固定している計数器（[EvaluationCounter]）とは分けて置く。debugビルドの受信口が課題の開始時に設定し、
 * IMEと受信口はどちらもUIスレッドから呼ぶ。
 */
class CorrectnessJudge {
    // 数えている課題の許容表記。空なら判定しない。
    private var accepted: List<String> = emptyList()

    /** 許容表記を持ち、判定できる状態か。 */
    val isActive: Boolean
        get() = accepted.isNotEmpty()

    /** 課題の許容表記を設定する。空のリストなら判定しない。 */
    fun start(acceptedForms: List<String>) {
        accepted = acceptedForms.filter(String::isNotEmpty)
    }

    /** 許容表記を捨てる。 */
    fun clear() {
        accepted = emptyList()
    }

    /** 変換結果の適用で表示が[before]から[after]へ変わったことが、正→誤の遷移に当たるか。[prefix]はcompositionより前の欄の文字列。 */
    fun isCorrectToWrong(prefix: String, before: List<LiveSegment>, after: List<LiveSegment>): Boolean =
        NeuralAudit.isCorrectToWrong(prefix, before, after, accepted)

    /** 最終文[finalText]の数字の並びの判定。違反なら1、一致なら0、許容表記が無ければ-1。 */
    fun digitViolation(finalText: String?): Int = when {
        finalText == null || accepted.isEmpty() -> -1
        NeuralAudit.digitSequenceViolation(finalText, accepted) -> 1
        else -> 0
    }

    companion object {
        /** IMEと受信口が共有する、プロセスで一つの判定器。 */
        val shared = CorrectnessJudge()
    }
}
