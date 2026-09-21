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

    /** 複数行とNO_ENTER_ACTIONではeditor actionより改行を優先する。 */
    @Test
    fun multilineAndNoEnterActionInsertNewline() {
        val multiline = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND,
            null,
        )
        val noEnterAction = InputFieldPolicy.fromRaw(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            "送る",
        )

        assertTrue(EditorActionPolicy.shouldInsertNewline(multiline))
        assertTrue(EditorActionPolicy.shouldInsertNewline(noEnterAction))
        assertEquals("改行", noEnterAction.actionLabel)
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
}
