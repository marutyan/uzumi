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

    /** 同じキーの連続タップでは行の文字を順に切り替え、や行はフリックの括弧を含めない。 */
    @Test
    fun toggleCharsFollowRowOrder() {
        assertEquals(listOf("あ", "い", "う", "え", "お"), KeyboardLayoutData.toggleChars(KanaKeyType.A))
        assertEquals(listOf("や", "ゆ", "よ"), KeyboardLayoutData.toggleChars(KanaKeyType.YA))
        assertEquals(listOf("わ", "を", "ん", "ー", "〜"), KeyboardLayoutData.toggleChars(KanaKeyType.WA))
        assertEquals(listOf("、", "。", "？", "！", "…"), KeyboardLayoutData.toggleChars(KanaKeyType.PUNCT))
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
    fun spaceAndConvertHintsExplainCursorSwipeAndTalkBackActions() {
        assertTrue(KeySpeech.spaceCursorDragHint(KeyboardAction.Space).contains("タップで空白"))
        assertTrue(KeySpeech.spaceCursorDragHint(KeyboardAction.Convert).contains("タップで変換"))
        assertEquals("カーソルを左へ移動", KeySpeech.actionDescription(KeyboardAction.MoveEditorCursor(-1)))
        assertEquals("カーソルを右へ移動", KeySpeech.actionDescription(KeyboardAction.MoveEditorCursor(1)))
    }

    /** 顔文字・絵文字の一覧操作を読み上げ、選択値を操作名へ含めない。 */
    @Test
    fun emojiPickerActionsHavePrivacySafeDescriptions() {
        assertEquals("絵文字・顔文字一覧を開く", KeySpeech.actionDescription(KeyboardAction.OpenEmojiPicker))
        assertEquals("絵文字または顔文字を入力", KeySpeech.actionDescription(KeyboardAction.InsertEmoji("🔐")))
        assertEquals("記号の一覧へ戻る", KeySpeech.actionDescription(KeyboardAction.CloseEmojiPicker))
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
        // 消す文字が無い（空の欄など）ときは案内を出さない
        assertEquals("", deleteDragHintText(DeleteDragState.ARMED, 0))
    }

    @Test
    fun keyboardHeightChoicesKeepTouchTargetsAndQwertyRatio() {
        assertEquals(KeyboardDimens.ROW_HEIGHT_DP, KeyboardPreferences().rowHeightDp)
        assertEquals(KeyboardDimens.QWERTY_ROW_HEIGHT_DP, KeyboardPreferences().qwertyRowHeightDp)
        assertEquals(listOf(52f, 60f, 66f), KeyboardPreferences.Height.entries.map { it.rowHeightDp })
        // 4行の面の高さと5行のQWERTYの高さは、どの段階でも1dp程度しか違わない
        KeyboardPreferences.Height.entries.forEach { height ->
            val prefs = KeyboardPreferences(rowHeightDp = height.rowHeightDp)
            assertTrue(Math.abs(prefs.rowHeightDp * 4 - prefs.qwertyRowHeightDp * 5) < 2f)
            assertTrue("タップ領域が48dp未満: $height", prefs.rowHeightDp >= 48f)
        }
        assertEquals(52, KeyboardPreferences.normalizeRowHeightDp(1))
        assertEquals(66, KeyboardPreferences.normalizeRowHeightDp(66))
        assertEquals(96, KeyboardPreferences.normalizeRowHeightDp(120))
        listOf(52, 66, 84, 96).forEach { heightDp ->
            val prefs = KeyboardPreferences(rowHeightDp = heightDp.toFloat())
            assertTrue(Math.abs(prefs.rowHeightDp * 4 - prefs.qwertyRowHeightDp * 5) < 2f)
        }
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

    /** ←→は変換中だけ長押しで文節を伸縮し、それ以外は従来どおり押し続けるとカーソルを連続で動かす。 */
    @Test
    fun arrowKeysResizeSegmentOnLongPressOnlyWhileConverting() {
        assertEquals(
            KeySpec.Action(KeyboardAction.MoveCursor(-1), "←", longPressAction = KeyboardAction.ResizeSegment(-1)),
            KeyboardLayoutData.arrowKey(-1, resizable = true),
        )
        assertEquals(
            KeySpec.Action(KeyboardAction.MoveCursor(1), "→", longPressAction = KeyboardAction.ResizeSegment(1)),
            KeyboardLayoutData.arrowKey(1, resizable = true),
        )
        assertEquals(KeySpec.Action(KeyboardAction.MoveCursor(1), "→"), KeyboardLayoutData.arrowKey(1, resizable = false))
        assertEquals("文節を縮める", KeySpeech.actionDescription(KeyboardAction.ResizeSegment(-1)))
        assertEquals("文節を伸ばす", KeySpeech.actionDescription(KeyboardAction.ResizeSegment(1)))
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
                "kb_text" to "kb_bar",
                "kb_text_secondary" to "kb_bar",
                "kb_accent" to "kb_bar",
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

    /** 保存値は配列名のカンマ区切りで、未知の名前と重複を除き、空なら既定の選択にする。未保存はnullで移行に任せる。 */
    @Test
    fun keymapsEncodeDecodeAndNormalize() {
        val order = listOf(Keymap.EN_TWELVE, Keymap.JA_TWELVE, Keymap.NUMBER_CALCULATOR)
        assertEquals("EN_TWELVE,JA_TWELVE,NUMBER_CALCULATOR", KeymapRules.encode(order))
        assertEquals(order, KeymapRules.decode(KeymapRules.encode(order)))
        assertEquals(listOf(Keymap.JA_QWERTY), KeymapRules.decode("JA_QWERTY,REMOVED,JA_QWERTY"))
        assertEquals(KeymapRules.DEFAULT_ORDER, KeymapRules.decode(""))
        assertEquals(KeymapRules.DEFAULT_ORDER, KeymapRules.normalize(emptyList()))
        assertNull(KeymapRules.decode(null))
        assertEquals(listOf(Keymap.JA_TWELVE, Keymap.EN_QWERTY, Keymap.NUMBER_SIMEJI), KeymapRules.DEFAULT_ORDER)
    }

    /** 旧設定からの移行。かなの12キーを先頭に、ローマ字かな入力と英字の扱いに応じてQWERTYを足し、最後にSimeji型の数字。 */
    @Test
    fun keymapsMigrateFromLegacyRomajiSettings() {
        // 旧設定の既定（ローマ字かな入力オフ、英字を含める）は新しい既定の選択と同じ
        assertEquals(KeymapRules.DEFAULT_ORDER, KeymapRules.migrate(romajiInput = false, includesEnglish = true))
        assertEquals(KeymapRules.DEFAULT_ORDER, KeymapRules.migrate(romajiInput = false, includesEnglish = false))
        assertEquals(
            listOf(Keymap.JA_TWELVE, Keymap.JA_QWERTY, Keymap.EN_QWERTY, Keymap.NUMBER_SIMEJI),
            KeymapRules.migrate(romajiInput = true, includesEnglish = true),
        )
        assertEquals(
            listOf(Keymap.JA_TWELVE, Keymap.JA_QWERTY, Keymap.NUMBER_SIMEJI),
            KeymapRules.migrate(romajiInput = true, includesEnglish = false),
        )
    }

    /** 左下のキーは選んだ順の次へ移り、最後の次は最初。キーには次の配列の短い名前を出し、移る先の種類を読み上げる。 */
    @Test
    fun keymapSwitchKeyCyclesInSelectedOrder() {
        val order = listOf(Keymap.JA_TWELVE, Keymap.JA_QWERTY, Keymap.EN_QWERTY, Keymap.EN_TWELVE, Keymap.NUMBER_SIMEJI)
        assertEquals(Keymap.JA_QWERTY, KeymapRules.next(order, Keymap.JA_TWELVE, password = false))
        assertEquals(Keymap.EN_QWERTY, KeymapRules.next(order, Keymap.JA_QWERTY, password = false))
        assertEquals(Keymap.JA_TWELVE, KeymapRules.next(order, Keymap.NUMBER_SIMEJI, password = false))
        // 選んでいない配列（「123」で移った数字の配列など）からは最初へ戻る
        assertEquals(Keymap.JA_TWELVE, KeymapRules.next(order, Keymap.NUMBER_CALCULATOR, password = false))
        // 1つだけ選んだ場合は同じ配列のまま
        assertEquals(Keymap.EN_TWELVE, KeymapRules.next(listOf(Keymap.EN_TWELVE), Keymap.EN_TWELVE, password = false))

        assertEquals(
            KeySpec.ModeSwitch("ローマ字", KeyboardMode.KANA, emphasized = true),
            KeymapRules.switchKey(order, Keymap.JA_TWELVE, password = false),
        )
        assertEquals(
            KeySpec.ModeSwitch("abc", KeyboardMode.QWERTY, emphasized = true),
            KeymapRules.switchKey(order, Keymap.EN_QWERTY, password = false),
        )
        assertEquals(
            KeySpec.ModeSwitch("123", KeyboardMode.NUMERIC, emphasized = true),
            KeymapRules.switchKey(order, Keymap.EN_TWELVE, password = false),
        )
        assertEquals(
            KeySpec.ModeSwitch("あ", KeyboardMode.KANA, emphasized = true),
            KeymapRules.switchKey(order, Keymap.NUMBER_SIMEJI, password = false),
        )
        assertEquals("かな入力へ切り替え", KeySpeech.modeSwitchDescription(Keymap.JA_QWERTY.category))
        assertEquals("英字入力へ切り替え", KeySpeech.modeSwitchDescription(Keymap.EN_TWELVE.category))
        assertEquals("数字入力へ切り替え", KeySpeech.modeSwitchDescription(Keymap.NUMBER_SIMEJI.category))
        assertEquals(listOf("あ", "ローマ字", "ABC", "abc", "123", "123"), Keymap.entries.map { it.shortLabel })
    }

    /** パスワード欄では日本語の配列を飛ばし、英字の配列が無ければ英字QWERTYを足す。 */
    @Test
    fun keymapCycleSkipsJapaneseInPasswordFields() {
        val order = listOf(Keymap.JA_TWELVE, Keymap.EN_TWELVE, Keymap.JA_QWERTY, Keymap.NUMBER_CALCULATOR)
        assertEquals(listOf(Keymap.EN_TWELVE, Keymap.NUMBER_CALCULATOR), KeymapRules.cycleOrder(order, password = true))
        assertEquals(Keymap.NUMBER_CALCULATOR, KeymapRules.next(order, Keymap.EN_TWELVE, password = true))
        assertEquals(Keymap.EN_TWELVE, KeymapRules.next(order, Keymap.NUMBER_CALCULATOR, password = true))
        // 英字の配列を選んでいなければ、文字を打てるよう英字QWERTYを先頭に足す
        assertEquals(
            listOf(Keymap.EN_QWERTY, Keymap.NUMBER_SIMEJI),
            KeymapRules.cycleOrder(listOf(Keymap.JA_TWELVE, Keymap.NUMBER_SIMEJI), password = true),
        )
        assertEquals(listOf(Keymap.EN_QWERTY), KeymapRules.cycleOrder(listOf(Keymap.JA_TWELVE), password = true))
        // かなを求められても英字にする
        assertEquals(Keymap.EN_TWELVE, KeymapRules.forCategory(order, KeyboardMode.KANA, password = true))
    }

    /** 「123」などの種類を指定するキーは、選んだ順の中で最初のその種類の配列へ移り、無ければ決まった配列を使う。 */
    @Test
    fun keymapForCategoryUsesFirstSelectedOfThatKind() {
        val order = listOf(Keymap.JA_QWERTY, Keymap.EN_TWELVE, Keymap.NUMBER_CALCULATOR, Keymap.NUMBER_SIMEJI)
        assertEquals(Keymap.NUMBER_CALCULATOR, KeymapRules.forCategory(order, KeyboardMode.NUMERIC, password = false))
        assertEquals(Keymap.EN_TWELVE, KeymapRules.forCategory(order, KeyboardMode.QWERTY, password = false))
        assertEquals(Keymap.JA_QWERTY, KeymapRules.forCategory(order, KeyboardMode.KANA, password = false))
        val kanaOnly = listOf(Keymap.JA_TWELVE)
        assertEquals(Keymap.NUMBER_SIMEJI, KeymapRules.forCategory(kanaOnly, KeyboardMode.NUMERIC, password = false))
        assertEquals(Keymap.EN_QWERTY, KeymapRules.forCategory(kanaOnly, KeyboardMode.QWERTY, password = false))
        assertEquals(Keymap.JA_TWELVE, KeymapRules.forCategory(listOf(Keymap.EN_QWERTY), KeyboardMode.KANA, password = false))
    }

    /** 入力欄を開いたときは、数字の欄で最初の数字の配列、パスワード欄で英字、それ以外は前回の通常の欄の配列を出す。 */
    @Test
    fun initialKeymapFollowsFieldAndLastNormalKeymap() {
        val order = listOf(Keymap.JA_TWELVE, Keymap.EN_TWELVE, Keymap.NUMBER_CALCULATOR)
        assertEquals(Keymap.NUMBER_CALCULATOR, KeymapRules.initial(order, numeric = true, password = false, last = Keymap.EN_TWELVE))
        assertEquals(Keymap.NUMBER_SIMEJI, KeymapRules.initial(listOf(Keymap.JA_TWELVE), numeric = true, password = false, last = null))
        assertEquals(Keymap.EN_TWELVE, KeymapRules.initial(order, numeric = false, password = true, last = Keymap.JA_TWELVE))
        assertEquals(Keymap.EN_TWELVE, KeymapRules.initial(order, numeric = false, password = false, last = Keymap.EN_TWELVE))
        // 前回の配列を外した場合と初回は、数字以外の最初の配列
        assertEquals(Keymap.JA_TWELVE, KeymapRules.initial(order, numeric = false, password = false, last = Keymap.JA_QWERTY))
        assertEquals(Keymap.EN_QWERTY, KeymapRules.initial(listOf(Keymap.NUMBER_SIMEJI, Keymap.EN_QWERTY), false, false, null))
        assertEquals(Keymap.NUMBER_SIMEJI, KeymapRules.initial(listOf(Keymap.NUMBER_SIMEJI), false, false, null))
        // 覚えるのは通常の欄で使った数字以外の配列だけ
        assertTrue(KeymapRules.remembers(Keymap.EN_TWELVE, numeric = false, password = false))
        assertFalse(KeymapRules.remembers(Keymap.NUMBER_SIMEJI, numeric = false, password = false))
        assertFalse(KeymapRules.remembers(Keymap.EN_QWERTY, numeric = false, password = true))
        assertFalse(KeymapRules.remembers(Keymap.JA_TWELVE, numeric = true, password = false))
    }

    /**
     * 評価の記録中は、キーマップの設定によらず導入前の面と切り替えキーにする。自動測定の道具（run_automated.py の MODE_SWITCH）が
     * 前提にする面ごとの読み上げ名と、電卓型の数字面を守る。
     */
    @Test
    fun recordingRulesKeepPreKeymapSwitchesForAutomatedMeasurement() {
        fun description(keymap: Keymap, password: Boolean = false) =
            KeySpeech.modeSwitchDescription(RecordingKeymapRules.switchKey(keymap, password).targetMode)
        // かなの面は「あA」で英字へ、英字の面は「あ」でかなへ、数字の面は「あ/A」でかなへ
        assertEquals(KeySpec.ModeSwitch("あA", KeyboardMode.QWERTY), RecordingKeymapRules.switchKey(Keymap.JA_TWELVE, false))
        assertEquals(KeySpec.ModeSwitch("あ", KeyboardMode.KANA), RecordingKeymapRules.switchKey(Keymap.EN_QWERTY, false))
        assertEquals(KeySpec.ModeSwitch("あ/A", KeyboardMode.KANA), RecordingKeymapRules.switchKey(Keymap.NUMBER_CALCULATOR, false))
        assertEquals("英字入力へ切り替え", description(Keymap.JA_TWELVE))
        assertEquals("かな入力へ切り替え", description(Keymap.EN_QWERTY))
        assertEquals("かな入力へ切り替え", description(Keymap.NUMBER_CALCULATOR))
        // パスワード欄の数字の面は「ABC」で英字へ移り、かなへ移るキーは隠す
        assertEquals(KeySpec.ModeSwitch("ABC", KeyboardMode.QWERTY), RecordingKeymapRules.switchKey(Keymap.NUMBER_CALCULATOR, true))
        assertTrue(RecordingKeymapRules.hidesInPassword(RecordingKeymapRules.switchKey(Keymap.EN_QWERTY, true)))
        assertFalse(RecordingKeymapRules.hidesInPassword(RecordingKeymapRules.switchKey(Keymap.NUMBER_CALCULATOR, true)))
        // 種類を指定するキーは、選択によらず導入前の面（かなの12キー、英字QWERTY、電卓型の数字）へ移る
        assertEquals(Keymap.JA_TWELVE, RecordingKeymapRules.keymapFor(KeyboardMode.KANA, password = false))
        assertEquals(Keymap.EN_QWERTY, RecordingKeymapRules.keymapFor(KeyboardMode.KANA, password = true))
        assertEquals(Keymap.EN_QWERTY, RecordingKeymapRules.keymapFor(KeyboardMode.QWERTY, password = false))
        assertEquals(Keymap.NUMBER_CALCULATOR, RecordingKeymapRules.keymapFor(KeyboardMode.NUMERIC, password = false))
        // 記録を始めたときに導入前に無い配列を出していれば、同じ種類の導入前の面へ移す
        assertEquals(Keymap.EN_QWERTY, RecordingKeymapRules.coerce(Keymap.EN_TWELVE, password = false))
        assertEquals(Keymap.NUMBER_CALCULATOR, RecordingKeymapRules.coerce(Keymap.NUMBER_SIMEJI, password = false))
        assertEquals(Keymap.JA_TWELVE, RecordingKeymapRules.coerce(Keymap.JA_QWERTY, password = false))
        assertEquals(Keymap.NUMBER_CALCULATOR, RecordingKeymapRules.coerce(Keymap.NUMBER_CALCULATOR, password = false))
        // 入力欄を開いたときの面も導入前と同じ
        assertEquals(Keymap.NUMBER_CALCULATOR, RecordingKeymapRules.initial(numeric = true, password = false, last = Keymap.JA_TWELVE))
        assertEquals(Keymap.EN_QWERTY, RecordingKeymapRules.initial(numeric = false, password = true, last = Keymap.JA_TWELVE))
        assertEquals(Keymap.JA_TWELVE, RecordingKeymapRules.initial(numeric = false, password = false, last = Keymap.EN_TWELVE))
        assertEquals(Keymap.EN_QWERTY, RecordingKeymapRules.initial(numeric = false, password = false, last = Keymap.EN_QWERTY))
        assertEquals("space", RecordingKeymapRules.QWERTY_SPACE_LABEL)
    }

    /** 記録を終えたら、道具が切り替えを送らずに済むよう、選択によらずかなの12キーの面へ戻す（パスワード欄では英字）。 */
    @Test
    fun recordingEndReturnsToKanaTwelveKeys() {
        assertEquals(Keymap.JA_TWELVE, RecordingKeymapRules.afterRecording(password = false))
        assertEquals(Keymap.EN_QWERTY, RecordingKeymapRules.afterRecording(password = true))
    }

    /** 英字12キーは、中央が1文字目、左が2文字目、上が3文字目、右が4文字目（5文字目は下）で、押すたびにその順で替わる。 */
    @Test
    fun englishTwelveKeysAssignFlickAndToggleOrder() {
        val keys = KeyboardLayoutData.ENGLISH_TWELVE_ROWS.flatten() + KeyboardLayoutData.ENGLISH_TWELVE_SYMBOL_KEYS
        assertEquals(
            listOf("@#/&_", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "'\"()", ".,?!"),
            keys.map { it.label },
        )
        assertEquals(
            listOf("@#/&_", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "'\"()", ".,?!"),
            keys.map { it.toggleChars.joinToString("") },
        )
        val pqrs = keys.single { it.label == "PQRS" }
        assertEquals(
            mapOf(
                FlickDirection.CENTER to "p",
                FlickDirection.LEFT to "q",
                FlickDirection.UP to "r",
                FlickDirection.RIGHT to "s",
            ),
            pqrs.chars,
        )
        assertEquals("_", keys.first().chars[FlickDirection.DOWN])
        assertTrue(keys.all { it.toggles && !it.showsGuide })
        // a〜zがちょうど1回ずつ入る
        assertEquals(('a'..'z').toList(), keys.flatMap { it.toggleChars }.filter { it.single() in 'a'..'z' }.map { it.single() }.sorted())
    }

    /** 数字Simeji型は中央3列に1〜9と「@ 0 #」を置き、フリックは下の段だけ（上・左・右）で、方向の文字を常に出す。 */
    @Test
    fun simejiNumberKeysMatchMeasuredLayout() {
        assertEquals(listOf("123", "456", "789"), KeyboardLayoutData.SIMEJI_DIGIT_ROWS)
        assertEquals(":", KeyboardLayoutData.SIMEJI_LEFT_SYMBOL)
        assertEquals("-", KeyboardLayoutData.SIMEJI_RIGHT_SYMBOL)
        val bottom = KeyboardLayoutData.SIMEJI_BOTTOM_KEYS
        assertEquals(listOf("@", "0", "#"), bottom.map { it.label })
        fun flicks(key: FlickKey) = listOf(FlickDirection.UP, FlickDirection.LEFT, FlickDirection.RIGHT).map { key.chars[it] }
        assertEquals(listOf("~", "&", "…"), flicks(bottom[0]))
        assertEquals(listOf("=", "+", "%"), flicks(bottom[1]))
        assertEquals(listOf("^", ".", ","), flicks(bottom[2]))
        assertTrue(bottom.all { !it.toggles && it.showsGuide && FlickDirection.DOWN !in it.chars })
        assertEquals(listOf("@", "0", "#"), bottom.map { it.chars[FlickDirection.CENTER] })
    }

    /** 新しい配列の記号にも読み上げ名があり、フリックキーの補足説明は方向と文字を並べる。 */
    @Test
    fun newKeymapSymbolsHaveSpokenNames() {
        val symbols = (KeyboardLayoutData.ENGLISH_TWELVE_ROWS.flatten() + KeyboardLayoutData.ENGLISH_TWELVE_SYMBOL_KEYS +
            KeyboardLayoutData.SIMEJI_BOTTOM_KEYS).flatMap { it.chars.values }
            .filter { it.single() !in 'a'..'z' && it.single() !in '0'..'9' } +
            listOf(KeyboardLayoutData.SIMEJI_LEFT_SYMBOL, KeyboardLayoutData.SIMEJI_RIGHT_SYMBOL)
        symbols.forEach { symbol -> assertTrue("読み上げ名がない: $symbol", KeySpeech.hasSymbolName(symbol)) }
        assertEquals(
            "フリック、左 アンド、上 チルダ、右 三点リーダー。操作メニューからも入力できます",
            KeySpeech.flickHint(KeyboardLayoutData.SIMEJI_BOTTOM_KEYS.first().chars),
        )
        assertEquals("元に戻す", KeySpeech.actionDescription(KeyboardAction.Undo))
    }

    @Test
    fun autoCapsShiftsOneLetterAndYieldsToManualShift() {
        // 文頭で自動のShiftが入り、1文字打つと解除される
        val state = QwertyShiftState()
        assertTrue(state.applyAutoCaps(shift = true, characters = false))
        assertTrue(state.isShiftActive)
        assertTrue(state.onTextInput())
        assertFalse(state.isShiftActive)
        // 文の途中では自動のShiftを外すが、手動のShiftは残す
        state.toggle(nowMillis = 0L)
        assertFalse(state.applyAutoCaps(shift = false, characters = false))
        assertTrue(state.isShiftActive)
        state.onTextInput()
        assertFalse(state.isShiftActive)
    }

    @Test
    fun autoCapsCharactersKeepsShiftAndCapsLockWins() {
        // すべて大文字の欄では、1文字打っても自動のShiftを続ける
        val state = QwertyShiftState()
        state.applyAutoCaps(shift = true, characters = true)
        assertFalse(state.onTextInput())
        assertTrue(state.isShiftActive)
        // Caps Lockは自動大文字化の有無に関係なく続く
        val locked = QwertyShiftState()
        locked.toggle(nowMillis = 1_000L)
        locked.toggle(nowMillis = 1_100L)
        assertTrue(locked.isCapsLock)
        locked.applyAutoCaps(shift = false, characters = false)
        locked.onTextInput()
        assertTrue(locked.isCapsLock)
    }

    @Test
    fun shiftKeyCancelsAutoShiftWithoutTurningShiftOn() {
        // 自動のShiftだけが入っている間にShiftを押すと、小文字に戻す
        val state = QwertyShiftState()
        state.applyAutoCaps(shift = true, characters = false)
        state.toggle(nowMillis = 0L)
        assertFalse(state.isShiftActive)
        assertFalse(state.isCapsLock)
        // 自動のShiftを取り消した押下から素早く続けて押すと、Caps Lockになり「ab」が「AB」になる
        val doubled = QwertyShiftState()
        doubled.applyAutoCaps(shift = true, characters = false)
        doubled.toggle(nowMillis = 1_000L)
        doubled.toggle(nowMillis = 1_200L)
        assertTrue(doubled.isCapsLock)
        doubled.onTextInput()
        doubled.applyAutoCaps(shift = false, characters = false)
        doubled.onTextInput()
        assertTrue(doubled.isCapsLock)
        // 間を空けて押した場合は、通常のShiftになる
        val slow = QwertyShiftState()
        slow.applyAutoCaps(shift = true, characters = false)
        slow.toggle(nowMillis = 1_000L)
        slow.toggle(nowMillis = 2_000L)
        assertFalse(slow.isCapsLock)
        assertTrue(slow.isShiftActive)
        // 自動大文字化を求めない欄では、従来どおり押すたびにShiftを切り替える
        val manual = QwertyShiftState()
        manual.toggle(nowMillis = 0L)
        assertTrue(manual.isShiftActive)
        manual.toggle(nowMillis = 1_000L)
        assertFalse(manual.isShiftActive)
    }

    /**
     * フリックの吹き出しの文字は、離したときに入る文字（配列定義の同じ方向の文字）と一致する。
     * 12キーは中央があ段、左がい段、上がう段、右がえ段、下がお段（例: な・に・ぬ・ね・の）。
     */
    @Test
    fun flickBubbleTextMatchesInputForEveryDirection() {
        val na = mapOf(
            FlickDirection.LEFT to "に",
            FlickDirection.UP to "ぬ",
            FlickDirection.RIGHT to "ね",
            FlickDirection.DOWN to "の",
        )
        na.forEach { (direction, expected) ->
            val preview = flickPreview(KanaKeyType.NA, direction, FlickDisplayMode.BUBBLE)
            assertEquals(listOf(FlickBubbleLabel(direction, expected)), preview.bubbles)
            assertNull(preview.centerText)
        }
        assertEquals(FlickPreview("な", emptyList()), flickPreview(KanaKeyType.NA, FlickDirection.CENTER, FlickDisplayMode.BUBBLE))

        val directions = listOf(FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN)
        KanaKeyType.entries.forEach { type ->
            directions.forEach { direction ->
                // KeyViewは離したときにgetKanaChar(type, 方向)の文字を入力する。吹き出しの文字はそれと同じでなければならない。
                val input = KeyboardLayoutData.getKanaChar(type, direction)
                assertEquals(input, flickPreview(type, direction, FlickDisplayMode.BUBBLE).bubbles.single().text)
                val cross = flickPreview(type, direction, FlickDisplayMode.CROSS)
                assertEquals(input, cross.bubbles.single { it.direction == direction }.text)
                assertNull(cross.centerText)
                assertEquals(input, flickPreview(type, direction, FlickDisplayMode.IN_KEY).centerText)
                assertTrue(flickPreview(type, direction, FlickDisplayMode.IN_KEY).bubbles.isEmpty())
                assertEquals(FlickPreview(null, emptyList()), flickPreview(type, direction, FlickDisplayMode.NONE))
            }
            // 十字のガイドは四方向の吹き出しを、それぞれの方向の文字で出す
            val cross = flickPreview(type, FlickDirection.CENTER, FlickDisplayMode.CROSS)
            assertEquals(directions.toSet(), cross.bubbles.map { it.direction }.toSet())
            cross.bubbles.forEach { assertEquals(KeyboardLayoutData.getKanaChar(type, it.direction), it.text) }
        }
    }

    /** 吹き出しは押している方向の側に出し、先をキーの縁から内側へ重ねる。大きさの設定は寸法に比例する。 */
    @Test
    fun flickBubbleGeometryPlacesBubbleOnDirectionSide() {
        // 1dp=1pxのキー（左上100,200、幅80、高さ66）
        val up = flickBubbleGeometry(FlickDirection.UP, 100f, 200f, 80f, 66f, density = 1f, scale = 1f)
        assertEquals(140f, up.centerX)
        assertEquals(66f, up.width)
        assertEquals(54f, up.height)
        assertEquals(0f, up.rotation)
        // 先の端（中心+高さ/2）はキーの上端から18dp内側
        assertEquals(218f, up.centerY + up.height / 2f)
        val down = flickBubbleGeometry(FlickDirection.DOWN, 100f, 200f, 80f, 66f, density = 1f, scale = 1f)
        assertEquals(180f, down.rotation)
        assertTrue(down.centerY > 266f - 18f)
        val left = flickBubbleGeometry(FlickDirection.LEFT, 100f, 200f, 80f, 66f, density = 1f, scale = 1f)
        // 左右の吹き出しは実測68×47dpを90度回して描くため、上向きの形では幅47・高さ68になる
        assertEquals(47f, left.width)
        assertEquals(68f, left.height)
        assertEquals(270f, left.rotation)
        assertEquals(233f, left.centerY)
        assertTrue(left.centerX < 100f)
        val right = flickBubbleGeometry(FlickDirection.RIGHT, 100f, 200f, 80f, 66f, density = 1f, scale = 1f)
        assertEquals(90f, right.rotation)
        assertTrue(right.centerX > 180f)
        val large = flickBubbleGeometry(FlickDirection.UP, 100f, 200f, 80f, 66f, density = 2f, scale = 1.4f)
        assertEquals(66f * 2.8f, large.width, 0.001f)
    }

    /** きせかえの設定は保存値で往復し、範囲外の数値は範囲へ収め、知らない値は既定値にする。既定値は試作のとおり。 */
    @Test
    fun appearanceStorageRoundTripAndDefaults() {
        val defaults = KeyboardAppearance()
        assertEquals(ThemePreset.STANDARD, defaults.preset)
        assertEquals(ThemeMode.SYSTEM, defaults.mode)
        assertEquals(85, defaults.backgroundBrightness)
        assertEquals(0, defaults.keyOpacity)
        assertEquals(KeyFont.GOTHIC, defaults.keyFont)
        assertEquals(FlickDisplayMode.BUBBLE, defaults.flickMode)
        assertEquals(FlickBubbleShape.PENTAGON, defaults.flickShape)
        assertFalse(defaults.hasBackgroundImage)
        assertEquals(defaults, KeyboardAppearance.fromStorage(emptyMap()))

        KeyFont.entries.forEach { font ->
            val stored = KeyboardAppearance(keyFont = font).toStorage()
            assertEquals(font.name, stored[KeyboardAppearance.KEY_FONT])
            assertEquals(font, KeyboardAppearance.fromStorage(stored).keyFont)
        }
        assertEquals(
            KeyFont.GOTHIC,
            KeyboardAppearance.fromStorage(mapOf(KeyboardAppearance.KEY_FONT to "FUTURE")).keyFont,
        )

        val custom = KeyboardAppearance(
            preset = ThemePreset.SAKURA,
            mode = ThemeMode.DARK,
            textColor = 0xFF123456.toInt(),
            flickColor = 0xFFABCDEF.toInt(),
            backgroundBrightness = 120,
            backgroundBlur = 4,
            keyShape = KeyShape.PILL,
            keyOpacity = 40,
            keyBorder = true,
            keyFont = KeyFont.MINCHO,
            keyTextScale = 110,
            flickMode = FlickDisplayMode.CROSS,
            flickShape = FlickBubbleShape.HEART,
            flickSize = 130,
            flickOpacity = 60,
            flickGuide = true,
        ).toCustom()
        assertEquals(ThemePreset.CUSTOM, custom.preset)
        assertEquals(ThemePreset.SAKURA.light!!.roles(), custom.customLight)
        assertEquals(ThemePreset.SAKURA.dark!!.roles(), custom.customDark)
        assertEquals(custom, KeyboardAppearance.fromStorage(custom.toStorage()))
        // 画像の有無は保存値に含めず、読み込み時に渡す
        assertTrue(KeyboardAppearance.fromStorage(custom.toStorage(), backgroundStamp = 5L).hasBackgroundImage)

        val clamped = KeyboardAppearance.fromStorage(
            mapOf(
                KeyboardAppearance.KEY_PRESET to "FUTURE",
                KeyboardAppearance.KEY_BACKGROUND_BRIGHTNESS to "999",
                KeyboardAppearance.KEY_FLICK_SIZE to "10",
                KeyboardAppearance.KEY_TEXT_COLOR to "red",
                KeyboardAppearance.KEY_CUSTOM_LIGHT to "#FFFFFF",
                KeyboardAppearance.KEY_BORDER to "yes",
            ),
        )
        assertEquals(ThemePreset.STANDARD, clamped.preset)
        assertEquals(130, clamped.backgroundBrightness)
        assertEquals(70, clamped.flickSize)
        assertNull(clamped.textColor)
        assertNull(clamped.customLight)
        assertFalse(clamped.keyBorder)
    }

    /**
     * プリセットの配色から導く色は、強調色・吹き出しの上の文字が読める明るさになる。標準のプリセット値はresの色と同じで、
     * 押下中は明るいテーマで暗く、暗いテーマで明るくする。
     */
    @Test
    fun derivedThemeColorsKeepReadableContrast() {
        ThemePreset.entries.filter { it != ThemePreset.CUSTOM }.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val palette = preset.palette(dark)!!
                val colors = deriveKeyboardColors(palette, danger = 0xFFB3261E.toInt(), onDanger = 0xFFFFFFFF.toInt())
                assertTrue("$preset $dark", ThemeColorMath.contrast(colors.accent, colors.onAccent) >= 4.5)
                assertTrue("$preset $dark", ThemeColorMath.contrast(colors.flick, colors.onFlick) >= LARGE_TEXT_CONTRAST)
                val keyLuminance = ThemeColorMath.luminance(colors.keyBackground)
                val pressedLuminance = ThemeColorMath.luminance(colors.keyPressed)
                if (dark) assertTrue(pressedLuminance > keyLuminance) else assertTrue(pressedLuminance < keyLuminance)
            }
        }
        // Simejiと同じ灰色の吹き出しには白い文字を使う
        assertEquals(ThemeColorMath.LIGHT_ON_COLOR, ThemeColorMath.onColor(ThemeColorMath.parseHex("#8A8A8A")!!, LARGE_TEXT_CONTRAST))
        // 明るい黄色の上では濃い文字にする
        assertEquals(ThemeColorMath.DARK_ON_COLOR, ThemeColorMath.onColor(ThemeColorMath.parseHex("#FDD835")!!, LARGE_TEXT_CONTRAST))

        val resColors = File("src/main/res/values/colors.xml").readText()
        val standard = ThemePreset.STANDARD.light!!
        listOf(
            "kb_background" to standard.background, "kb_key" to standard.key, "kb_function" to standard.function,
            "kb_key_active" to standard.active, "kb_accent" to standard.accent, "kb_text" to standard.text,
            "kb_bar" to standard.bar, "kb_divider" to standard.divider, "kb_flick" to standard.flick,
        ).forEach { (name, color) ->
            assertTrue(name, resColors.contains("<color name=\"$name\">#FF${ThemeColorMath.toHex(color).removePrefix("#")}</color>"))
        }
    }

    /** カスタムでは試作と同じ規則で候補バー・区切り線・有効なキーを導き、文字とフリックは個別の指定を優先する。 */
    @Test
    fun customPaletteDerivesBarDividerAndActive() {
        val light = ThemePreset.STANDARD.light!!.roles()
        val palette = customPalette(light)
        assertEquals(ThemeColorMath.shiftLightness(light.background, 5f), palette.bar)
        assertEquals(ThemeColorMath.shiftLightness(light.background, -10f), palette.divider)
        assertEquals(ThemeColorMath.shiftLightness(light.accent, 38f), palette.active)
        val dark = ThemePreset.SUMI.dark!!.roles()
        val darkPalette = customPalette(dark)
        assertEquals(ThemeColorMath.shiftLightness(dark.background, 3f), darkPalette.bar)
        assertEquals(ThemeColorMath.shiftLightness(dark.accent, -25f), darkPalette.active)

        val appearance = KeyboardAppearance(preset = ThemePreset.CUSTOM, customLight = light.with(ColorRole.ACCENT, 0xFF00AA00.toInt()))
        assertEquals(0xFF00AA00.toInt(), themePalette(appearance, dark = false)!!.accent)
        // ダークのカスタムが無ければ標準のダークを使う
        assertEquals(ThemePreset.STANDARD.dark!!.background, themePalette(appearance, dark = true)!!.background)
        // 標準はresourceの色を使うためnull
        assertNull(themePalette(KeyboardAppearance(), dark = false))
        val colors = deriveKeyboardColors(palette, 0, 0).withOverrides(textColor = 0xFFE53935.toInt(), flickColor = null)
        assertEquals(0xFFE53935.toInt(), colors.text)
        assertEquals(palette.flick, colors.flick)
    }

    /** 色の計算: #RRGGBBの読み書き、HSLの往復、コントラスト比。 */
    @Test
    fun themeColorMathConvertsColors() {
        assertEquals(0xFF345995.toInt(), ThemeColorMath.parseHex("#345995"))
        assertNull(ThemeColorMath.parseHex("345995"))
        assertNull(ThemeColorMath.parseHex("#34599G"))
        assertEquals("#345995", ThemeColorMath.toHex(0xFF345995.toInt()))
        COLOR_CHIP_PALETTE.forEach { color ->
            val (h, s, l) = ThemeColorMath.toHsl(color)
            assertTrue(ThemeColorMath.isClose(color, ThemeColorMath.fromHsl(h, s, l)))
        }
        assertEquals(21.0, ThemeColorMath.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(0x80FFFFFF.toInt(), ThemeColorMath.withAlpha(0xFFFFFFFF.toInt(), 128f / 255f))
        assertEquals(15, COLOR_CHIP_PALETTE.size)
    }

    /** 背景画像はキーボードの縦横比で中央を切り抜き、ぼかしは平均を保って端を同じ色で延ばす。 */
    @Test
    fun backgroundCropAndBlur() {
        // 横長の写真（4000×3000）をキーボード（1080×400）へ: 上下を削る
        assertEquals(CropRect(0, 759, 4000, 1481), centerCropRect(4000, 3000, 1080, 400))
        // 縦長のキーボード比より横長の写真: 左右を削る
        assertEquals(CropRect(500, 0, 1000, 1000), centerCropRect(2000, 1000, 1, 1))
        assertEquals(0.27f, coverScale(4000, 3000, 1080, 400), 0.001f)
        assertEquals(2f, coverScale(500, 100, 1000, 100), 0.001f)

        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // 一様な画像はぼかしても変わらない
        assertTrue(boxBlur(IntArray(9) { white }, 3, 3, 1).all { it == white })
        // 一画素だけ白い画像は、周りへ広がる
        val dot = IntArray(25) { black }.also { it[12] = white }
        val blurred = boxBlur(dot, 5, 5, 1)
        assertNotEquals(black, blurred[6])
        assertEquals(black, blurred[0])
        assertTrue(boxBlur(dot, 5, 5, 0).contentEquals(dot))
    }
}
