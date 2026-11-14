package dev.uzumi.ime

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 起動画面。上に「はじめに」の手順（Uzumiを有効にする、Uzumiに切り替える）を置き、両方済んだら「準備ができました」の1行にまとめる。
 * その下に導入直後に入力を試せる欄と、Uzumiの設定・ユーザー辞書・第三者ライセンスへの行を並べる。
 */
class MainActivity : Activity() {
    private lateinit var ui: SettingsUi
    private lateinit var readyBanner: View
    private lateinit var stepsSection: View
    private lateinit var enableStep: StepViews
    private lateinit var switchStep: StepViews

    /** 手順の1行で、状態に合わせて書き換える部品。 */
    private class StepViews(val mark: FrameLayout, val row: SettingsUi.Row, val button: TextView)

    /** 画面を組み立てる。手順の状態は[onResume]と、入力方法の選択から戻ったときに読み直す。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        ui = SettingsUi(this)
        val page = ui.page()

        enableStep = step(1, R.string.main_step_enable, R.string.main_step_enable_action) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        switchStep = step(2, R.string.main_step_switch, R.string.main_step_switch_action) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        readyBanner = ui.banner(R.drawable.ic_check_circle_filled, getString(R.string.main_ready_title), getString(R.string.main_ready_summary))
        stepsSection = ui.section(getString(R.string.main_steps_title), ui.group(listOf(enableStep.row.view, switchStep.row.view)))
        ui.addBlock(page, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(readyBanner)
            addView(stepsSection)
        })
        ui.addBlock(page, ui.section(getString(R.string.trial_input_title), trialInput()))
        ui.addBlock(page, ui.group(listOf(
            ui.row(getString(R.string.settings_title), getString(R.string.main_settings_summary), R.drawable.ic_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }.view,
            ui.row(getString(R.string.open_user_dictionary), getString(R.string.user_dictionary_summary), R.drawable.ic_menu_book) {
                startActivity(Intent(this, UserDictionaryActivity::class.java))
            }.view,
        )))
        ui.addBlock(page, ui.group(listOf(
            ui.row(getString(R.string.open_licenses), iconRes = R.drawable.ic_description) {
                startActivity(Intent(this, LicenseActivity::class.java))
            }.view,
        )))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.appBar(getString(R.string.app_name), onBack = null))
            addView(ScrollView(this@MainActivity).apply { addView(page) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        applySystemInsets(root)
        setContentView(root)
    }

    /** 端末の設定から戻ったとき、手順の状態を読み直す。 */
    override fun onResume() {
        super.onResume()
        showSetupProgress(readSetupProgress())
    }

    /** 入力方法の選択は画面の上に出る窓のため、閉じて画面へ戻ったときにも状態を読み直す。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showSetupProgress(readSetupProgress())
    }

    /** 端末で有効な入力方法の一覧と、今使っている入力方法から、手順の進み具合を読む。 */
    private fun readSetupProgress(): SetupProgress {
        val uzumi = getSystemService(InputMethodManager::class.java).enabledInputMethodList.firstOrNull {
            it.packageName == packageName && it.serviceName == UzumiInputMethodService::class.java.name
        }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return SetupProgress.of(enabled = uzumi != null, selected = uzumi != null && uzumi.id == current)
    }

    /** 手順の印・説明・ボタンを進み具合に合わせる。両方済んだら手順を隠して「準備ができました」を出す。 */
    private fun showSetupProgress(progress: SetupProgress) {
        val enabled = progress != SetupProgress.NOT_ENABLED
        val ready = progress == SetupProgress.READY
        readyBanner.visibility = if (ready) View.VISIBLE else View.GONE
        stepsSection.visibility = if (ready) View.GONE else View.VISIBLE
        showStep(enableStep, done = enabled, summary = if (enabled) R.string.main_step_enable_done else R.string.main_step_enable_todo)
        showStep(switchStep, done = ready,
            summary = if (enabled) R.string.main_step_switch_todo else R.string.main_step_switch_blocked)
        ui.setFilledButtonEnabled(switchStep.button, enabled)
    }

    /** 手順1行の印と説明を書き換え、済んだ手順のボタンを隠す。 */
    private fun showStep(step: StepViews, done: Boolean, summary: Int) {
        step.row.summary.text = getString(summary)
        step.button.visibility = if (done) View.GONE else View.VISIBLE
        step.mark.background = if (done) {
            ui.circle(ui.accent)
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(ui.px(2), ui.outline)
            }
        }
        step.mark.getChildAt(0).visibility = if (done) View.GONE else View.VISIBLE
        step.mark.getChildAt(1).visibility = if (done) View.VISIBLE else View.GONE
    }

    /** 手順の1行。左に番号の丸（済んだら✓）、右に操作のボタンを置く。 */
    private fun step(number: Int, titleId: Int, actionId: Int, onAction: () -> Unit): StepViews {
        val mark = FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(TextView(this@MainActivity).apply {
                text = String.format(Locale.getDefault(), "%d", number)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ui.textSecondary)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ui.icon(R.drawable.ic_check, ui.onAccent), FrameLayout.LayoutParams(ui.px(20), ui.px(20), Gravity.CENTER))
        }
        val button = ui.filledButton(getString(actionId), onAction)
        val row = ui.row(getString(titleId), summary = "", leading = mark, trailing = button)
        // 番号の丸は直径32dpに固定する（行は中身に合わせた大きさで置くため、置いた後に大きさを決める）。
        mark.layoutParams = (mark.layoutParams as LinearLayout.LayoutParams).apply { width = ui.px(32); height = ui.px(32) }
        return StepViews(mark, row, button)
    }

    /** 試し入力の欄。枠線の角丸の箱にし、入力中は強調色の太い枠にする。 */
    private fun trialInput(): EditText = EditText(this).apply {
        hint = getString(R.string.trial_input_hint)
        gravity = Gravity.TOP or Gravity.START
        minLines = 5
        minHeight = ui.px(120)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ui.text)
        setHintTextColor(ui.textSecondary)
        setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(12))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(ui.px(2), ui.accent))
            addState(intArrayOf(), box(ui.px(1), ui.outline))
        }
    }

    /** 試し入力の欄の地（行の地の色、角丸12dp、[stroke]の太さの枠線）。 */
    private fun box(stroke: Int, color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = ui.px(12).toFloat()
        setColor(ui.surface)
        setStroke(stroke, color)
    }
}

/** 起動画面の手順の進み具合。端末の設定でUzumiを有効にしたか、使うキーボードにUzumiを選んだかで決まる。 */
enum class SetupProgress {
    /** まだ有効にしていない。手順1から。 */
    NOT_ENABLED,

    /** 有効にしたが、使うキーボードに選んでいない。手順2から。 */
    ENABLED,

    /** 使うキーボードに選んでいる。手順を隠して「準備ができました」を出す。 */
    READY,
    ;

    companion object {
        /** 有効か・選ばれているかから進み具合を決める。有効でない入力方法は選べないため、有効でなければ選ばれていても手順1にする。 */
        fun of(enabled: Boolean, selected: Boolean): SetupProgress = when {
            !enabled -> NOT_ENABLED
            !selected -> ENABLED
            else -> READY
        }
    }
}
