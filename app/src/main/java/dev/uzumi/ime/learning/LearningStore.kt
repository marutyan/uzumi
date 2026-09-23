package dev.uzumi.ime.learning

import dev.uzumi.ime.dictionary.writeFileAtomically
import java.io.File
import java.io.IOException
import java.util.TreeMap
import kotlin.math.ln

/**
 * 確定で学習した一語。lastUsedMillisは最後に確定した時刻、useCountは確定した回数。
 */
data class LearnedWord(
    val reading: String,
    val surface: String,
    val lastUsedMillis: Long,
    val useCount: Int,
) {
    /**
     * 候補の並びに使う値。最後に使った時刻を基準にし、回数が多いほど時刻を先へ進めて補正する。
     * 回数がe倍になるごとにFREQUENCY_BONUS_MILLIS（1日）だけ新しく使ったものとして扱う。
     */
    val score: Double
        get() = lastUsedMillis + ln(useCount.toDouble()) * LearningStore.FREQUENCY_BONUS_MILLIS
}

/** 学習してよい読みと表記の組を決める規則。記録の入口を一か所にまとめる。 */
object LearningRules {
    /** 保存する表記の最大の長さ（UTF-16単位）。長い文を丸ごと覚えないための上限。 */
    const val MAX_SURFACE_LENGTH = 50

    /**
     * 読みと表記が学習の対象か。表記がひらがなだけのもの、ASCIIだけのもの、読みと同じもの、
     * 長すぎるもの、保存形式の区切り（タブ・改行）を含むものは覚えない。
     */
    fun isLearnable(reading: String, surface: String): Boolean {
        if (reading.isEmpty() || surface.isEmpty() || surface == reading) return false
        if (surface.length > MAX_SURFACE_LENGTH) return false
        if (listOf(reading, surface).any { text -> text.any { it == '\t' || it == '\n' || it == '\r' } }) return false
        if (surface.all { it.code < 0x80 }) return false
        return !surface.all { it in '\u3041'..'\u309F' || it == 'ー' }
    }
}

/**
 * 確定した「読み→表記」を覚え、完全一致と前方一致で候補を返すIME側の学習キャッシュ。
 * Mozcの文節履歴は学習時と同じ前後の文節でしか効かない語があるため、読みだけで引ける学習をIME側に持つ。
 * 変換workerと設定画面の両方から呼ばれるため、すべての操作を内部のlockで直列化する。
 * 記録は保存ファイルへすぐには書かず、flush（入力欄の切替時）でまとめて書く。
 */
