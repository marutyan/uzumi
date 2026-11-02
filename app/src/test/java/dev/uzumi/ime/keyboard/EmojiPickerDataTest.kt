package dev.uzumi.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 絵文字一覧の最近履歴を保存する条件と件数を検証する。 */
class EmojiPickerDataTest {
    /** 入力に成功した通常欄の値だけを最近の先頭へ移し、機密欄や失敗を保存しない。 */
    @Test
    fun recentHistoryOnlyRecordsSuccessfulNonSensitiveSelections() {
        val current = listOf("😀", "(・・;)")

        assertEquals(
            listOf("😂", "😀", "(・・;)"),
            EmojiRecentHistory.updated(current, "😂", insertionSucceeded = true, learningSuppressed = false),
        )
        assertEquals(
            current,
            EmojiRecentHistory.updated(current, "🔐", insertionSucceeded = true, learningSuppressed = true),
        )
        assertEquals(
            current,
            EmojiRecentHistory.updated(current, "🙂", insertionSucceeded = false, learningSuppressed = false),
        )
    }

    /** 最近タブでも絵文字は絵文字の列数、顔文字は顔文字の列数で行に分ける。 */
    @Test
    fun gridSplitsEmojiAndKaomojiRowsByTheirColumnCounts() {
        fun item(value: String, kaomoji: Boolean) = EmojiPickerItem(value, value, emptySet(), kaomoji)
        val items = listOf(item("a", true), item("😀", false), item("😂", false), item("😍", false), item("b", true), item("c", true))

        val rows = EmojiPickerGrid.rows(items, emojiColumns = 2, kaomojiColumns = 3)

        assertEquals(listOf(listOf("😀", "😂"), listOf("😍"), listOf("a", "b", "c")), rows.map { row -> row.map { it.value } })
    }

    /** 同じ項目は重複させず、保存する件数を数十件の上限内に保つ。 */
    @Test
    fun recentHistoryMovesDuplicatesAndCapsEntryCount() {
        val current = (1..48).map { "item-$it" }

        val moved = EmojiRecentHistory.updated(current, current.last(), insertionSucceeded = true, learningSuppressed = false)
        assertEquals(48, moved.size)
        assertEquals("item-48", moved.first())
        assertEquals(moved.size, moved.toSet().size)

        val added = EmojiRecentHistory.updated(current, "新しい項目", insertionSucceeded = true, learningSuppressed = false)
        assertEquals(48, added.size)
        assertEquals("新しい項目", added.first())
        assertTrue("item-48" !in added)
    }
}
