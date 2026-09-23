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
     * 変換中の文節の区切りを一文字縮める・伸ばす要求。変換中に←→キーを長押ししたときに送る。
     *
     * @property delta 負で縮め、正で伸ばす
     * @property continued 同じ長押しの2回目以降の繰り返しか。評価用の計数では、画面に触れた1回を1操作とするため数えない
     */
    data class ResizeSegment(val delta: Int, val continued: Boolean = false) : KeyboardAction

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

    /**
     * カーソルから同じ行の行頭までを一度に消す要求。削除キーを左へドラッグして離したときに送る（Simejiと同じ操作）。
     */
    data object DeleteToLineStart : KeyboardAction
}
