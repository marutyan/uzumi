package dev.uzumi.ime

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * IMEの有効化・切替導線と、導入直後に入力を試せる欄を表示する。
 */
class MainActivity : Activity() {
    /** 設定ボタンと試用欄を、追加UI依存なしで構築する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.setup_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.setup_steps)
            textSize = 16f
            setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
        })
        content.addView(Button(this).apply {
            text = getString(R.string.open_keyboard_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })
        content.addView(Button(this).apply {
            text = getString(R.string.choose_input_method)
            setOnClickListener {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.trial_input_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (28 * density).toInt(), 0, (8 * density).toInt())
        })
        content.addView(EditText(this).apply {
            hint = getString(R.string.trial_input_hint)
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        })

        val scrollView = ScrollView(this).apply {
            addView(content)
        }
        applySystemInsets(scrollView)
        setContentView(scrollView)
    }

    /** システムバー、ディスプレイカットアウト、IME領域のInsetsを反映し重なりを防ぐ。 */
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { targetView, insets ->
            val systemBars = insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime()
            )
            targetView.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
    }
}
