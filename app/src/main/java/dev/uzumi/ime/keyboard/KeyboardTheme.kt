package dev.uzumi.ime.keyboard

import android.content.Context
import dev.uzumi.ime.R

/**
 * キーボードの配色。値は`res/values`と`res/values-night`の色resourceだけに置き、
 * 端末のダークモード設定に従ってAndroidが選んだ方を読み込む。
 */
data class KeyboardColors(
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyPressed: Int,
    val functionBackground: Int,
    val functionPressed: Int,
    val keyActive: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val text: Int,
    val textSecondary: Int,
    val popupBackground: Int,
    val onPopup: Int,
) {
    companion object {
        /** 現在の構成（ライト／ダーク）に合う配色をresourceから読む。 */
        fun from(context: Context): KeyboardColors = KeyboardColors(
            keyboardBackground = context.getColor(R.color.kb_background),
            keyBackground = context.getColor(R.color.kb_key),
            keyPressed = context.getColor(R.color.kb_key_pressed),
            functionBackground = context.getColor(R.color.kb_function),
            functionPressed = context.getColor(R.color.kb_function_pressed),
            keyActive = context.getColor(R.color.kb_key_active),
            accent = context.getColor(R.color.kb_accent),
            accentPressed = context.getColor(R.color.kb_accent_pressed),
            onAccent = context.getColor(R.color.kb_on_accent),
            text = context.getColor(R.color.kb_text),
            textSecondary = context.getColor(R.color.kb_text_secondary),
            popupBackground = context.getColor(R.color.kb_popup),
            onPopup = context.getColor(R.color.kb_on_popup),
        )
    }
}

/**
 * キーの形の寸法（dp）。Simejiの実測と仕様案に合わせた値を一か所に置き、キーの描画が同じ値を使うようにする。
 */
object KeyboardDimens {
    /** 12キー・数字・記号の1行の高さ。Simejiの実測66.3dpに合わせる。 */
    const val ROW_HEIGHT_DP = 66f

    /** QWERTYの1行の高さ。数字行を含む5行で、ほかの面の4行とほぼ同じ高さになる。 */
    const val QWERTY_ROW_HEIGHT_DP = 53f

    /** キーボード全体の外周の余白。 */
    const val PANEL_PADDING_DP = 2f

    /** キーの描画を枠から内側へ縮める量。隣のキーとの見た目の隙間は2倍の4dpになる。 */
    const val KEY_INSET_DP = 2f

    /** キーの角丸の半径。 */
    const val KEY_CORNER_DP = 8f
}
