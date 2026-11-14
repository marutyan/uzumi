package dev.uzumi.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VoiceSessionEngine]の状態遷移、二重開始防止、途中結果・区切り結果の統合、安全な破棄を検証する。 */
class VoiceSessionEngineTest {
    /** 端末内認識器が使えない場合はエラーを通知し、録音開始へ進まない。 */
    @Test
    fun reportsUnavailableWithoutFallback() {
        val backend = FakeBackend(onDeviceAvailable = false)
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        val status = engine.start()

        assertEquals(VoiceStartStatus.UNAVAILABLE, status)
        assertEquals(VoiceInputState.IDLE, engine.state)
        assertEquals(0, backend.startCalls)
        assertEquals(listOf(VoiceInputError.UNAVAILABLE), listener.errors)
    }

    /** 録音権限が無い場合は開始せずPERMISSION_DENIEDを返す。 */
    @Test
    fun reportsPermissionDeniedWhenRecordAudioMissing() {
        val backend = FakeBackend(recordAudioGranted = false)
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        val status = engine.start()

        assertEquals(VoiceStartStatus.PERMISSION_DENIED, status)
        assertEquals(VoiceInputState.IDLE, engine.state)
        assertEquals(0, backend.startCalls)
        assertEquals(listOf(VoiceInputError.PERMISSION_DENIED), listener.errors)
    }

    /** 認識中・停止待ちの二重開始を安全に拒否する。 */
    @Test
    fun ignoresDoubleStartWhileActive() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertEquals(VoiceStartStatus.STARTED, engine.start())
        assertEquals(VoiceStartStatus.ALREADY_ACTIVE, engine.start())
        assertTrue(engine.stop())
        assertEquals(VoiceStartStatus.ALREADY_ACTIVE, engine.start())
        assertEquals(1, backend.startCalls)
    }

    /** 待機中のstopは無視される。 */
    @Test
    fun stopWhileIdleDoesNothing() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertFalse(engine.stop())
        assertEquals(0, backend.stopCalls)
    }

    /** 途中結果は加工せず通知し、onResultsが空なら最後の途中結果へフィラー除去をかけて確定通知する。 */
    @Test
    fun fallsBackToLastPartialWhenOnResultsIsEmptyAndRemovesFillers() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertEquals(VoiceStartStatus.STARTED, engine.start())
        backend.callbacks!!.onPartialResults(listOf("えっと"))
        backend.callbacks!!.onPartialResults(listOf("えっと明日の会議"))
        backend.callbacks!!.onResults(emptyList())

        assertEquals(listOf("えっと", "えっと明日の会議"), listener.partials)
        assertEquals(listOf("明日の会議"), listener.finals)
        assertEquals(VoiceInputState.IDLE, engine.state)
        assertEquals(1, backend.destroyCalls)
    }

    /** 仮名→漢字の書き換わりで途中結果が短くなっても前の仮名を二重に残さない。 */
    @Test
    fun doesNotDuplicateShrinkingJapaneseKanjiConversionPartial() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertEquals(VoiceStartStatus.STARTED, engine.start())
        backend.callbacks!!.onPartialResults(listOf("ごごはおんせいにゅうりょく"))
        backend.callbacks!!.onPartialResults(listOf("午後は音声入力"))
        backend.callbacks!!.onResults(emptyList())

        assertEquals(listOf("午後は音声入力"), listener.finals)
    }

    /** onSegmentResultsで確定した区切りを保持し、onEndOfSegmentedSessionでフィラー除去して返す。 */
    @Test
    fun accumulatesSegmentResultsAcrossLongSession() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertEquals(VoiceStartStatus.STARTED, engine.start())
        backend.callbacks!!.onPartialResults(listOf("えっと今日の報告です。"))
        backend.callbacks!!.onSegmentResults(listOf("えっと今日の報告です。"))
        backend.callbacks!!.onPartialResults(listOf("午後はえ音声入力を試します。"))
        backend.callbacks!!.onEndOfSegmentedSession()

        assertEquals(listOf("今日の報告です。午後は音声入力を試します。"), listener.finals)
        assertEquals(VoiceInputState.IDLE, engine.state)
        assertEquals(1, backend.destroyCalls)
    }

    /** 取り消し後は古い認識器から遅れて届いた結果を無視する。 */
    @Test
    fun cancelIgnoresLateCallbacks() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertEquals(VoiceStartStatus.STARTED, engine.start())
        val staleCallbacks = backend.callbacks!!
        assertTrue(engine.cancel())

        staleCallbacks.onPartialResults(listOf("古い途中結果"))
        staleCallbacks.onResults(listOf("古い確定結果"))
        staleCallbacks.onError(VoiceInputError.NO_MATCH)

        assertEquals(1, backend.cancelCalls)
        assertEquals(1, listener.cancelledCount)
        assertTrue(listener.partials.isEmpty())
        assertTrue(listener.finals.isEmpty())
        assertTrue(listener.errors.isEmpty())
        assertEquals(VoiceInputState.IDLE, engine.state)
    }

    /** 停止前のdestroyは安全に認識を取り消して破棄し、以降の開始を拒否する。 */
    @Test
    fun destroyBeforeStopCancelsSafelyAndBlocksFurtherStarts() {
        val backend = FakeBackend()
        val listener = RecordingListener()
        val engine = VoiceSessionEngine(backend, listener)

        assertEquals(VoiceStartStatus.STARTED, engine.start())
        val staleCallbacks = backend.callbacks!!
        engine.destroy()
        engine.destroy()

        staleCallbacks.onResults(listOf("破棄後の結果"))

        assertEquals(VoiceInputState.DESTROYED, engine.state)
        assertEquals(1, backend.cancelCalls)
        assertEquals(VoiceStartStatus.DESTROYED, engine.start())
        assertFalse(engine.stop())
        assertFalse(engine.cancel())
        assertTrue(listener.finals.isEmpty())
    }

    private class FakeBackend(
        private val onDeviceAvailable: Boolean = true,
        private val recordAudioGranted: Boolean = true,
        private val startResult: Boolean = true,
    ) : VoiceRecognizerBackend {
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        var destroyCalls = 0
        var callbacks: VoiceRecognizerCallbacks? = null

        override fun isOnDeviceAvailable(): Boolean = onDeviceAvailable

        override fun hasRecordAudioPermission(): Boolean = recordAudioGranted

        override fun startListening(callbacks: VoiceRecognizerCallbacks): Boolean {
            startCalls++
            if (!startResult) return false
            this.callbacks = callbacks
            return true
        }

        override fun stopListening() {
            stopCalls++
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun destroy() {
            destroyCalls++
        }
    }

    private class RecordingListener : VoiceInputListener {
        val states = mutableListOf<VoiceInputState>()
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val errors = mutableListOf<VoiceInputError>()
        var cancelledCount = 0

        override fun onStateChanged(state: VoiceInputState) {
            states.add(state)
        }

        override fun onPartialResult(text: String) {
            partials.add(text)
        }

        override fun onFinalResult(text: String) {
            finals.add(text)
        }

        override fun onError(error: VoiceInputError) {
            errors.add(error)
        }

        override fun onCancelled() {
            cancelledCount++
        }
    }
}
