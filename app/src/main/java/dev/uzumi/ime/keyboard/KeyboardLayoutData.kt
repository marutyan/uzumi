package dev.uzumi.ime.keyboard

/**
 * 12キーかな配列の各キー種別を表す列挙型。
 */
enum class KanaKeyType {
    /** あ行（あいうえお） */
    A,
    /** か行（かきくけこ） */
    KA,
    /** さ行（さしすせそ） */
    SA,
    /** た行（たちつてと） */
    TA,
    /** な行（なにぬねの） */
    NA,
    /** は行（はひふへほ） */
    HA,
    /** ま行（まみむめも） */
    MA,
    /** や行（や（ゆ）よ） */
    YA,
    /** ら行（らりるれろ） */
    RA,
    /** 句読点・記号（、。？！…） */
    PUNCT,
    /** わ行（わをんー〜） */
    WA,
}

/**
 * キーボード配列の文字定義および変換ロジックを提供するヘルパーオブジェクト。
 */
object KeyboardLayoutData {

    /**
     * 12キーかな配列のフリック方向と文字の対応テーブル。
     */
    private val KANA_MAP: Map<KanaKeyType, Map<FlickDirection, String>> = mapOf(
        KanaKeyType.A to mapOf(
            FlickDirection.CENTER to "あ",
            FlickDirection.LEFT to "い",
            FlickDirection.UP to "う",
            FlickDirection.RIGHT to "え",
            FlickDirection.DOWN to "お",
        ),
        KanaKeyType.KA to mapOf(
            FlickDirection.CENTER to "か",
            FlickDirection.LEFT to "き",
            FlickDirection.UP to "く",
            FlickDirection.RIGHT to "け",
            FlickDirection.DOWN to "こ",
        ),
        KanaKeyType.SA to mapOf(
            FlickDirection.CENTER to "さ",
            FlickDirection.LEFT to "し",
            FlickDirection.UP to "す",
            FlickDirection.RIGHT to "せ",
            FlickDirection.DOWN to "そ",
        ),
        KanaKeyType.TA to mapOf(
            FlickDirection.CENTER to "た",
            FlickDirection.LEFT to "ち",
            FlickDirection.UP to "つ",
            FlickDirection.RIGHT to "て",
            FlickDirection.DOWN to "と",
        ),
        KanaKeyType.NA to mapOf(
            FlickDirection.CENTER to "な",
            FlickDirection.LEFT to "に",
            FlickDirection.UP to "ぬ",
            FlickDirection.RIGHT to "ね",
            FlickDirection.DOWN to "の",
        ),
        KanaKeyType.HA to mapOf(
            FlickDirection.CENTER to "は",
            FlickDirection.LEFT to "ひ",
            FlickDirection.UP to "ふ",
            FlickDirection.RIGHT to "へ",
            FlickDirection.DOWN to "ほ",
        ),
        KanaKeyType.MA to mapOf(
            FlickDirection.CENTER to "ま",
            FlickDirection.LEFT to "み",
            FlickDirection.UP to "む",
            FlickDirection.RIGHT to "め",
            FlickDirection.DOWN to "も",
        ),
        KanaKeyType.YA to mapOf(
            FlickDirection.CENTER to "や",
            FlickDirection.LEFT to "（",
            FlickDirection.UP to "ゆ",
            FlickDirection.RIGHT to "）",
            FlickDirection.DOWN to "よ",
        ),
        KanaKeyType.RA to mapOf(
            FlickDirection.CENTER to "ら",
            FlickDirection.LEFT to "り",
            FlickDirection.UP to "る",
            FlickDirection.RIGHT to "れ",
            FlickDirection.DOWN to "ろ",
        ),
        KanaKeyType.PUNCT to mapOf(
            FlickDirection.CENTER to "、",
            FlickDirection.LEFT to "。",
            FlickDirection.UP to "？",
            FlickDirection.RIGHT to "！",
            FlickDirection.DOWN to "…",
        ),
        KanaKeyType.WA to mapOf(
            FlickDirection.CENTER to "わ",
            FlickDirection.LEFT to "を",
            FlickDirection.UP to "ん",
            FlickDirection.RIGHT to "ー",
            FlickDirection.DOWN to "〜",
        ),
    )

    /**
     * 指定されたキー種別とフリック方向に対応する文字を取得する。
     *
     * @param type かなキー種別
     * @param direction フリック方向
     * @return 対応する文字（未定義の場合は空文字列）
     */
    fun getKanaChar(type: KanaKeyType, direction: FlickDirection): String {
        return KANA_MAP[type]?.get(direction) ?: ""
    }

    /**
     * 指定されたキー種別の全方向の文字マップを取得する。
     *
     * @param type かなキー種別
     * @return 方向と文字のマップ
     */
    fun getKanaDirections(type: KanaKeyType): Map<FlickDirection, String> {
        return KANA_MAP[type] ?: emptyMap()
    }

    /**
     * QWERTYの文字をShift状態に応じて変換する。
     *
     * @param char 基準となるアルファベット文字
     * @param isShifted Shiftが有効（大文字）かどうか
     * @return 変換後の文字列
     */
    fun getQwertyChar(char: Char, isShifted: Boolean): String {
        return if (isShifted) {
            char.uppercaseChar().toString()
        } else {
            char.lowercaseChar().toString()
        }
    }
}
