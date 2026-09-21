package dev.uzumi.ime.keyboard

/**
 * キーボードの入力モードを表す列挙型。
 */
enum class KeyboardMode {
    /** 日本語12キー（フリック入力）モード */
    KANA,
    /** 英語QWERTY配列モード */
    QWERTY,
    /** 数字・記号テンキーモード */
    NUMERIC,
}
