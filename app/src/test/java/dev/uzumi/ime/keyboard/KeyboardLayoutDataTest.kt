package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun qwertyRowsHaveDigitRowAndEveryLetterOnce() {
        val rows = KeyboardLayoutData.QWERTY_ROWS
        assertEquals(listOf(10, 10, 10, 7), rows.map { it.length })
        assertEquals("1234567890", rows.first())
        val letters = rows.drop(1).joinToString("").filter { it.isLetter() }
        assertEquals(('a'..'z').toList(), letters.toList().sorted())
        // 文字キーに置く記号は読み上げ名を持つ
        rows.joinToString("").filterNot { it.isLetterOrDigit() }.forEach { symbol ->
            assertTrue("読み上げ名がない: $symbol", KeySpeech.hasSymbolName(symbol.toString()))
        }
    }

    @Test
    fun keyPreviewSitsAboveKeyAndStaysInsideInputView() {
        // 中央のキー：キーの中心の真上、隙間8を空けて置く
        assertEquals(PreviewPlacement(left = 218f, top = 132f), placeKeyPreview(200f, 200f, 100f, 1000f, 64f, 60f, 8f))
        // 右端のキー：入力Viewの右端からはみ出さない
        assertEquals(936f, placeKeyPreview(950f, 200f, 50f, 1000f, 64f, 60f, 8f).left)
        // 左端のキー：左端からはみ出さない
        assertEquals(0f, placeKeyPreview(0f, 200f, 40f, 1000f, 64f, 60f, 8f).left)
        // 最上段のキー：上端より上へ出さず、候補バーへ重ねる
        assertEquals(0f, placeKeyPreview(200f, 30f, 100f, 1000f, 64f, 60f, 8f).top)
    }

    @Test
    fun deleteDragFollowsSimejiReleaseRules() {
        fun tracker() = DeleteDragTracker(slopPx = 10f, thresholdPx = 68f, escapePx = 60f)

        // 動かさずに離す：通常の削除
        assertEquals(DeleteRelease.TAP, tracker().release())
        // 閾値の手前で離す：1文字
        tracker().apply {
            move(40f, 0f)
            assertEquals(DeleteDragState.DRAGGING, state)
            assertEquals(DeleteRelease.SINGLE, release())
        }
        // 閾値を超えて離す：行頭まで
        tracker().apply {
            move(40f, 0f)
            move(80f, 5f)
            assertEquals(DeleteDragState.ARMED, state)
            assertEquals(DeleteRelease.LINE, release())
        }
        // 閾値を超えた後に元の位置へ戻す：取り消し
        tracker().apply {
            move(80f, 0f)
            move(2f, 0f)
            assertEquals(DeleteDragState.CANCELED, state)
            assertEquals(DeleteRelease.CANCEL, release())
        }
        // キーボードの上へ外す：取り消し
        tracker().apply {
            move(80f, 0f)
            move(80f, 70f)
            assertEquals(DeleteRelease.CANCEL, release())
        }
        // 左へ動かさずに上下へ揺れただけ：ドラッグにしない
        tracker().apply {
            move(-30f, 20f)
            assertFalse(isDragging)
            assertEquals(DeleteRelease.TAP, release())
        }
        assertEquals("× 行頭まで 12文字", deleteDragHintText(DeleteDragState.ARMED, 12))
        assertEquals("× 行頭まで", deleteDragHintText(DeleteDragState.ARMED, null))
    }

    @Test
    fun kanaKeysSwitchRoleOnlyWhileComposing() {
        // 入力前はSimejiと同じく数字面への切替と空白
        assertEquals(KeySpec.ModeSwitch("123", KeyboardMode.NUMERIC), KeyboardLayoutData.kanaNumberKey(composing = false))
        assertEquals(KeySpec.Action(KeyboardAction.Space, "空白"), KeyboardLayoutData.kanaSpaceKey(composing = false))
        // 入力中は同じ位置が「カナ」と「変換」になる
        assertEquals(KeySpec.Action(KeyboardAction.ToKatakana, "カナ"), KeyboardLayoutData.kanaNumberKey(composing = true))
        assertEquals(KeySpec.Action(KeyboardAction.Convert, "変換"), KeyboardLayoutData.kanaSpaceKey(composing = true))
    }

    @Test
    fun lightAndDarkPalettesDefineSameColorsWithReadableText() {
        val light = readKeyboardColors("src/main/res/values/colors.xml")
        val dark = readKeyboardColors("src/main/res/values-night/colors.xml")
        // ダーク側に欠けた色があると、その色だけライトの値で描かれてしまう
        assertEquals(light.keys, dark.keys)
        listOf(light, dark).forEach { palette ->
            listOf(
                "kb_text" to "kb_key",
                "kb_text" to "kb_function",
                "kb_text" to "kb_key_pressed",
                "kb_text" to "kb_key_active",
                "kb_text_secondary" to "kb_key",
                "kb_on_accent" to "kb_accent",
                "kb_on_popup" to "kb_popup",
                "kb_on_danger" to "kb_danger",
            ).forEach { (fg, bg) ->
                val ratio = contrastRatio(palette.getValue(fg), palette.getValue(bg))
                assertTrue("$fg / $bg のコントラスト比が不足: $ratio", ratio >= 4.5)
            }
        }
    }

    /** 色resourceのXMLから、キーボード用の色名とRGB値を読む。テスト専用の簡易な読み取り。 */
    private fun readKeyboardColors(path: String): Map<String, Int> {
        val pattern = Regex("""<color name="(kb_[a-z_]+)">#FF([0-9A-Fa-f]{6})</color>""")
        return pattern.findAll(File(path).readText()).associate { it.groupValues[1] to it.groupValues[2].toInt(16) }
    }

    /** WCAG 2の式で二色のコントラスト比を求める。 */
    private fun contrastRatio(a: Int, b: Int): Double {
        fun luminance(rgb: Int): Double {
            val channels = listOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF).map { it / 255.0 }
                .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
        }
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }
}
