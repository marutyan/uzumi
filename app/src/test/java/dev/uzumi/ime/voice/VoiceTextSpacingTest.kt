package dev.uzumi.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** [VoiceTextSpacing]が、英文を続けて入れるときだけ空白を足すことを確かめる。 */
class VoiceTextSpacingTest {
    @Test
    fun addsSpaceBetweenEnglishSentences() {
        assertEquals(" ", VoiceTextSpacing.leadingSeparator('.', "Tomorrow's meeting."))
        assertEquals(" ", VoiceTextSpacing.leadingSeparator('?', "Okay."))
        assertEquals(" ", VoiceTextSpacing.leadingSeparator('k', "Thanks."))
        assertEquals(" ", VoiceTextSpacing.leadingSeparator('3', "500 yen."))
    }

    @Test
    fun addsNothingOtherwise() {
        assertEquals("", VoiceTextSpacing.leadingSeparator(null, "Hello."))
        assertEquals("", VoiceTextSpacing.leadingSeparator(' ', "Hello."))
        assertEquals("", VoiceTextSpacing.leadingSeparator('\n', "Hello."))
        assertEquals("", VoiceTextSpacing.leadingSeparator('。', "Hello."))
        assertEquals("", VoiceTextSpacing.leadingSeparator('.', "明日の会議です。"))
        assertEquals("", VoiceTextSpacing.leadingSeparator('a', "「Hello」"))
        assertEquals("", VoiceTextSpacing.leadingSeparator('.', ""))
    }
}
