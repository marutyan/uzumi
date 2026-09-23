package dev.uzumi.ime.dictionary

import java.text.Normalizer

/**
 * ユーザー辞書の一項目。読みと表記の組で一意になり、管理画面・保存形式・IME候補の参照で共通に使う。
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

    /** 登録できる総数。IMEのプロセスが全項目をメモリ上のindexへ保持するため上限を置く。 */
    const val MAX_ENTRY_COUNT = 50_000

    /** 前後の空白を除き、読みと表記をNFCへ揃える。結合濁点で入力された読みも合成済みの読みと同じ項目として扱う。 */
    fun normalize(entry: UserDictionaryEntry): UserDictionaryEntry = entry.copy(
        reading = normalizeReading(entry.reading),
        surface = Normalizer.normalize(entry.surface.trim(), Normalizer.Form.NFC),
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
            surface.codePointCount(0, surface.length) > MAX_SURFACE_LENGTH -> UserDictionaryError.SURFACE_TOO_LONG
            // タブと改行は保存形式の区切りになるため、表記に制御文字を許さない。
            surface.any(Char::isISOControl) -> UserDictionaryError.SURFACE_HAS_CONTROL_CHARACTER
            else -> null
        }
    }

    /** 読みに使える文字か判定する。ひらがな、踊り字「ゝゞ」、長音符「ー」だけを許す。 */
    private fun isReadingCharacter(character: Char): Boolean =
        character in '\u3041'..'\u3096' || character == '\u309D' || character == '\u309E' || character == '\u30FC'
}
