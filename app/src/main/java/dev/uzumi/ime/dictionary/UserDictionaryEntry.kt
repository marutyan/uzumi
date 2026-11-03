package dev.uzumi.ime.dictionary

import java.text.Normalizer

/**
 * ユーザー辞書の一項目。読みと表記の組で一意になり、管理画面・保存形式・IME候補の参照で共通に使う。
 * 定型文の本文は空白・改行・Unicodeの結合形も入力どおり保持する。
 */
data class UserDictionaryEntry(
    val reading: String,
    val surface: String,
    val category: UserDictionaryCategory = UserDictionaryCategory.NOUN,
)

/**
 * 品詞に相当する最小の分類。labelは保存・入出力のTSVへ書く名前で、Mozcのユーザー辞書の品詞名と同じ表記にしている。
 */
enum class UserDictionaryCategory(val label: String) {
    NOUN("名詞"),
    PROPER_NOUN("固有名詞"),
    PERSON_NAME("人名"),
    PLACE_NAME("地名"),
    ORGANIZATION("組織"),
    SHORTCUT("短縮よみ"),
    TEMPLATE("定型文"),
    SYMBOL("記号"),
    EMOTICON("顔文字"),
    ;

    companion object {
        /** TSVの分類列から分類を引く。未知の名前は推測で別分類へ寄せず、nullを返して失敗行にする。 */
        fun fromLabel(label: String): UserDictionaryCategory? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 登録・編集・import・保存で起こり得る失敗の理由。画面側で文言へ変換し、核の処理はAndroidの文字列資源に依存しない。
 */
enum class UserDictionaryError {
    EMPTY_READING,
    READING_TOO_LONG,
    READING_NOT_HIRAGANA,
    EMPTY_SURFACE,
    SURFACE_TOO_LONG,
    TEMPLATE_TOO_LONG,
    SURFACE_HAS_CONTROL_CHARACTER,
    WRONG_COLUMN_COUNT,
    UNKNOWN_CATEGORY,
    DUPLICATE,
    NOT_FOUND,
    TOO_MANY_ENTRIES,
    STORAGE_FAILED,
}

/**
 * 項目の正規化と入力値の検証規則を一か所に集める。画面入力、import、保存ファイルの読込みが同じ規則を通る。
 */
object UserDictionaryRules {
    /** 読みの最大長（Unicodeコードポイント数）。候補検索のindexとIME側の読みの長さを抑える。 */
    const val MAX_READING_LENGTH = 64

    /** 表記の最大長（Unicodeコードポイント数）。候補行へ出す一語として十分な長さに制限する。 */
    const val MAX_SURFACE_LENGTH = 128

    /** 定型文の最大長（Unicodeコードポイント数）。複数行の本文を保存しつつ、極端な登録で候補表示を圧迫しない。 */
    const val MAX_TEMPLATE_LENGTH = 8192

    /** 登録できる総数。IMEのプロセスが全項目をメモリ上のindexへ保持するため上限を置く。 */
    const val MAX_ENTRY_COUNT = 50_000

    /** 読みはNFCへ揃え、通常語は前後を整える。定型文本文は空白やUnicodeの結合形を含め入力どおり残す。 */
    fun normalize(entry: UserDictionaryEntry): UserDictionaryEntry = entry.copy(
        reading = normalizeReading(entry.reading),
        surface = if (entry.category == UserDictionaryCategory.TEMPLATE) {
            entry.surface
        } else {
            Normalizer.normalize(entry.surface.trim(), Normalizer.Form.NFC)
        },
    )

    /** 検索・参照に使う読みを登録時と同じ形へ揃える。 */
    fun normalizeReading(reading: String): String {
        val trimmed = reading.trim()
        return if (Normalizer.isNormalized(trimmed, Normalizer.Form.NFC)) {
            trimmed
        } else {
            Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        }
    }

    /** 正規化済みの項目を検証し、問題がなければnullを返す。 */
    fun validate(entry: UserDictionaryEntry): UserDictionaryError? {
        val reading = entry.reading
        val surface = entry.surface
        return when {
            reading.isEmpty() -> UserDictionaryError.EMPTY_READING
            reading.codePointCount(0, reading.length) > MAX_READING_LENGTH -> UserDictionaryError.READING_TOO_LONG
            !reading.all(::isReadingCharacter) -> UserDictionaryError.READING_NOT_HIRAGANA
            surface.isEmpty() -> UserDictionaryError.EMPTY_SURFACE
            entry.category == UserDictionaryCategory.TEMPLATE &&
                surface.codePointCount(0, surface.length) > MAX_TEMPLATE_LENGTH -> UserDictionaryError.TEMPLATE_TOO_LONG
            entry.category != UserDictionaryCategory.TEMPLATE &&
                surface.codePointCount(0, surface.length) > MAX_SURFACE_LENGTH -> UserDictionaryError.SURFACE_TOO_LONG
            entry.category == UserDictionaryCategory.TEMPLATE && surface.any {
                it.isISOControl() && it != '\n' && it != '\r' && it != '\t'
            } -> UserDictionaryError.SURFACE_HAS_CONTROL_CHARACTER
            entry.category != UserDictionaryCategory.TEMPLATE && surface.any(Char::isISOControl) ->
                UserDictionaryError.SURFACE_HAS_CONTROL_CHARACTER
            else -> null
        }
    }

    /** 読みに使える文字か判定する。ひらがな、踊り字「ゝゞ」、長音符「ー」だけを許す。 */
    private fun isReadingCharacter(character: Char): Boolean =
        character in '\u3041'..'\u3096' || character == '\u309D' || character == '\u309E' || character == '\u30FC'
}
