package dev.uzumi.ime.keyboard

/**
 * キーボードからIMEサービスや編集層へ通知する操作イベント。
 */
sealed interface KeyboardAction {
    /**
     * 通常の文字列入力。
     *
     * @property value 入力する文字列
     */
    data class Text(val value: String) : KeyboardAction

    /**
     * 1文字（またはカーソル直前の要素）の削除。
     */
    data object Delete : KeyboardAction

    /**
     * カーソル移動。
     *
     * @property delta 移動量（負で左、正で右）
     */
    data class MoveCursor(val delta: Int) : KeyboardAction

    /**
     * 改行またはエディタのアクション実行。
     */
    data object Enter : KeyboardAction

    /**
     * 空白文字の入力。
     */
    data object Space : KeyboardAction

    /**
     * 明示的なかな漢字変換の要求。
     */
    data object Convert : KeyboardAction

    /**
     * 濁点・半濁点・小文字（拗音・促音等）の切り替え編集要求。
     */
    data object TransformKana : KeyboardAction

    /**
     * 入力中の読み（ライブ変換では対象の文節）をカタカナにする要求。12キーの入力中に「123」の位置へ出る。
     */
    data object ToKatakana : KeyboardAction
}
