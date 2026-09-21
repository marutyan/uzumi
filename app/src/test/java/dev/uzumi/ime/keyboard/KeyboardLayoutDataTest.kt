package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * キーボード配列データ [KeyboardLayoutData] の単体テスト。
 */
class KeyboardLayoutDataTest {

    @Test
    fun testKanaKeyA() {
        assertEquals("あ", KeyboardLayoutData.getKanaChar(KanaKeyType.A, FlickDirection.CENTER))
        assertEquals("い", KeyboardLayoutData.getKanaChar(KanaKeyType.A, FlickDirection.LEFT))
        assertEquals("う", KeyboardLayoutData.getKanaChar(KanaKeyType.A, FlickDirection.UP))
        assertEquals("え", KeyboardLayoutData.getKanaChar(KanaKeyType.A, FlickDirection.RIGHT))
        assertEquals("お", KeyboardLayoutData.getKanaChar(KanaKeyType.A, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyKa() {
        assertEquals("か", KeyboardLayoutData.getKanaChar(KanaKeyType.KA, FlickDirection.CENTER))
        assertEquals("き", KeyboardLayoutData.getKanaChar(KanaKeyType.KA, FlickDirection.LEFT))
        assertEquals("く", KeyboardLayoutData.getKanaChar(KanaKeyType.KA, FlickDirection.UP))
        assertEquals("け", KeyboardLayoutData.getKanaChar(KanaKeyType.KA, FlickDirection.RIGHT))
        assertEquals("こ", KeyboardLayoutData.getKanaChar(KanaKeyType.KA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeySa() {
        assertEquals("さ", KeyboardLayoutData.getKanaChar(KanaKeyType.SA, FlickDirection.CENTER))
        assertEquals("し", KeyboardLayoutData.getKanaChar(KanaKeyType.SA, FlickDirection.LEFT))
        assertEquals("す", KeyboardLayoutData.getKanaChar(KanaKeyType.SA, FlickDirection.UP))
        assertEquals("せ", KeyboardLayoutData.getKanaChar(KanaKeyType.SA, FlickDirection.RIGHT))
        assertEquals("そ", KeyboardLayoutData.getKanaChar(KanaKeyType.SA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyTa() {
        assertEquals("た", KeyboardLayoutData.getKanaChar(KanaKeyType.TA, FlickDirection.CENTER))
        assertEquals("ち", KeyboardLayoutData.getKanaChar(KanaKeyType.TA, FlickDirection.LEFT))
        assertEquals("つ", KeyboardLayoutData.getKanaChar(KanaKeyType.TA, FlickDirection.UP))
        assertEquals("て", KeyboardLayoutData.getKanaChar(KanaKeyType.TA, FlickDirection.RIGHT))
        assertEquals("と", KeyboardLayoutData.getKanaChar(KanaKeyType.TA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyNa() {
        assertEquals("な", KeyboardLayoutData.getKanaChar(KanaKeyType.NA, FlickDirection.CENTER))
        assertEquals("に", KeyboardLayoutData.getKanaChar(KanaKeyType.NA, FlickDirection.LEFT))
        assertEquals("ぬ", KeyboardLayoutData.getKanaChar(KanaKeyType.NA, FlickDirection.UP))
        assertEquals("ね", KeyboardLayoutData.getKanaChar(KanaKeyType.NA, FlickDirection.RIGHT))
        assertEquals("の", KeyboardLayoutData.getKanaChar(KanaKeyType.NA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyHa() {
        assertEquals("は", KeyboardLayoutData.getKanaChar(KanaKeyType.HA, FlickDirection.CENTER))
        assertEquals("ひ", KeyboardLayoutData.getKanaChar(KanaKeyType.HA, FlickDirection.LEFT))
        assertEquals("ふ", KeyboardLayoutData.getKanaChar(KanaKeyType.HA, FlickDirection.UP))
        assertEquals("へ", KeyboardLayoutData.getKanaChar(KanaKeyType.HA, FlickDirection.RIGHT))
        assertEquals("ほ", KeyboardLayoutData.getKanaChar(KanaKeyType.HA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyMa() {
        assertEquals("ま", KeyboardLayoutData.getKanaChar(KanaKeyType.MA, FlickDirection.CENTER))
        assertEquals("み", KeyboardLayoutData.getKanaChar(KanaKeyType.MA, FlickDirection.LEFT))
        assertEquals("む", KeyboardLayoutData.getKanaChar(KanaKeyType.MA, FlickDirection.UP))
        assertEquals("め", KeyboardLayoutData.getKanaChar(KanaKeyType.MA, FlickDirection.RIGHT))
        assertEquals("も", KeyboardLayoutData.getKanaChar(KanaKeyType.MA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyYa() {
        assertEquals("や", KeyboardLayoutData.getKanaChar(KanaKeyType.YA, FlickDirection.CENTER))
        assertEquals("（", KeyboardLayoutData.getKanaChar(KanaKeyType.YA, FlickDirection.LEFT))
        assertEquals("ゆ", KeyboardLayoutData.getKanaChar(KanaKeyType.YA, FlickDirection.UP))
        assertEquals("）", KeyboardLayoutData.getKanaChar(KanaKeyType.YA, FlickDirection.RIGHT))
        assertEquals("よ", KeyboardLayoutData.getKanaChar(KanaKeyType.YA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyRa() {
        assertEquals("ら", KeyboardLayoutData.getKanaChar(KanaKeyType.RA, FlickDirection.CENTER))
        assertEquals("り", KeyboardLayoutData.getKanaChar(KanaKeyType.RA, FlickDirection.LEFT))
        assertEquals("る", KeyboardLayoutData.getKanaChar(KanaKeyType.RA, FlickDirection.UP))
        assertEquals("れ", KeyboardLayoutData.getKanaChar(KanaKeyType.RA, FlickDirection.RIGHT))
        assertEquals("ろ", KeyboardLayoutData.getKanaChar(KanaKeyType.RA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyWa() {
        assertEquals("わ", KeyboardLayoutData.getKanaChar(KanaKeyType.WA, FlickDirection.CENTER))
        assertEquals("を", KeyboardLayoutData.getKanaChar(KanaKeyType.WA, FlickDirection.LEFT))
        assertEquals("ん", KeyboardLayoutData.getKanaChar(KanaKeyType.WA, FlickDirection.UP))
        assertEquals("ー", KeyboardLayoutData.getKanaChar(KanaKeyType.WA, FlickDirection.RIGHT))
        assertEquals("〜", KeyboardLayoutData.getKanaChar(KanaKeyType.WA, FlickDirection.DOWN))
    }

    @Test
    fun testKanaKeyPunct() {
        assertEquals("、", KeyboardLayoutData.getKanaChar(KanaKeyType.PUNCT, FlickDirection.CENTER))
        assertEquals("。", KeyboardLayoutData.getKanaChar(KanaKeyType.PUNCT, FlickDirection.LEFT))
        assertEquals("？", KeyboardLayoutData.getKanaChar(KanaKeyType.PUNCT, FlickDirection.UP))
        assertEquals("！", KeyboardLayoutData.getKanaChar(KanaKeyType.PUNCT, FlickDirection.RIGHT))
        assertEquals("…", KeyboardLayoutData.getKanaChar(KanaKeyType.PUNCT, FlickDirection.DOWN))
    }

    @Test
    fun testQwertyCharConversion() {
        // Shift OFF (小文字)
        assertEquals("a", KeyboardLayoutData.getQwertyChar('a', isShifted = false))
        assertEquals("z", KeyboardLayoutData.getQwertyChar('z', isShifted = false))

        // Shift ON (大文字)
        assertEquals("A", KeyboardLayoutData.getQwertyChar('a', isShifted = true))
        assertEquals("Z", KeyboardLayoutData.getQwertyChar('z', isShifted = true))
    }
}
