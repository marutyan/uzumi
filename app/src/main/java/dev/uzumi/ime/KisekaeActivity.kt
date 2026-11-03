package dev.uzumi.ime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.uzumi.ime.keyboard.COLOR_CHIP_PALETTE
import dev.uzumi.ime.keyboard.CandidateBarViews
import dev.uzumi.ime.keyboard.ColorRole
import dev.uzumi.ime.keyboard.FlickBubbleShape
import dev.uzumi.ime.keyboard.FlickDirection
import dev.uzumi.ime.keyboard.FlickDisplayMode
import dev.uzumi.ime.keyboard.FlickJudge
import dev.uzumi.ime.keyboard.InputTuning
import dev.uzumi.ime.keyboard.KeyFont
import dev.uzumi.ime.keyboard.KeyShape
import dev.uzumi.ime.keyboard.KeyboardAction
import dev.uzumi.ime.keyboard.KeyboardAppearance
import dev.uzumi.ime.keyboard.KeyboardBackgroundDrawable
import dev.uzumi.ime.keyboard.KeyboardBackgroundImage
import dev.uzumi.ime.keyboard.KeyboardColors
import dev.uzumi.ime.keyboard.KeyboardLayoutData
import dev.uzumi.ime.keyboard.KeyboardPanel
import dev.uzumi.ime.keyboard.ThemeColorMath
import dev.uzumi.ime.keyboard.ThemeMode
import dev.uzumi.ime.keyboard.ThemePreset
import kotlin.math.roundToInt

/**
 * きせかえの設定画面。上に実際のキーボードの部品（候補バーと12キー）でプレビューを置き、下に背景画像、テーマと色、キー、
 * フリックの表示、入力の操作の項目を並べる。値は変えるとすぐUzumiSettingsへ保存し、プレビューを作り直す。
 * 設定画面の「きせかえ」から開く。キーボードには次に表示したときから反映される。
 */
class KisekaeActivity : Activity() {
    private val density: Float
        get() = resources.displayMetrics.density

    private lateinit var appearance: KeyboardAppearance
    private lateinit var tuning: InputTuning
    private lateinit var previewFrame: FrameLayout
    private lateinit var echo: TextView
    private lateinit var settingsScroll: ScrollView

    // プレビューで入力した文字。フリックの吹き出しの文字と実際に入る文字が同じかを確かめられるようにする。
    private val echoText = StringBuilder()

    // 色を選ぶ役割と、色の面で選んだ色相・鮮やかさ。灰色を選んでも色相を失わないよう、面の位置を別に持つ。
    private var role = ColorRole.ACCENT
    private var mapHue = 0f
    private var mapSaturation = 0f

    // スライダーを動かしている間にプレビューを作り直しすぎないよう、少し待ってからまとめて作り直す。
    private val handler = Handler(Looper.getMainLooper())
    private val previewRefresh = Runnable { buildPreview() }

    /** 保存済みの設定を読み、プレビューと設定項目を組み立てる。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.kisekae_title)
        appearance = UzumiSettings.keyboardAppearance(this)
        tuning = UzumiSettings.inputTuning(this)
        syncMapFromRole()

        previewFrame = FrameLayout(this).apply {
            contentDescription = getString(R.string.kisekae_preview_description)
        }
        echo = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), 0, px(16), 0)
            minHeight = px(40)
        }
        settingsScroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@KisekaeActivity).apply {
                text = getString(R.string.kisekae_preview_title)
                textSize = 13f
                alpha = DESCRIPTION_ALPHA
                setPadding(px(16), px(8), px(16), px(4))
            })
            addView(echo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(previewFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(settingsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        applySystemInsets(root)
        setContentView(root)
        updateEcho()
        buildPreview()
        buildSettings()
    }

    override fun onDestroy() {
        handler.removeCallbacks(previewRefresh)
        super.onDestroy()
    }

    /** 写真の選択から戻ったら、UI thread外で切り抜いて保存し、終わったらプレビューを作り直す。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKGROUND_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val width = resources.displayMetrics.widthPixels
        val height = (estimatedKeyboardHeightDp() * density).roundToInt()
        Thread({
            val saved = KeyboardBackgroundImage.save(applicationContext, uri, width, height)
            if (saved) UzumiSettings.setBackgroundStamp(applicationContext, System.currentTimeMillis())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!saved) Toast.makeText(this, R.string.kisekae_background_failed, Toast.LENGTH_LONG).show()
                reload()
            }
        }, "uzumi-kisekae-image").start()
    }

    /** 背景画像を切り抜く大きさの目安。候補バー・機能バーの段数とキーの高さの設定から、キーボード全体の高さを見積もる。 */
    private fun estimatedKeyboardHeightDp(): Float {
        val toolbar = UzumiSettings.toolbarPreferences(this)
        val bars = (toolbar.toolbarRows + toolbar.candidateRows) * CandidateBarViews.BAR_HEIGHT_DP
        return bars + KANA_ROWS * UzumiSettings.keyboardHeightDp(this) + PANEL_PADDING_DP
    }

