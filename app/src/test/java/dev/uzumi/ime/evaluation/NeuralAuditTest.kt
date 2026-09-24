package dev.uzumi.ime.evaluation

import dev.uzumi.ime.live.LiveSegment
import dev.uzumi.ime.live.ResultSegment
import dev.uzumi.ime.live.SegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3aのH3の数え方と正→誤の遷移の数え方を、変換器と独立に実装した判定として固定する。 */
class NeuralAuditTest {
    // 「じゅう」「じ」「から」に分けるMozcの文節（候補に「10」と「時」がある）。
    private val jujikara = listOf(
        ResultSegment("じゅう", "十", listOf("十", "10", "銃")),
        ResultSegment("じ", "時", listOf("時", "字")),
        ResultSegment("から", "から"),
    )

    /** 生成件数：Mozcの候補の連結と完全一致する数字は数えず、一致しない数字（11時、0時）は数える。 */
    @Test
    fun generatedRunsAreCountedAgainstMozcCandidates() {
        assertEquals(0, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "10時から", jujikara))
        assertEquals(1, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "11時から", jujikara))
        assertEquals(1, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "0時から", jujikara))
        assertEquals(0, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "十時から", jujikara))
    }

    /** かなの並びが読みと合わない出力や、Mozcが使えない場合は、範囲を決められないためすべての連なりを数える。 */
    @Test
    fun unalignableOutputsCountAllRuns() {
        assertEquals(2, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "10時まで1分", jujikara))
        assertEquals(1, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "10時から", null))
        assertEquals(0, NeuralAudit.generatedDigitLetterRuns("じゅうじから", "十時から", null))
    }

    /**
     * 適用後の違反件数：読みに無い数字を含み、Mozcの候補に無い表記のsegmentを数える。
     * 検査4を外した変換器が出し得る「じゅうじ」→「11時」を数え、「10時」と、読みにある数字だけのsegmentは数えない。
     */
    @Test
    fun appliedViolationsCountUnbackedGeneratedDigits() {
        val mozc = { reading: String -> if (reading == "じゅうじ") jujikara.take(2) else listOf(ResultSegment(reading, reading)) }
        val segments = listOf(
            ResultSegment("じゅうじ", "11時"),
            ResultSegment("じゅうじ", "10時"),
            ResultSegment("2かい", "2階"),
            ResultSegment("abc", "abc"),
        )

        assertEquals(1, NeuralAudit.appliedViolations(segments, mozc))
        assertEquals(1, NeuralAudit.appliedViolations(listOf(ResultSegment("じゅうじ", "10時")), { null }))
    }

    /** 入力した数字・英字の改変：全角・半角の違いは改変にせず、桁や綴りが変わったものを数える。 */
    @Test
    fun inputModificationsIgnoreWidthOnly() {
        val segments = listOf(
            ResultSegment("2かい", "２階"),
            ResultSegment("2かい", "3階"),
            ResultSegment("demoを", "デモを"),
            ResultSegment("7がつ14にち", "7月14日"),
        )

        assertEquals(2, NeuralAudit.inputModifications(segments))
    }

    /** 最終文の数字の並び：漢数字と算用数字の許容表記のどちらかと一致すればよく、全角は半角へ直して比べる。 */
    @Test
    fun digitSequenceMatchesAnyAcceptedForm() {
        val accepted = listOf("300円の切符を買う。", "三百円の切符を買う。")

        assertFalse(NeuralAudit.digitSequenceViolation("３００円の切符を買う。", accepted))
        assertFalse(NeuralAudit.digitSequenceViolation("三百円の切符を買う。", accepted))
        assertTrue(NeuralAudit.digitSequenceViolation("30円の切符を買う。", accepted))
        assertTrue(NeuralAudit.digitSequenceViolation("300円の切符を1枚買う。", accepted))
        assertFalse(NeuralAudit.digitSequenceViolation("何でも", emptyList()))
    }

    /** 正→誤の遷移：正しい接頭部が短くなり、失われた範囲が変換済みだったときだけ数える。 */
    @Test
    fun correctToWrongNeedsLostConvertedPrefix() {
        val accepted = listOf("今日は晴れ")
        val correct = listOf(segment(1, "今日", converted = true), segment(2, "は", converted = true))
        val wrong = listOf(segment(1, "京", converted = true), segment(2, "は", converted = true))
        val raw = listOf(segment(1, "きょう", converted = false), segment(2, "は", converted = false))

        assertTrue(NeuralAudit.isCorrectToWrong("", correct, wrong, accepted))
        assertFalse(NeuralAudit.isCorrectToWrong("", wrong, correct, accepted))
        // 未変換の読みが初めて変換された場合は、正しい接頭部が短くならないため数えない
        assertFalse(NeuralAudit.isCorrectToWrong("", raw, wrong, accepted))
        // compositionより前の欄の文字列も接頭部に含める
        assertTrue(NeuralAudit.isCorrectToWrong("今日", listOf(segment(3, "は", converted = true)), listOf(segment(3, "派", converted = true)), listOf("今日は")))
        assertEquals(3, NeuralAudit.correctPrefixLength("今日は雨", accepted))
    }

    /** 許容表記を課題の間だけ持つ判定器は、許容表記が無ければ判定しない。 */
    @Test
    fun judgeReportsUnjudgedWithoutAcceptedForms() {
        val judge = CorrectnessJudge()
        assertEquals(-1, judge.digitViolation("3時"))
        judge.start(listOf("3時", "三時"))
        assertEquals(0, judge.digitViolation("３時"))
        assertEquals(1, judge.digitViolation("4時"))
        judge.clear()
        assertFalse(judge.isActive)
    }

    /** 表記だけを指定した試験用の文節。 */
    private fun segment(id: Long, surface: String, converted: Boolean) = LiveSegment(
        id = id,
        readingStart = 0,
        readingEnd = 1,
        reading = "あ",
        surface = surface,
        state = SegmentState.PROVISIONAL,
        candidates = listOf(surface),
        converted = converted,
        observations = 1,
        lastObservedReadingVersion = 0,
    )
}
