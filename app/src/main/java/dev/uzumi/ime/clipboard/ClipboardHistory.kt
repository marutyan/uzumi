package dev.uzumi.ime.clipboard

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64

/** 端末内に保存するクリップボード履歴の一項目を表す。 */
data class ClipboardHistoryItem(
    val text: String,
    val copiedAtMillis: Long,
    val pinned: Boolean,
)

/** クリップ本文を含めず、同じOSクリップを二度取り込まないための識別情報を持つ。 */
internal data class ClipboardHistoryIdentity(
    val timestamp: Long,
    val mimeTypes: List<String>,
    val clipboardSensitive: Boolean,
)

/** clip変更通知を受けた瞬間の欄の制限を、遅延処理後も維持する。 */
internal data class ClipboardHistoryCapturePolicy(
    val sensitiveField: Boolean,
    val learningSuppressed: Boolean,
    val evaluationRecording: Boolean,
)

/** クリップボード履歴の保存・期限・件数を、Android UIから独立して扱う。 */
internal object ClipboardHistoryRules {
    /** 未固定項目を自動削除するまでの時間。 */
    const val LIFETIME_MILLIS = 60L * 60L * 1000L

    /** 未固定の履歴に残す最大件数。固定項目はこの上限に含めない。 */
    const val MAX_UNPINNED_ITEMS = 30

    /** 機密clip、機密欄、学習禁止欄の本文を履歴へ保存してよいか判定する。 */
    fun mayStoreText(clipboardSensitive: Boolean, capturePolicy: ClipboardHistoryCapturePolicy): Boolean =
        !clipboardSensitive && !capturePolicy.sensitiveField && !capturePolicy.learningSuppressed &&
            !capturePolicy.evaluationRecording

    /** 取得時刻が未来でなく、1時間の期限内にあるclipだけを新規履歴へ取り込む。 */
    fun isFreshClip(timestamp: Long, nowMillis: Long): Boolean =
        timestamp > 0 && timestamp <= nowMillis && nowMillis - timestamp < LIFETIME_MILLIS

    /** 期限切れと上限超過の未固定項目を除き、固定項目を残す。 */
    fun prune(items: List<ClipboardHistoryItem>, nowMillis: Long): List<ClipboardHistoryItem> =
        limitItems(items.filter { it.pinned || isFreshClip(it.copiedAtMillis, nowMillis) })

    /** 同じ本文が再度コピーされたら先頭へ移し、固定状態を保ったまま古い未固定項目を上限から落とす。 */
    fun add(
        items: List<ClipboardHistoryItem>,
        text: String,
        copiedAtMillis: Long,
        nowMillis: Long,
    ): List<ClipboardHistoryItem> {
        val retained = prune(items, nowMillis)
        if (text.isEmpty() || !isFreshClip(copiedAtMillis, nowMillis)) return retained
        val previous = retained.firstOrNull { it.text == text }
        val updated = ClipboardHistoryItem(text, copiedAtMillis, previous?.pinned == true)
        return limitItems(listOf(updated) + retained.filterNot { it.text == text })
    }

    /** 固定状態を切り替え、固定項目を別枠に保ちながら未固定の上限を適用する。 */
    fun setPinned(items: List<ClipboardHistoryItem>, text: String, pinned: Boolean): List<ClipboardHistoryItem> {
        val updated = items.map { item -> if (item.text == text) item.copy(pinned = pinned) else item }
        return limitItems(updated)
    }

    /** 指定した本文の履歴だけを取り除く。 */
    fun delete(items: List<ClipboardHistoryItem>, text: String): List<ClipboardHistoryItem> =
        items.filterNot { it.text == text }

    /** 固定項目を別枠で保ち、未固定項目はコピー時刻が新しい順に最大件数へ収める。 */
    private fun limitItems(items: List<ClipboardHistoryItem>): List<ClipboardHistoryItem> {
        // 並び（新しいものが先頭、固定項目もその位置のまま）は変えず、上限を超えた古い未固定項目だけを落とす。
        val keptUnpinned = items.filterNot { it.pinned }
            .sortedByDescending(ClipboardHistoryItem::copiedAtMillis)
            .take(MAX_UNPINNED_ITEMS)
            .toSet()
        return items.filter { it.pinned || it in keptUnpinned }
    }
}

