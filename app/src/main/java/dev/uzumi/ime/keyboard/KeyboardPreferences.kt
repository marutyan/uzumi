package dev.uzumi.ime.keyboard

/**
 * 設定画面で選んだキーボードの見た目と反応。IMEが入力Viewを作るときに一度読み、KeyboardPanelとKeyViewへ渡す。
 */
data class KeyboardPreferences(
    /** 12キー・数字・記号の1行の高さ（dp）。QWERTYはこれに比例させる。 */
    val rowHeightDp: Float = KeyboardDimens.ROW_HEIGHT_DP,
    /** 押したキーの拡大表示を出すか。 */
    val keyPreview: Boolean = true,
    /** キーを押したときに振動させるか（端末の設定で振動が切られていれば振動しない）。 */
    val vibration: Boolean = true,
    /** キーを押したときにキー音を鳴らすか。 */
    val keySound: Boolean = false,
    /** 削除キーの左ドラッグで行頭まで消す操作を使うか。 */
    val deleteDrag: Boolean = true,
    /** きせかえ（配色、背景画像、キーの形、フリックの表示）。 */
    val appearance: KeyboardAppearance = KeyboardAppearance(),
    /** フリックの判定幅、長押しの時間、トグルの区切り。 */
    val input: InputTuning = InputTuning(),
) {
    /** QWERTYの1行の高さ（dp）。既定の比（53:66）を保ち、5行で他の面の4行とほぼ同じ高さにする。 */
    val qwertyRowHeightDp: Float
        get() = rowHeightDp * KeyboardDimens.QWERTY_ROW_HEIGHT_DP / KeyboardDimens.ROW_HEIGHT_DP

    /** 旧設定の高さ。保存済みの「低・中・高」をdp値へ移すために残す。 */
    enum class Height(val rowHeightDp: Float) {
        /** 変更前のUzumiと同じ高さ。 */
        LOW(52f),
        /** 中間。 */
        MEDIUM(60f),
        /** Simejiと同じ高さ（既定）。 */
        HIGH(KeyboardDimens.ROW_HEIGHT_DP),
    }

    companion object {
        /** 既存の「低」を維持し、キーの最小タップ領域を損なわない高さ調整の範囲。 */
        const val MIN_ROW_HEIGHT_DP = 52
        const val MAX_ROW_HEIGHT_DP = 96

        /** 保存値や入力値を、調整画面とキーボードの共通範囲に収める。 */
        fun normalizeRowHeightDp(value: Int): Int = value.coerceIn(MIN_ROW_HEIGHT_DP, MAX_ROW_HEIGHT_DP)
    }
}
