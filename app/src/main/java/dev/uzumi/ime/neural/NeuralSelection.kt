package dev.uzumi.ime.neural

import android.content.Context
import dev.uzumi.ime.R
import dev.uzumi.ime.conversion.NeuralModelSpec

/**
 * 評価の条件を切り替える「変換エンジン：Mozc／モデル名」の選択。debugビルドだけで有効で（`R.bool.neural_selection_enabled`）、
 * releaseビルドでは常にMozcだけになる。製品の設定画面には出さず、debugの受信口（adb）から書く。
 * IMEは入力欄の開始ごとに読み、次の入力欄から反映する。
 */
object NeuralSelection {
    // 選択の保存ファイル名とキー。値は評価条件の条件名（ZS、ZX、JS、JX）で、無ければMozcだけ（条件M）。
    private const val PREFERENCES_NAME = "uzumi_neural_debug"
    private const val KEY_MODEL = "model"

    /** このビルドで選択を使えるか。 */
    fun isEnabled(context: Context): Boolean = context.resources.getBoolean(R.bool.neural_selection_enabled)

    /** 選ばれているモデル。Mozcだけ、またはreleaseビルドではnull。 */
    fun current(context: Context): NeuralModelSpec? {
        if (!isEnabled(context)) return null
        return NeuralModelSpec.fromKey(preferences(context).getString(KEY_MODEL, null))
    }

    /** モデルを選ぶ。nullならMozcだけに戻す。選択を使えないビルドではfalseを返し、何もしない。 */
    fun select(context: Context, spec: NeuralModelSpec?): Boolean {
        if (!isEnabled(context)) return false
        preferences(context).edit().apply {
            if (spec == null) remove(KEY_MODEL) else putString(KEY_MODEL, spec.key)
        }.apply()
        return true
    }

    /** 選択の保存先。 */
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
