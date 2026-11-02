package dev.uzumi.ime.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** inputTypeとIME optionから安全方針への変換を検証する。 */
class InputFieldPolicyTest {
    /** 機密variationを通常欄と区別し、候補と学習を止める。 */
    @Test
    fun sensitiveVariationsSuppressSuggestionsAndLearning() {
        val webSensitiveTextType = 0x000000e1
        val sensitiveNumberType = 0x00000012
        val textSensitive = InputFieldPolicy.fromRaw(webSensitiveTextType, EditorInfo.IME_ACTION_DONE, null)
        val numericSensitive = InputFieldPolicy.fromRaw(sensitiveNumberType, EditorInfo.IME_ACTION_DONE, null)

        assertTrue(textSensitive.isPassword)
        assertTrue(textSensitive.suppressSuggestions)
        assertTrue(textSensitive.suppressLearning)
        assertTrue(numericSensitive.isPassword)
        assertTrue(numericSensitive.isNumeric)
    }

    /** no-learningは学習だけを止め、端末内の基本候補は維持する。 */
    @Test
    fun noPersonalizedLearningKeepsBasicSuggestions() {
        val policy = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            null,
        )

        assertTrue(policy.noPersonalizedLearning)
        assertTrue(policy.suppressLearning)
        assertFalse(policy.suppressSuggestions)
        assertEquals("検索", policy.actionLabel)
    }

    /** TYPE_NULLではcomposition候補を使わない安全方針にする。 */
    @Test
    fun typeNullSuppressesCompositionSuggestions() {
        val policy = InputFieldPolicy.fromRaw(
            InputType.TYPE_NULL,
            EditorInfo.IME_ACTION_NONE,
            null,
        )

        assertTrue(policy.isTypeNull)
        assertTrue(policy.suppressSuggestions)
        assertEquals("改行", policy.actionLabel)
    }

    /**
     * NO_ENTER_ACTIONとactionの指定がない複数行欄は改行し、複数行でも検索・送信が指定された欄はactionを優先する。
     * actionの指定がない単一行欄はEnterキーのイベントを送る。
     */
    @Test
    fun enterFollowsNoEnterActionAndExplicitAction() {
        val multilineSearch = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEARCH,
            null,
        )
        val multilineUnspecified = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_UNSPECIFIED,
            null,
        )
        val noEnterAction = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            "送る",
        )
        val singleLineUnspecified = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_UNSPECIFIED,
            null,
        )

        assertFalse(EditorActionPolicy.shouldInsertNewline(multilineSearch))
        assertFalse(EditorActionPolicy.shouldSendEnterKey(multilineSearch))
        assertEquals("検索", multilineSearch.actionLabel)
        assertTrue(EditorActionPolicy.shouldInsertNewline(multilineUnspecified))
        assertEquals("改行", multilineUnspecified.actionLabel)
        assertTrue(EditorActionPolicy.shouldInsertNewline(noEnterAction))
        assertEquals("改行", noEnterAction.actionLabel)
        assertFalse(EditorActionPolicy.shouldInsertNewline(singleLineUnspecified))
        assertTrue(EditorActionPolicy.shouldSendEnterKey(singleLineUnspecified))
    }

    /** 独自action IDがある場合は標準action maskより優先する。 */
    @Test
    fun customActionIdTakesPriority() {
        val customActionId = 42
        val policy = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_UNSPECIFIED,
            "実行",
            customActionId,
        )

        assertEquals(customActionId, policy.imeAction)
        assertEquals("実行", policy.actionLabel)
        assertFalse(EditorActionPolicy.shouldInsertNewline(policy))
    }

    /** ライブ変換は設定がONの通常欄だけで使い、機密・互換入力・数字・URL・メールの欄では明示変換にする。 */
    @Test
    fun liveConversionFollowsSettingAndFieldKind() {
        val text = InputFieldPolicy.fromRaw(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE, null)
        val noLearning = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            null,
        )
        val hidden = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfo.IME_ACTION_DONE,
            null,
        )
        val uri = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            EditorInfo.IME_ACTION_GO,
            null,
        )
        val email = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.IME_ACTION_NEXT,
            null,
        )
        val typeNull = InputFieldPolicy.fromRaw(InputType.TYPE_NULL, EditorInfo.IME_ACTION_NONE, null)
        val number = InputFieldPolicy.fromRaw(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE, null)

        assertTrue(text.usesLiveConversion(liveSettingEnabled = true))
        assertFalse(text.usesLiveConversion(liveSettingEnabled = false))
        assertTrue(noLearning.usesLiveConversion(liveSettingEnabled = true))
        assertFalse(hidden.usesLiveConversion(liveSettingEnabled = true))
        assertFalse(uri.usesLiveConversion(liveSettingEnabled = true))
        assertFalse(email.usesLiveConversion(liveSettingEnabled = true))
        assertFalse(typeNull.usesLiveConversion(liveSettingEnabled = true))
        assertFalse(number.usesLiveConversion(liveSettingEnabled = true))
    }

    /** Inline候補は未確定文字も通常候補もない待機中だけ出し、入力を始めたら通常の表示を優先する。 */
    @Test
    fun inlineAutofillShowsOnlyWhileIdle() {
        assertTrue(InlineAutofillPolicy.shouldShow(hasComposition = false, hasRegularCandidates = false, inlineCount = 2))
        assertFalse(InlineAutofillPolicy.shouldShow(hasComposition = true, hasRegularCandidates = false, inlineCount = 2))
        assertFalse(InlineAutofillPolicy.shouldShow(hasComposition = false, hasRegularCandidates = true, inlineCount = 2))
        assertFalse(InlineAutofillPolicy.shouldShow(hasComposition = false, hasRegularCandidates = false, inlineCount = 0))
    }

    /** 機密欄でもInline候補は出せるが、通常の変換候補と学習は従来どおり止める。 */
    @Test
    fun sensitiveFieldKeepsSuppressionWhileAllowingInlineAutofill() {
        val hiddenField = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfo.IME_ACTION_DONE,
            null,
        )

        assertTrue(hiddenField.suppressSuggestions)
        assertTrue(hiddenField.suppressLearning)
        assertTrue(InlineAutofillPolicy.shouldShow(hasComposition = false, hasRegularCandidates = false, inlineCount = 1))
    }

    /** 別の欄の応答、セッションのない応答、後から来た応答に追い越された応答は捨てる。 */
    @Test
    fun inlineAutofillDropsStaleResponses() {
        assertTrue(InlineAutofillPolicy.acceptsResponse(responseEpoch = 3, currentEpoch = 3, responseGeneration = 5, latestGeneration = 5))
        assertFalse(InlineAutofillPolicy.acceptsResponse(responseEpoch = 3, currentEpoch = 4, responseGeneration = 5, latestGeneration = 5))
        assertFalse(InlineAutofillPolicy.acceptsResponse(responseEpoch = null, currentEpoch = null, responseGeneration = 5, latestGeneration = 5))
        assertFalse(InlineAutofillPolicy.acceptsResponse(responseEpoch = 3, currentEpoch = null, responseGeneration = 5, latestGeneration = 5))
        assertFalse(InlineAutofillPolicy.acceptsResponse(responseEpoch = 3, currentEpoch = 3, responseGeneration = 4, latestGeneration = 5))
    }
    /** 文字欄の大文字化の指定だけを取り出し、パスワード欄や数字欄では大文字化しない。 */
    @Test
    fun capsModeFlagsFollowTextFieldRequest() {
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals(0, InputFieldPolicy.fromRaw(text, EditorInfo.IME_ACTION_DONE, null).capsModeFlags)
        assertEquals(
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            InputFieldPolicy.fromRaw(
                text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_DONE,
                null,
            ).capsModeFlags,
        )
        assertEquals(
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
            InputFieldPolicy.fromRaw(
                text or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,
                EditorInfo.IME_ACTION_DONE,
                null,
            ).capsModeFlags,
        )
        val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        assertEquals(0, InputFieldPolicy.fromRaw(password, EditorInfo.IME_ACTION_DONE, null).capsModeFlags)
        // 数字欄ではCAPのビットが別の意味（符号・小数）になるため使わない
        val number = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        assertEquals(0, InputFieldPolicy.fromRaw(number, EditorInfo.IME_ACTION_DONE, null).capsModeFlags)
    }
}
