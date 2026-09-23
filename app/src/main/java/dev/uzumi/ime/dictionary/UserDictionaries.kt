package dev.uzumi.ime.dictionary

import android.content.Context
import java.io.File

/**
 * アプリのプロセス内で一つのユーザー辞書を共有する入口。管理画面とIMEは同じプロセスで動くため、
 * 管理画面での変更がIMEの参照へそのまま反映される。
 */
object UserDictionaries {
    // noBackupFilesDir配下の保存ファイル名。この領域はOSのバックアップ対象外で、端末間の移行は利用者のexportで行う。
    private const val FILE_NAME = "user_dictionary.tsv"

    @Volatile
    private var instance: UserDictionaryRepository? = null

    /** 辞書を返す。初回だけ保存ファイルを同期で読むため、IMEのUIスレッドで初めて呼ぶ前に別スレッドで開いておく。 */
    fun get(context: Context): UserDictionaryRepository = instance ?: synchronized(this) {
        instance ?: UserDictionaryRepository.open(
            UserDictionaryFileStore(File(context.applicationContext.noBackupFilesDir, FILE_NAME)),
        ).also { instance = it }
    }
}
