package dev.uzumi.ime.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「小゛゜」キーのかな変換表を検証する。 */
class KanaModifierTest {
    /** 小文字、濁点、半濁点を決めた順序で循環する。 */
    @Test
    fun cyclesSupportedHiraganaForms() {
        assertEquals("ぁ", KanaModifier.transform("あ"))
        assertEquals("づ", KanaModifier.transform("っ"))
        assertEquals("ば", KanaModifier.transform("は"))
        assertEquals("ぱ", KanaModifier.transform("ば"))
        assertEquals("は", KanaModifier.transform("ぱ"))
    }

    /** カタカナにも同じ変換を適用し、対象外文字は変更しない。 */
    @Test
    fun supportsKatakanaAndRejectsUnsupportedText() {
        assertEquals("ッ", KanaModifier.transform("ツ"))
        assertEquals("ヅ", KanaModifier.transform("ッ"))
        assertNull(KanaModifier.transform("A"))
    }
}
