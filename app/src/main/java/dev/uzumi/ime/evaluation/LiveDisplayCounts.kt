package dev.uzumi.ime.evaluation

import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.RejectReason
import dev.uzumi.ime.live.SegmentState

/**
 * 1課題のライブ変換の表示と文節状態の計数。flickerと誤書換えの判定に使う件数だけを持ち、本文は持たない。
 * 各項目の定義は`docs/phase2c-evaluation-protocol.md`の「評価用計数」に従う。
 */
data class LiveDisplayCounts(
    /** 変換結果の適用でEditorの表示（文字列）が変わった回数。未変換の読みが初めて変換された表示も含む。 */
    val displayChanges: Int = 0,
    /** [displayChanges]のうち、変わった部分が既に変換済みの表示を含んでいた回数。初回の表示を除いたもの。 */
    val flicker: Int = 0,
    /** 変換結果の適用で、stableだった文節の読み範囲・表記・状態のどれかが変わった数。コアの規則上は0。 */
    val stableOverwrites: Int = 0,
    /** 変換結果の適用で、chosenだった文節の読み範囲・表記・状態のどれかが変わった数。コアの規則上は0。 */
    val chosenOverwrites: Int = 0,
    /** 古い要求への結果としてコアが照合して捨てた数。捨てずに適用された古い結果はこの計数器では検出できない。 */
    val staleResultsDiscarded: Int = 0,
    /** provisionalからstableへ変わった文節の数。 */
    val toStable: Int = 0,
    /** provisionalまたはstableからchosenへ変わった文節の数。 */
    val toChosen: Int = 0,
    /** stableまたはchosenからprovisionalへ戻った文節の数（Undo・Redoで同じ文節が戻った場合）。 */
    val toProvisional: Int = 0,
) {
    /** 二つの計数を項目ごとに足す。 */
    operator fun plus(other: LiveDisplayCounts): LiveDisplayCounts = LiveDisplayCounts(
        displayChanges = displayChanges + other.displayChanges,
        flicker = flicker + other.flicker,
        stableOverwrites = stableOverwrites + other.stableOverwrites,
        chosenOverwrites = chosenOverwrites + other.chosenOverwrites,
        staleResultsDiscarded = staleResultsDiscarded + other.staleResultsDiscarded,
        toStable = toStable + other.toStable,
        toChosen = toChosen + other.toChosen,
        toProvisional = toProvisional + other.toProvisional,
    )
}

/**
 * ライブ変換の前後の文節列を比べて[LiveDisplayCounts]を作る規則。文節列はこの関数の中だけで読み、
 * 返り値は件数だけにすることで、計数器へ本文が渡らないようにする。評価モードの間だけ呼ばれる。
 */
object LiveChangeClassifier {
    /** 古い要求への結果として捨てた1件。 */
    val STALE_RESULT_DISCARDED = LiveDisplayCounts(staleResultsDiscarded = 1)

    // 要求の識別情報が現在値と合わないため捨てた理由。形の誤りなど、古さ以外の理由は含めない。
    private val STALE_REASONS = setOf(
        RejectReason.EPOCH_MISMATCH,
        RejectReason.GENERATION_MISMATCH,
        RejectReason.REVISION_MISMATCH,
        RejectReason.READING_MISMATCH,
        RejectReason.TARGET_MISMATCH,
        RejectReason.PROTECTED_MISMATCH,
    )

    /** 変換結果を捨てた理由[reason]が、要求が古いためかを返す。 */
    fun isStale(reason: RejectReason): Boolean = reason in STALE_REASONS

    /**
     * 変換結果を適用した前後[before]・[after]の計数。表示が変わったか、変わった部分に変換済みの表示が
     * 含まれていたか（flicker）、stable・chosenの文節が変わったか、文節状態の変化を数える。
     */
    fun resultApplied(before: List<LiveSegment>, after: List<LiveSegment>): LiveDisplayCounts {
        val overwrites = before.filter { it.state != SegmentState.PROVISIONAL }.filterNot { old ->
            after.any {
                it.readingStart == old.readingStart && it.readingEnd == old.readingEnd &&
                    it.surface == old.surface && it.state == old.state
            }
        }
        val beforeText = before.joinToString(separator = "") { it.surface }
        val afterText = after.joinToString(separator = "") { it.surface }
        val changed = beforeText != afterText
        return transitions(before, after, newSegmentsStartProvisional = true) + LiveDisplayCounts(
            displayChanges = if (changed) 1 else 0,
            flicker = if (changed && changedPartWasConverted(before, beforeText, afterText)) 1 else 0,
            stableOverwrites = overwrites.count { it.state == SegmentState.STABLE },
            chosenOverwrites = overwrites.count { it.state == SegmentState.CHOSEN },
        )
    }

    /** ユーザーの操作（候補の選択、Undoなど）の前後[before]・[after]の文節状態の変化を数える。 */
    fun userOperation(before: List<LiveSegment>, after: List<LiveSegment>): LiveDisplayCounts =
        transitions(before, after, newSegmentsStartProvisional = false)

    /**
     * 同じidの文節の状態の変化を数える。変換結果が新しく作った文節はprovisionalから始まるため、
     * [newSegmentsStartProvisional]がtrueなら新しいidをprovisionalからの変化として扱う。
     * ユーザーの操作が作った文節（入力した読みや句読点）は作られた時の状態から始まるので数えない。
     */
    private fun transitions(
        before: List<LiveSegment>,
        after: List<LiveSegment>,
        newSegmentsStartProvisional: Boolean,
    ): LiveDisplayCounts {
        val previousStates = before.associate { it.id to it.state }
        var toStable = 0
        var toChosen = 0
        var toProvisional = 0
        for (segment in after) {
            val previous = previousStates[segment.id]
                ?: if (newSegmentsStartProvisional) SegmentState.PROVISIONAL else segment.state
            if (previous == segment.state) continue
            when (segment.state) {
                SegmentState.STABLE -> toStable += 1
                SegmentState.CHOSEN -> toChosen += 1
                SegmentState.PROVISIONAL -> toProvisional += 1
            }
        }
        return LiveDisplayCounts(toStable = toStable, toChosen = toChosen, toProvisional = toProvisional)
    }

    /**
     * 表示[beforeText]から[afterText]への変化で、変わった部分が変換済みの文節の表示に掛かるかを返す。
     * 共通の前後を除いた範囲を変わった部分とし、未変換の読みだけが変わった場合は初回の表示として扱う。
     */
    private fun changedPartWasConverted(before: List<LiveSegment>, beforeText: String, afterText: String): Boolean {
        val shorter = minOf(beforeText.length, afterText.length)
        var prefix = 0
        while (prefix < shorter && beforeText[prefix] == afterText[prefix]) prefix += 1
        var suffix = 0
        while (suffix < shorter - prefix &&
            beforeText[beforeText.length - 1 - suffix] == afterText[afterText.length - 1 - suffix]
        ) {
            suffix += 1
        }
        // 変わった部分の、変更前の表示でのUTF-16範囲[changedStart, changedEnd)。挿入だけなら空になる。
        val changedStart = prefix
        val changedEnd = beforeText.length - suffix
        var offset = 0
        for (segment in before) {
            val start = offset
            offset += segment.surface.length
            val touches = if (changedStart < changedEnd) {
                start < changedEnd && offset > changedStart
            } else {
                start <= changedStart && changedStart <= offset
            }
            if (touches && segment.converted) return true
        }
        return false
    }
}