    /** 保存し直した設定を読み直し、プレビューと設定項目を作り直す。スクロール位置は保つ。 */
    private fun reload() {
        appearance = UzumiSettings.keyboardAppearance(this)
        tuning = UzumiSettings.inputTuning(this)
        syncMapFromRole()
        buildPreview()
        val scrollY = settingsScroll.scrollY
        buildSettings()
        settingsScroll.post { settingsScroll.scrollTo(0, scrollY) }
    }

    /** きせかえの設定を変えて保存する。[rebuild]なら設定項目も作り直す（選択中の表示が複数の項目にまたがる変更）。 */
    private fun updateAppearance(rebuild: Boolean = false, change: (KeyboardAppearance) -> KeyboardAppearance) {
        appearance = change(appearance)
        UzumiSettings.setKeyboardAppearance(this, appearance)
        if (rebuild) reload() else schedulePreview()
    }

    /** 入力の操作を変えて保存する。 */
    private fun updateTuning(rebuild: Boolean = false, change: (InputTuning) -> InputTuning) {
        tuning = change(tuning)
        UzumiSettings.setInputTuning(this, tuning)
        if (rebuild) reload() else schedulePreview()
    }

    /** 少し待ってからプレビューを作り直す。 */
    private fun schedulePreview() {
        handler.removeCallbacks(previewRefresh)
        handler.postDelayed(previewRefresh, PREVIEW_DELAY_MS)
    }

