package dev.uzumi.ime.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 段階0で固定したプロンプト形式（`docs/phase3a-stage0.md`）を、Macでの確認（`tools/phase3a/stage0.py`）と同じ文字列で固定する。 */
class NeuralPromptFormatTest {
    /** zenzは左文脈があるときだけU+EE02を付け、読みをカタカナへ写し、半角空白を全角へ写す。 */
    @Test
    fun zenzPromptAddsContextTagOnlyWithContext() {
        assertEquals("カンジ", NeuralPromptFormat.ZENZ.build("かんじ", ""))
        assertEquals("明日の　demoヲ", NeuralPromptFormat.ZENZ.build("を", "明日の demo"))
    }

    /** jinenは左文脈が空でもU+EE02を付け、プロンプト全体をNFKC正規化する（全角英数は半角になる）。 */
    @Test
    fun jinenPromptAlwaysAddsContextTagAndNormalizes() {
        assertEquals("カンジ", NeuralPromptFormat.JINEN.build("かんじ", ""))
        assertEquals("PDFヲ", NeuralPromptFormat.JINEN.build("を", "ＰＤＦ"))
    }

    /** 欄の中の区切り記号と制御文字は除き、左文脈は後ろの64文字だけを使う。繰り返し記号もカタカナへ写す。 */
    @Test
    fun fieldsAreSanitizedAndContextIsLimited() {
        assertEquals("あいヽ", NeuralPromptFormat.JINEN.build("ゝ", "あ\nい"))
        val context = "一".repeat(10) + "二".repeat(64)
        assertEquals("" + "二".repeat(64) + "ア", NeuralPromptFormat.ZENZ.build("あ", context))
        // サロゲートの組（絵文字）は途中で切らない
        val emoji = "😀".repeat(70)
        assertEquals(64, NeuralPromptFormat.ZENZ.build("あ", emoji).codePoints().filter { it == 0x1F600 }.count().toInt())
    }

    /** 条件名からモデルを引き、Mozcだけ（M）は引かない。モデルごとに変換結果の世代が異なる。 */
    @Test
    fun modelSpecsAreLookedUpByConditionName() {
        assertEquals(NeuralModelSpec.JINEN_SMALL, NeuralModelSpec.fromKey("JS"))
        assertNull(NeuralModelSpec.fromKey("M"))
        assertEquals(4, NeuralModelSpec.entries.map { it.generation }.toSet().size)
        assertNotEquals(0L, NeuralModelSpec.ZENZ_XSMALL.generation)
    }
}
