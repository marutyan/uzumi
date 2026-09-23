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

    // QWERTYの英字キーを長押ししたときに入力する数字・記号。一般的な英語キーボードの配置に倣う。
    private val QWERTY_LONG_PRESS: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&",
        'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";",
        'n' to "!", 'm' to "?",
    )

    /**
     * QWERTYの英字キーの長押しで入力する文字を返す。Shift状態によらず同じ文字を返し、対象外ならnullを返す。
     */
    fun getQwertyLongPress(char: Char): String? = QWERTY_LONG_PRESS[char.lowercaseChar()]

    /**
     * 12キーの3行目左端のキー。入力中は読みをカタカナにする「カナ」、それ以外は数字面への切替とする（Simejiと同じ）。
     */
    fun kanaNumberKey(composing: Boolean): KeySpec = if (composing) {
        KeySpec.Action(KeyboardAction.ToKatakana, "カナ")
    } else {
        KeySpec.ModeSwitch("123", KeyboardMode.NUMERIC)
    }

    /**
     * 12キーの3行目右端のキー。入力中は「変換」、それ以外は「空白」とする。独立した変換キーを置かないSimejiの並びに合わせる。
     */
    fun kanaSpaceKey(composing: Boolean): KeySpec = if (composing) {
        KeySpec.Action(KeyboardAction.Convert, "変換")
    } else {
        KeySpec.Action(KeyboardAction.Space, "空白")
    }

    /**
     * 記号面の一ページ分の配列。
     *
     * @property label 切替キーに表示する短い名前
     * @property spokenName TalkBackで読み上げるページ名
     * @property rows 上から順の各行の記号。1・2行目は10個、3行目は切替・削除キーを挟むため8個とする
     */
    data class SymbolPage(
        val label: String,
        val spokenName: String,
        val rows: List<List<String>>,
    )

    /**
     * 記号面のページ一覧。日本語の句読点・括弧、全角の記号、半角の記号の順に並べる。
     */
    val SYMBOL_PAGES: List<SymbolPage> = listOf(
        SymbolPage(
            label = "全角",
            spokenName = "句読点と括弧",
            rows = listOf(
                listOf("、", "。", "・", "：", "；", "？", "！", "ー", "〜", "…"),
                listOf("「", "」", "『", "』", "（", "）", "【", "】", "［", "］"),
                listOf("＠", "＃", "％", "＆", "＊", "＋", "＝", "／"),
            ),
        ),
        SymbolPage(
            label = "記号",
            spokenName = "記号と矢印",
            rows = listOf(
                listOf("￥", "＄", "＿", "｜", "＜", "＞", "《", "》", "〈", "〉"),
                listOf("※", "〒", "♪", "☆", "★", "○", "●", "◎", "△", "▲"),
                listOf("→", "←", "↑", "↓", "×", "÷", "±", "〃"),
            ),
        ),
        SymbolPage(
            label = "半角",
            spokenName = "半角記号",
            rows = listOf(
                listOf("!", "?", "@", "#", "$", "%", "&", "*", "(", ")"),
                listOf("-", "_", "=", "+", "/", "\\", "|", "~", "'", "\""),
                listOf(":", ";", "<", ">", "[", "]", "{", "}"),
            ),
        ),
    )
}
