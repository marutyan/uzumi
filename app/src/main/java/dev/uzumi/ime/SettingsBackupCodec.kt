package dev.uzumi.ime

import dev.uzumi.ime.dictionary.UserDictionaryRules

/** JSON内の値を表し、AndroidのJSON実装へ依存せず設定バックアップを検証する。 */
sealed interface BackupJsonValue {
    /** JSON objectをキーと値の組として保持する。 */
    data class ObjectValue(val values: Map<String, BackupJsonValue>) : BackupJsonValue

    /** JSON arrayの順序を保って保持する。 */
    data class ArrayValue(val values: List<BackupJsonValue>) : BackupJsonValue

    /** JSON文字列の復号後の値。 */
    data class StringValue(val value: String) : BackupJsonValue

    /** JSON numberの原表記。設定へ適用する際に整数かを別途検証する。 */
    data class NumberValue(val value: String) : BackupJsonValue

    /** JSON trueまたはfalse。 */
    data class BooleanValue(val value: Boolean) : BackupJsonValue

    /** JSON null。 */
    data object NullValue : BackupJsonValue
}

/** versionと設定値、任意のユーザー辞書TSVをまとめる。 */
data class SettingsBackupDocument(
    val values: Map<String, BackupJsonValue>,
    val userDictionaryTsv: String? = null,
)

/** JSONが不正、またはバックアップ形式が未対応の場合に使う。 */
class SettingsBackupException(message: String) : Exception(message)

/** バックアップ形式versionを固定し、設定画面と純JVMテストから同じJSON処理を使う。 */
object SettingsBackupCodec {
    /** 読み書きに対応する設定バックアップの形式番号。 */
    const val VERSION = 1

    /** メモリを使い切るほど大きいJSONを拒否する上限。 */
    const val MAX_JSON_CHARS = 16 * 1024 * 1024

    /** 入れ子の未知JSON項目も含めた最大深さ。 */
    private const val MAX_DEPTH = 16

    /** 入れ子の未知JSON項目も含めた最大値要素数。 */
    private const val MAX_NODES = 250_000

    /** 設定値をversion付きJSONへ変換する。文字列と配列はJSON規則に従ってescapeする。 */
    fun encode(document: SettingsBackupDocument): String = buildString {
        append('{')
        append("\"version\":").append(VERSION)
        append(",\"settings\":")
        appendValue(BackupJsonValue.ObjectValue(document.values))
        document.userDictionaryTsv?.let {
            append(",\"userDictionaryTsv\":")
            appendString(it)
        }
        append('}')
    }.also { require(it.length <= MAX_JSON_CHARS) { "バックアップが大きすぎます" } }

    /** JSONを読み、versionと既知の外側項目の型を確かめる。未知の外側項目は無視する。 */
    @Throws(SettingsBackupException::class)
    fun decode(json: String): SettingsBackupDocument {
        if (json.length > MAX_JSON_CHARS) throw SettingsBackupException("バックアップが大きすぎます")
        val root = JsonParser(json).parse() as? BackupJsonValue.ObjectValue
            ?: throw SettingsBackupException("JSONの最上位はobjectである必要があります")
        val version = (root.values["version"] as? BackupJsonValue.NumberValue)?.value?.toIntOrNull()
            ?: throw SettingsBackupException("versionがありません")
        if (version != VERSION) throw SettingsBackupException("未対応のversionです")
        val settings = root.values["settings"] as? BackupJsonValue.ObjectValue
            ?: throw SettingsBackupException("settingsがありません")
        val dictionary = when (val value = root.values["userDictionaryTsv"]) {
            null -> null
            is BackupJsonValue.StringValue -> value.value.also { tsv ->
                // ファイルからの辞書importと同じ上限を超える辞書は、解析してメモリを使い切る前に拒否する。
                if (tsv.toByteArray(Charsets.UTF_8).size > UserDictionaryRules.MAX_IMPORT_BYTES) {
                    throw SettingsBackupException("userDictionaryTsvが大きすぎます")
                }
            }
            else -> throw SettingsBackupException("userDictionaryTsvの型が正しくありません")
        }
        return SettingsBackupDocument(settings.values, dictionary)
    }

