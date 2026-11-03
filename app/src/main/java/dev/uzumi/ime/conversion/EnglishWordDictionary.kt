package dev.uzumi.ime.conversion

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** 読みが完全一致した英単語の登録内容。 */
data class EnglishWordEntry(val reading: String, val surface: String)

/** 読みから英単語候補を引く読み取り専用API。参照は不変indexだけを使う。 */
interface EnglishWordLookup {
    /** 読みが完全一致する表記を、TSVに書いた順で返す。 */
    fun exactMatches(reading: String): List<EnglishWordEntry>
}

/**
 * 同梱TSVを不変indexへ読み込み、読みが一致する英語表記を返す。
 * 読み込み後はデータを変更せず、変換workerから同期参照できる。
 */
class EnglishWordDictionary private constructor(entries: List<EnglishWordEntry>) : EnglishWordLookup {
    private val byReading: Map<String, List<EnglishWordEntry>> =
        entries.groupBy(EnglishWordEntry::reading).mapValues { (_, words) -> words.toList() }.toMap()

    /** 読みが完全一致する表記を、登録順で返す。 */
    override fun exactMatches(reading: String): List<EnglishWordEntry> = byReading[reading].orEmpty()

    /** 外部からの辞書読み込みとTSV検査をまとめる。 */
    companion object {
        /** UTF-8の読み<TAB>表記形式を検査して不変の辞書を作る。 */
        fun load(input: InputStream): EnglishWordDictionary =
            InputStreamReader(input, Charsets.UTF_8).buffered().use { reader -> parse(reader) }

        /** TSVの空行、列数、空の読み・表記を拒否し、同一の読みと表記を重ねて登録しない。 */
        fun parse(reader: BufferedReader): EnglishWordDictionary {
            val entries = mutableListOf<EnglishWordEntry>()
            val keys = HashSet<Pair<String, String>>()
            reader.forEachLineIndexed { lineNumber, line ->
                require(line.isNotBlank()) { "英単語辞書の${lineNumber}行目が空です" }
                val columns = line.split('\t')
                require(columns.size == 2 && columns.all(String::isNotBlank)) {
                    "英単語辞書の${lineNumber}行目は読みと表記の2列が必要です"
                }
                val entry = EnglishWordEntry(columns[0], columns[1])
                require(entry.reading.all { it in '\u3041'..'\u309F' || it == 'ー' }) {
                    "英単語辞書の${lineNumber}行目の読みはひらがなと長音記号だけにしてください"
                }
                require(keys.add(entry.reading to entry.surface)) {
                    "英単語辞書の${lineNumber}行目が重複しています"
                }
                entries += entry
            }
            return EnglishWordDictionary(entries)
        }

        /** 各行を行番号付きで読み取り、書式エラーを検査する。 */
        private inline fun BufferedReader.forEachLineIndexed(action: (Int, String) -> Unit) {
            var lineNumber = 0
            while (true) {
                val line = readLine() ?: break
                action(++lineNumber, line)
            }
        }
    }
}
