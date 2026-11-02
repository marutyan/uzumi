package dev.uzumi.ime

import android.content.Context
import android.content.SharedPreferences
import dev.uzumi.ime.keyboard.KeyboardPreferences
import dev.uzumi.ime.keyboard.ToolbarAction
import dev.uzumi.ime.keyboard.ToolbarPreferences
import kotlin.math.roundToInt

/**
 * 利用者が設定画面で切り替える値をSharedPreferencesへ保存する。設定画面が書き、IMEは入力開始ごとに読む。
 * IMEと設定画面は同じプロセスで動くため、次の入力開始から新しい値が反映される。
 */
object UzumiSettings {
    // 設定の保存ファイル名。アプリのbackupは無効なので、端末の外へは出ない。
    private const val PREFERENCES_NAME = "uzumi_settings"

    // ライブ変換のON/OFFを保存するキー。
    private const val KEY_LIVE_CONVERSION = "live_conversion_enabled"

    // QWERTYでローマ字をかなへ変換する設定を保存するキー。
    private const val KEY_ROMAJI_INPUT = "romaji_input_enabled"

    // ローマ字かな入力がONのとき、配列切替に英字QWERTY（ローマ字変換しない）を含めるかを保存するキー。
    private const val KEY_ROMAJI_ENGLISH_QWERTY = "romaji_english_qwerty"

    /** ライブ変換の既定値。確定操作を省ける入力から始められるようONにする。 */
    const val DEFAULT_LIVE_CONVERSION = true

    /** QWERTYのローマ字入力は、既存の英字入力を保つため既定ではOFFにする。 */
    const val DEFAULT_ROMAJI_INPUT = false

    /** ローマ字かな入力がONでも小文字の英語を打てるよう、英字QWERTYは既定で切替に含める。 */
    const val DEFAULT_ROMAJI_ENGLISH_QWERTY = true

    // キーボードの高さ（dp）、拡大表示、振動、キー音、削除ドラッグを保存するキー。
    private const val KEY_HEIGHT = "keyboard_height"
    private const val KEY_ROW_HEIGHT_DP = "keyboard_row_height_dp"
    private const val KEY_PREVIEW = "key_preview"
    private const val KEY_VIBRATION = "vibration"
    private const val KEY_SOUND = "key_sound"
    private const val KEY_DELETE_DRAG = "delete_drag"
    private const val KEY_TOOLBAR_ACTIONS = "toolbar_actions"
    private const val KEY_TOOLBAR_ROWS = "toolbar_rows"
    private const val KEY_CANDIDATE_ROWS = "candidate_rows"

    /** キーボードの見た目と反応の設定を読む。未設定の項目は既定値にする。 */
    fun keyboardPreferences(context: Context): KeyboardPreferences {
        val prefs = preferences(context)
        val defaults = KeyboardPreferences()
        return KeyboardPreferences(
            rowHeightDp = keyboardHeightDp(context).toFloat(),
            keyPreview = prefs.getBoolean(KEY_PREVIEW, defaults.keyPreview),
            vibration = prefs.getBoolean(KEY_VIBRATION, defaults.vibration),
            keySound = prefs.getBoolean(KEY_SOUND, defaults.keySound),
            deleteDrag = prefs.getBoolean(KEY_DELETE_DRAG, defaults.deleteDrag),
        )
    }

    /** 機能バーの選択順と、機能バー・候補バーの段数を読む。 */
    fun toolbarPreferences(context: Context): ToolbarPreferences {
        val prefs = preferences(context)
        val defaultActions = ToolbarPreferences.DEFAULT_ACTIONS.joinToString(",") { it.name }
        val actions = prefs.getString(KEY_TOOLBAR_ACTIONS, defaultActions)
            ?.split(',')
            ?.mapNotNull { name -> ToolbarAction.entries.firstOrNull { it.name == name } }
            ?: ToolbarPreferences.DEFAULT_ACTIONS
        return ToolbarPreferences(
            selectedActions = actions,
            toolbarRows = prefs.getInt(KEY_TOOLBAR_ROWS, ToolbarPreferences.DEFAULT_ROWS),
            candidateRows = prefs.getInt(KEY_CANDIDATE_ROWS, ToolbarPreferences.DEFAULT_ROWS),
        ).normalized()
    }

    /** 機能バーへ出す機能と順序を保存する。 */
    fun setToolbarActions(context: Context, actions: List<ToolbarAction>) {
        preferences(context).edit()
            .putString(KEY_TOOLBAR_ACTIONS, ToolbarPreferences.normalizeActions(actions).joinToString(",") { it.name })
            .apply()
    }

    /** 機能バーの段数を保存する。 */
    fun setToolbarRows(context: Context, rows: Int) {
        preferences(context).edit().putInt(KEY_TOOLBAR_ROWS, ToolbarPreferences.normalizeRows(rows)).apply()
    }

    /** 候補バーの段数を保存する。 */
    fun setCandidateRows(context: Context, rows: Int) {
        preferences(context).edit().putInt(KEY_CANDIDATE_ROWS, ToolbarPreferences.normalizeRows(rows)).apply()
    }

    /** 保存済みのdp値を読み、初回だけ従来の「低・中・高」を同じ高さへ引き継ぐ。 */
    fun keyboardHeightDp(context: Context): Int {
        val prefs = preferences(context)
        if (prefs.contains(KEY_ROW_HEIGHT_DP)) {
            val defaultHeight = KeyboardPreferences().rowHeightDp.roundToInt()
            return KeyboardPreferences.normalizeRowHeightDp(prefs.getInt(KEY_ROW_HEIGHT_DP, defaultHeight))
        }
        val legacyName = prefs.getString(KEY_HEIGHT, null)
        val legacyHeight = KeyboardPreferences.Height.entries.firstOrNull { it.name == legacyName }
            ?: KeyboardPreferences.Height.HIGH
        return legacyHeight.rowHeightDp.roundToInt()
    }

    /** キーボードの1行の高さをdpで保存する。 */
    fun setKeyboardHeightDp(context: Context, heightDp: Int) {
        preferences(context).edit()
            .putInt(KEY_ROW_HEIGHT_DP, KeyboardPreferences.normalizeRowHeightDp(heightDp))
            .apply()
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

    /** QWERTYのローマ字入力がONか。 */
    fun isRomajiInputEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ROMAJI_INPUT, DEFAULT_ROMAJI_INPUT)

    /** QWERTYのローマ字入力のON/OFFを保存する。 */
    fun setRomajiInputEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, KEY_ROMAJI_INPUT, enabled)

    /** ローマ字かな入力がONのとき、配列切替に英字QWERTYを含めるか。 */
    fun isRomajiEnglishQwertyIncluded(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ROMAJI_ENGLISH_QWERTY, DEFAULT_ROMAJI_ENGLISH_QWERTY)

    /** 配列切替に英字QWERTYを含めるかを保存する。 */
    fun setRomajiEnglishQwertyIncluded(context: Context, included: Boolean) =
        putBoolean(context, KEY_ROMAJI_ENGLISH_QWERTY, included)

    /** 設定の保存先。 */
    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
