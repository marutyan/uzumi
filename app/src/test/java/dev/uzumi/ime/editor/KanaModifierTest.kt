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

    /** 英字12キーの「a/A」は英字一文字の大文字と小文字を入れ替え、英字以外と複数の文字は対象外にする。 */
    @Test
    fun toggleLetterCaseSwapsOnlySingleLatinLetters() {
        assertEquals("A", KanaModifier.toggleLetterCase("a"))
        assertEquals("z", KanaModifier.toggleLetterCase("Z"))
        assertNull(KanaModifier.toggleLetterCase("あ"))
        assertNull(KanaModifier.toggleLetterCase("1"))
        assertNull(KanaModifier.toggleLetterCase("ab"))
        assertNull(KanaModifier.toggleLetterCase("é"))
    }

    /** 小書き・濁点・半濁点を清音の大きいかなへ揃え、対象外の文字はそのまま残す。 */
    @Test
    fun stripModifiersUnifiesKanaVariants() {
        assertEquals("りようかいしました", KanaModifier.stripModifiers("りょうかいしました"))
        assertEquals("かつこう", KanaModifier.stripModifiers("がっこう"))
        assertEquals("ハツヒ", KanaModifier.stripModifiers("パッピ"))
        assertEquals("漢字A", KanaModifier.stripModifiers("漢字A"))
    }
}
