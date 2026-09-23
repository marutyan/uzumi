package dev.uzumi.ime.learning

import android.content.Context
import dev.uzumi.ime.conversion.MozcDataInstaller
import java.io.File

/**
 * アプリのプロセス内で一つの学習キャッシュを共有する入口。設定画面とIMEは同じプロセスで動くため、
 * 設定画面でのOFFや消去がIMEの参照へそのまま反映される。
 */
object LearningStores {
    // noBackupFilesDir配下の保存ファイル名。学習は端末内だけに置き、OSのバックアップへ含めない。
    private const val FILE_NAME = "learning.tsv"

    @Volatile
    private var instance: LearningStore? = null

    /** 学習キャッシュを返す。初回だけ保存ファイルを同期で読むため、UIスレッドで初めて呼ぶ前に別スレッドで開いておく。 */
    fun get(context: Context): LearningStore = instance ?: synchronized(this) {
        val appContext = context.applicationContext
        instance ?: LearningStore(
            file = File(appContext.noBackupFilesDir, FILE_NAME),
            clearEngineFiles = { MozcDataInstaller(appContext).clearLearningFiles() },
        ).also { instance = it }
    }
}
