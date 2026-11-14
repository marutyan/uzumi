package dev.uzumi.ime

import dev.uzumi.ime.conversion.NeuralModelSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** 設定トップの説明行に出す、今の値の短い文の組み立てを確かめる。文言の型はresourceと同じものを渡す。 */
class SettingsSummaryTextTest {
    private val text = SettingsSummaryText(
        on = "オン",
        off = "オフ",
        inputFormat = "ライブ変換 %1\$s",
        keyboardFormat = "%1\$s・キー%2\$d dp",
        keysFormat = "拡大表示 %1\$s・振動 %2\$s・キー音 %3\$s",
        clipboardFormat = "履歴 %1\$s",
        developerFormat = "変換エンジン：%1\$s",
        mozcName = "Mozc",
    )

    @Test
    fun switchesAreShownAsOnOrOff() {
        assertEquals("ライブ変換 オン", text.input(liveConversion = true))
        assertEquals("ライブ変換 オフ", text.input(liveConversion = false))
        assertEquals("拡大表示 オン・振動 オフ・キー音 オフ", text.keys(keyPreview = true, vibration = false, keySound = false))
        assertEquals("オフ", text.learning(enabled = false))
        assertEquals("履歴 オン", text.clipboard(historyEnabled = true))
    }

    @Test
    fun keyboardShowsThemeAndKeyHeight() {
        assertEquals("標準・キー66 dp", text.keyboard("標準", 66))
    }

    /** 変換エンジンは条件名だけを短く出し、モデルを選んでいなければMozcと出す。 */
    @Test
    fun developerShowsShortEngineName() {
        assertEquals("変換エンジン：ZS", text.developer(NeuralModelSpec.ZENZ_SMALL))
        assertEquals("変換エンジン：Mozc", text.developer(null))
    }
}
