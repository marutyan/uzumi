package dev.uzumi.ime

import android.content.Context
import android.content.SharedPreferences

/**
 * 利用者が設定画面で切り替える値をSharedPreferencesへ保存する。設定画面が書き、IMEは入力開始ごとに読む。
 * IMEと設定画面は同じプロセスで動くため、次の入力開始から新しい値が反映される。
 */
object UzumiSettings {
    // 設定の保存ファイル名。アプリのbackupは無効なので、端末の外へは出ない。
    private const val PREFERENCES_NAME = "uzumi_settings"

    // ライブ変換のON/OFFを保存するキー。
    private const val KEY_LIVE_CONVERSION = "live_conversion_enabled"

    /** ライブ変換の既定値。確定操作を省ける入力から始められるようONにする。 */
    const val DEFAULT_LIVE_CONVERSION = true

    /** ライブ変換がONか。 */
    fun isLiveConversionEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_LIVE_CONVERSION, DEFAULT_LIVE_CONVERSION)
    }

    /** ライブ変換のON/OFFを保存する。 */
    fun setLiveConversionEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_LIVE_CONVERSION, enabled).apply()
    }

    /** 設定の保存先。 */
    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
