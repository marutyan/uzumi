package dev.uzumi.ime.dictionary

import java.io.IOException

/**
 * IMEの候補生成からユーザー辞書を引くための読み取り専用API。
 * 実装はメモリ上の不変indexを返すだけで、どのスレッドからも同期で呼べる。
 */
interface UserDictionaryLookup {
    /** 辞書内容の世代。変更が保存されるたびに増え、変換結果が古い辞書に基づくかの照合に使える。 */
    val generation: Long

    /** 読みが完全一致する項目を登録順に返す。読みは登録時と同じくNFCへ揃えてから照合する。 */
    fun exactMatches(reading: String): List<UserDictionaryEntry>

    /** 読みが前方一致する項目を、読みの辞書順・同じ読みの中では登録順に最大limit件返す。 */
    fun prefixMatches(prefix: String, limit: Int): List<UserDictionaryEntry>
}

/** importの結果件数。失敗行は行番号付きで返し、画面で利用者へ示す。 */
data class UserDictionaryImportSummary(
    val added: Int,
    val duplicates: Int,
    val overLimit: Int,
    val failures: List<TsvLineFailure>,
    val storageFailed: Boolean,
)

/**
 * ユーザー辞書の登録・検索・編集・削除・入出力を担う。変更はロック内で保存に成功してからindexを差し替えるため、
 * 保存に失敗した変更はメモリにも残らない。参照はvolatileの不変スナップショットを読むだけでロックを取らない。
 */
