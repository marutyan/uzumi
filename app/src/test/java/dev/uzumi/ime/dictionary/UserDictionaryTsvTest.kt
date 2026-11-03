package dev.uzumi.ime.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 入力値の検証規則と、保存・import/exportで共通に使うTSVの読み書きを検証する。 */
class UserDictionaryTsvTest {
    /** 空、長すぎる、ひらがな以外の読みと、空・制御文字を含む表記を拒否する。 */
    @Test
    fun rejectsInvalidEntries() {
        fun errorOf(reading: String, surface: String) =
            UserDictionaryRules.validate(UserDictionaryRules.normalize(UserDictionaryEntry(reading, surface)))

        assertNull(errorOf("うずみー", "渦巻"))
        assertEquals(UserDictionaryError.EMPTY_READING, errorOf("  ", "渦巻"))
        assertEquals(UserDictionaryError.READING_TOO_LONG, errorOf("あ".repeat(65), "渦巻"))
        assertNull(errorOf("あ".repeat(64), "渦巻"))
        assertEquals(UserDictionaryError.READING_NOT_HIRAGANA, errorOf("ウズミ", "渦巻"))
        assertEquals(UserDictionaryError.READING_NOT_HIRAGANA, errorOf("uzumi", "渦巻"))
        assertEquals(UserDictionaryError.EMPTY_SURFACE, errorOf("うずみ", " "))
        assertEquals(UserDictionaryError.SURFACE_TOO_LONG, errorOf("うずみ", "渦".repeat(129)))
        assertEquals(UserDictionaryError.SURFACE_HAS_CONTROL_CHARACTER, errorOf("うずみ", "渦\t巻"))
    }

    /** 結合濁点の読みを合成済みの読みへ揃え、前後の空白を除く。 */
    @Test
    fun normalizesReadingToNfc() {
        val entry = UserDictionaryRules.normalize(UserDictionaryEntry(" か\u3099っこう ", " 学校 "))
        assertEquals(UserDictionaryEntry("がっこう", "学校"), entry)
    }

    /** 書き出した内容を読み直すと同じ項目が得られる。 */
    @Test
    fun formatAndParseRoundTrip() {
        val entries = listOf(
            UserDictionaryEntry("うずみ", "Uzumi", UserDictionaryCategory.PROPER_NOUN),
            UserDictionaryEntry("やまだ", "山田", UserDictionaryCategory.PERSON_NAME),
            UserDictionaryEntry("めーる", " 署名\\n表記 \n\tか\u3099\r\n 終わり ", UserDictionaryCategory.TEMPLATE),
        )
        val text = UserDictionaryTsv.format(entries)
        assertEquals(
            "${UserDictionaryTsv.HEADER}\nうずみ\tUzumi\t固有名詞\nやまだ\t山田\t人名\n" +
                "めーる\t 署名\\\\n表記 \\n\\tか\u3099\\r\\n 終わり \t定型文\n",
            text,
        )
        assertEquals(TsvParseResult(entries, emptyList()), UserDictionaryTsv.parse(text))
    }

    /** 旧形式の短縮よみにあるバックスラッシュはそのまま保ち、定型文の既知のエスケープだけを復元する。 */
    @Test
    fun keepsLegacyBackslashesAndDecodesTemplateEscapes() {
        val result = UserDictionaryTsv.parse(
            "# Uzumi user dictionary v2\n" +
                "しょーと\t旧\\n表記\t短縮よみ\n" +
                "てんぷれ\t先頭\\n\\\\n\\t末尾\t定型文\n",
        )

        assertEquals(
            listOf(
                UserDictionaryEntry("しょーと", "旧\\n表記", UserDictionaryCategory.SHORTCUT),
                UserDictionaryEntry("てんぷれ", "先頭\n\\n\t末尾", UserDictionaryCategory.TEMPLATE),
            ),
            result.entries,
        )
        assertEquals(emptyList<TsvLineFailure>(), result.failures)
    }

    /** BOM、CRLF、注釈、空行、分類列の省略、Mozcのコメント列を受け付け、不正な行は行番号と理由を返す。 */
    @Test
    fun reportsFailedLinesWithLineNumbers() {
        val text = "\uFEFF# comment\r\n" +
            "とうきょう\t東京\t地名\r\n" +
            "\r\n" +
            "かいしゃ\t会社\r\n" +
            "カタカナ\t片仮名\r\n" +
            "ひとつ\n" +
            "ふめい\t不明\t動詞\n" +
            "から\t\n" +
            "おおい\ta\t名詞\tc\td\n" +
            "もずく\tMozc\t固有名詞\tコメントは無視\n"
        val result = UserDictionaryTsv.parse(text)
        assertEquals(
            listOf(
                UserDictionaryEntry("とうきょう", "東京", UserDictionaryCategory.PLACE_NAME),
                UserDictionaryEntry("かいしゃ", "会社", UserDictionaryCategory.NOUN),
                UserDictionaryEntry("もずく", "Mozc", UserDictionaryCategory.PROPER_NOUN),
            ),
            result.entries,
        )
        assertEquals(
            listOf(
                TsvLineFailure(5, UserDictionaryError.READING_NOT_HIRAGANA),
                TsvLineFailure(6, UserDictionaryError.WRONG_COLUMN_COUNT),
                TsvLineFailure(7, UserDictionaryError.UNKNOWN_CATEGORY),
                TsvLineFailure(8, UserDictionaryError.EMPTY_SURFACE),
                TsvLineFailure(9, UserDictionaryError.WRONG_COLUMN_COUNT),
            ),
            result.failures,
        )
    }

    /** UTF-8として不正なbyte列は置換せずに読込み失敗とする。 */
    @Test
    fun rejectsMalformedUtf8() {
        assertEquals("あ\tい", UserDictionaryTsv.decodeUtf8("あ\tい".toByteArray(Charsets.UTF_8)))
        assertNull(UserDictionaryTsv.decodeUtf8(byteArrayOf(0xE3.toByte(), 0x81.toByte())))
    }
}
