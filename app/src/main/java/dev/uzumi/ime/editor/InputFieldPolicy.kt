package dev.uzumi.ime.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 入力欄の種類とeditor actionを、IMEが守るべき方針へ変換する。
 * 機密欄の候補・学習停止をInputConnectionの呼び出し箇所から分離する。
 */
data class InputFieldPolicy(
    val isPassword: Boolean,
    val noPersonalizedLearning: Boolean,
    val isTypeNull: Boolean,
    val isNumeric: Boolean,
    val isMultiLine: Boolean,
    val noEnterAction: Boolean,
    val imeAction: Int,
    val actionLabel: String,
) {
    /** 機密欄または明示的な学習禁止欄かどうか。 */
    val suppressLearning: Boolean
        get() = isPassword || noPersonalizedLearning

    /** 候補生成を抑止すべき欄かどうか。 */
    val suppressSuggestions: Boolean
        get() = isPassword || isTypeNull

    companion object {
        /** EditorInfoを、接続をまたいで持ち運べる方針へ変換する。 */
        fun fromEditorInfo(info: EditorInfo): InputFieldPolicy {
            return fromRaw(
                inputType = info.inputType,
                imeOptions = info.imeOptions,
                actionLabel = info.actionLabel?.toString(),
                actionId = info.actionId,
            )
        }

        /** Androidの定数を受け取り、テスト可能な値へ変換する。 */
        fun fromRaw(
            inputType: Int,
            imeOptions: Int,
            actionLabel: String?,
            actionId: Int = 0,
        ): InputFieldPolicy {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val password = when (inputClass) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
            val numeric = inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME
            val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val multiLine = inputClass == InputType.TYPE_CLASS_TEXT &&
                (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 || noEnterAction)
            val action = actionId.takeIf { it != 0 } ?: (imeOptions and EditorInfo.IME_MASK_ACTION)

            return InputFieldPolicy(
                isPassword = password,
                noPersonalizedLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
                isTypeNull = inputType == InputType.TYPE_NULL,
                isNumeric = numeric,
                isMultiLine = multiLine,
                noEnterAction = noEnterAction,
                imeAction = action,
                actionLabel = if (multiLine || noEnterAction) {
                    "改行"
                } else {
                    actionLabel?.takeIf { it.isNotBlank() }
                        ?: defaultActionLabel(action, multiLine = false)
                },
            )
        }

        private fun defaultActionLabel(action: Int, multiLine: Boolean): String {
            if (multiLine || action == EditorInfo.IME_ACTION_NONE) return "改行"
            return when (action) {
                EditorInfo.IME_ACTION_GO -> "移動"
                EditorInfo.IME_ACTION_NEXT -> "次へ"
                EditorInfo.IME_ACTION_SEARCH -> "検索"
                EditorInfo.IME_ACTION_SEND -> "送信"
                EditorInfo.IME_ACTION_DONE -> "完了"
                else -> "決定"
            }
        }
    }
}

/**
 * Enter入力を改行として扱うか、editor actionとして送るかを判定する。
 */
object EditorActionPolicy {
    /** 改行欄やactionなしの欄では、Enterを文字として確定する。 */
    fun shouldInsertNewline(policy: InputFieldPolicy): Boolean {
        return policy.isMultiLine || policy.noEnterAction || policy.imeAction == EditorInfo.IME_ACTION_NONE
    }
}
