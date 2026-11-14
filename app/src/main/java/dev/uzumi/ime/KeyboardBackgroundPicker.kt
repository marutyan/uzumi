package dev.uzumi.ime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.uzumi.ime.keyboard.CandidateBarViews
import dev.uzumi.ime.keyboard.KeyboardBackgroundImage
import kotlin.math.roundToInt

/**
 * きせかえの背景画像を端末の写真から選んで保存する手順。「キーボード」の画面ときせかえの画面の両方から使うため、ここに一つだけ置く。
 */
internal object KeyboardBackgroundPicker {
    /** 写真を選ぶ画面を開く。写真の読み取り権限を増やさず、利用者が選んだ1枚だけを受け取る。 */
    fun open(activity: Activity, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * 選んだ[uri]の写真を、UI thread外でキーボードの大きさに合わせて切り抜いて保存する。
     * 終わったらUI threadで、保存できなかったときは知らせてから[onDone]を呼ぶ。
     */
    fun save(activity: Activity, uri: Uri, onDone: () -> Unit) {
        val width = activity.resources.displayMetrics.widthPixels
        val height = (estimatedKeyboardHeightDp(activity) * activity.resources.displayMetrics.density).roundToInt()
        val context = activity.applicationContext
        Thread({
            val saved = KeyboardBackgroundImage.save(context, uri, width, height)
            if (saved) UzumiSettings.setBackgroundStamp(context, System.currentTimeMillis())
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (!saved) Toast.makeText(activity, R.string.kisekae_background_failed, Toast.LENGTH_LONG).show()
                onDone()
            }
        }, "uzumi-kisekae-image").start()
    }

    /** 背景画像を切り抜く大きさの目安。候補バー・機能バーの段数とキーの高さの設定から、キーボード全体の高さを見積もる。 */
    private fun estimatedKeyboardHeightDp(activity: Activity): Float {
        val toolbar = UzumiSettings.toolbarPreferences(activity)
        // 機能バーと候補バーは同じ領域に出るため、段数の大きい方が高さになる。
        val bars = maxOf(toolbar.toolbarRows, toolbar.candidateRows) * CandidateBarViews.BAR_HEIGHT_DP
        return bars + KANA_ROWS * UzumiSettings.keyboardHeightDp(activity) + PANEL_PADDING_DP
    }

    /** 背景画像の切り抜きの見積もりに使う、12キーの行数とキーボードの外周の余白（dp）。 */
    private const val KANA_ROWS = 4
    private const val PANEL_PADDING_DP = 4f
}