    /** JSON値を再帰的に書き出す。 */
    private fun StringBuilder.appendValue(value: BackupJsonValue) {
        when (value) {
            is BackupJsonValue.ObjectValue -> {
                append('{')
                value.values.entries.forEachIndexed { index, (key, child) ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendValue(child)
                }
                append('}')
            }
            is BackupJsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendValue(child)
                }
                append(']')
            }
            is BackupJsonValue.StringValue -> appendString(value.value)
            is BackupJsonValue.NumberValue -> {
                require(JSON_NUMBER.matches(value.value)) { "数値の形式が正しくありません" }
                append(value.value)
            }
            is BackupJsonValue.BooleanValue -> append(if (value.value) "true" else "false")
            BackupJsonValue.NullValue -> append("null")
        }
    }

    /** JSON文字列を書き、制御文字と引用符をescapeする。 */
    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /** JSON文法を必要な範囲で解析し、深さ・要素数を制限して大きな未知項目も安全に読み飛ばす。 */
    private class JsonParser(private val source: String) {
        private var index = 0
        private var nodes = 0

        /** JSONを一つ読み、末尾に余分な文字がないことを確かめる。 */
        fun parse(): BackupJsonValue {
            val value = readValue(0)
            skipWhitespace()
            if (index != source.length) fail()
            return value
        }

        /** 次の値を型に応じて解析する。 */
        private fun readValue(depth: Int): BackupJsonValue {
            skipWhitespace()
            if (++nodes > MAX_NODES || depth > MAX_DEPTH || index >= source.length) fail()
            return when (source[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> BackupJsonValue.StringValue(readString())
                't' -> { readLiteral("true"); BackupJsonValue.BooleanValue(true) }
                'f' -> { readLiteral("false"); BackupJsonValue.BooleanValue(false) }
                'n' -> { readLiteral("null"); BackupJsonValue.NullValue }
                '-', in '0'..'9' -> BackupJsonValue.NumberValue(readNumber())
                else -> fail()
            }
        }

        /** objectを解析し、重複キーを拒否する。 */
        private fun readObject(depth: Int): BackupJsonValue.ObjectValue {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, BackupJsonValue>()
            if (consume('}')) return BackupJsonValue.ObjectValue(values)
            while (true) {
                skipWhitespace()
                if (index >= source.length || source[index] != '"') fail()
                val key = readString()
                skipWhitespace()
                expect(':')
                if (values.containsKey(key)) fail()
                values[key] = readValue(depth)
                skipWhitespace()
                if (consume('}')) return BackupJsonValue.ObjectValue(values)
                expect(',')
            }
        }

        /** arrayを解析する。 */
        private fun readArray(depth: Int): BackupJsonValue.ArrayValue {
            index++
            skipWhitespace()
            val values = mutableListOf<BackupJsonValue>()
            if (consume(']')) return BackupJsonValue.ArrayValue(values)
            while (true) {
                values += readValue(depth)
                skipWhitespace()
                if (consume(']')) return BackupJsonValue.ArrayValue(values)
                expect(',')
            }
        }

        /** escapeを復号し、制御文字を含まないJSON文字列を読む。 */
        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> {
                        if (index >= source.length) fail()
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(readUnicodeEscape())
                            else -> fail()
                        }
                    }
                    character.code < 0x20 -> fail()
                    else -> result.append(character)
                }
                if (result.length > MAX_JSON_CHARS) fail()
            }
            fail()
        }

        /** 4桁のUnicode escapeを一文字へ変換する。 */
        private fun readUnicodeEscape(): Char {
            if (index + 4 > source.length) fail()
            val digits = source.substring(index, index + 4)
            if (!digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) fail()
            val value = digits.toIntOrNull(16) ?: fail()
            index += 4
            return value.toChar()
        }

        /** JSON number文法を確認して元の表記を返す。 */
        private fun readNumber(): String {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) fail()
            } else {
                if (!consumeDigits()) fail()
            }
            if (consume('.')) if (!consumeDigits()) fail()
            if (index < source.length && source[index] in "eE") {
                index++
                if (index < source.length && source[index] in "+-") index++
                if (!consumeDigits()) fail()
            }
            val value = source.substring(start, index)
            if (value.length > 64) fail()
            return value
        }

        /** 1桁以上の数字を読む。 */
        private fun consumeDigits(): Boolean {
            val start = index
            while (index < source.length && source[index] in '0'..'9') index++
            return index > start
        }

        /** true/false/nullの固定文字列を読み進める。 */
        private fun readLiteral(value: String) {
            if (!source.startsWith(value, index)) fail()
            index += value.length
        }

        /** 空白文字を飛ばす。 */
        private fun skipWhitespace() {
            while (index < source.length && source[index] in " \t\r\n") index++
        }

        /** 次の文字が一致する場合だけ進める。 */
        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }

        /** 期待する区切り文字を確認する。 */
        private fun expect(expected: Char) {
            if (!consume(expected)) fail()
        }

        /** 入力位置を示さず不正なJSONとして失敗させる。本文をエラー表示へ含めない。 */
        private fun fail(): Nothing = throw SettingsBackupException("JSON形式が正しくありません")
    }

    /** JSON numberの文法。JSONを書き出す時に外部由来の値をそのまま連結しない。 */
    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