/** noBackupFilesDir配下のTSVへ履歴と本文を含まない最終処理clip情報を保存する。 */
internal class ClipboardHistoryStore(
    file: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val atomicFile = AtomicFile(file)
    private var loaded = false
    private var items = emptyList<ClipboardHistoryItem>()
    private var lastHandledIdentity: ClipboardHistoryIdentity? = null

    /** 期限切れを掃除した履歴を返す。 */
    @Synchronized
    fun readItems(): List<ClipboardHistoryItem> {
        loadIfNeeded()
        val retained = ClipboardHistoryRules.prune(items, clockMillis())
        if (retained != items) {
            items = retained
            writeFile()
        }
        return items.toList()
    }

    /** 同じ現在clipを再表示のたびに取り込まないよう、本文なしの識別情報を比較する。 */
    @Synchronized
    fun wasHandled(identity: ClipboardHistoryIdentity): Boolean {
        loadIfNeeded()
        return lastHandledIdentity == identity
    }

    /** 本文を取得しなかったclipも処理済みとして記録し、後から通常欄で読み直すのを防ぐ。 */
    @Synchronized
    fun markHandled(identity: ClipboardHistoryIdentity) {
        loadIfNeeded()
        if (lastHandledIdentity == identity) return
        lastHandledIdentity = identity
        writeFile()
    }

    /** 新しい本文を先頭へ加え、clip識別情報も同じ一回の保存で記録する。 */
    @Synchronized
    fun add(identity: ClipboardHistoryIdentity, text: String): List<ClipboardHistoryItem> {
        loadIfNeeded()
        val now = clockMillis()
        val updated = ClipboardHistoryRules.add(items, text, identity.timestamp, now)
        val changed = updated != items || lastHandledIdentity != identity
        items = updated
        lastHandledIdentity = identity
        if (changed) writeFile()
        return items.toList()
    }

    /** 固定状態を保存して、期限処理用の一覧を返す。 */
    @Synchronized
    fun setPinned(text: String, pinned: Boolean): List<ClipboardHistoryItem> {
        loadIfNeeded()
        val updated = ClipboardHistoryRules.setPinned(
            ClipboardHistoryRules.prune(items, clockMillis()),
            text,
            pinned,
        )
        if (updated != items) {
            items = updated
            writeFile()
        }
        return items.toList()
    }

    /** 一項目を削除し、現在clipの処理済み識別情報は保持する。 */
    @Synchronized
    fun delete(text: String): List<ClipboardHistoryItem> {
        loadIfNeeded()
        val updated = ClipboardHistoryRules.delete(items, text)
        if (updated != items) {
            items = updated
            writeFile()
        }
        return items.toList()
    }

    /** 全履歴を削除するが、同じ現在clipの自動再取込を防ぐ識別情報は残す。 */
    @Synchronized
    fun clearAll(currentIdentity: ClipboardHistoryIdentity?) {
        loadIfNeeded()
        items = emptyList()
        if (currentIdentity != null) lastHandledIdentity = currentIdentity
        writeFile()
    }

    /** 未固定項目の最も早い期限を返し、表示中タイマーの設定に使う。 */
    @Synchronized
    fun nextExpiryMillis(): Long? = readItems()
        .asSequence()
        .filterNot { it.pinned }
        .minOfOrNull { it.copiedAtMillis + ClipboardHistoryRules.LIFETIME_MILLIS }

    /** TSVを一度読み、壊れた行や未対応形式の本文を無視してメモリへ展開する。 */
    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val parsedItems = mutableListOf<ClipboardHistoryItem>()
        var parsedIdentity: ClipboardHistoryIdentity? = null
        try {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                val values = lines.toList()
                if (values.firstOrNull() == FILE_HEADER) {
                    values.drop(1).forEach { line ->
                        val fields = line.split('\t', limit = 5)
                        when (fields.firstOrNull()) {
                            "seen" -> if (fields.size == 4) {
                                val timestamp = fields[1].toLongOrNull()
                                val sensitive = fields[2].toBooleanStrictOrNull()
                                val mimeTypes = decode(fields[3])?.split('\u0000')
                                if (timestamp != null && sensitive != null && mimeTypes != null) {
                                    parsedIdentity = ClipboardHistoryIdentity(timestamp, mimeTypes, sensitive)
                                }
                            }
                            "item" -> if (fields.size == 4) {
                                val copiedAt = fields[1].toLongOrNull()
                                val pinned = fields[2].toBooleanStrictOrNull()
                                val text = decode(fields[3])
                                if (copiedAt != null && pinned != null && text != null) {
                                    parsedItems.add(ClipboardHistoryItem(text, copiedAt, pinned))
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 読み取りに失敗した場合は空の履歴から始め、本文をログへ出さない。
        } catch (_: IllegalArgumentException) {
            // 不正なBase64行だけを破棄し、他の項目は読み続ける。
        }
        items = parsedItems
        lastHandledIdentity = parsedIdentity
    }

    /** 履歴をAtomicFileで置き換え、途中終了で旧履歴を壊さないようにする。 */
    private fun writeFile() {
        var stream: FileOutputStream? = null
        try {
            val output = atomicFile.startWrite()
            stream = output
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(FILE_HEADER)
            writer.write('\n'.code)
            lastHandledIdentity?.let { identity ->
                writer.write("seen\t${identity.timestamp}\t${identity.clipboardSensitive}\t")
                writer.write(encode(identity.mimeTypes.joinToString("\u0000")))
                writer.write('\n'.code)
            }
            items.forEach { item ->
                writer.write("item\t${item.copiedAtMillis}\t${item.pinned}\t")
                writer.write(encode(item.text))
                writer.write('\n'.code)
            }
            writer.flush()
            atomicFile.finishWrite(output)
        } catch (_: IOException) {
            stream?.let(atomicFile::failWrite)
        }
    }

    /** 本文やMIMEの改行がTSVの列と衝突しないようUTF-8 Base64へ変換する。 */
    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    /** TSV内のBase64列を復号し、不正な列だけを無効にする。 */
    private fun decode(value: String): String? = try {
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        /** ファイル形式を更新した場合に旧形式を誤読しないための識別行。 */
        const val FILE_HEADER = "uzumi-clipboard-history-v1"
    }
}
