package dev.uzumi.ime.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** composition内の挿入、選択置換、削除を純粋な状態として検証する。 */
class CompositionBufferTest {
    /** 選択範囲を置換し、結合文字を一操作で削除する。 */
    @Test
    fun replacesSelectionAndDeletesWholeCluster() {
        val buffer = CompositionBuffer()
        assertTrue(buffer.insert("Aか\u3099B"))
        assertTrue(buffer.setSelection(0, 1))
        assertTrue(buffer.insert("X"))
        assertEquals("Xか\u3099B", buffer.reading)
        assertTrue(buffer.setCursor(2))
        assertTrue(buffer.deleteBackward())
        assertEquals("XB", buffer.reading)
    }

    /** 候補表示を変えても読みを保持し、次の編集で読み表示へ戻す。 */
    @Test
    fun preservesReadingUnderCandidateDisplay() {
        val buffer = CompositionBuffer()
        buffer.insert("かな")
        assertTrue(buffer.setDisplay("カナ"))
        assertEquals("かな", buffer.reading)
        assertEquals("カナ", buffer.display)
        assertTrue(buffer.insert("い"))
        assertEquals("かない", buffer.display)
        assertFalse(buffer.hasSelection)
    }

    /** カーソル直前のかなだけを変換し、候補表示を読みへ戻す。 */
    @Test
    fun transformsKanaBeforeCursor() {
        val buffer = CompositionBuffer()
        buffer.insert("はな")
        buffer.setDisplay("ハナ")
        buffer.moveCursor(-1)

        assertTrue(buffer.transformBeforeCursor(KanaModifier::transform))
        assertEquals("ばな", buffer.reading)
        assertEquals("ばな", buffer.display)
    }
}
