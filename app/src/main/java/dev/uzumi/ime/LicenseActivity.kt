package dev.uzumi.ime

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.FileNotFoundException

/**
 * APKに同梱した第三者の著作権表示を原文のまま表示する。
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
     * 同梱した表示文書を読む。Mozc、llama.cpp、同梱フォントのライセンスを続けて表示する。
     * どれも無かった場合はnullを返す。
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
        /** Gradleが各ライセンス原本を取り込むassetsの場所。 */
        val NOTICE_ASSET_PATHS = listOf(
            "licenses/mozc-NOTICE.txt",
            "licenses/llama.cpp-NOTICE.txt",
            "licenses/zen-maru-gothic-OFL.txt",
            "licenses/yusei-magic-OFL.txt",
        )
    }
}
