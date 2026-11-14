package dev.uzumi.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EnglishFillerRemover]が訳文から独立した「Um」「Uh」だけを消すことを確かめる。
 * 期待出力は合意した表の文字列をそのまま固定し、規則から計算しない。
 */
class EnglishFillerRemoverTest {
    /** 合意した表のうち、フィラーを消す文がすべて期待どおりになることを確かめる。 */
    @Test
    fun removesStandaloneFillers() {
        val cases = listOf(
            "Well, tomorrow's meeting is Um, please start at 3 o'clock." to
                "Well, tomorrow's meeting is please start at 3 o'clock.",
            "The budget is, um, about 3,500 yen per person. I'm thinking about it." to
                "The budget is about 3,500 yen per person. I'm thinking about it.",
            "Photos taken with an X-Cell Ten Pro camera Um, I've uploaded it to Google Photos." to
                "Photos taken with an X-Cell Ten Pro camera I've uploaded it to Google Photos.",
            "So, um, after that, we all went to karaoke together." to
                "So after that, we all went to karaoke together.",
            "Um, I think so." to "I think so.",
            "Uh, okay." to "Okay.",
            // フィラーだけの文は文末記号ごと消し、文の途中のフィラーに付いた文末記号は残す。
            "Um. I agree." to "I agree.",
            "I agree. Uh. Let us go." to "I agree. Let us go.",
            "I agree, um." to "I agree.",
            "Is that right, uh?" to "Is that right?",
            "Um." to "",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, EnglishFillerRemover.removeFillers(input))
        }
    }

    /** 語の一部になっている「um」「Uh」や、意味を持ちうる語を含む文は変えないことを確かめる。 */
    @Test
    fun keepsWordsThatAreNotFillers() {
        val unchanged = listOf(
            "Umbrella and drum.",
            "Uh-oh, it broke.",
            "Well, you know, it's fine.",
        )
        for (input in unchanged) {
            assertEquals(input, input, EnglishFillerRemover.removeFillers(input))
        }
    }
}
