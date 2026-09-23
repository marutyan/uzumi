package dev.uzumi.ime.conversion

import android.content.Context
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJni
import java.io.File
import java.io.FileNotFoundException

/**
 * 公式JNI class（MozcJni）を呼ぶ実装。APKにlibmozc.soが無いビルドではloadLibraryがfalseを返す。
 */
class JniMozcNativeBridge : MozcNativeBridge {
    override fun loadLibrary(): Boolean {
        return try {
            System.loadLibrary(LIBRARY_NAME)
            MozcJni.initialize()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    override fun onPostLoad(profileDirectory: String, dataFile: String): Boolean {
        return MozcJni.onPostLoad(profileDirectory, dataFile)
    }

    override fun evalCommand(command: ByteArray): ByteArray = MozcJni.evalCommand(command)

    override fun dataVersion(): String = MozcJni.getDataVersion().orEmpty()

    private companion object {
        /** System.loadLibraryへ渡すnative library名（libmozc.so）。 */
        const val LIBRARY_NAME = "mozc"
    }
}

/**
 * APKのassetsにあるMozc辞書を、nativeがpathで開けるfileへ展開する。
 * 展開済みの辞書はAPKの更新時刻が変わった場合だけ作り直す。
 */
class MozcDataInstaller(private val context: Context) {
    private val directory: File
        get() = File(context.noBackupFilesDir, "mozc")

    /** Mozcの学習履歴や設定を置くprofileの場所。バックアップ対象外の領域に置く。 */
    fun profileDirectory(): File = File(directory, "profile")

    /** 辞書fileを用意して返す。APKに辞書が無ければ古い展開物を消してnullを返す。 */
    fun install(): File? {
        val target = File(directory, DATA_FILE_NAME)
        val stamp = File(directory, "$DATA_FILE_NAME.stamp")
        val expectedStamp = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime
            .toString()
        val input = try {
            context.assets.open(ASSET_PATH)
        } catch (_: FileNotFoundException) {
            target.delete()
            stamp.delete()
            return null
        }
        input.use { source ->
            if (target.isFile && stamp.isFile && stamp.readText() == expectedStamp) return target
            directory.mkdirs()
            val temporary = File(directory, "$DATA_FILE_NAME.tmp")
            temporary.outputStream().use(source::copyTo)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
            stamp.writeText(expectedStamp)
        }
        return target
    }

    private companion object {
        /** Gradleがassetsへ取り込む辞書の場所。 */
        const val ASSET_PATH = "mozc/mozc.data"

        /** 展開先の辞書file名。 */
        const val DATA_FILE_NAME = "mozc.data"
    }
}
