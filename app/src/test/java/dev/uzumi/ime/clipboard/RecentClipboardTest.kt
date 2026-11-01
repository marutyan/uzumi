package dev.uzumi.ime.clipboard

import android.content.ClipDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** クリップボード提案の期限と表示規則を検証する。 */
class RecentClipboardTest {
    @Test
    fun sensitiveExtraKeyMatchesFrameworkConstant() {
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, RecentClipboardRules.EXTRA_IS_SENSITIVE)
    }

    @Test
    fun acceptsTextThroughSixtySecondBoundaryOnly() {
        assertTrue(RecentClipboardRules.isFresh(timestamp = 63_000L, nowMillis = 123_000L, lifetimeMillis = 60_000L))
        assertFalse(RecentClipboardRules.isFresh(timestamp = 62_999L, nowMillis = 123_000L, lifetimeMillis = 60_000L))
        assertFalse(RecentClipboardRules.isFresh(timestamp = 123_001L, nowMillis = 123_000L, lifetimeMillis = 60_000L))
        assertFalse(RecentClipboardRules.isFresh(timestamp = 0L, nowMillis = 123_000L, lifetimeMillis = 60_000L))
        assertFalse(RecentClipboardRules.isFresh(timestamp = 123_000L, nowMillis = 123_000L, lifetimeMillis = 0L))
    }

    @Test
    fun onlyPlainTextMimeTypeCanBeSuggested() {
        assertTrue(RecentClipboardRules.hasPlainTextMimeType(listOf("text/plain")))
        assertTrue(RecentClipboardRules.hasPlainTextMimeType(listOf("image/png", "TEXT/PLAIN")))
        assertFalse(RecentClipboardRules.hasPlainTextMimeType(listOf("image/png")))
        assertFalse(RecentClipboardRules.hasPlainTextMimeType(listOf("text/uri-list")))
    }

    @Test
    fun doesNotReadBodyForSensitiveClipsOrFields() {
        assertFalse(RecentClipboardRules.shouldReadTextBody(clipboardSensitive = true, sensitiveField = false))
        assertFalse(RecentClipboardRules.shouldReadTextBody(clipboardSensitive = false, sensitiveField = true))
        assertTrue(RecentClipboardRules.shouldReadTextBody(clipboardSensitive = false, sensitiveField = false))
    }

    @Test
    fun previewCollapsesLinesAndTruncatesWithoutSplittingSurrogatePairs() {
        val original = "A😀BC\nD"

        assertEquals("A😀BC…", RecentClipboardRules.preview(original, maxCodePoints = 5))
        assertEquals("first line second", RecentClipboardRules.preview(" first line\nsecond ", maxCodePoints = 24))
    }

    @Test
    fun emptyTextHasNoPreview() {
        assertNull(RecentClipboardRules.preview("  \n\t", maxCodePoints = 24))
        assertNull(RecentClipboardRules.preview("text", maxCodePoints = 0))
    }
}
