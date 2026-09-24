package dev.uzumi.ime.neural

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:neural`の終了後に接続し直す規則を固定する。実機で`:neural`を4回続けて終了させたとき、4回目の後は接続し直さず、
 * 同じモデルのまま次の入力欄でも作り直されなかった（Mozcだけが続いた）不具合の再発を防ぐ。
 */
class NeuralRestartPolicyTest {
    @Test
    fun delaysGrowAndStopAtTheLimit() {
        val policy = NeuralRestartPolicy(maxRestarts = 3, baseDelayMillis = 1_000)
        assertFalse(policy.exhausted)
        assertEquals(1_000L, policy.nextDelayMillis())
        assertEquals(2_000L, policy.nextDelayMillis())
        assertEquals(3_000L, policy.nextDelayMillis())
        assertTrue(policy.exhausted)
        assertNull(policy.nextDelayMillis())
    }

    @Test
    fun connectionUsesTheDocumentedLimit() {
        val policy = NeuralRestartPolicy(NeuralRuntimeConnection.MAX_RESTARTS, NeuralRuntimeConnection.RESTART_DELAY_MILLIS)
        repeat(NeuralRuntimeConnection.MAX_RESTARTS) { assertTrue(policy.nextDelayMillis() != null) }
        // 上限を超えた後は接続し直さず、IMEが次の入力欄で接続を作り直す（gaveUp）。
        assertTrue(policy.exhausted)
    }
}
