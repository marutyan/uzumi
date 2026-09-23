package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun qwertyLongPressCoversEveryLetterWithDigitsOnTopRow() {
        val topRow = "qwertyuiop"
        topRow.forEachIndexed { index, char ->
            assertEquals(((index + 1) % 10).toString(), KeyboardLayoutData.getQwertyLongPress(char))
        }
        ('a'..'z').forEach { char ->
            assertNotNull("長押し文字がない: $char", KeyboardLayoutData.getQwertyLongPress(char))
        }
        // Shift中の大文字でも同じ長押し文字を返す
        assertEquals("@", KeyboardLayoutData.getQwertyLongPress('A'))
        assertNull(KeyboardLayoutData.getQwertyLongPress(','))
        // 長押し文字は重複せず、利用者が一つの文字を一か所で覚えられる
        val values = ('a'..'z').map { KeyboardLayoutData.getQwertyLongPress(it) }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun symbolPagesHaveFixedRowSizesAndNoDuplicates() {
        val pages = KeyboardLayoutData.SYMBOL_PAGES
        assertEquals(3, pages.size)
        pages.forEach { page ->
            assertEquals(listOf(10, 10, 8), page.rows.map { it.size })
            page.rows.flatten().forEach { symbol ->
                assertEquals("一文字ではない記号: $symbol", 1, symbol.length)
            }
        }
        val all = pages.flatMap { it.rows.flatten() }
        assertEquals(all.size, all.toSet().size)
        assertEquals(pages.size, pages.map { it.label }.toSet().size)
    }

    @Test
    fun symbolPagesContainJapanesePunctuationAndBrackets() {
        val all = KeyboardLayoutData.SYMBOL_PAGES.flatMap { it.rows.flatten() }.toSet()
        listOf("、", "。", "・", "「", "」", "『", "』", "（", "）", "【", "】", "？", "！", "ー", "〜", "…")
            .forEach { assertTrue("記号面にない: $it", it in all) }
        listOf("@", "#", "/", "-", "_", ":", "(", ")", "?", "!")
            .forEach { assertTrue("記号面にない: $it", it in all) }
        // 最初のページは日本語の句読点から始める
        assertEquals("、", KeyboardLayoutData.SYMBOL_PAGES.first().rows.first().first())
    }

    @Test
    fun everySymbolOnKeysHasSpokenName() {
        val symbols = KeyboardLayoutData.SYMBOL_PAGES.flatMap { it.rows.flatten() } +
            ('a'..'z').mapNotNull { KeyboardLayoutData.getQwertyLongPress(it) }.filter { it.single() !in '0'..'9' } +
            KanaKeyType.entries.flatMap { KeyboardLayoutData.getKanaDirections(it).values }
                .filter { it.single() !in 'ぁ'..'ゖ' } +
            listOf(",", ".")
        symbols.forEach { symbol ->
            assertTrue("読み上げ名がない: $symbol", KeySpeech.hasSymbolName(symbol))
            assertNotEquals(symbol, KeySpeech.spokenText(symbol))
        }
    }

    @Test
    fun spokenTextKeepsKanaAndMarksCapitalLetters() {
        assertEquals("あ", KeySpeech.spokenText("あ"))
        assertEquals("a", KeySpeech.spokenText("a"))
        assertEquals("大文字 A", KeySpeech.spokenText("A"))
        assertEquals("1", KeySpeech.spokenText("1"))
        assertEquals("読点", KeySpeech.spokenText("、"))
        assertEquals("かぎかっこ開き", KeySpeech.spokenText("「"))
    }

    @Test
    fun kanaHintListsEveryFlickDirection() {
        assertEquals(
            "フリック、左 い、上 う、右 え、下 お。操作メニューからも入力できます",
            KeySpeech.kanaHint(KanaKeyType.A),
        )
        assertEquals(
            "フリック、左 句点、上 全角疑問符、右 全角感嘆符、下 三点リーダー。操作メニューからも入力できます",
            KeySpeech.kanaHint(KanaKeyType.PUNCT),
        )
        assertEquals("いを入力、左フリック", KeySpeech.flickActionLabel("い", FlickDirection.LEFT))
        assertEquals("読点を入力", KeySpeech.flickActionLabel("、", FlickDirection.CENTER))
    }

    @Test
    fun modeDescriptionsNameEveryMode() {
        val descriptions = KeyboardMode.entries.map(KeySpeech::modeSwitchDescription)
        assertEquals(KeyboardMode.entries.size, descriptions.toSet().size)
        val names = KeyboardMode.entries.map(KeySpeech::modeName)
        assertEquals(KeyboardMode.entries.size, names.toSet().size)
        assertEquals("記号入力へ切り替え", KeySpeech.modeSwitchDescription(KeyboardMode.SYMBOL))
    }
}
