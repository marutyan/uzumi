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

    /** 同梱した表示文書を読む。ビルド時にNOTICEが無かった場合はnullを返す。 */
    private fun readNotice(): String? {
        return try {
            assets.open(NOTICE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private companion object {
        /** Gradleがthird_party/mozc/NOTICE.txtを取り込むassetsの場所。 */
        const val NOTICE_ASSET_PATH = "licenses/mozc-NOTICE.txt"
    }
}
