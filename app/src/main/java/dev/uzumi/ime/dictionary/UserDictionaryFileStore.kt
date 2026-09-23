package dev.uzumi.ime.dictionary

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 保存ファイルの読込み結果。problemがtrueなら、読めなかった行または復号・読込みの失敗があった。 */
data class StoredDictionary(
    val entries: List<UserDictionaryEntry>,
    val problem: Boolean,
)

/**
 * ユーザー辞書を一つのTSVファイルとして保存する。一時ファイルへ書いてfsyncしてからrenameで置き換え、
 * 書込み途中で異常終了しても既存のファイルを壊さない。Android frameworkに依存せずJVMテストで検証できる。
 */
class UserDictionaryFileStore(private val file: File) {
    /** 保存ファイルを読む。ファイルがなければ空の辞書を返し、壊れた行は除いたうえでproblemを立てる。 */
    fun load(): StoredDictionary {
        if (!file.exists()) return StoredDictionary(emptyList(), problem = false)
        val text = try {
            UserDictionaryTsv.decodeUtf8(file.readBytes())
        } catch (_: IOException) {
            null
        } ?: return StoredDictionary(emptyList(), problem = true)
        val parsed = UserDictionaryTsv.parse(text)
        return StoredDictionary(parsed.entries, problem = parsed.failures.isNotEmpty())
    }

    /** 全項目を書き込み、成功した場合だけ保存ファイルを置き換える。失敗時はIOExceptionを投げ、既存ファイルを残す。 */
    @Throws(IOException::class)
    fun save(entries: List<UserDictionaryEntry>) {
        writeFileAtomically(file, UserDictionaryTsv.format(entries).toByteArray(Charsets.UTF_8))
    }

    /**
     * 読めない行があった保存ファイルを、上書きする前に別名で残す。
     * 自動で捨てた行を後から確認・復旧できるようにするためで、既存の退避ファイルは上書きしない。
     */
    @Throws(IOException::class)
    fun preserveUnreadableCopy(timestampMillis: Long): File? {
        if (!file.exists()) return null
        val copy = File(file.path + ".unreadable-$timestampMillis")
        Files.copy(file.toPath(), copy.toPath())
        return copy
    }
}

/**
 * bytesを一時ファイルへ書いてfsyncしてからrenameでfileを置き換える。書込み途中で異常終了しても既存のファイルを壊さない。
 * ユーザー辞書と学習キャッシュの保存で共通に使う。失敗時はIOExceptionを投げ、一時ファイルを消して既存ファイルを残す。
 */
@Throws(IOException::class)
fun writeFileAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    // 書込み中のデータ。renameの前に異常終了した場合だけ残り、次回の読込みでは使わない。
    val temporaryFile = File(file.path + ".tmp")
    try {
        FileOutputStream(temporaryFile).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(
            temporaryFile.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (error: IOException) {
        temporaryFile.delete()
        throw error
    }
}
