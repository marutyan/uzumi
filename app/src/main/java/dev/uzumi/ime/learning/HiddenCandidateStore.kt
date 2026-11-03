package dev.uzumi.ime.learning

import dev.uzumi.ime.dictionary.writeFileAtomically
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * 表示しない候補の表記をアプリ内だけに保存する。no_backupのファイルを使い、入力本文をバックアップへ含めない。
 */
class HiddenCandidateStore(private val file: File) {
    private val lock = Any()
    private val surfaces = linkedSetOf<String>()

    init {
        load()
    }

    /** 表記を表示対象から外してすぐ保存する。空文字列は登録しない。 */
    fun hide(surface: String): Boolean = synchronized(lock) {
        if (surface.isEmpty() || !surfaces.add(surface)) return@synchronized false
        if (!saveLocked()) {
            surfaces.remove(surface)
            return@synchronized false
        }
        true
    }

    /** 表記の非表示を解除してすぐ保存する。 */
    fun unhide(surface: String): Boolean = synchronized(lock) {
        if (!surfaces.remove(surface)) return@synchronized false
        if (!saveLocked()) {
            surfaces.add(surface)
            return@synchronized false
        }
        true
    }

    /** 現在非表示にしている表記をコピーして返す。 */
    fun all(): Set<String> = synchronized(lock) { surfaces.toSet() }

    /** 表記が非表示一覧にあるかを返す。 */
    fun contains(surface: String): Boolean = synchronized(lock) { surface in surfaces }

    /** UTF-8の各表記をBase64で読み、壊れた行だけを無視する。 */
    private fun load() = synchronized(lock) {
        if (!file.isFile) return@synchronized
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                runCatching { String(Base64.getDecoder().decode(line), Charsets.UTF_8) }
                    .getOrNull()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(surfaces::add)
            }
        }
    }

    /** 一覧を一時ファイル経由で置き換え、途中状態を残さない。lockを持った状態で呼ぶ。 */
    private fun saveLocked(): Boolean {
        val text = surfaces.joinToString(separator = "\n") {
            Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
        }
        return try {
            writeFileAtomically(file, text.toByteArray(Charsets.UTF_8))
            true
        } catch (_: IOException) {
            false
        }
    }
}

/** no_backup配下の非表示候補一覧をアプリプロセス内で共有する。 */
object HiddenCandidateStores {
    /** no_backup配下の保存ファイル名。 */
    private const val FILE_NAME = "hidden-candidates.txt"

    @Volatile
    private var instance: HiddenCandidateStore? = null

    /** 保存先が読み込み済みなら返し、初回はアプリのno_backup領域から開く。 */
    fun get(context: android.content.Context): HiddenCandidateStore = instance ?: synchronized(this) {
        instance ?: HiddenCandidateStore(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
            .also { instance = it }
    }
}
