package dev.uzumi.ime

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.uzumi.ime.keyboard.KeyboardPreferences

/**
 * Uzumiの設定画面。入力・表示・操作の反応・学習と辞書に分けて並べ、値はUzumiSettingsだけに保存する。
 * 候補バーの⚙と、導入画面の「Uzumiの設定」から開く。変更は次にキーボードを表示したときから反映される。
 */
class SettingsActivity : Activity() {
    private val density: Float
        get() = resources.displayMetrics.density

    /** 設定項目を追加UI依存なしで組み立てる。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }

        content.addView(section(R.string.settings_section_input))
        content.addView(switchRow(R.string.live_conversion_switch, R.string.live_conversion_description,
            UzumiSettings.isLiveConversionEnabled(this)) { UzumiSettings.setLiveConversionEnabled(this, it) })
        content.addView(switchRow(R.string.settings_delete_drag, R.string.settings_delete_drag_description,
            UzumiSettings.keyboardPreferences(this).deleteDrag) { UzumiSettings.setDeleteDrag(this, it) })

        content.addView(section(R.string.settings_section_display))
        content.addView(label(R.string.settings_height, R.string.settings_height_description))
        content.addView(heightChoices())
        content.addView(switchRow(R.string.settings_key_preview, R.string.settings_key_preview_description,
            UzumiSettings.keyboardPreferences(this).keyPreview) { UzumiSettings.setKeyPreview(this, it) })

        content.addView(section(R.string.settings_section_feedback))
        content.addView(switchRow(R.string.settings_vibration, null,
            UzumiSettings.keyboardPreferences(this).vibration) { UzumiSettings.setVibration(this, it) })
        content.addView(switchRow(R.string.settings_key_sound, null,
            UzumiSettings.keyboardPreferences(this).keySound) { UzumiSettings.setKeySound(this, it) })

        content.addView(section(R.string.settings_section_learning))
        content.addView(switchRow(R.string.settings_learning, R.string.settings_learning_description,
            UzumiSettings.isLearningEnabled(this)) { UzumiSettings.setLearningEnabled(this, it) })
        content.addView(linkRow(R.string.open_user_dictionary) {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        })
        content.addView(linkRow(R.string.open_licenses) {
            startActivity(Intent(this, LicenseActivity::class.java))
        })

        val scrollView = ScrollView(this).apply { addView(content) }
        applySystemInsets(scrollView)
        setContentView(scrollView)
    }

    /** 分類の見出し。 */
    private fun section(titleId: Int): TextView = TextView(this).apply {
        text = getString(titleId)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.kb_accent))
        setPadding(0, px(20), 0, px(4))
    }

    /** 項目名と補足の説明。 */
    private fun label(titleId: Int, descriptionId: Int?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(10), 0, px(4))
        addView(TextView(this@SettingsActivity).apply {
            text = getString(titleId)
            textSize = 16f
        })
        if (descriptionId != null) {
            addView(TextView(this@SettingsActivity).apply {
                text = getString(descriptionId)
                textSize = 13f
                alpha = DESCRIPTION_ALPHA
            })
        }
    }

    /** ON/OFFの項目。切り替えるとすぐ保存する。 */
    private fun switchRow(titleId: Int, descriptionId: Int?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = px(56)
            val switch = Switch(this@SettingsActivity).apply {
                isChecked = checked
                contentDescription = getString(titleId)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
            addView(label(titleId, descriptionId), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch)
            // 行のどこを押しても切り替わるようにし、小さなスイッチだけを狙わなくてよくする
            setOnClickListener { switch.toggle() }
        }
    }

    /** キーボードの高さの「低・中・高」。選ぶとすぐ保存する。 */
    private fun heightChoices(): RadioGroup {
        val labels = mapOf(
            KeyboardPreferences.Height.LOW to R.string.settings_height_low,
            KeyboardPreferences.Height.MEDIUM to R.string.settings_height_medium,
            KeyboardPreferences.Height.HIGH to R.string.settings_height_high,
        )
        val current = UzumiSettings.keyboardHeight(this)
        return RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            labels.forEach { (height, labelId) ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = getString(labelId)
                    minimumHeight = px(48)
                    isChecked = height == current
                    setOnCheckedChangeListener { _, checked -> if (checked) UzumiSettings.setKeyboardHeight(this@SettingsActivity, height) }
                })
            }
        }
    }

    /** 別の画面を開く項目。 */
    private fun linkRow(titleId: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = getString(titleId)
        textSize = 16f
        minimumHeight = px(56)
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun px(dp: Int): Int = (dp * density).toInt()

    private companion object {
        /** 補足の説明を項目名より控えめに見せる不透明度。 */
        const val DESCRIPTION_ALPHA = 0.7f
    }
}
