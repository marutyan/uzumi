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
 * かな以外のフリックキー（英字12キーと、数字Simeji型の下の段）の文字と動き。
 *
 * @property label キーに大きく出す表示
 * @property chars フリックの方向ごとの文字。中央は押して離したときの文字
 * @property toggles 同じキーを続けて押すたびに文字を順に切り替えるか（かなのトグル入力と同じ仕組み）。falseなら押すたびに中央の文字
 * @property showsGuide 方向の文字を、押していない間もキーの上と左右に小さく出すか
 */
data class FlickKey(
    val label: String,
    val chars: Map<FlickDirection, String>,
    val toggles: Boolean,
    val showsGuide: Boolean,
) {
    /** 続けて押したときに順に入る文字。フリックの並び（中央・左・上・右・下）と同じ順にする。 */
    val toggleChars: List<String>
        get() = TOGGLE_ORDER.mapNotNull { chars[it] }

    companion object {
        // 押すたびに切り替わる順。フリックの1文字目から5文字目の方向の順と同じ。
        private val TOGGLE_ORDER = listOf(
            FlickDirection.CENTER, FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN,
        )

        /** 文字列[chars]の1文字目を中央、2文字目を左、3文字目を上、4文字目を右、5文字目を下に置いた、順に切り替わるキーを作る。 */
        fun toggling(label: String, chars: String): FlickKey =
            FlickKey(label, TOGGLE_ORDER.zip(chars.map(Char::toString)).toMap(), toggles = true, showsGuide = false)
    }
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
     * 同じキーを続けてタップしたときに順に切り替わる文字（あ→い→う→え→お）。フリックの並び（中央・左・上・右・下）を使い、
     * や行だけはフリックの括弧を除いて「や→ゆ→よ」にする（Gboard・iPhoneのトグル入力と同じ）。
     */
    fun toggleChars(type: KanaKeyType): List<String> {
        val order = if (type == KanaKeyType.YA) {
            listOf(FlickDirection.CENTER, FlickDirection.UP, FlickDirection.DOWN)
        } else {
            listOf(FlickDirection.CENTER, FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN)
        }
        return order.map { getKanaChar(type, it) }.filter(String::isNotEmpty)
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

    /**
     * QWERTYの文字キーの行。1行目は数字、4行目の両脇には切替と削除が入る。Simejiの5行配列に合わせる。
     */
    val QWERTY_ROWS: List<String> = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm")

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
     * 英字12キーの中央3列のうち、上の3段の文字キー。押すたびに順に切り替わり、フリックでも選べる。
     * 表示はiPhone・Simejiと同じ大文字で、入るのは小文字とする（大文字は「a/A」キーで切り替える）。
     */
    val ENGLISH_TWELVE_ROWS: List<List<FlickKey>> = listOf(
        listOf(FlickKey.toggling("@#/&_", "@#/&_"), FlickKey.toggling("ABC", "abc"), FlickKey.toggling("DEF", "def")),
        listOf(FlickKey.toggling("GHI", "ghi"), FlickKey.toggling("JKL", "jkl"), FlickKey.toggling("MNO", "mno")),
        listOf(FlickKey.toggling("PQRS", "pqrs"), FlickKey.toggling("TUV", "tuv"), FlickKey.toggling("WXYZ", "wxyz")),
    )

    /** 英字12キーの下の段で「a/A」キーの右に並ぶ2つの記号キー。 */
    val ENGLISH_TWELVE_SYMBOL_KEYS: List<FlickKey> = listOf(FlickKey.toggling("'\"()", "'\"()"), FlickKey.toggling(".,?!", ".,?!"))

    /** 数字Simeji型の中央3列のうち、上の3段の数字。フリックは持たない。 */
    val SIMEJI_DIGIT_ROWS: List<String> = listOf("123", "456", "789")

    /**
     * 数字Simeji型の下の段の中央3キー。Pixel 10 ProのSimejiの実測に合わせ、上・左・右のフリックで記号を入れる。
     * 押すたびに切り替える動きは持たず、方向の文字を常にキーの上と左右に小さく出す。
     */
    val SIMEJI_BOTTOM_KEYS: List<FlickKey> = listOf(
        simejiKey("@", up = "~", left = "&", right = "…"),
        simejiKey("0", up = "=", left = "+", right = "%"),
        simejiKey("#", up = "^", left = ".", right = ","),
    )

    /** 数字Simeji型の下の段のキーを作る。中央の文字を表示にする。 */
    private fun simejiKey(center: String, up: String, left: String, right: String): FlickKey = FlickKey(
        label = center,
        chars = mapOf(
            FlickDirection.CENTER to center,
            FlickDirection.LEFT to left,
            FlickDirection.UP to up,
            FlickDirection.RIGHT to right,
        ),
        toggles = false,
        showsGuide = true,
    )

    /** 数字Simeji型の左の列の2段目と、右の列の2段目に置く記号（Simejiの実測と同じ）。 */
    const val SIMEJI_LEFT_SYMBOL = ":"
    const val SIMEJI_RIGHT_SYMBOL = "-"

    /**
     * 12キーの3行目右端のキー。入力中は「変換」、それ以外は「空白」とする。独立した変換キーを置かないSimejiの並びに合わせる。
     */
    fun kanaSpaceKey(composing: Boolean): KeySpec = if (composing) {
        KeySpec.Action(KeyboardAction.Convert, "変換")
    } else {
        KeySpec.Action(KeyboardAction.Space, "空白")
    }

    /**
     * ←→キー。文節を伸縮できる間（変換中）は、押して離すと文節の移動、長押しで文節の区切りの伸縮にする。
     * それ以外は従来どおり、押すとカーソルを動かし、押し続けると繰り返す。
     *
     * @param delta 負で←、正で→
     */
    fun arrowKey(delta: Int, resizable: Boolean): KeySpec = KeySpec.Action(
        action = KeyboardAction.MoveCursor(delta),
        label = if (delta < 0) "←" else "→",
        longPressAction = if (resizable) KeyboardAction.ResizeSegment(delta) else null,
    )

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