    /**
     * 実際のキーボードと同じ部品で、候補バーと12キーのプレビューを作る。背景画像と配色は保存済みの設定から読み、
     * 押すとフリックの表示と判定を試せる。高さを抑えるため、キーの高さはタップ領域の下限にする。
     */
    private fun buildPreview() {
        handler.removeCallbacks(previewRefresh)
        previewFrame.removeAllViews()
        val preferences = UzumiSettings.keyboardPreferences(this).copy(rowHeightDp = PREVIEW_ROW_HEIGHT_DP)
        val views = CandidateBarViews(this)
        val image = KeyboardBackgroundImage.load(this, appearance.backgroundStamp, appearance.backgroundBlur)
        previewFrame.background = if (image != null) {
            KeyboardBackgroundDrawable(image, appearance.backgroundBrightness, views.colors.keyboardBackground)
        } else {
            GradientDrawable().apply { setColor(views.colors.keyboardBackground) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val barAlpha = if (image != null) maxOf(MIN_BAR_ALPHA_WITH_IMAGE, appearance.keyOpacity / 100f) else 1f
            setBackgroundColor(ThemeColorMath.withAlpha(views.colors.bar, barAlpha))
            PREVIEW_PREDICTIONS.forEach { addView(views.item(it, selected = false, prediction = true) {}) }
            addView(views.divider())
            PREVIEW_CANDIDATES.forEachIndexed { index, value -> addView(views.item(value, selected = index == 0) {}) }
        }
        val panel = KeyboardPanel(this, ::onPreviewAction, preferences = preferences)
        previewFrame.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@KisekaeActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(CandidateBarViews.BAR_HEIGHT_DP.toInt())))
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
    }

    /** プレビューのキーで入力した文字を、入力欄の代わりに上の行へ出す。 */
    private fun onPreviewAction(action: KeyboardAction) {
        when (action) {
            is KeyboardAction.KanaTap -> echoText.append(KeyboardLayoutData.getKanaChar(action.type, FlickDirection.CENTER))
            is KeyboardAction.Text -> echoText.append(action.value)
            KeyboardAction.Delete -> if (echoText.isNotEmpty()) echoText.setLength(echoText.length - 1)
            else -> return
        }
        if (echoText.length > ECHO_MAX_CHARS) echoText.delete(0, echoText.length - ECHO_MAX_CHARS)
        updateEcho()
    }

    /** 入力の確認の行を更新する。 */
    private fun updateEcho() {
        echo.text = if (echoText.isEmpty()) getString(R.string.kisekae_preview_echo_empty) else echoText.toString()
        echo.alpha = if (echoText.isEmpty()) DESCRIPTION_ALPHA else 1f
    }

    /** 設定項目を作り直す。 */
    private fun buildSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(4), px(16), px(24))
        }
        addBackgroundSection(content)
        addThemeSection(content)
        addKeySection(content)
        addFlickSection(content)
        addInputSection(content)
        settingsScroll.removeAllViews()
        settingsScroll.addView(content)
    }

    /** 背景画像の選択・解除と、明るさ・ぼかし。 */
    private fun addBackgroundSection(content: LinearLayout) {
        content.addView(section(R.string.kisekae_section_background))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button(R.string.kisekae_background_pick) {
                // 写真の読み取り権限を増やさず、利用者が選んだ1枚だけを受け取る
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, REQUEST_BACKGROUND_IMAGE)
            })
            addView(button(R.string.kisekae_background_remove) {
                KeyboardBackgroundImage.remove(this@KisekaeActivity)
                UzumiSettings.setBackgroundStamp(this@KisekaeActivity, 0L)
                reload()
            }.apply { isEnabled = appearance.hasBackgroundImage })
            addView(TextView(this@KisekaeActivity).apply {
                text = getString(if (appearance.hasBackgroundImage) R.string.kisekae_background_set else R.string.kisekae_background_none)
                setPadding(px(8), 0, 0, 0)
            })
        })
        content.addView(slider(R.string.kisekae_background_brightness, KeyboardAppearance.BACKGROUND_BRIGHTNESS_RANGE, 5,
            appearance.backgroundBrightness, R.string.kisekae_value_percent) { value -> updateAppearance { it.copy(backgroundBrightness = value) } })
        content.addView(slider(R.string.kisekae_background_blur, KeyboardAppearance.BACKGROUND_BLUR_RANGE, 1,
            appearance.backgroundBlur, R.string.kisekae_value_plain) { value -> updateAppearance { it.copy(backgroundBlur = value) } })
        content.addView(note(R.string.kisekae_background_note))
    }

    /** プリセットのテーマ、明るさの選び方、役割ごとの色の選択。 */
    private fun addThemeSection(content: LinearLayout) {
        content.addView(section(R.string.kisekae_section_theme))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ThemePreset.entries.forEach { preset -> tiles.addView(themeTile(preset)) }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tiles)
        })
        content.addView(caption(R.string.kisekae_mode))
        content.addView(choice(
            listOf(ThemeMode.SYSTEM to R.string.kisekae_mode_system, ThemeMode.LIGHT to R.string.kisekae_mode_light,
                ThemeMode.DARK to R.string.kisekae_mode_dark),
            appearance.mode,
        ) { mode -> updateAppearance(rebuild = true) { it.copy(mode = mode) } })

        content.addView(caption(R.string.kisekae_color_pick))
        content.addView(choice(
            listOf(ColorRole.BACKGROUND to R.string.kisekae_role_background, ColorRole.KEY to R.string.kisekae_role_key,
                ColorRole.FUNCTION to R.string.kisekae_role_function, ColorRole.ACCENT to R.string.kisekae_role_accent,
                ColorRole.TEXT to R.string.kisekae_role_text, ColorRole.FLICK to R.string.kisekae_role_flick),
            role,
        ) { selected ->
            role = selected
            syncMapFromRole()
            reload()
        })
        addColorEditor(content)
        content.addView(button(R.string.kisekae_color_reset) {
            updateAppearance(rebuild = true) {
                it.copy(preset = ThemePreset.STANDARD, customLight = null, customDark = null, textColor = null, flickColor = null)
            }
        })
    }

    /** 色相×鮮やかさの面、明るさのスライダー、#RRGGBBの入力で、選んだ役割の色を決める。 */
    private fun addColorEditor(content: LinearLayout) {
        val current = roleColor(role)
        val lightness = ThemeColorMath.toHsl(current)[2].roundToInt().coerceIn(LIGHTNESS_RANGE.first, LIGHTNESS_RANGE.last)
        val hexInput = EditText(this).apply {
            setText(ThemeColorMath.toHex(current))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            contentDescription = getString(R.string.kisekae_color_hex_description)
        }
        val swatch = View(this).apply { background = swatchDrawable(current, selected = false) }
        lateinit var map: ColorMapView
        var mapLightness = lightness

        /** 選んだ色を保存し、見本と数値の表示を合わせる。 */
        fun applyColor(color: Int) {
            setRoleColor(role, color)
            swatch.background = swatchDrawable(color, selected = false)
            hexInput.setText(ThemeColorMath.toHex(color))
        }
        // 指を離したら、テーマの札（カスタムへの切り替え）などの選択中の表示を作り直す
        map = ColorMapView(this, mapLightness.toFloat(), mapHue, mapSaturation, onRelease = ::reload) { hue, saturation ->
            mapHue = hue
            mapSaturation = saturation
            applyColor(ThemeColorMath.fromHsl(hue, saturation, mapLightness.toFloat()))
        }.apply { contentDescription = getString(R.string.kisekae_color_map_description) }
        content.addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(COLOR_MAP_HEIGHT_DP)).apply {
            topMargin = px(4)
        })
        content.addView(slider(R.string.kisekae_color_lightness, LIGHTNESS_RANGE, 1, lightness, R.string.kisekae_value_percent) { value ->
            mapLightness = value
            map.setLightness(value.toFloat())
            applyColor(ThemeColorMath.fromHsl(mapHue, mapSaturation, value.toFloat()))
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(px(32), px(32)).apply { marginEnd = px(8) })
            addView(hexInput, LinearLayout.LayoutParams(0, px(MIN_TOUCH_DP), 1f))
            addView(button(R.string.kisekae_color_hex_apply) {
                val color = ThemeColorMath.parseHex(hexInput.text.toString().trim().uppercase())
                if (color == null) {
                    Toast.makeText(this@KisekaeActivity, R.string.kisekae_color_hex_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    setRoleColor(role, color)
                    syncMapFromRole()
                    reload()
                }
            })
        })
    }

    /** キーの形、不透明度、境界線、書体、文字の大きさ、文字の色。 */
    private fun addKeySection(content: LinearLayout) {
        content.addView(section(R.string.kisekae_section_key))
        content.addView(caption(R.string.kisekae_key_shape))
        content.addView(choice(
            listOf(KeyShape.ROUNDED to R.string.kisekae_shape_rounded, KeyShape.SQUARE to R.string.kisekae_shape_square,
                KeyShape.PILL to R.string.kisekae_shape_pill, KeyShape.FLAT to R.string.kisekae_shape_flat),
            appearance.keyShape,
        ) { shape -> updateAppearance { it.copy(keyShape = shape) } })
        content.addView(slider(R.string.kisekae_key_opacity, KeyboardAppearance.KEY_OPACITY_RANGE, 5,
            appearance.keyOpacity, R.string.kisekae_value_percent) { value -> updateAppearance { it.copy(keyOpacity = value) } })
        content.addView(note(R.string.kisekae_key_opacity_note))
        content.addView(switchRow(R.string.kisekae_key_border, appearance.keyBorder) { value ->
            updateAppearance { it.copy(keyBorder = value) }
        })
        content.addView(caption(R.string.kisekae_key_font))
        content.addView(choice(
            listOf(
                KeyFont.GOTHIC to R.string.kisekae_font_gothic,
                KeyFont.MINCHO to R.string.kisekae_font_mincho,
                KeyFont.ZEN_MARU_GOTHIC to R.string.kisekae_font_zen_maru_gothic,
                KeyFont.YUSEI_MAGIC to R.string.kisekae_font_yusei_magic,
            ),
            appearance.keyFont,
        ) { font -> updateAppearance { it.copy(keyFont = font) } })
        content.addView(slider(R.string.kisekae_key_text_scale, KeyboardAppearance.KEY_TEXT_SCALE_RANGE, 5,
            appearance.keyTextScale, R.string.kisekae_value_percent) { value -> updateAppearance { it.copy(keyTextScale = value) } })
        content.addView(caption(R.string.kisekae_key_text_color))
        content.addView(colorChips(appearance.textColor) { color -> updateAppearance(rebuild = true) { it.copy(textColor = color) } })
    }

    /** フリックの表示の方式、吹き出しの形・色・大きさ・不透明度、中央の拡大表示、方向の文字の常時表示。 */
    private fun addFlickSection(content: LinearLayout) {
        content.addView(section(R.string.kisekae_section_flick))
        content.addView(caption(R.string.kisekae_flick_mode))
        content.addView(choice(
            listOf(FlickDisplayMode.BUBBLE to R.string.kisekae_flick_bubble, FlickDisplayMode.CROSS to R.string.kisekae_flick_cross,
                FlickDisplayMode.IN_KEY to R.string.kisekae_flick_in_key, FlickDisplayMode.NONE to R.string.kisekae_flick_none),
            appearance.flickMode,
        ) { mode -> updateAppearance { it.copy(flickMode = mode) } })
        content.addView(caption(R.string.kisekae_flick_shape))
        content.addView(choice(
            listOf(FlickBubbleShape.PENTAGON to R.string.kisekae_bubble_pentagon, FlickBubbleShape.ROUNDED to R.string.kisekae_bubble_rounded,
                FlickBubbleShape.CIRCLE to R.string.kisekae_bubble_circle, FlickBubbleShape.DROP to R.string.kisekae_bubble_drop,
                FlickBubbleShape.HEART to R.string.kisekae_bubble_heart, FlickBubbleShape.STAR to R.string.kisekae_bubble_star,
                FlickBubbleShape.CLOUD to R.string.kisekae_bubble_cloud),
            appearance.flickShape,
        ) { shape -> updateAppearance { it.copy(flickShape = shape) } })
        content.addView(caption(R.string.kisekae_flick_color))
        content.addView(colorChips(appearance.flickColor) { color -> updateAppearance(rebuild = true) { it.copy(flickColor = color) } })
        content.addView(slider(R.string.kisekae_flick_size, KeyboardAppearance.FLICK_SIZE_RANGE, 5,
            appearance.flickSize, R.string.kisekae_value_percent) { value -> updateAppearance { it.copy(flickSize = value) } })
        content.addView(slider(R.string.kisekae_flick_opacity, KeyboardAppearance.FLICK_OPACITY_RANGE, 5,
            appearance.flickOpacity, R.string.kisekae_value_percent) { value -> updateAppearance { it.copy(flickOpacity = value) } })
        // 中央の文字の拡大表示は、設定画面の「押したキーの拡大表示」と同じ設定を切り替える
        content.addView(switchRow(R.string.kisekae_flick_center, UzumiSettings.keyboardPreferences(this).keyPreview) { value ->
            UzumiSettings.setKeyPreview(this, value)
            schedulePreview()
        })
        content.addView(switchRow(R.string.kisekae_flick_guide, appearance.flickGuide) { value ->
            updateAppearance { it.copy(flickGuide = value) }
        })
    }

    /** フリックの判定幅と判定のしかた、長押しの時間、トグルの区切り。 */
    private fun addInputSection(content: LinearLayout) {
        content.addView(section(R.string.kisekae_section_input))
        content.addView(slider(R.string.kisekae_input_threshold, InputTuning.FLICK_THRESHOLD_RANGE, 1,
            tuning.flickThresholdDp, R.string.kisekae_value_dp) { value -> updateTuning { it.copy(flickThresholdDp = value) } })
        content.addView(caption(R.string.kisekae_input_judge))
        content.addView(choice(
            listOf(FlickJudge.CIRCLE to R.string.kisekae_judge_circle, FlickJudge.AXIS to R.string.kisekae_judge_axis),
            tuning.flickJudge,
        ) { judge -> updateTuning { it.copy(flickJudge = judge) } })
        val systemLongPress = tuning.longPressMillis == 0
        content.addView(switchRow(R.string.kisekae_input_long_press_system, systemLongPress) { useSystem ->
            updateTuning(rebuild = true) {
                it.copy(longPressMillis = if (useSystem) 0 else deviceLongPressMillis())
            }
        })
        content.addView(slider(R.string.kisekae_input_long_press, InputTuning.LONG_PRESS_RANGE, 50,
            if (systemLongPress) deviceLongPressMillis() else tuning.longPressMillis, R.string.kisekae_value_ms,
        ) { value -> updateTuning { it.copy(longPressMillis = value) } }.apply { setEnabledDeep(!systemLongPress) })
        content.addView(slider(R.string.kisekae_input_toggle, InputTuning.TOGGLE_WINDOW_RANGE, 100,
            tuning.toggleWindowMillis, R.string.kisekae_value_ms) { value -> updateTuning { it.copy(toggleWindowMillis = value) } })
        content.addView(note(R.string.kisekae_input_note))
        content.addView(button(R.string.kisekae_input_simeji) { updateTuning(rebuild = true) { it.simejiLike() } })
        content.addView(button(R.string.kisekae_input_reset) { updateTuning(rebuild = true) { InputTuning() } })
    }

    /** 端末の長押し時間を、スライダーの刻み（50ms）と範囲へ収めた値。 */
    private fun deviceLongPressMillis(): Int {
        val range = InputTuning.LONG_PRESS_RANGE
        return ((ViewConfiguration.getLongPressTimeout() / 50f).roundToInt() * 50).coerceIn(range.first, range.last)
    }

    /** 今の明るさの選び方で、ダークの配色を描いているか。 */
    private fun isDarkNow(): Boolean = when (appearance.mode) {
        ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** 今の配色での役割の色。 */
    private fun roleColor(target: ColorRole): Int {
        val colors = KeyboardColors.from(this, appearance)
        return when (target) {
            ColorRole.BACKGROUND -> colors.keyboardBackground
            ColorRole.KEY -> colors.keyBackground
            ColorRole.FUNCTION -> colors.functionBackground
            ColorRole.ACCENT -> colors.accent
            ColorRole.TEXT -> colors.text
            ColorRole.FLICK -> colors.flick
        }
    }

    /** 色の面の位置を、選んだ役割の今の色に合わせる。 */
    private fun syncMapFromRole() {
        val (hue, saturation) = ThemeColorMath.toHsl(roleColor(role))
        mapHue = hue
        mapSaturation = saturation
    }

    /**
     * 役割の色を保存する。文字とフリックはテーマとは別の指定として持ち（試作と同じ）、
     * ほかの役割は選んでいるテーマの色をカスタムへ写したうえで、今の明るさの側だけを変える。
     */
    private fun setRoleColor(target: ColorRole, color: Int) {
        updateAppearance { current ->
            when (target) {
                ColorRole.TEXT -> current.copy(textColor = color)
                ColorRole.FLICK -> current.copy(flickColor = color)
                else -> {
                    val custom = current.toCustom()
                    if (isDarkNow()) {
                        custom.copy(customDark = requireNotNull(custom.customDark).with(target, color))
                    } else {
                        custom.copy(customLight = requireNotNull(custom.customLight).with(target, color))
                    }
                }
            }
        }
    }

    /** プリセットのテーマを選ぶ札。ライトの地色・キー・機能キーと、ダークのキー、強調色の見本を並べる。 */
    private fun themeTile(preset: ThemePreset): View {
        val selected = appearance.preset == preset
        val name = getString(themeName(preset))
        val light = preset.light
        val dark = preset.dark
        val custom = appearance.customLight
        val swatchColors = when {
            light != null && dark != null -> listOf(light.background, light.key, dark.key, light.accent)
            custom != null -> listOf(custom.background, custom.key, custom.function, custom.accent)
            else -> CUSTOM_SWATCH
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(6), px(6), px(6), px(6))
            minimumWidth = px(MIN_TOUCH_DP)
            minimumHeight = px(MIN_TOUCH_DP)
            background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat()
                setStroke(px(if (selected) 2 else 1), if (selected) getColor(R.color.kb_accent) else getColor(R.color.kb_divider))
            }
            addView(LinearLayout(this@KisekaeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                swatchColors.forEach { color ->
                    addView(View(this@KisekaeActivity).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = px(3).toFloat()
                            setColor(color)
                            setStroke(1, getColor(R.color.kb_divider))
                        }
                    }, LinearLayout.LayoutParams(px(16), px(28)).apply { marginEnd = px(2) })
                }
            })
            addView(TextView(this@KisekaeActivity).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, px(4), 0, 0)
            })
            contentDescription = name
            stateDescription = if (selected) getString(R.string.kisekae_selected) else null
            isSelected = selected
            isClickable = true
            isFocusable = true
            setOnClickListener {
                updateAppearance(rebuild = true) { current ->
                    if (preset == ThemePreset.CUSTOM) current.toCustom() else current.copy(preset = preset)
                }
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = px(8)
                topMargin = px(4)
                bottomMargin = px(4)
            }
        }
    }

    /** テーマの表示名。 */
    private fun themeName(preset: ThemePreset): Int = when (preset) {
        ThemePreset.STANDARD -> R.string.kisekae_theme_standard
        ThemePreset.WASHI -> R.string.kisekae_theme_washi
        ThemePreset.AI -> R.string.kisekae_theme_ai
        ThemePreset.WAKATAKE -> R.string.kisekae_theme_wakatake
        ThemePreset.SAKURA -> R.string.kisekae_theme_sakura
        ThemePreset.SUMI -> R.string.kisekae_theme_sumi
        ThemePreset.CUSTOM -> R.string.kisekae_theme_custom
    }

    /** 「テーマの色」と15色のパレットから色を選ぶ丸い札の列。[selected]がnullならテーマの色を選択中として示す。 */
    private fun colorChips(selected: Int?, onSelect: (Int?) -> Unit): View {
        val names = resources.getStringArray(R.array.kisekae_palette_names)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Button(this).apply {
            text = getString(R.string.kisekae_color_theme)
            isSelected = selected == null
            stateDescription = if (selected == null) getString(R.string.kisekae_selected) else null
            minimumHeight = px(MIN_TOUCH_DP)
            setOnClickListener { onSelect(null) }
        })
        COLOR_CHIP_PALETTE.forEachIndexed { index, color ->
            val isCurrent = selected != null && ThemeColorMath.isClose(selected, color, tolerance = 0)
            row.addView(FrameLayout(this).apply {
                contentDescription = names.getOrNull(index) ?: ThemeColorMath.toHex(color)
                stateDescription = if (isCurrent) getString(R.string.kisekae_selected) else null
                isSelected = isCurrent
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(color) }
                addView(View(this@KisekaeActivity).apply {
                    background = swatchDrawable(color, isCurrent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER))
            }, LinearLayout.LayoutParams(px(MIN_TOUCH_DP), px(MIN_TOUCH_DP)))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** 色の見本の丸。選択中は強調色の太い枠で示す。 */
    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(px(if (selected) 3 else 1), if (selected) getColor(R.color.kb_accent) else getColor(R.color.kb_divider))
    }

    /** 選択肢を横に並べたラジオボタン。狭い画面では横にスクロールする。 */
    private fun <T> choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit): View {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        options.forEach { (value, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                minimumHeight = px(MIN_TOUCH_DP)
                isChecked = value == selected
                setOnCheckedChangeListener { _, checked -> if (checked) onSelect(value) }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        }
    }

    /**
     * 値を表示するラベルとスライダー。[step]刻みで[range]の値を選び、指を動かしている間も保存してプレビューへ反映する。
     * [format]は項目名と数値を受け取る文字列resource。
     */
    private fun slider(titleId: Int, range: IntRange, step: Int, value: Int, format: Int, onChange: (Int) -> Unit): LinearLayout {
        val title = getString(titleId)
        val label = TextView(this).apply {
            textSize = 14f
            text = getString(format, title, value)
        }
        val bar = SeekBar(this).apply {
            max = (range.last - range.first) / step
            progress = ((value.coerceIn(range.first, range.last) - range.first) / step)
            contentDescription = title
            minimumHeight = px(MIN_TOUCH_DP)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val selected = range.first + progress * step
                    label.text = getString(format, title, selected)
                    onChange(selected)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(6), 0, 0)
            addView(label)
            addView(bar)
        }
    }

    /** ON/OFFの項目。行のどこを押しても切り替わる。 */
    private fun switchRow(titleId: Int, checked: Boolean, onChange: (Boolean) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(56)
        val switch = Switch(this@KisekaeActivity).apply {
            isChecked = checked
            contentDescription = getString(titleId)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        addView(TextView(this@KisekaeActivity).apply {
            text = getString(titleId)
            textSize = 15f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(switch)
        setOnClickListener { switch.toggle() }
    }

    /** 分類の見出し。 */
    private fun section(titleId: Int): TextView = TextView(this).apply {
        text = getString(titleId)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.kb_accent))
        setPadding(0, px(20), 0, px(4))
    }

    /** 項目の小見出し。 */
    private fun caption(titleId: Int): TextView = TextView(this).apply {
        text = getString(titleId)
        textSize = 14f
        setPadding(0, px(10), 0, px(2))
    }

    /** 補足の説明。 */
    private fun note(textId: Int): TextView = TextView(this).apply {
        text = getString(textId)
        textSize = 12f
        alpha = DESCRIPTION_ALPHA
        setPadding(0, px(2), 0, px(4))
    }

    /** 押すと[onClick]を行うボタン。 */
    private fun button(textId: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = getString(textId)
        minimumHeight = px(MIN_TOUCH_DP)
        setOnClickListener { onClick() }
    }

    /** 子のViewも含めて押せるかどうかを切り替える。 */
    private fun View.setEnabledDeep(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
        (this as? LinearLayout)?.let { group -> (0 until group.childCount).forEach { group.getChildAt(it).isEnabled = enabled } }
    }

    private fun px(dp: Int): Int = (dp * density).toInt()

    private companion object {
        /** 写真を選ぶStorage Access Frameworkのrequest code。 */
        const val REQUEST_BACKGROUND_IMAGE = 41

        /** 補足の説明を控えめに見せる不透明度。 */
        const val DESCRIPTION_ALPHA = 0.7f

        /** 押せない項目の不透明度。 */
        const val DISABLED_ALPHA = 0.4f

        /** タップ領域の下限（dp）。 */
        const val MIN_TOUCH_DP = 48

        /** プレビューのキーの高さ（dp）。タップ領域の下限にして、設定項目を見る場所を残す。 */
        const val PREVIEW_ROW_HEIGHT_DP = 48f

        /** スライダーを動かしている間にプレビューを作り直すまでの待ち時間（ミリ秒）。 */
        const val PREVIEW_DELAY_MS = 80L

        /** 入力の確認の行に残す文字数。 */
        const val ECHO_MAX_CHARS = 40

        /** 色の面の高さ（dp）。 */
        const val COLOR_MAP_HEIGHT_DP = 140

        /** 背景画像の切り抜きの見積もりに使う、12キーの行数とキーボードの外周の余白（dp）。 */
        const val KANA_ROWS = 4
        const val PANEL_PADDING_DP = 4f

        /** 背景画像があるとき、候補バーの地に残す最小の不透明度（キーボードと同じ55%）。 */
        const val MIN_BAR_ALPHA_WITH_IMAGE = 0.55f

        /** 色の明るさのスライダーの範囲（%）。真っ黒・真っ白は数値の入力で選ぶ。 */
        val LIGHTNESS_RANGE = 5..95

        /** プレビューの候補バーに並べる見本の予測と候補。ライブ変換中の見た目を示す。 */
        val PREVIEW_PREDICTIONS = listOf("ありがとう", "ありがとうございます")
        val PREVIEW_CANDIDATES = listOf("ありがと", "有難と", "アリガト")

        /** カスタムをまだ作っていないときの札の見本の色。 */
        val CUSTOM_SWATCH = listOf(0xFFE45555.toInt(), 0xFFEEBB44.toInt(), 0xFF55CC66.toInt(), 0xFF8866EE.toInt())
    }
}

