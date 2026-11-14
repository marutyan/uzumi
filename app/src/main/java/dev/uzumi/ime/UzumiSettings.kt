package dev.uzumi.ime

import android.content.Context
import android.content.SharedPreferences
import dev.uzumi.ime.clipboard.ClipboardHistoryStores
import dev.uzumi.ime.keyboard.InputTuning
import dev.uzumi.ime.keyboard.KeyboardAppearance
import dev.uzumi.ime.keyboard.KeyboardBackgroundImage
import dev.uzumi.ime.keyboard.KeyboardPreferences
import dev.uzumi.ime.keyboard.ToolbarAction
import dev.uzumi.ime.keyboard.ToolbarLayout
import dev.uzumi.ime.keyboard.ToolbarPreferences
import dev.uzumi.ime.learning.LearningStores
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

    /** コピーした文字を履歴へ保存する設定の既定値。 */
    const val DEFAULT_CLIPBOARD_HISTORY_ENABLED = true

    // キーボードの高さ（dp）、拡大表示、振動、キー音、削除ドラッグを保存するキー。
    private const val KEY_HEIGHT = "keyboard_height"
    private const val KEY_ROW_HEIGHT_DP = "keyboard_row_height_dp"
    private const val KEY_PREVIEW = "key_preview"
    private const val KEY_VIBRATION = "vibration"
    private const val KEY_SOUND = "key_sound"
    private const val KEY_DELETE_DRAG = "delete_drag"
    // バーの中央の機能。導入前から使っている名前で、右端と置き場の保存名はこの導入で加えた。
    private const val KEY_TOOLBAR_ACTIONS = "toolbar_actions"
    private const val KEY_TOOLBAR_RIGHT = "toolbar_right"
    private const val KEY_TOOLBAR_TRAY = "toolbar_tray"
    private const val KEY_CANDIDATE_DIVIDERS = "candidate_dividers"
    private const val KEY_TOOLBAR_ROWS = "toolbar_rows"
    private const val KEY_CANDIDATE_ROWS = "candidate_rows"
    private const val KEY_TOOLBAR_ON_TOP = "toolbar_on_top"
    // クリップボード履歴の表示・保存を切り替えるキー。
    private const val KEY_CLIPBOARD_HISTORY_ENABLED = "clipboard_history_enabled"
    // 履歴を最後にONへ戻した時刻（ミリ秒）。これより前のコピーは保存しない。
    private const val KEY_CLIPBOARD_HISTORY_ENABLED_SINCE = "clipboard_history_enabled_since"
    // きせかえと入力の操作の各項目を保存するキーの接頭辞。項目名はKeyboardAppearanceとInputTuningの保存名を使う。
    private const val KEY_APPEARANCE_PREFIX = "kisekae_"
    private const val KEY_INPUT_TUNING_PREFIX = "input_tuning_"
    // 背景画像を保存した時刻。画像の本体はアプリの非公開領域（no_backup）に置き、ここには差し替えの判定用の時刻だけを持つ。
    private const val KEY_BACKGROUND_STAMP = "background_image_stamp"

    /** バックアップの各設定を読み、型検証・正規化・反映する定義。新しい設定はこの一覧へ追加する。 */
    private data class BackupSettingDescriptor(
        val key: String,
        val capture: (Context) -> BackupJsonValue,
        val normalize: (BackupJsonValue) -> BackupJsonValue?,
        val apply: (Context, BackupJsonValue) -> Unit,
    )

    /**
     * 書き出しと読み込みに含める利用者設定の一覧。学習・クリップボード履歴の有効状態は含め、
     * 学習履歴、非表示にした候補、クリップボード履歴の本文、debugモデルは個人の入力データまたは開発用のため含めない。
     */
    private val BACKUP_SETTINGS = listOf(
        BackupSettingDescriptor(
            key = "keyboardHeightDp",
            capture = { BackupJsonValue.NumberValue(keyboardHeightDp(it).toString()) },
            normalize = { value -> value.intOrNull()?.let { BackupJsonValue.NumberValue(KeyboardPreferences.normalizeRowHeightDp(it).toString()) } },
            apply = { context, value -> setKeyboardHeightDp(context, value.intOrNull()!!) },
        ),
        BackupSettingDescriptor(
            key = "liveConversion",
            capture = { BackupJsonValue.BooleanValue(isLiveConversionEnabled(it)) },
            normalize = { value -> (value as? BackupJsonValue.BooleanValue) },
            apply = { context, value -> setLiveConversionEnabled(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "learningEnabled",
            capture = { BackupJsonValue.BooleanValue(LearningStores.get(it).isEnabled) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> LearningStores.get(context).setEnabled(value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "deleteDrag",
            capture = { BackupJsonValue.BooleanValue(keyboardPreferences(it).deleteDrag) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setDeleteDrag(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "vibration",
            capture = { BackupJsonValue.BooleanValue(keyboardPreferences(it).vibration) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setVibration(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "keySound",
            capture = { BackupJsonValue.BooleanValue(keyboardPreferences(it).keySound) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setKeySound(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "keyPreview",
            capture = { BackupJsonValue.BooleanValue(keyboardPreferences(it).keyPreview) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setKeyPreview(context, value.booleanValue()!!) },
        ),
        // 三か所の並びを導入する前の形式の項目。古いバックアップでは、バーに出す機能の一覧（6個以上もありうる）だけを持つ。
        // アプリ更新時と同じ移行で三か所をまとめて作る。新しいバックアップでは、続く toolbarBar・toolbarRight・toolbarTray が上書きする。
        BackupSettingDescriptor(
            key = "toolbarActions",
            capture = { context -> BackupJsonValue.ArrayValue(toolbarPreferences(context).selectedActions.map { BackupJsonValue.StringValue(it.name) }) },
            normalize = normalize@{ value ->
                val actions = backupActions(value) ?: return@normalize null
                // 移行で6個目以降を置き場へ回すため、ここでは個数で切らない
                BackupJsonValue.ArrayValue(
                    actions.filter { it in ToolbarPreferences.AVAILABLE_ACTIONS }.distinct().map { BackupJsonValue.StringValue(it.name) },
                )
            },
            apply = { context, value -> applyLegacyToolbarActions(context, actionsOf(value)) },
        ),
        // バーの中央の並び（最大5個）。
        BackupSettingDescriptor(
            key = "toolbarBar",
            capture = { context -> BackupJsonValue.ArrayValue(toolbarPreferences(context).selectedActions.map { BackupJsonValue.StringValue(it.name) }) },
            normalize = normalize@{ value ->
                val actions = backupActions(value) ?: return@normalize null
                BackupJsonValue.ArrayValue(ToolbarPreferences.normalizeActions(actions).map { BackupJsonValue.StringValue(it.name) })
            },
            apply = { context, value -> setToolbarActions(context, actionsOf(value)) },
        ),
        // 右端の機能。空の文字列は右端なし。
        BackupSettingDescriptor(
            key = "toolbarRight",
            capture = { context -> BackupJsonValue.StringValue(toolbarPreferences(context).rightAction?.name.orEmpty()) },
            normalize = { value ->
                (value as? BackupJsonValue.StringValue)?.let {
                    BackupJsonValue.StringValue(ToolbarPreferences.decodeAction(it.value)?.name.orEmpty())
                }
            },
            apply = { context, value -> setToolbarRight(context, ToolbarPreferences.decodeAction(value.stringValue()!!)) },
        ),
        BackupSettingDescriptor(
            key = "toolbarTray",
            capture = { context -> BackupJsonValue.ArrayValue(toolbarPreferences(context).trayActions.map { BackupJsonValue.StringValue(it.name) }) },
            normalize = normalize@{ value ->
                val names = (value as? BackupJsonValue.ArrayValue)?.values?.map {
                    (it as? BackupJsonValue.StringValue)?.value ?: return@normalize null
                } ?: return@normalize null
                val actions = names.mapNotNull(ToolbarPreferences::decodeAction).filter { it in ToolbarPreferences.AVAILABLE_ACTIONS }.distinct()
                BackupJsonValue.ArrayValue(actions.map { BackupJsonValue.StringValue(it.name) })
            },
            apply = { context, value ->
                setToolbarTray(context, value.arrayValue()!!.mapNotNull { ToolbarPreferences.decodeAction(it.stringValue()!!) })
            },
        ),
        BackupSettingDescriptor(
            key = "candidateDividers",
            capture = { BackupJsonValue.BooleanValue(toolbarPreferences(it).candidateDividers) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setCandidateDividers(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "toolbarRows",
            capture = { BackupJsonValue.NumberValue(toolbarPreferences(it).toolbarRows.toString()) },
            normalize = { value -> value.intOrNull()?.let { BackupJsonValue.NumberValue(ToolbarPreferences.normalizeRows(it).toString()) } },
            apply = { context, value -> setToolbarRows(context, value.intOrNull()!!) },
        ),
        BackupSettingDescriptor(
            key = "candidateRows",
            capture = { BackupJsonValue.NumberValue(toolbarPreferences(it).candidateRows.toString()) },
            normalize = { value -> value.intOrNull()?.let { BackupJsonValue.NumberValue(ToolbarPreferences.normalizeRows(it).toString()) } },
            apply = { context, value -> setCandidateRows(context, value.intOrNull()!!) },
        ),
        BackupSettingDescriptor(
            key = "toolbarOnTop",
            capture = { BackupJsonValue.BooleanValue(toolbarPreferences(it).toolbarOnTop) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setToolbarOnTop(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "romajiInput",
            capture = { BackupJsonValue.BooleanValue(isRomajiInputEnabled(it)) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setRomajiInputEnabled(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "romajiEnglishQwerty",
            capture = { BackupJsonValue.BooleanValue(isRomajiEnglishQwertyIncluded(it)) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setRomajiEnglishQwertyIncluded(context, value.booleanValue()!!) },
        ),
        BackupSettingDescriptor(
            key = "clipboardHistory",
            capture = { BackupJsonValue.BooleanValue(isClipboardHistoryEnabled(it)) },
            normalize = { value -> value as? BackupJsonValue.BooleanValue },
            apply = { context, value -> setClipboardHistoryEnabled(context, value.booleanValue()!!) },
        ),
        // きせかえ（色・形・フリックの表示）。背景画像の本体は個人の写真のため含めず、明るさとぼかしの値だけを含める。
        BackupSettingDescriptor(
            key = "appearance",
            capture = { context -> stringObject(keyboardAppearance(context).toStorage()) },
            normalize = { value -> stringMapOrNull(value)?.let { stringObject(KeyboardAppearance.fromStorage(it).toStorage()) } },
            apply = { context, value -> setKeyboardAppearance(context, KeyboardAppearance.fromStorage(stringMapOrNull(value)!!)) },
        ),
        // 入力の操作（フリックの判定幅と判定のしかた、長押しの時間、トグルの区切り）。
        BackupSettingDescriptor(
            key = "inputTuning",
            capture = { context -> stringObject(inputTuning(context).toStorage()) },
            normalize = { value -> stringMapOrNull(value)?.let { stringObject(InputTuning.fromStorage(it).toStorage()) } },
            apply = { context, value -> setInputTuning(context, InputTuning.fromStorage(stringMapOrNull(value)!!)) },
        ),
    )

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
            appearance = keyboardAppearance(context),
            input = inputTuning(context),
        )
    }

    /** きせかえの設定を読む。背景画像は、保存時刻があり画像のファイルも残っている場合だけ使う。 */
    fun keyboardAppearance(context: Context): KeyboardAppearance {
        val prefs = preferences(context)
        val stamp = prefs.getLong(KEY_BACKGROUND_STAMP, 0L)
            .takeIf { it != 0L && KeyboardBackgroundImage.file(context).isFile } ?: 0L
        return KeyboardAppearance.fromStorage(readPrefixed(prefs, KEY_APPEARANCE_PREFIX, KeyboardAppearance.STORAGE_KEYS), stamp)
    }

    /** きせかえの設定を保存する。無くなった項目（テーマの色へ戻した文字の色など）は消す。 */
    fun setKeyboardAppearance(context: Context, appearance: KeyboardAppearance) {
        writePrefixed(preferences(context), KEY_APPEARANCE_PREFIX, KeyboardAppearance.STORAGE_KEYS, appearance.toStorage())
    }

    /** 背景画像を保存した時刻を記録する。0なら画像を外したことを示す。 */
    fun setBackgroundStamp(context: Context, stamp: Long) {
        preferences(context).edit().putLong(KEY_BACKGROUND_STAMP, stamp).apply()
    }

    /** 入力の操作の設定を読む。 */
    fun inputTuning(context: Context): InputTuning =
        InputTuning.fromStorage(readPrefixed(preferences(context), KEY_INPUT_TUNING_PREFIX, InputTuning.STORAGE_KEYS))

    /** 入力の操作の設定を保存する。 */
    fun setInputTuning(context: Context, tuning: InputTuning) {
        writePrefixed(preferences(context), KEY_INPUT_TUNING_PREFIX, InputTuning.STORAGE_KEYS, tuning.toStorage())
    }

    /** 接頭辞付きで保存した文字列の項目を、保存名だけの組として読む。 */
    private fun readPrefixed(prefs: SharedPreferences, prefix: String, keys: List<String>): Map<String, String> = buildMap {
        keys.forEach { key -> prefs.getString(prefix + key, null)?.let { put(key, it) } }
    }

    /** 保存名の組を接頭辞付きで保存し、組に無い既知の項目は消す。 */
    private fun writePrefixed(prefs: SharedPreferences, prefix: String, keys: List<String>, values: Map<String, String>) {
        val editor = prefs.edit()
        keys.forEach { key ->
            val value = values[key]
            if (value == null) editor.remove(prefix + key) else editor.putString(prefix + key, value)
        }
        editor.apply()
    }

    /** バーの並び（中央、右端、置き場）と、機能バー・候補バーの段数、候補の区切り線、機能バーの段を読む。導入前の保存値は移行して読む。 */
    fun toolbarPreferences(context: Context): ToolbarPreferences {
        val prefs = preferences(context)
        return ToolbarPreferences.fromStorage(
            barNames = prefs.getString(KEY_TOOLBAR_ACTIONS, null),
            rightName = prefs.getString(KEY_TOOLBAR_RIGHT, null),
            trayNames = prefs.getString(KEY_TOOLBAR_TRAY, null),
        ).copy(
            toolbarRows = prefs.getInt(KEY_TOOLBAR_ROWS, ToolbarPreferences.DEFAULT_ROWS),
            candidateRows = prefs.getInt(KEY_CANDIDATE_ROWS, ToolbarPreferences.DEFAULT_ROWS),
            candidateDividers = prefs.getBoolean(KEY_CANDIDATE_DIVIDERS, true),
            toolbarOnTop = prefs.getBoolean(KEY_TOOLBAR_ON_TOP, true),
        ).normalized()
    }

    /**
     * バーの中央に出す機能と順序を保存する。設定画面の「上段に表示する機能」から呼ぶ。
     * 右端の機能がバーへ移るときは右端を空にし、バーから外した機能は置き場へ戻る。
     */
    fun setToolbarActions(context: Context, actions: List<ToolbarAction>) {
        val current = toolbarPreferences(context)
        val bar = ToolbarPreferences.normalizeActions(actions)
        saveToolbarLayout(context, current.copy(selectedActions = bar, rightAction = current.rightAction?.takeIf { it !in bar }))
    }

    /** 導入前の形式の一覧[legacy]から、アプリ更新時と同じ移行で三か所の並びを作って保存する。段数と区切り線は今の値を保つ。 */
    private fun applyLegacyToolbarActions(context: Context, legacy: List<ToolbarAction>) {
        val migrated = ToolbarPreferences.migrateFromBarActions(legacy)
        saveToolbarLayout(context, toolbarPreferences(context).copy(
            selectedActions = migrated.selectedActions,
            rightAction = migrated.rightAction,
            trayActions = migrated.trayActions,
        ))
    }

    /** バックアップの機能名の配列を読む。文字列でない要素があれば値全体を拒否し、未知の名前は除く。 */
    private fun backupActions(value: BackupJsonValue): List<ToolbarAction>? {
        val values = (value as? BackupJsonValue.ArrayValue)?.values ?: return null
        val names = values.map { (it as? BackupJsonValue.StringValue)?.value ?: return null }
        return names.mapNotNull(ToolbarPreferences::decodeAction)
    }

    /** 正規化済みのバックアップ値から機能の一覧を取り出す。 */
    private fun actionsOf(value: BackupJsonValue): List<ToolbarAction> =
        value.arrayValue()!!.mapNotNull { ToolbarPreferences.decodeAction(it.stringValue()!!) }

    /** 右端の丸いボタンの機能を保存する。nullで空にする。バーに同じ機能があればバーから外す。 */
    fun setToolbarRight(context: Context, action: ToolbarAction?) {
        val current = toolbarPreferences(context)
        saveToolbarLayout(context, current.copy(selectedActions = current.selectedActions.filter { it != action }, rightAction = action))
    }

    /** 機能タイルの置き場の並びを保存する。バーと右端にある機能は置き場へ入れない。 */
    fun setToolbarTray(context: Context, actions: List<ToolbarAction>) {
        saveToolbarLayout(context, toolbarPreferences(context).copy(trayActions = actions))
    }

    /** バーの中央・右端・置き場の並びを、ドラッグでの並べ替えの結果としてまとめて保存する。 */
    fun setToolbarLayout(context: Context, layout: ToolbarLayout) {
        saveToolbarLayout(context, toolbarPreferences(context).withLayout(layout))
    }

    /** 整えた並びを三つの保存名へ書く。右端が空でも名前を書き、導入後の保存であると分かるようにする。 */
    private fun saveToolbarLayout(context: Context, value: ToolbarPreferences) {
        val normalized = value.normalized()
        preferences(context).edit()
            .putString(KEY_TOOLBAR_ACTIONS, ToolbarPreferences.encodeActions(normalized.selectedActions))
            .putString(KEY_TOOLBAR_RIGHT, normalized.rightAction?.name.orEmpty())
            .putString(KEY_TOOLBAR_TRAY, ToolbarPreferences.encodeActions(normalized.trayActions))
            .apply()
    }

    /** 候補の区切り線を引くかを保存する。 */
    fun setCandidateDividers(context: Context, enabled: Boolean) = putBoolean(context, KEY_CANDIDATE_DIVIDERS, enabled)

    /** 機能バーの段数を保存する。 */
    fun setToolbarRows(context: Context, rows: Int) {
        preferences(context).edit().putInt(KEY_TOOLBAR_ROWS, ToolbarPreferences.normalizeRows(rows)).apply()
    }

    /** 入力していない間の機能バーを上の段に置くか（falseなら下の段）を保存する。 */
    fun setToolbarOnTop(context: Context, onTop: Boolean) = putBoolean(context, KEY_TOOLBAR_ON_TOP, onTop)

    /** 候補バーの段数を保存する。 */
    fun setCandidateRows(context: Context, rows: Int) {
        preferences(context).edit().putInt(KEY_CANDIDATE_ROWS, ToolbarPreferences.normalizeRows(rows)).apply()
    }

    /** クリップボード履歴を表示・保存する設定を読む。 */
    fun isClipboardHistoryEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CLIPBOARD_HISTORY_ENABLED, DEFAULT_CLIPBOARD_HISTORY_ENABLED)

    /**
     * クリップボード履歴の表示・保存を切り替える。OFFでは固定を含む保存済みの履歴を消し、
     * OFFからONへ戻した時刻を残して、OFFの間のコピーを後から保存しないようにする。
     */
    fun setClipboardHistoryEnabled(context: Context, enabled: Boolean) {
        val wasEnabled = isClipboardHistoryEnabled(context)
        if (!enabled) ClipboardHistoryStores.get(context).clearAll(currentIdentity = null)
        val editor = preferences(context).edit().putBoolean(KEY_CLIPBOARD_HISTORY_ENABLED, enabled)
        if (enabled && !wasEnabled) editor.putLong(KEY_CLIPBOARD_HISTORY_ENABLED_SINCE, System.currentTimeMillis())
        editor.apply()
    }

    /** 履歴を最後にONへ戻した時刻を読む。OFFにしたことがなければ0。 */
    fun clipboardHistoryEnabledSinceMillis(context: Context): Long =
        preferences(context).getLong(KEY_CLIPBOARD_HISTORY_ENABLED_SINCE, 0L)

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

    /** 現在の設定をバックアップへ渡すJSON値の一覧として取り出す。 */
    fun captureBackupSettings(context: Context): Map<String, BackupJsonValue> =
        BACKUP_SETTINGS.associate { it.key to it.capture(context.applicationContext) }

    /** 既知設定だけを型検証して正規化し、未知の設定名を無視する。 */
    fun normalizeBackupSettings(values: Map<String, BackupJsonValue>): Map<String, BackupJsonValue> = buildMap {
        BACKUP_SETTINGS.forEach { descriptor ->
            if (!values.containsKey(descriptor.key)) return@forEach
            val normalized = descriptor.normalize(values.getValue(descriptor.key))
                ?: throw SettingsBackupException("${descriptor.key}の型が正しくありません")
            put(descriptor.key, normalized)
        }
    }

    /** すべての既知設定を先に検証してから保存するため、型不正で既存設定だけが部分更新されるのを防ぐ。 */
    fun applyBackupSettings(context: Context, values: Map<String, BackupJsonValue>) {
        val normalized = normalizeBackupSettings(values)
        val operations = BACKUP_SETTINGS.filter { it.key in normalized }
        operations.forEach { it.apply(context.applicationContext, normalized.getValue(it.key)) }
    }

    /** 文字列の組をバックアップのJSON objectにする。 */
    private fun stringObject(values: Map<String, String>): BackupJsonValue =
        BackupJsonValue.ObjectValue(values.mapValues { BackupJsonValue.StringValue(it.value) })

    /** JSON objectの値がすべて文字列なら、文字列の組として返す。型が違えばnull（読み込み全体を拒否する）。 */
    private fun stringMapOrNull(value: BackupJsonValue): Map<String, String>? {
        val values = (value as? BackupJsonValue.ObjectValue)?.values ?: return null
        return values.mapValues { (it.value as? BackupJsonValue.StringValue)?.value ?: return null }
    }

    /** JSON設定値から32bit整数を読む。小数や範囲外の数値は設定値として認めない。 */
    private fun BackupJsonValue.intOrNull(): Int? =
        (this as? BackupJsonValue.NumberValue)?.value?.toIntOrNull()

    /** JSON設定値から真偽値を読む。 */
    private fun BackupJsonValue.booleanValue(): Boolean? =
        (this as? BackupJsonValue.BooleanValue)?.value

    /** JSON設定値から配列を読む。型検証はバックアップ定義で先に行う。 */
    private fun BackupJsonValue.arrayValue(): List<BackupJsonValue>? =
        (this as? BackupJsonValue.ArrayValue)?.values

    /** JSON設定値から文字列を読む。型検証はバックアップ定義で先に行う。 */
    private fun BackupJsonValue.stringValue(): String? =
        (this as? BackupJsonValue.StringValue)?.value

    /** 設定の保存先。 */
    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