class LearningStore(
    private val file: File,
    // 現在時刻（ミリ秒）。JVMテストでは固定の時計へ差し替える。
    private val clock: () -> Long = System::currentTimeMillis,
    // 保存する語の上限。超えたらscoreが最も低い語から捨てる。
    private val maxWords: Int = MAX_WORDS,
    // 変換エンジンが動いていないときに、エンジン側の学習（Mozcのprofile）を消す処理。
    private val clearEngineFiles: () -> Unit = {},
) {
    private val lock = Any()

    // 読みごとの学習語。前方一致をsubMapで引くため、読みの辞書順に並べる。
    private val byReading = TreeMap<String, MutableList<LearnedWord>>()
    private var wordCount = 0
    private var enabled = true

    // 保存ファイルへ未反映の変更があるか。
    private var dirty = false

    // 変換エンジンが動いている間だけ設定する、エンジンへ学習の消去を依頼する処理。依頼できなければfalseを返す。
    @Volatile
    private var engineClearer: (() -> Boolean)? = null

    init {
        load()
    }

    /** 学習を使うか。falseの間は記録も候補への合成もしない。保存済みの語は消さない。 */
    val isEnabled: Boolean
        get() = synchronized(lock) { enabled }

    /** 学習のON/OFFを切り替え、すぐに保存する。設定画面から呼ぶ。 */
    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabled == value) return@synchronized
        enabled = value
        dirty = true
        saveLocked()
    }

    /** 確定した読みと表記を記録する。学習がOFF、または規則で対象外なら何もせずfalseを返す。 */
    fun record(reading: String, surface: String): Boolean = synchronized(lock) {
        if (!enabled || !LearningRules.isLearnable(reading, surface)) return@synchronized false
        val words = byReading.getOrPut(reading) { mutableListOf() }
        val index = words.indexOfFirst { it.surface == surface }
        val now = clock()
        if (index >= 0) {
            val old = words[index]
            words[index] = old.copy(lastUsedMillis = now, useCount = old.useCount + 1)
        } else {
            words += LearnedWord(reading, surface, now, useCount = 1)
            wordCount += 1
            while (wordCount > maxWords) evictLowestLocked()
        }
        dirty = true
        true
    }

    /** 読みが完全一致する学習語を、scoreの高い順に最大limit件返す。学習がOFFなら空。 */
    fun exactMatches(reading: String, limit: Int = MAX_CANDIDATES): List<LearnedWord> = synchronized(lock) {
        if (!enabled) return@synchronized emptyList()
        byReading[reading].orEmpty().sortedByDescending { it.score }.take(limit)
    }

    /**
     * 読みがprefixで始まり、prefixより長い学習語を、scoreの高い順に最大limit件返す。
     * 入力途中の読みから学習語を予測するために使う。学習がOFFなら空。
     */
    fun prefixMatches(prefix: String, limit: Int = MAX_CANDIDATES): List<LearnedWord> = synchronized(lock) {
        if (!enabled || prefix.isEmpty()) return@synchronized emptyList()
        byReading.subMap(prefix, false, prefix + '\uFFFF', false).values
            .flatten()
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** 保存している全語を、scoreの高い順に返す。設定画面で一覧を表示するために使う。 */
    fun allWords(): List<LearnedWord> = synchronized(lock) {
        byReading.values.flatten().sortedByDescending { it.score }
    }

    /** 一語を削除してすぐに保存する。無ければfalseを返す。 */
    fun remove(reading: String, surface: String): Boolean = synchronized(lock) {
        val words = byReading[reading] ?: return@synchronized false
        if (!words.removeAll { it.surface == surface }) return@synchronized false
        if (words.isEmpty()) byReading.remove(reading)
        wordCount -= 1
        dirty = true
        saveLocked()
        true
    }

    /**
     * 学習をすべて消してすぐに保存し、変換エンジン側の学習も消す。エンジンが動いていればエンジンへ依頼し、
     * 動いていなければエンジンの学習ファイルを直接消す。学習のON/OFFは変えない。
     */
    fun clearAll() {
        synchronized(lock) {
            byReading.clear()
            wordCount = 0
            dirty = true
            saveLocked()
        }
        val requested = runCatching { engineClearer?.invoke() == true }.getOrDefault(false)
        if (!requested) clearEngineFiles()
    }

    /** 変換エンジンが動いている間、学習の消去をエンジンへ依頼する処理を登録する。nullで解除する。 */
    fun setEngineClearer(clearer: (() -> Boolean)?) {
        engineClearer = clearer
    }

    /** 未保存の変更があれば保存する。入力欄やIMEの切替時に呼ぶ。保存に失敗しても次回に再試行する。 */
    fun flush() = synchronized(lock) { saveLocked() }

    /** scoreが最も低い語を一つ捨てる。lockを持った状態で呼ぶ。 */
    private fun evictLowestLocked() {
        val lowest = byReading.values.flatten().minByOrNull { it.score } ?: return
        val words = byReading.getValue(lowest.reading)
        words.remove(lowest)
        if (words.isEmpty()) byReading.remove(lowest.reading)
        wordCount -= 1
    }

    /** 変更があれば保存ファイルを原子的に置き換える。lockを持った状態で呼ぶ。 */
    private fun saveLocked() {
        if (!dirty) return
        val text = buildString {
            append(HEADER).append('\t').append(if (enabled) ENABLED else DISABLED).append('\n')
            for (word in byReading.values.flatten()) {
                append(word.reading).append('\t').append(word.surface).append('\t')
                append(word.lastUsedMillis).append('\t').append(word.useCount).append('\n')
            }
        }
        try {
            writeFileAtomically(file, text.toByteArray(Charsets.UTF_8))
            dirty = false
        } catch (_: IOException) {
            // 保存できなくても入力は続ける。dirtyを残し、次のflushで再試行する。
        }
    }

    /** 保存ファイルを読む。読めない行は捨て、ファイルが無いか読めなければ空の学習から始める。 */
    private fun load() = synchronized(lock) {
        val lines = runCatching { file.readText(Charsets.UTF_8).lines() }.getOrNull() ?: return@synchronized
        val header = lines.firstOrNull()?.split('\t')
        if (header?.firstOrNull() != HEADER) return@synchronized
        enabled = header.getOrNull(1) != DISABLED
        for (line in lines.drop(1)) {
            val columns = line.split('\t')
            if (columns.size != 4) continue
            val lastUsed = columns[2].toLongOrNull() ?: continue
            val count = columns[3].toIntOrNull()?.takeIf { it > 0 } ?: continue
            if (!LearningRules.isLearnable(columns[0], columns[1])) continue
            val words = byReading.getOrPut(columns[0]) { mutableListOf() }
            if (words.any { it.surface == columns[1] }) continue
            words += LearnedWord(columns[0], columns[1], lastUsed, count)
            wordCount += 1
        }
        while (wordCount > maxWords) evictLowestLocked()
    }

    companion object {
        /** 保存する語の上限。 */
        const val MAX_WORDS = 10_000

        /** 一つの読みから候補の先頭へ出す学習語の最大数。 */
        const val MAX_CANDIDATES = 3

        /** 回数による補正の大きさ。回数がe倍になるごとに、この時間だけ新しく使ったものとして扱う。 */
        const val FREQUENCY_BONUS_MILLIS = 24.0 * 60 * 60 * 1000

        /** 保存ファイルの先頭行の識別子。2列目に学習のON/OFFを書く。 */
        private const val HEADER = "uzumi-learning-v1"
        private const val ENABLED = "enabled"
        private const val DISABLED = "disabled"
    }
}