/**
 * 色相（左右、0〜360）と鮮やかさ（上が100、下が0）の面を、決めた明るさで描き、触れた位置の色相と鮮やかさを返す。
 * 面の画像は小さく作って拡大して描き、明るさを変えたときだけ作り直す。
 */
@SuppressLint("ViewConstructor")
class ColorMapView(
    context: Context,
    private var lightness: Float,
    private var hue: Float,
    private var saturation: Float,
    private val onRelease: () -> Unit,
    private val onPick: (hue: Float, saturation: Float) -> Unit,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private var bitmap: Bitmap = createMap()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val destination = Rect()

    init {
        isClickable = true
        isFocusable = true
    }

    /** 明るさを変えて面を描き直す。 */
    fun setLightness(value: Float) {
        lightness = value
        bitmap = createMap()
        invalidate()
    }

    /** 今の明るさで、色相×鮮やかさの小さな画像を作る。 */
    private fun createMap(): Bitmap {
        val pixels = IntArray(MAP_WIDTH * MAP_HEIGHT)
        for (y in 0 until MAP_HEIGHT) {
            val s = 100f - y * 100f / (MAP_HEIGHT - 1)
            for (x in 0 until MAP_WIDTH) {
                pixels[y * MAP_WIDTH + x] = ThemeColorMath.fromHsl(x * 360f / MAP_WIDTH, s, lightness)
            }
        }
        return Bitmap.createBitmap(pixels, MAP_WIDTH, MAP_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        val x = hue / 360f * width
        val y = (100f - saturation) / 100f * height
        markerPaint.color = if (lightness > 55f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, 7f * density, markerPaint)
    }

    // 指で触れている途中か。離したときの作り直しを1回の操作につき1回にするために使う。
    private var tracking = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 触れている位置の色相と鮮やかさを選ぶ。指を動かしている間は親のスクロールを止める。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                hue = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * 360f
                saturation = 100f - (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f) * 100f
                invalidate()
                onPick(hue, saturation)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // 離した後の作り直しでこのView自身が外され、ACTION_CANCELが入れ子で届く。
                // 1回の操作につき1回だけ、タッチの処理が終わってから作り直す。
                if (tracking) {
                    tracking = false
                    mainHandler.post(onRelease)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        /** 面の画像の大きさ（px）。拡大して描くため小さくてよい。 */
        const val MAP_WIDTH = 120
        const val MAP_HEIGHT = 60
    }
}
