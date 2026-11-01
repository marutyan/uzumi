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
    // URL・メールアドレスの欄か。句読点や自動変換の規則をそのまま当てはめない。
    val isAddressLike: Boolean = false,
    // 利用者が設定で変換の学習を止めているか。欄の機密性とは別に、学習と履歴の保存だけを止める。
    val learningDisabledBySetting: Boolean = false,
) {
    /** 機密欄、明示的な学習禁止欄、または設定で学習を止めているかどうか。 */
    val suppressLearning: Boolean
        get() = isPassword || noPersonalizedLearning || learningDisabledBySetting

    /** 入力した文字列を一時的にも覚えるべきでない欄か（password、学習禁止欄）。削除の「元に戻す」の記録に使う。 */
    val isSensitive: Boolean
        get() = isPassword || noPersonalizedLearning

    /** 候補生成を抑止すべき欄かどうか。 */
    val suppressSuggestions: Boolean
        get() = isPassword || isTypeNull

    /**
     * ライブ変換の設定がONのとき、この欄でライブ変換を使うか。機密・互換入力・数字・URL・メールの欄は
     * 自動変換を避け、従来の明示変換で扱う。IMEは入力開始ごとにこの判定で編集セッションの種類を決める。
     */
    fun usesLiveConversion(liveSettingEnabled: Boolean): Boolean {
        return liveSettingEnabled && !isPassword && !isTypeNull && !isNumeric && !isAddressLike
    }

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
            val addressLike = inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )
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
                isAddressLike = addressLike,
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
 * Inline Autofill候補（パスワードやSMS認証コードなど、Autofill serviceが出す候補）を候補バーへ出すかの判定。
 * 候補の中身はOSが描画し、IMEは中身を読まないため、判定には入力状態と件数・世代だけを使う。
 * 機密欄でもOSの候補は出すが、通常の変換候補と学習の抑止は[InputFieldPolicy]のまま変えない。
 */
object InlineAutofillPolicy {
    /** 未確定文字も通常候補もない待機中で、Inline候補が1件以上あるときだけ待機表示の代わりに並べる。 */
    fun shouldShow(hasComposition: Boolean, hasRegularCandidates: Boolean, inlineCount: Int): Boolean {
        return !hasComposition && !hasRegularCandidates && inlineCount > 0
    }

    /**
     * 展開を終えた応答を表示へ採用するか。応答を受けた欄の編集セッション番号が今の番号と同じで、
     * かつその応答が最後に受けた応答（[responseGeneration]が[latestGeneration]と同じ）である場合だけ採用する。
     */
    fun acceptsResponse(responseEpoch: Long?, currentEpoch: Long?, responseGeneration: Long, latestGeneration: Long): Boolean {
        return responseEpoch != null && responseEpoch == currentEpoch && responseGeneration == latestGeneration
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
