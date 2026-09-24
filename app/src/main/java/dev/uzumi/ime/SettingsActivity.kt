package dev.uzumi.ime

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Toast
import dev.uzumi.ime.conversion.NeuralModelSpec
import dev.uzumi.ime.keyboard.KeyboardPreferences
import dev.uzumi.ime.learning.LearningStores
import dev.uzumi.ime.neural.NeuralRuntimeConnection
import dev.uzumi.ime.neural.NeuralSelection

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
        content.addView(learningRow())
        content.addView(linkRow(R.string.settings_clear_learning) { confirmClearLearning() })
        content.addView(linkRow(R.string.open_user_dictionary) {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        })
        content.addView(linkRow(R.string.open_licenses) {
            startActivity(Intent(this, LicenseActivity::class.java))
        })

        // 変換エンジンの選択は、選択を使えるビルド（debug）だけに出す。releaseでは常にMozcだけで、項目も出さない。
        if (NeuralSelection.isEnabled(this)) {
            content.addView(section(R.string.settings_section_developer))
            content.addView(label(R.string.settings_neural_engine, R.string.settings_neural_engine_description))
            content.addView(engineChoices())
        }

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

    /**
     * 変換の学習のON/OFF。学習の保存ファイルはUI thread外で読み、読み終わるまでスイッチを押せなくする。
     */
    private fun learningRow(): View {
        var ready = false
        val row = switchRow(R.string.settings_learning, R.string.settings_learning_description, true) { enabled ->
            if (ready) background { LearningStores.get(this).setEnabled(enabled) }
        }
        row.isEnabled = false
        background {
            val enabled = LearningStores.get(this).isEnabled
            runOnUiThread {
                row.findSwitch()?.isChecked = enabled
                ready = true
                row.isEnabled = true
                row.findSwitch()?.isEnabled = true
            }
        }
        row.findSwitch()?.isEnabled = false
        return row
    }

    /** 学習履歴を消す前に確認する。消去はファイルを書き換えるためUI thread外で行う。 */
    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_learning)
            .setMessage(R.string.settings_clear_learning_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_learning_ok) { _, _ ->
                background {
                    LearningStores.get(this).clearAll()
                    runOnUiThread { Toast.makeText(this, R.string.settings_clear_learning_done, Toast.LENGTH_SHORT).show() }
                }
            }
            .show()
    }

    /** ファイルI/Oを伴う処理をUI thread外で一度だけ行う。 */
    private fun background(work: () -> Unit) {
        Thread(work, "uzumi-settings").start()
    }

    /** 行の中のスイッチを探す。 */
    private fun View.findSwitch(): Switch? = (this as? LinearLayout)?.let { row ->
        (0 until row.childCount).map(row::getChildAt).filterIsInstance<Switch>().firstOrNull()
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

    /**
     * 変換エンジン（開発用）の「Mozc／ZS／ZX／JS／JX」。ユーザーが実機でモデルを試すための項目で、選ぶとadbの
     * `NEURAL_SELECT`と同じ[NeuralSelection.select]で保存する。モデルのファイルが端末に無い項目は選べなくする。
     */
    private fun engineChoices(): RadioGroup {
        val current = NeuralSelection.current(this)
        val modelDirectory = NeuralRuntimeConnection.modelDirectory(this)
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            (listOf<NeuralModelSpec?>(null) + NeuralModelSpec.entries).forEach { spec ->
                // モデルを置いたか。ファイルの中身（SHA-256）は読み込み時に`:neural`が確かめるため、ここでは有無だけを見る。
                val available = spec == null || modelDirectory.resolve(spec.fileName).isFile
                val name = spec?.let { "${it.key}（${it.fileName.removeSuffix(".gguf")}）" }
                    ?: getString(R.string.settings_neural_engine_mozc)
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = if (available) name else getString(R.string.settings_neural_engine_missing, name)
                    minimumHeight = px(48)
                    isEnabled = available
                    isChecked = spec == current
                    setOnCheckedChangeListener { _, checked -> if (checked) NeuralSelection.select(this@SettingsActivity, spec) }
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
