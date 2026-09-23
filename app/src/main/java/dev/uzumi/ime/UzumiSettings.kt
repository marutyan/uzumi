package dev.uzumi.ime

import android.content.Context
import android.content.SharedPreferences
import dev.uzumi.ime.keyboard.KeyboardPreferences

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

    // キーボードの高さ（KeyboardPreferences.Heightの名前）、拡大表示、振動、キー音、削除ドラッグを保存するキー。
    private const val KEY_HEIGHT = "keyboard_height"
    private const val KEY_PREVIEW = "key_preview"
    private const val KEY_VIBRATION = "vibration"
    private const val KEY_SOUND = "key_sound"
    private const val KEY_DELETE_DRAG = "delete_drag"

    /** キーボードの見た目と反応の設定を読む。未設定の項目は既定値にする。 */
    fun keyboardPreferences(context: Context): KeyboardPreferences {
        val prefs = preferences(context)
        val defaults = KeyboardPreferences()
        return KeyboardPreferences(
            rowHeightDp = keyboardHeight(context).rowHeightDp,
            keyPreview = prefs.getBoolean(KEY_PREVIEW, defaults.keyPreview),
            vibration = prefs.getBoolean(KEY_VIBRATION, defaults.vibration),
            keySound = prefs.getBoolean(KEY_SOUND, defaults.keySound),
            deleteDrag = prefs.getBoolean(KEY_DELETE_DRAG, defaults.deleteDrag),
        )
    }

    /** キーボードの高さの段階。保存値が読めなければ既定の「高」にする。 */
    fun keyboardHeight(context: Context): KeyboardPreferences.Height {
        val name = preferences(context).getString(KEY_HEIGHT, null)
        return KeyboardPreferences.Height.entries.firstOrNull { it.name == name } ?: KeyboardPreferences.Height.HIGH
    }

    /** キーボードの高さの段階を保存する。 */
    fun setKeyboardHeight(context: Context, height: KeyboardPreferences.Height) {
        preferences(context).edit().putString(KEY_HEIGHT, height.name).apply()
    }

    /** 拡大表示を保存する。 */
    fun setKeyPreview(context: Context, enabled: Boolean) = putBoolean(context, KEY_PREVIEW, enabled)

    /** 振動を保存する。 */
    fun setVibration(context: Context, enabled: Boolean) = putBoolean(context, KEY_VIBRATION, enabled)

    /** キー音を保存する。 */
    fun setKeySound(context: Context, enabled: Boolean) = putBoolean(context, KEY_SOUND, enabled)

    /** 削除キーの左ドラッグを保存する。 */
    fun setDeleteDrag(context: Context, enabled: Boolean) = putBoolean(context, KEY_DELETE_DRAG, enabled)

    /** 真偽値の設定を一つ保存する。 */
    private fun putBoolean(context: Context, key: String, value: Boolean) {
        preferences(context).edit().putBoolean(key, value).apply()
    }

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
