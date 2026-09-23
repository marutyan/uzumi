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
) {
    /** QWERTYの1行の高さ（dp）。既定の比（53:66）を保ち、5行で他の面の4行とほぼ同じ高さにする。 */
    val qwertyRowHeightDp: Float
        get() = rowHeightDp * KeyboardDimens.QWERTY_ROW_HEIGHT_DP / KeyboardDimens.ROW_HEIGHT_DP

    /** キーボードの高さの段階。設定画面の「低・中・高」に当たる。 */
    enum class Height(val rowHeightDp: Float) {
        /** 変更前のUzumiと同じ高さ。 */
        LOW(52f),
        /** 中間。 */
        MEDIUM(60f),
        /** Simejiと同じ高さ（既定）。 */
        HIGH(KeyboardDimens.ROW_HEIGHT_DP),
    }
}
