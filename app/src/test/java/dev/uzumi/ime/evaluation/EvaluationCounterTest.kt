package dev.uzumi.ime.evaluation

import dev.uzumi.ime.keyboard.KeyboardAction
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
        val counter = EvaluationCounter()
        counter.record(OperationKind.INPUT)
        assertNull(counter.finish())

        assertTrue(counter.start("N01"))
        counter.record(OperationKind.INPUT)
        counter.record(OperationKind.TERMINATOR)
        val result = counter.finish()
        assertEquals(TaskOperationCounts("N01", OperationCounts(keys = 1, terminators = 1)), result)
        assertEquals("1\tN01\t1\t0\t0\t1", result?.toTsvRow())
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

    /** 計数の結果は件数と課題IDだけを持ち、本文・読み・候補を入れる欄が無い。 */
    @Test
    fun resultsHoldNoTextFields() {
        val countFields = OperationCounts::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(countFields.isNotEmpty())
        assertTrue(countFields.all { it.type == Int::class.javaPrimitiveType })

        val resultFields = TaskOperationCounts::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.type }
        assertEquals(mapOf("taskId" to String::class.java, "counts" to OperationCounts::class.java), resultFields)

        // 計数器が保持する値も、課題IDと件数だけ
        val counterFields = EvaluationCounter::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.type }
        assertEquals(mapOf("taskId" to String::class.java, "counts" to OperationCounts::class.java), counterFields)
    }
}
