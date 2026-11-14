package dev.uzumi.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 起動画面の手順の進み具合が、有効か・選ばれているかから決まることを確かめる。 */
class SetupProgressTest {
    @Test
    fun progressFollowsEnableThenSelect() {
        assertEquals(SetupProgress.NOT_ENABLED, SetupProgress.of(enabled = false, selected = false))
        assertEquals(SetupProgress.ENABLED, SetupProgress.of(enabled = true, selected = false))
        assertEquals(SetupProgress.READY, SetupProgress.of(enabled = true, selected = true))
    }

    /** 有効でない入力方法は選べないため、選ばれていても有効にする手順から見せる。 */
    @Test
    fun selectedButNotEnabledStartsFromFirstStep() {
        assertEquals(SetupProgress.NOT_ENABLED, SetupProgress.of(enabled = false, selected = true))
    }
}
