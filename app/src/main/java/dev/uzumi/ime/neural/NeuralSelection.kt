package dev.uzumi.ime.neural

import android.content.Context
import dev.uzumi.ime.R
import dev.uzumi.ime.conversion.NeuralModelSpec

/**
 * 評価の条件を切り替える「変換エンジン：Mozc／モデル名」の選択。debugビルドだけで有効で（`R.bool.neural_selection_enabled`）、
 * releaseビルドでは常にMozcだけになる。debugの受信口（adb）と、debugビルドの設定画面の「変換エンジン（開発用）」から書く。
 * 未設定ならZXを選ぶ。モデルやnativeライブラリが無ければ既存の失敗時の経路でMozcを使う。
 * IMEは入力欄の開始ごとに読み、次の入力欄から反映する。
 */
object NeuralSelection {
    // 未設定と、利用者が明示的に選んだMozcだけ（M）を区別して保存する。
    private const val PREFERENCES_NAME = "uzumi_neural_debug"
    private const val KEY_MODEL = "model"

    /** このビルドで選択を使えるか。 */
    fun isEnabled(context: Context): Boolean = context.resources.getBoolean(R.bool.neural_selection_enabled)

    /** 選ばれているモデル。Mozcだけ、またはreleaseビルドではnull。 */
    fun current(context: Context): NeuralModelSpec? {
        if (!isEnabled(context)) return null
        return resolveNeuralSelection(preferences(context).getString(KEY_MODEL, null))
    }

    /**
     * モデルを選ぶ。nullならMozcだけに戻す。選択を使えないビルドではfalseを返し、何もしない。
     * 自動測定の道具が選んだ直後にアプリを止めるため、非同期のapplyではなくcommitでファイルへ書き終えてから返す。
     */
    fun select(context: Context, spec: NeuralModelSpec?): Boolean {
        if (!isEnabled(context)) return false
        return preferences(context).edit().putString(KEY_MODEL, spec?.key ?: "M").commit()
    }

    /** 選択の保存先。 */
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

/** 未設定ならZXを使う。明示したMozcだけの設定や未知の保存値ではモデルを使わない。 */
internal fun resolveNeuralSelection(storedKey: String?): NeuralModelSpec? =
    if (storedKey == null) NeuralModelSpec.ZENZ_XSMALL else NeuralModelSpec.fromKey(storedKey)
