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

    /** 12キーの中央タップ。フリック入力と区別して同じキーの行内トグルを可能にする。 */
    data class KanaTap(val type: KanaKeyType) : KeyboardAction

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
     * 空白・変換キーの横ドラッグによる実カーソル移動。MoveCursorと異なり、ライブ変換中も文節焦点ではなく入力位置を動かす。
     *
     * @property delta 移動する書記素数（負で左、正で右）
     * @property continued 同じ指のドラッグ中に続けて送る移動か。評価では最初の移動だけを一操作として数える
     */
    data class MoveEditorCursor(val delta: Int, val continued: Boolean = false) : KeyboardAction

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

    /** 記号面から絵文字・顔文字の一覧を開く操作。 */
    data object OpenEmojiPicker : KeyboardAction

    /** 絵文字一覧から選んだ値を未確定文字の確定後にそのまま入力する操作。
     *
     * @property value 入力する絵文字または顔文字
     */
    data class InsertEmoji(val value: String) : KeyboardAction

    /** 絵文字・顔文字一覧から記号面へ戻る操作。 */
    data object CloseEmojiPicker : KeyboardAction
}
