package dev.uzumi.ime.dictionary

import dev.uzumi.ime.BackupJsonValue
import dev.uzumi.ime.SettingsBackupCodec
import dev.uzumi.ime.SettingsBackupDocument
import dev.uzumi.ime.SettingsBackupException
import dev.uzumi.ime.UzumiSettings
import dev.uzumi.ime.keyboard.FlickBubbleShape
import dev.uzumi.ime.keyboard.FlickJudge
import dev.uzumi.ime.keyboard.InputTuning
import dev.uzumi.ime.keyboard.KeyFont
import dev.uzumi.ime.keyboard.KeyShape
import dev.uzumi.ime.keyboard.KeyboardAppearance
import dev.uzumi.ime.keyboard.ThemePreset
import dev.uzumi.ime.keyboard.ToolbarAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /** 設定と任意のユーザー辞書TSVをJSONへ書き出し、読み戻した内容が一致する。 */
    @Test
    fun settingsBackupJsonRoundTripIncludesOptionalDictionaryTsv() {
        val entries = listOf(UserDictionaryEntry("うずみ", "Uzumi", UserDictionaryCategory.PROPER_NOUN))
        val document = SettingsBackupDocument(
            values = mapOf(
                "liveConversion" to BackupJsonValue.BooleanValue(true),
                "keyboardHeightDp" to BackupJsonValue.NumberValue("66"),
                "learningEnabled" to BackupJsonValue.BooleanValue(false),
                "deleteDrag" to BackupJsonValue.BooleanValue(true),
                "vibration" to BackupJsonValue.BooleanValue(false),
                "keySound" to BackupJsonValue.BooleanValue(true),
                "keyPreview" to BackupJsonValue.BooleanValue(false),
                "toolbarActions" to BackupJsonValue.ArrayValue(
                    listOf(BackupJsonValue.StringValue("SETTINGS"), BackupJsonValue.StringValue("UNDO")),
                ),
                "toolbarRows" to BackupJsonValue.NumberValue("2"),
                "candidateRows" to BackupJsonValue.NumberValue("3"),
                "romajiInput" to BackupJsonValue.BooleanValue(true),
                "romajiEnglishQwerty" to BackupJsonValue.BooleanValue(false),
                "clipboardHistory" to BackupJsonValue.BooleanValue(false),
                "appearance" to stringObject(
                    KeyboardAppearance(
                        preset = ThemePreset.AI,
                        keyShape = KeyShape.SQUARE,
                        flickShape = FlickBubbleShape.STAR,
                        textColor = 0xFF283593.toInt(),
                    ).toCustom().toStorage(),
                ),
                "inputTuning" to stringObject(InputTuning().simejiLike().copy(longPressMillis = 300).toStorage()),
            ),
            userDictionaryTsv = UserDictionaryTsv.format(entries),
        )

        val decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encode(document))

        assertEquals(document, decoded)
        assertEquals(document.values, UzumiSettings.normalizeBackupSettings(decoded.values))
        assertEquals(TsvParseResult(entries, emptyList()), UserDictionaryTsv.parse(decoded.userDictionaryTsv!!))
        val withoutDictionary = document.copy(userDictionaryTsv = null)
        assertEquals(withoutDictionary, SettingsBackupCodec.decode(SettingsBackupCodec.encode(withoutDictionary)))
    }

    /** 未知項目はネストした値ごと無視し、不正JSONや未対応versionは設定へ進む前に拒否する。 */
    @Test
    fun settingsBackupRejectsInvalidFilesAndKeepsUnknownFieldsIgnorable() {
        val decoded = SettingsBackupCodec.decode(
            """{"version":1,"settings":{"future":{"array":[1,true,null]},"liveConversion":false},"future":{}}""",
        )
        val normalized = UzumiSettings.normalizeBackupSettings(decoded.values)

        assertEquals(mapOf("liveConversion" to BackupJsonValue.BooleanValue(false)), normalized)
        assertThrows(SettingsBackupException::class.java) { SettingsBackupCodec.decode("{broken") }
        assertThrows(SettingsBackupException::class.java) {
            SettingsBackupCodec.decode("""{"version":2,"settings":{}}""")
        }
        assertThrows(SettingsBackupException::class.java) {
            SettingsBackupCodec.decode("""{"version":1,"settings":{"future":"\u+041"}}""")
        }
        assertThrows(SettingsBackupException::class.java) {
            SettingsBackupCodec.decode("""{"version":1,"version":1,"settings":{}}""")
        }
        assertThrows(SettingsBackupException::class.java) {
            SettingsBackupCodec.decode("""{"version":1,"settings":{}}trailing""")
        }
        val nestedJson = "{\"version\":1,\"settings\":{\"future\":" +
            "[".repeat(20) + "0" + "]".repeat(20) + "}}"
        assertThrows(SettingsBackupException::class.java) { SettingsBackupCodec.decode(nestedJson) }
    }

    /** バックアップ内の辞書TSVは、ファイルからのimportと同じbyte数の上限を超えると読み込みを拒否する。 */
    @Test
    fun settingsBackupRejectsDictionaryLargerThanImportLimit() {
        val limit = UserDictionaryRules.MAX_IMPORT_BYTES
        val accepted = SettingsBackupDocument(emptyMap(), userDictionaryTsv = "a".repeat(limit))
        val rejected = SettingsBackupDocument(emptyMap(), userDictionaryTsv = "a".repeat(limit + 1))

        assertEquals(limit, SettingsBackupCodec.decode(SettingsBackupCodec.encode(accepted)).userDictionaryTsv!!.length)
        assertThrows(SettingsBackupException::class.java) {
            SettingsBackupCodec.decode(SettingsBackupCodec.encode(rejected))
        }
    }

    /** 範囲外の数値は既存設定の範囲へ丸め、未知の機能タイルは除く。 */
    @Test
    fun settingsBackupNormalizesOutOfRangeValuesAndToolbarActions() {
        val normalized = UzumiSettings.normalizeBackupSettings(
            mapOf(
                "keyboardHeightDp" to BackupJsonValue.NumberValue("1000"),
                "toolbarRows" to BackupJsonValue.NumberValue("0"),
                "candidateRows" to BackupJsonValue.NumberValue("8"),
                "toolbarActions" to BackupJsonValue.ArrayValue(
                    listOf(BackupJsonValue.StringValue(ToolbarAction.SETTINGS.name), BackupJsonValue.StringValue("FUTURE_ACTION")),
                ),
            ),
        )

        assertEquals("96", (normalized.getValue("keyboardHeightDp") as BackupJsonValue.NumberValue).value)
        assertEquals("1", (normalized.getValue("toolbarRows") as BackupJsonValue.NumberValue).value)
        assertEquals("3", (normalized.getValue("candidateRows") as BackupJsonValue.NumberValue).value)
        assertEquals(
            listOf(BackupJsonValue.StringValue(ToolbarAction.SETTINGS.name)),
            (normalized.getValue("toolbarActions") as BackupJsonValue.ArrayValue).values,
        )
    }

    /** 文字列の組をバックアップのJSON objectにする。 */
    private fun stringObject(values: Map<String, String>): BackupJsonValue =
        BackupJsonValue.ObjectValue(values.mapValues { BackupJsonValue.StringValue(it.value) })

    /** きせかえと入力の操作は、知らない項目を捨て、範囲外を収め、型の違う値を含むと読み込み全体を拒否する。 */
    @Test
    fun settingsBackupNormalizesAppearanceAndInputTuning() {
        val normalized = UzumiSettings.normalizeBackupSettings(
            mapOf(
                "appearance" to BackupJsonValue.ObjectValue(
                    mapOf(
                        "keyOpacity" to BackupJsonValue.StringValue("500"),
                        KeyboardAppearance.KEY_FONT to BackupJsonValue.StringValue(KeyFont.YUSEI_MAGIC.name),
                        "futureField" to BackupJsonValue.StringValue("x"),
                    ),
                ),
                "inputTuning" to BackupJsonValue.ObjectValue(
                    mapOf("flickJudge" to BackupJsonValue.StringValue("AXIS")),
                ),
            ),
        )
        assertEquals(
            stringObject(KeyboardAppearance(keyOpacity = 100, keyFont = KeyFont.YUSEI_MAGIC).toStorage()),
            normalized.getValue("appearance"),
        )
        KeyFont.entries.forEach { font ->
            val appearance = UzumiSettings.normalizeBackupSettings(
                mapOf(
                    "appearance" to BackupJsonValue.ObjectValue(
                        mapOf(KeyboardAppearance.KEY_FONT to BackupJsonValue.StringValue(font.name)),
                    ),
                ),
            )
            assertEquals(stringObject(KeyboardAppearance(keyFont = font).toStorage()), appearance.getValue("appearance"))
        }
        val unknownFont = UzumiSettings.normalizeBackupSettings(
            mapOf(
                "appearance" to BackupJsonValue.ObjectValue(
                    mapOf(KeyboardAppearance.KEY_FONT to BackupJsonValue.StringValue("FUTURE")),
                ),
            ),
        )
        assertEquals(stringObject(KeyboardAppearance().toStorage()), unknownFont.getValue("appearance"))
        assertEquals(stringObject(InputTuning(flickJudge = FlickJudge.AXIS).toStorage()), normalized.getValue("inputTuning"))
        assertThrows(SettingsBackupException::class.java) {
            UzumiSettings.normalizeBackupSettings(
                mapOf("appearance" to BackupJsonValue.ObjectValue(mapOf("keyOpacity" to BackupJsonValue.NumberValue("50")))),
            )
        }
        assertThrows(SettingsBackupException::class.java) {
            UzumiSettings.normalizeBackupSettings(mapOf("inputTuning" to BackupJsonValue.StringValue("AXIS")))
        }
    }

    /** 既知項目の型が違う場合は他の設定も含めて反映しないために失敗する。 */
    @Test
    fun settingsBackupRejectsWrongKnownFieldTypeAndInvalidNumbers() {
        assertThrows(SettingsBackupException::class.java) {
            UzumiSettings.normalizeBackupSettings(
                mapOf("liveConversion" to BackupJsonValue.StringValue("true")),
            )
        }
        assertThrows(SettingsBackupException::class.java) {
            UzumiSettings.normalizeBackupSettings(
                mapOf("keyboardHeightDp" to BackupJsonValue.NumberValue("100000000000000000000")),
            )
        }
    }
}
