package dev.uzumi.ime

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.FileNotFoundException

/**
 * APKに同梱した第三者の著作権表示（Mozc由来資産のNOTICE）を原文のまま表示する。
 * 配布条件として求められる表示を、アプリ内から利用者が読めるようにするための画面である。
 */
class LicenseActivity : Activity() {
    /** assetsの表示文書を読み、等幅の本文としてスクロール表示する。同梱されていないビルドではその旨を出す。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.licenses_title)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            text = readNotice() ?: getString(R.string.licenses_missing)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        val scrollView = ScrollView(this).apply { addView(body) }
        applySystemInsets(scrollView)
        setContentView(scrollView)
    }

    /**
     * 同梱した表示文書を読む。Mozcの表示に、ニューラル変換の生成物を取り込んだビルドではllama.cppの表示を続ける。
     * どちらも無かった場合はnullを返す。
     */
    private fun readNotice(): String? {
        val notices = NOTICE_ASSET_PATHS.mapNotNull { path ->
            try {
                assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: FileNotFoundException) {
                null
            }
        }
        return notices.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n\n")
    }

    private companion object {
        /** Gradleがthird_party/mozc/NOTICE.txtとthird_party/llama.cpp/NOTICE.txtを取り込むassetsの場所。 */
        val NOTICE_ASSET_PATHS = listOf("licenses/mozc-NOTICE.txt", "licenses/llama.cpp-NOTICE.txt")
    }
}