class UserDictionaryRepository private constructor(
    private val store: UserDictionaryFileStore,
    initialEntries: List<UserDictionaryEntry>,
    loadProblem: Boolean,
    private val clock: () -> Long,
) : UserDictionaryLookup {
    // 書込み処理を直列化するロック。参照処理は取らない。
    private val writeLock = Any()

    @Volatile
    private var snapshot = Snapshot(initialEntries, generation = 1)

    // 保存ファイルに読めない部分があった場合、最初の上書き前に原本を退避するためのフラグ。
    private var unreadableCopyPending = loadProblem

    /** 起動時の保存ファイルに読めない行や復号できない内容があったか。管理画面で利用者へ知らせる。 */
    val hadLoadProblem: Boolean = loadProblem

    override val generation: Long get() = snapshot.generation

    /** 全項目を登録順に返す。 */
    fun allEntries(): List<UserDictionaryEntry> = snapshot.entries

    override fun exactMatches(reading: String): List<UserDictionaryEntry> =
        snapshot.byReading[UserDictionaryRules.normalizeReading(reading)].orEmpty()

    override fun prefixMatches(prefix: String, limit: Int): List<UserDictionaryEntry> {
        if (limit <= 0) return emptyList()
        val current = snapshot
        val normalized = UserDictionaryRules.normalizeReading(prefix)
        // 前方一致する読みは辞書順の配列で連続するため、先頭位置を二分探索してから順に読む。
        val start = current.sortedReadings.binarySearch(normalized).let { if (it >= 0) it else -it - 1 }
        val result = mutableListOf<UserDictionaryEntry>()
        for (index in start until current.sortedReadings.size) {
            val reading = current.sortedReadings[index]
            if (!reading.startsWith(normalized)) break
            for (entry in current.byReading.getValue(reading)) {
                result += entry
                if (result.size >= limit) return result
            }
        }
        return result
    }

    /** 管理画面の検索。読みの前方一致または表記の部分一致を登録順に返し、空の検索語では全項目を返す。 */
    fun search(query: String): List<UserDictionaryEntry> {
        val normalized = UserDictionaryRules.normalizeReading(query)
        if (normalized.isEmpty()) return snapshot.entries
        return snapshot.entries.filter { it.reading.startsWith(normalized) || it.surface.contains(normalized) }
    }

    /** 項目を追加する。成功時はnull、失敗時は理由を返す。 */
    fun add(entry: UserDictionaryEntry): UserDictionaryError? = synchronized(writeLock) {
        val normalized = UserDictionaryRules.normalize(entry)
        UserDictionaryRules.validate(normalized)?.let { return it }
        val current = snapshot.entries
        if (current.any { it.sameKey(normalized) }) return UserDictionaryError.DUPLICATE
        if (current.size >= UserDictionaryRules.MAX_ENTRY_COUNT) return UserDictionaryError.TOO_MANY_ENTRIES
        commit(current + normalized)
    }

    /** originalをreplacementで置き換え、一覧上の位置を保つ。別の項目と読み・表記が重なる場合は拒否する。 */
    fun update(original: UserDictionaryEntry, replacement: UserDictionaryEntry): UserDictionaryError? =
        synchronized(writeLock) {
            val normalized = UserDictionaryRules.normalize(replacement)
            UserDictionaryRules.validate(normalized)?.let { return it }
            val current = snapshot.entries
            val index = current.indexOfFirst { it.sameKey(original) }
            if (index < 0) return UserDictionaryError.NOT_FOUND
            if (current.withIndex().any { (other, entry) -> other != index && entry.sameKey(normalized) }) {
                return UserDictionaryError.DUPLICATE
            }
            commit(current.toMutableList().also { it[index] = normalized })
        }

    /** 読みと表記が一致する項目を削除する。 */
    fun remove(entry: UserDictionaryEntry): UserDictionaryError? = synchronized(writeLock) {
        val current = snapshot.entries
        val index = current.indexOfFirst { it.sameKey(entry) }
        if (index < 0) return UserDictionaryError.NOT_FOUND
        commit(current.toMutableList().also { it.removeAt(index) })
    }

    /**
     * 解析済みのTSVを既存辞書へ追加する。既存と同じ読み・表記の組は重複として飛ばし、上限を超えた分は追加しない。
     * 保存は一回だけ行い、失敗した場合は既存の辞書をそのまま残す。
     */
    fun import(parsed: TsvParseResult): UserDictionaryImportSummary = synchronized(writeLock) {
        val merged = snapshot.entries.toMutableList()
        // 既存項目とimport内の項目を合わせた、読みと表記の組の集合。重複判定をO(1)にする。
        val keys = merged.mapTo(HashSet()) { it.reading to it.surface }
        var added = 0
        var duplicates = 0
        var overLimit = 0
        parsed.entries.forEach { entry ->
            when {
                (entry.reading to entry.surface) in keys -> duplicates++
                merged.size >= UserDictionaryRules.MAX_ENTRY_COUNT -> overLimit++
                else -> {
                    merged += entry
                    keys += entry.reading to entry.surface
                    added++
                }
            }
        }
        val storageFailed = added > 0 && commit(merged) != null
        UserDictionaryImportSummary(
            added = if (storageFailed) 0 else added,
            duplicates = duplicates,
            overLimit = overLimit,
            failures = parsed.failures,
            storageFailed = storageFailed,
        )
    }

    /** export用のテキストを作る。保存ファイルと同じ形式で、そのまま再importできる。 */
    fun exportText(): String = UserDictionaryTsv.format(snapshot.entries)

    /** 保存に成功した場合だけ新しいindexを公開する。writeLock内から呼ぶ。 */
    private fun commit(entries: List<UserDictionaryEntry>): UserDictionaryError? {
        try {
            if (unreadableCopyPending) {
                store.preserveUnreadableCopy(clock())
                unreadableCopyPending = false
            }
            store.save(entries)
        } catch (_: IOException) {
            return UserDictionaryError.STORAGE_FAILED
        }
        snapshot = Snapshot(entries.toList(), snapshot.generation + 1)
        return null
    }

    /** 読みと表記の組が同じか判定する。分類は重複判定に含めない。 */
    private fun UserDictionaryEntry.sameKey(other: UserDictionaryEntry): Boolean =
        reading == other.reading && surface == other.surface

    /** ある時点の全項目と検索用indexをまとめた不変値。差し替えだけで参照側へ公開する。 */
    private class Snapshot(val entries: List<UserDictionaryEntry>, val generation: Long) {
        // 読みごとの項目一覧。groupByは登録順を保つ。
        val byReading: Map<String, List<UserDictionaryEntry>> = entries.groupBy(UserDictionaryEntry::reading)

        // 前方一致検索のために辞書順へ並べた読みの一覧。
        val sortedReadings: List<String> = byReading.keys.sorted()
    }

    companion object {
        /** 保存ファイルを読み込んで辞書を開く。ファイルの読込みを同期で行うため、UIスレッド以外から呼ぶ。 */
        fun open(
            store: UserDictionaryFileStore,
            clock: () -> Long = System::currentTimeMillis,
        ): UserDictionaryRepository {
            val stored = store.load()
            // 保存ファイル内の重複は先に出た項目を残し、上限を超える分は読み込まない。
            val unique = stored.entries.distinctBy { it.reading to it.surface }
            val loaded = unique.take(UserDictionaryRules.MAX_ENTRY_COUNT)
            val problem = stored.problem || loaded.size != stored.entries.size
            return UserDictionaryRepository(store, loaded, problem, clock)
        }
    }
}
