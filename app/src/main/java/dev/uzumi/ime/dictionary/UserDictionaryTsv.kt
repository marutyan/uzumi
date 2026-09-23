package dev.uzumi.ime.dictionary

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** TSVの一行が読めなかった理由を、1始まりの行番号とともに表す。import結果の表示に使う。 */
data class TsvLineFailure(val lineNumber: Int, val error: UserDictionaryError)

/** TSV全体の解析結果。読めた項目と失敗行を分けて返し、呼び出し側が既存データを失わずに採否を決められるようにする。 */
data class TsvParseResult(
    val entries: List<UserDictionaryEntry>,
    val failures: List<TsvLineFailure>,
)

/**
 * 保存ファイルとimport/exportで共通に使うテキスト形式を読み書きする。
 * 形式はUTF-8の「読み<TAB>表記<TAB>分類」で、分類列は省略すると名詞になる。空行と`#`で始まる行は読み飛ばす。
 * Mozcのユーザー辞書が出力する4列目（コメント）は読込み時だけ受け付けて無視し、書き出しは3列にする。
 */
object UserDictionaryTsv {
    /** 書き出すファイルの先頭に置く形式名。読込み時は注釈行として無視される。 */
    const val HEADER = "# Uzumi user dictionary v1"

    /** 項目を保存・export用のテキストへ変換する。項目は検証済みで、タブや改行を含まない前提とする。 */
    fun format(entries: List<UserDictionaryEntry>): String = buildString {
        append(HEADER).append('\n')
        entries.forEach { entry ->
            append(entry.reading).append('\t')
            append(entry.surface).append('\t')
            append(entry.category.label).append('\n')
        }
    }

    /** テキストを行ごとに解析し、正規化と検証を通った項目だけを返す。重複の扱いは辞書側で決める。 */
    fun parse(text: String): TsvParseResult {
        val entries = mutableListOf<UserDictionaryEntry>()
        val failures = mutableListOf<TsvLineFailure>()
        text.removePrefix("\uFEFF").split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val lineNumber = index + 1
            when (val parsed = parseLine(line)) {
                is LineResult.Parsed -> entries += parsed.entry
                is LineResult.Failed -> failures += TsvLineFailure(lineNumber, parsed.error)
            }
        }
        return TsvParseResult(entries, failures)
    }

    /**
     * UTF-8として厳密に復号する。不正なbyte列は置換文字で読み進めず、nullを返して読込み全体を失敗にする。
     */
    fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** 一行の解析結果。成功なら正規化済みの項目、失敗なら理由を持つ。 */
    private sealed interface LineResult {
        data class Parsed(val entry: UserDictionaryEntry) : LineResult
        data class Failed(val error: UserDictionaryError) : LineResult
    }

    /** 列数と分類名を確認し、項目の規則で検証する。 */
    private fun parseLine(line: String): LineResult {
        val columns = line.split('\t')
        if (columns.size !in 2..4) return LineResult.Failed(UserDictionaryError.WRONG_COLUMN_COUNT)
        val categoryLabel = columns.getOrNull(2)?.trim().orEmpty()
        val category = if (categoryLabel.isEmpty()) {
            UserDictionaryCategory.NOUN
        } else {
            UserDictionaryCategory.fromLabel(categoryLabel)
                ?: return LineResult.Failed(UserDictionaryError.UNKNOWN_CATEGORY)
        }
        val entry = UserDictionaryRules.normalize(UserDictionaryEntry(columns[0], columns[1], category))
        val error = UserDictionaryRules.validate(entry)
        return if (error == null) LineResult.Parsed(entry) else LineResult.Failed(error)
    }
}
