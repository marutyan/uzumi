package dev.uzumi.ime.clipboard

import android.view.inputmethod.EditorInfo
import dev.uzumi.ime.editor.InputFieldPolicy
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
    fun onlyTextMimeTypesCanBeSuggested() {
        assertTrue(RecentClipboardRules.hasTextMimeType(listOf("text/plain")))
        assertTrue(RecentClipboardRules.hasTextMimeType(listOf("image/png", "TEXT/PLAIN")))
        assertTrue(RecentClipboardRules.hasTextMimeType(listOf("text/html")))
        assertTrue(RecentClipboardRules.hasTextMimeType(listOf("TEXT/HTML")))
        assertFalse(RecentClipboardRules.hasTextMimeType(listOf("image/png")))
        assertFalse(RecentClipboardRules.hasTextMimeType(listOf("image/png", "image/jpeg")))
        assertFalse(RecentClipboardRules.hasTextMimeType(listOf("text/uri-list")))
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

    /** 機密clip、機密欄、評価の記録中の本文を履歴へ保存しない。 */
    @Test
    fun clipboardHistoryRejectsSensitiveSourcesAndFields() {
        val ordinary = ClipboardHistoryCapturePolicy(
            sensitiveField = false,
            evaluationRecording = false,
        )
        assertFalse(ClipboardHistoryRules.mayStoreText(clipboardSensitive = true, capturePolicy = ordinary))
        assertFalse(ClipboardHistoryRules.mayStoreText(clipboardSensitive = false, capturePolicy = ordinary.copy(sensitiveField = true)))
        assertFalse(ClipboardHistoryRules.mayStoreText(clipboardSensitive = false, capturePolicy = ordinary.copy(evaluationRecording = true)))
        assertTrue(ClipboardHistoryRules.mayStoreText(clipboardSensitive = false, capturePolicy = ordinary))
    }

    /** 履歴の可否は欄の種類だけで決め、設定の学習OFFでは保存も表示も止めない。 */
    @Test
    fun clipboardHistoryFollowsFieldTypeInsteadOfLearningSetting() {
        val ordinary = InputFieldPolicy(
            isPassword = false,
            noPersonalizedLearning = false,
            isTypeNull = false,
            isNumeric = false,
            isMultiLine = false,
            noEnterAction = false,
            imeAction = EditorInfo.IME_ACTION_DONE,
            actionLabel = "完了",
        )
        val learningOff = ordinary.copy(learningDisabledBySetting = true)
        val noPersonalized = ordinary.copy(noPersonalizedLearning = true)
        val password = ordinary.copy(isPassword = true)

        assertTrue(ClipboardHistoryRules.mayStoreText(false, ClipboardHistoryRules.capturePolicy(learningOff, evaluationRecording = false)))
        assertTrue(ClipboardHistoryRules.mayShowHistory(learningOff, historyEnabled = true, evaluationRecording = false))
        listOf(noPersonalized, password, null).forEach { policy ->
            assertFalse(ClipboardHistoryRules.mayStoreText(false, ClipboardHistoryRules.capturePolicy(policy, evaluationRecording = false)))
            assertFalse(ClipboardHistoryRules.mayShowHistory(policy, historyEnabled = true, evaluationRecording = false))
        }
        assertFalse(ClipboardHistoryRules.mayShowHistory(ordinary, historyEnabled = false, evaluationRecording = false))
        assertFalse(ClipboardHistoryRules.mayShowHistory(ordinary, historyEnabled = true, evaluationRecording = true))
    }

    /** 履歴をONへ戻した時刻より前のコピーは保存対象にせず、OFFにしたことがなければ全てを対象にする。 */
    @Test
    fun clipboardHistorySkipsClipsCopiedWhileHistoryWasOff() {
        assertTrue(ClipboardHistoryRules.copiedBeforeEnabled(timestamp = 999L, enabledSinceMillis = 1_000L))
        assertFalse(ClipboardHistoryRules.copiedBeforeEnabled(timestamp = 1_000L, enabledSinceMillis = 1_000L))
        assertFalse(ClipboardHistoryRules.copiedBeforeEnabled(timestamp = 1L, enabledSinceMillis = 0L))
    }

    /** 通知時の機密欄状態を保存し、後から通常欄へ移っても後追い保存しない。 */
    @Test
    fun clipboardHistoryKeepsThePolicyCapturedWhenClipboardChanged() {
        val policyAtCopy = ClipboardHistoryCapturePolicy(
            sensitiveField = true,
            evaluationRecording = false,
        )
        val policyAtLaterHandling = ClipboardHistoryCapturePolicy(
            sensitiveField = false,
            evaluationRecording = false,
        )

        assertFalse(ClipboardHistoryRules.mayStoreText(clipboardSensitive = false, capturePolicy = policyAtCopy))
        assertTrue(ClipboardHistoryRules.mayStoreText(clipboardSensitive = false, capturePolicy = policyAtLaterHandling))
    }

    /** 未固定項目は1時間で消え、同じ期限を過ぎた固定項目は残す。 */
    @Test
    fun clipboardHistoryExpiresUnpinnedItemsAndKeepsPins() {
        val now = 7_200_000L
        val items = listOf(
            ClipboardHistoryItem("almost expired", 3_600_001L, pinned = false),
            ClipboardHistoryItem("expired", 3_600_000L, pinned = false),
            ClipboardHistoryItem("pinned", 1L, pinned = true),
        )

        assertEquals(
            listOf("almost expired", "pinned"),
            ClipboardHistoryRules.prune(items, now).map(ClipboardHistoryItem::text),
        )
    }

    /** 未固定履歴が30件を超えたとき、古い順に落とし、固定枠を別に保つ。 */
    @Test
    fun clipboardHistoryCapsUnpinnedItemsAndKeepsPinnedOutsideTheLimit() {
        val now = 10_000_000L
        var items = emptyList<ClipboardHistoryItem>()
        (1..40).forEach { index ->
            items = ClipboardHistoryRules.add(items, "item-$index", now - 100L + index, now)
        }
        (31..40).forEach { index -> items = ClipboardHistoryRules.setPinned(items, "item-$index", pinned = true) }
        (41..55).forEach { index ->
            items = ClipboardHistoryRules.add(items, "item-$index", now - 100L + index, now)
        }

        assertEquals(10, items.count { it.pinned })
        assertEquals(30, items.count { !it.pinned })
        assertFalse(items.any { it.text == "item-11" })
        assertTrue(items.any { it.text == "item-31" && it.pinned })
    }

    /** 同じ本文を再コピーすると一番前へ移し、固定状態を維持して取得時刻だけ更新する。 */
    @Test
    fun duplicateClipboardHistoryMovesToFrontAndPreservesPin() {
        var items = ClipboardHistoryRules.add(emptyList(), "same", 1_000L, 1_000L)
        items = ClipboardHistoryRules.setPinned(items, "same", pinned = true)
        items = ClipboardHistoryRules.add(items, "other", 2_000L, 2_000L)
        items = ClipboardHistoryRules.add(items, "same", 3_000L, 3_000L)

        assertEquals("same", items.first().text)
        assertTrue(items.first().pinned)
        assertEquals(3_000L, items.first().copiedAtMillis)
        assertEquals(2, items.size)
    }

    /** 古い項目を固定解除しても、より新しい30件が優先して残る。 */
    @Test
    fun unpinningOldClipboardItemDoesNotEvictNewerItems() {
        val now = 20_000_000L
        var items = ClipboardHistoryRules.add(emptyList(), "old", now - 100L, now)
        items = ClipboardHistoryRules.setPinned(items, "old", pinned = true)
        (1..30).forEach { index ->
            items = ClipboardHistoryRules.add(items, "new-$index", now - 99L + index, now)
        }
        items = ClipboardHistoryRules.setPinned(items, "old", pinned = false)

        assertEquals(30, items.size)
        assertFalse(items.any { it.text == "old" })
        assertTrue(items.any { it.text == "new-1" })
        assertTrue(items.any { it.text == "new-30" })
    }
}
