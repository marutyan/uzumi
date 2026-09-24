package dev.uzumi.ime.neural

import dev.uzumi.ime.conversion.NeuralModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:neural`の終了後に接続し直す規則と、入力欄の開始で接続を作り直すかの判定を固定する。
 * 実機で`:neural`を4回続けて終了させたとき、同じモデルのまま新しい入力欄でも作り直されなかった不具合と、
 * 同じ欄の再開始（restartInput）で予約と回数が作り直され、上限を越えて接続し直し続ける不具合の再発を防ぐ。
 */
class NeuralRestartPolicyTest {
    @Test
    fun delaysGrowAndStopAtTheLimit() {
        val policy = NeuralRestartPolicy(maxRestarts = 3, baseDelayMillis = 1_000)
        assertEquals(1_000L, policy.nextDelayMillis())
        assertEquals(2_000L, policy.nextDelayMillis())
        assertEquals(3_000L, policy.nextDelayMillis())
        assertTrue(policy.exhausted)
        assertNull(policy.nextDelayMillis())
        assertTrue(policy.gaveUp)
    }

    @Test
    fun notGivenUpWhileTheLastRestartIsScheduled() {
        val policy = NeuralRestartPolicy(NeuralRuntimeConnection.MAX_RESTARTS, NeuralRuntimeConnection.RESTART_DELAY_MILLIS)
        repeat(NeuralRuntimeConnection.MAX_RESTARTS) { assertTrue(policy.nextDelayMillis() != null) }
        // 3回目の再接続を予約した時点（まだ試していない）では、やめたことにしない。
        assertFalse(policy.gaveUp)
        // 3回目の再接続の後にも終了したら、やめる。
        assertNull(policy.nextDelayMillis())
        assertTrue(policy.gaveUp)
    }

    @Test
    fun scheduledRestartIsKeptOnRestartOfTheSameFieldAndOnANewField() {
        // やめていない（予約中の）接続は、同じ欄の再開始でも新しい欄の開始でも作り直さない（回数は戻らない）。
        val spec = NeuralModelSpec.ZENZ_XSMALL
        assertFalse(shouldRecreateNeuralConnection(spec, currentGaveUp = false, selected = spec, restarting = true))
        assertFalse(shouldRecreateNeuralConnection(spec, currentGaveUp = false, selected = spec, restarting = false))
    }

    @Test
    fun givenUpConnectionIsRecreatedOnlyForANewField() {
        val spec = NeuralModelSpec.ZENZ_XSMALL
        assertFalse(shouldRecreateNeuralConnection(spec, currentGaveUp = true, selected = spec, restarting = true))
        assertTrue(shouldRecreateNeuralConnection(spec, currentGaveUp = true, selected = spec, restarting = false))
    }

    @Test
    fun changedSelectionIsAlwaysApplied() {
        assertTrue(shouldRecreateNeuralConnection(NeuralModelSpec.ZENZ_XSMALL, false, NeuralModelSpec.JINEN_XSMALL, restarting = true))
        assertTrue(shouldRecreateNeuralConnection(null, false, NeuralModelSpec.JINEN_XSMALL, restarting = false))
        assertTrue(shouldRecreateNeuralConnection(NeuralModelSpec.JINEN_XSMALL, false, null, restarting = true))
        assertFalse(shouldRecreateNeuralConnection(null, false, null, restarting = false))
    }
}
