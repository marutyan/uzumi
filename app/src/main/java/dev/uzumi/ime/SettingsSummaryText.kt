package dev.uzumi.ime

import dev.uzumi.ime.conversion.NeuralModelSpec
import java.util.Locale

/**
 * 設定トップの各行の説明行に出す、今の値の短い文を組み立てる。文言の型（「ライブ変換 %1$s」など）はresourceから受け取り、
 * どの値をどの順で並べるかだけをここで決める。画面の部品に依存しないため、単体テストで確かめられる。
 */
class SettingsSummaryText(
    private val on: String,
    private val off: String,
    private val inputFormat: String,
    private val keyboardFormat: String,
    private val keysFormat: String,
    private val clipboardFormat: String,
    private val developerFormat: String,
    private val mozcName: String,
) {
    /** 「入力」の行。ライブ変換のオン・オフ。 */
    fun input(liveConversion: Boolean): String = inputFormat.format(Locale.ROOT, onOff(liveConversion))

    /** 「キーボード」の行。選んでいるテーマの名前と、キー1段の高さ（dp）。 */
    fun keyboard(themeName: String, rowHeightDp: Int): String = keyboardFormat.format(Locale.ROOT, themeName, rowHeightDp)

    /** 「キーの操作と音」の行。拡大表示・振動・キー音のオン・オフ。 */
    fun keys(keyPreview: Boolean, vibration: Boolean, keySound: Boolean): String =
        keysFormat.format(Locale.ROOT, onOff(keyPreview), onOff(vibration), onOff(keySound))

    /** 「変換の学習」の行。学習のオン・オフだけを出す。 */
    fun learning(enabled: Boolean): String = onOff(enabled)

    /** 「クリップボード」の行。履歴のオン・オフ。 */
    fun clipboard(historyEnabled: Boolean): String = clipboardFormat.format(Locale.ROOT, onOff(historyEnabled))

    /** 「開発用」の行。選んでいる変換エンジンの短い名前（モデルの条件名、Mozcだけならその名前）。 */
    fun developer(selected: NeuralModelSpec?): String = developerFormat.format(Locale.ROOT, selected?.key ?: mozcName)

    private fun onOff(enabled: Boolean): String = if (enabled) on else off
}
