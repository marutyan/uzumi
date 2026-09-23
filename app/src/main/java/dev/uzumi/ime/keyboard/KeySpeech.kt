package dev.uzumi.ime.keyboard

/**
 * TalkBackで読み上げるキーの説明文を、Androidの描画処理から切り離して組み立てる。
 * 記号は読み上げエンジンが無音や別の読みにすることがあるため、日本語の名前へ置き換える。
 */
object KeySpeech {
    // 読み上げで聞き分けにくい記号の日本語名。キー配列に載せた記号をすべて含める。
    private val SYMBOL_NAMES: Map<String, String> = mapOf(
        "、" to "読点", "。" to "句点", "・" to "中黒", "：" to "全角コロン", "；" to "全角セミコロン",
        "？" to "全角疑問符", "！" to "全角感嘆符", "ー" to "長音", "〜" to "波ダッシュ", "…" to "三点リーダー",
        "「" to "かぎかっこ開き", "」" to "かぎかっこ閉じ", "『" to "二重かぎかっこ開き", "』" to "二重かぎかっこ閉じ",
        "（" to "全角丸かっこ開き", "）" to "全角丸かっこ閉じ", "【" to "すみつきかっこ開き", "】" to "すみつきかっこ閉じ",
        "［" to "全角角かっこ開き", "］" to "全角角かっこ閉じ",
        "＠" to "全角アットマーク", "＃" to "全角シャープ", "％" to "全角パーセント", "＆" to "全角アンド",
        "＊" to "全角アスタリスク", "＋" to "全角プラス", "＝" to "全角イコール", "／" to "全角スラッシュ",
        "￥" to "円記号", "＄" to "全角ドル", "＿" to "全角アンダーバー", "｜" to "全角縦線",
        "＜" to "全角小なり", "＞" to "全角大なり", "《" to "二重山かっこ開き", "》" to "二重山かっこ閉じ",
        "〈" to "山かっこ開き", "〉" to "山かっこ閉じ",
        "※" to "米印", "〒" to "郵便記号", "♪" to "音符", "☆" to "白星", "★" to "黒星",
        "○" to "白丸", "●" to "黒丸", "◎" to "二重丸", "△" to "白三角", "▲" to "黒三角",
        "→" to "右矢印", "←" to "左矢印", "↑" to "上矢印", "↓" to "下矢印",
        "×" to "かける", "÷" to "わる", "±" to "プラスマイナス", "〃" to "同じく記号",
        "!" to "感嘆符", "?" to "疑問符", "@" to "アットマーク", "#" to "シャープ", "$" to "ドル",
        "%" to "パーセント", "&" to "アンド", "*" to "アスタリスク", "(" to "丸かっこ開き", ")" to "丸かっこ閉じ",
        "-" to "ハイフン", "_" to "アンダーバー", "=" to "イコール", "+" to "プラス", "/" to "スラッシュ",
        "\\" to "バックスラッシュ", "|" to "縦線", "~" to "チルダ", "'" to "アポストロフィ", "\"" to "二重引用符",
        ":" to "コロン", ";" to "セミコロン", "<" to "小なり", ">" to "大なり", "[" to "角かっこ開き",
        "]" to "角かっこ閉じ", "{" to "波かっこ開き", "}" to "波かっこ閉じ", "," to "カンマ", "." to "ピリオド",
    )

    /**
     * 入力される文字列の読み上げ名を返す。記号は日本語名、英大文字は「大文字」を付け、それ以外はそのまま返す。
     */
    fun spokenText(text: String): String {
        SYMBOL_NAMES[text]?.let { return it }
        if (text.length == 1 && text[0] in 'A'..'Z') return "大文字 $text"
        return text
    }

    /**
     * 記号の日本語名を持つかどうかを返す。配列の記号に読み上げ名の漏れがないことをテストで確かめるために使う。
     */
    fun hasSymbolName(text: String): Boolean = text in SYMBOL_NAMES

    /**
     * モード切替キーの読み上げ名を返す。表示ラベルではなく切替先を読み上げる。
     */
    fun modeSwitchDescription(target: KeyboardMode): String = when (target) {
        KeyboardMode.KANA -> "かな入力へ切り替え"
        KeyboardMode.QWERTY -> "英字入力へ切り替え"
        KeyboardMode.NUMERIC -> "数字入力へ切り替え"
        KeyboardMode.SYMBOL -> "記号入力へ切り替え"
    }

    /**
     * キーボード面が表示されたときに読み上げる面の名前を返す。
     */
    fun modeName(mode: KeyboardMode): String = when (mode) {
        KeyboardMode.KANA -> "かなキーボード"
        KeyboardMode.QWERTY -> "英字キーボード"
        KeyboardMode.NUMERIC -> "数字キーボード"
        KeyboardMode.SYMBOL -> "記号キーボード"
    }

    /**
     * かなキーの補足説明を返す。フリック先の文字を方向とともに並べ、操作メニューでも選べることを伝える。
     */
    fun kanaHint(type: KanaKeyType): String {
        val map = KeyboardLayoutData.getKanaDirections(type)
        val parts = listOf(
            FlickDirection.LEFT to "左",
            FlickDirection.UP to "上",
            FlickDirection.RIGHT to "右",
            FlickDirection.DOWN to "下",
        ).mapNotNull { (direction, name) ->
            map[direction]?.takeIf { it.isNotEmpty() }?.let { "$name ${spokenText(it)}" }
        }
        return "フリック、" + parts.joinToString("、") + "。操作メニューからも入力できます"
    }

    /**
     * フリック方向ごとの操作メニュー名を返す。例：「い」を入力、左フリック。
     */
    fun flickActionLabel(char: String, direction: FlickDirection): String {
        val directionName = when (direction) {
            FlickDirection.CENTER -> return "${spokenText(char)}を入力"
            FlickDirection.LEFT -> "左"
            FlickDirection.UP -> "上"
            FlickDirection.RIGHT -> "右"
            FlickDirection.DOWN -> "下"
        }
        return "${spokenText(char)}を入力、${directionName}フリック"
    }

    /**
     * 長押しで入力できる文字の補足説明を返す。
     */
    fun longPressHint(text: String): String = "長押しで ${spokenText(text)}"
}
