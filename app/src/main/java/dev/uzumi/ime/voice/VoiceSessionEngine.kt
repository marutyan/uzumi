package dev.uzumi.ime.voice

/**
 * 認識器バックエンドからセッション状態機械への通知。
 */
interface VoiceRecognizerCallbacks {
    /** 途中結果を受け取る。 */
    fun onPartialResults(texts: List<String>)

    /** 区切り単位の確定結果（API 33+ の onSegmentResults）を受け取る。 */
    fun onSegmentResults(texts: List<String>)

    /** 区切りセッションの終了（API 33+ の onEndOfSegmentedSession）を受け取る。 */
    fun onEndOfSegmentedSession()

    /** 最終結果を受け取る。 */
    fun onResults(texts: List<String>)

    /** エラーを受け取る。 */
    fun onError(error: VoiceInputError)
}

/**
 * AndroidのSpeechRecognizerを抽象化するバックエンド。JVMテストから差し替えられるよう分離している。
 */
interface VoiceRecognizerBackend {
    /** 端末内音声認識が利用可能かを返す。 */
    fun isOnDeviceAvailable(): Boolean

    /** RECORD_AUDIO権限が付与されているかを返す。 */
    fun hasRecordAudioPermission(): Boolean

    /** 新しい認識器を生成し、認識を開始する。失敗したらfalseを返す。 */
    fun startListening(callbacks: VoiceRecognizerCallbacks): Boolean

    /** 認識器へ発話終了（stopListening）を伝える。 */
    fun stopListening()

    /** 進行中の認識を取り消し、認識器を破棄する。 */
    fun cancel()

    /** 認識器を破棄する。 */
    fun destroy()
}

/**
 * 音声認識1セッションの状態遷移、二重開始防止、途中結果・区切り結果の統合、フィラー除去を担う純粋Kotlinクラス。
 *
 * 長い発話の扱いに関する判断：
 * - 試作の VoiceProbeSession.kt は EXTRA_SEGMENTED_SESSION を付けず、onSegmentResults /
 *   onEndOfSegmentedSession を実装せず、onPartialResults を lastPartialText に上書き保存していた
 *   （ただし events.tsv には全 partial を記録）。また、実機（Pixel 10 Pro / Android 17 / ASI）では
 *   約20秒・3文の V13 を含めて onResults が空となり、最後の onPartialResults に本文が入っていた。
 * - このため本実装でも、onResults が空のときは最後の onPartialResults を使うフォールバックを残す。
 * - さらに、OS や認識器が onSegmentResults / onEndOfSegmentedSession を発火した場合に取りこぼさないよう、
 *   これらを受け取って確定済み区切り（completedSegments）として積む。
 * - 一方で、「途中結果が前回より短くなったときに前の文とみなして連結する」ヒューリスティックは入れない。
 *   日本語の音声認識では仮名から漢字への変換で途中結果が頻繁に短く書き換わり（例：「ごごはおんせいにゅうりょく」
 *   →「午後は音声入力」）、文字数減少や先頭不一致で退避すると同じ文を二重に出力する危険が高いためである。
 */
class VoiceSessionEngine(
    private val backend: VoiceRecognizerBackend,
    private val listener: VoiceInputListener,
) {
    /** 現在の状態。 */
    var state: VoiceInputState = VoiceInputState.IDLE
        private set

    // 取消・終了後に遅延して届くコールバックを捨てるための世代番号。
    private var sessionGeneration = 0L
    private val completedSegments = mutableListOf<String>()
    private var lastPartialText: String? = null
    private var finalized = false

    /** 認識を開始する。すでに認識中または停止待ちなら開始せず [VoiceStartStatus.ALREADY_ACTIVE] を返す。 */
    fun start(): VoiceStartStatus {
        if (state == VoiceInputState.DESTROYED) return VoiceStartStatus.DESTROYED
        if (state != VoiceInputState.IDLE) return VoiceStartStatus.ALREADY_ACTIVE
        if (!backend.isOnDeviceAvailable()) {
            listener.onError(VoiceInputError.UNAVAILABLE)
            return VoiceStartStatus.UNAVAILABLE
        }
        if (!backend.hasRecordAudioPermission()) {
            listener.onError(VoiceInputError.PERMISSION_DENIED)
            return VoiceStartStatus.PERMISSION_DENIED
        }

        val generation = ++sessionGeneration
        resetBuffers()
        finalized = false
        setState(VoiceInputState.LISTENING)

        val started = backend.startListening(createCallbacks(generation))
        if (!started) {
            sessionGeneration++
            resetBuffers()
            setState(VoiceInputState.IDLE)
            listener.onError(VoiceInputError.UNAVAILABLE)
            return VoiceStartStatus.UNAVAILABLE
        }
        return VoiceStartStatus.STARTED
    }

    /** 発話終了を認識器へ伝え、最終結果の到着を待つ。認識中でなければ何もしない。 */
    fun stop(): Boolean {
        if (state != VoiceInputState.LISTENING) return false
        setState(VoiceInputState.STOPPING)
        backend.stopListening()
        return true
    }

    /** 進行中の認識を取り消して待機状態へ戻す。待機中・破棄済みなら何もしない。 */
    fun cancel(): Boolean {
        if (state == VoiceInputState.IDLE || state == VoiceInputState.DESTROYED) return false
        sessionGeneration++
        resetBuffers()
        finalized = true
        setState(VoiceInputState.IDLE)
        backend.cancel()
        listener.onCancelled()
        return true
    }

    /** 停止前や認識中であっても安全に認識器を破棄する。2回目以降の呼び出しは無視する。 */
    fun destroy() {
        if (state == VoiceInputState.DESTROYED) return
        val wasActive = state == VoiceInputState.LISTENING || state == VoiceInputState.STOPPING
        sessionGeneration++
        resetBuffers()
        finalized = true
        setState(VoiceInputState.DESTROYED)
        if (wasActive) {
            backend.cancel()
        } else {
            backend.destroy()
        }
    }

    private fun createCallbacks(generation: Long): VoiceRecognizerCallbacks =
        object : VoiceRecognizerCallbacks {
            override fun onPartialResults(texts: List<String>) {
                if (!isActiveGeneration(generation)) return
                val text = texts.firstOrNull() ?: return
                lastPartialText = text
                val combined = combineSegments(completedSegments, text)
                listener.onPartialResult(combined)
            }

            override fun onSegmentResults(texts: List<String>) {
                if (!isActiveGeneration(generation)) return
                val segment = texts.firstOrNull()
                if (!segment.isNullOrEmpty()) {
                    completedSegments.add(segment)
                }
                lastPartialText = null
                val combined = combineSegments(completedSegments, null)
                if (combined.isNotEmpty()) {
                    listener.onPartialResult(combined)
                }
            }

            override fun onEndOfSegmentedSession() {
                if (!isActiveGeneration(generation) || finalized) return
                completeWith(emptyList())
            }

            override fun onResults(texts: List<String>) {
                if (!isActiveGeneration(generation) || finalized) return
                completeWith(texts)
            }

            override fun onError(error: VoiceInputError) {
                if (!isActiveGeneration(generation) || finalized) return
                // Pixel 10 Pro（Android 17）の端末内認識器は、途中結果で本文を返した後でも、確定の段で
                // 「no speech」としてERROR_NO_MATCHを返すことがある（2026-11-14に実機のログで確認）。
                // そのときは、受け取っていた途中結果を確定文として扱う。
                if (error == VoiceInputError.NO_MATCH && (lastPartialText != null || completedSegments.isNotEmpty())) {
                    completeWith(emptyList())
                    return
                }
                finalized = true
                sessionGeneration++
                resetBuffers()
                setState(VoiceInputState.IDLE)
                backend.destroy()
                listener.onError(error)
            }
        }

    /** onResultsまたはonEndOfSegmentedSessionの到着でセッションを確定し、フィラー除去後の文を通知する。 */
    private fun completeWith(texts: List<String>) {
        finalized = true
        sessionGeneration++
        val resultText = texts.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartialText
        val rawCombined = combineSegments(completedSegments, resultText)
        resetBuffers()
        setState(VoiceInputState.IDLE)
        backend.destroy()
        val cleaned = VoiceFillerRemover.removeFillers(rawCombined)
        // 聞き取れなかった（またはフィラーだけだった）ときは、空の確定ではなくNO_MATCHとして知らせ、UIが出し分けられるようにする。
        if (cleaned.isBlank()) {
            listener.onError(VoiceInputError.NO_MATCH)
        } else {
            listener.onFinalResult(cleaned)
        }
    }

    private fun isActiveGeneration(generation: Long): Boolean =
        generation == sessionGeneration && (state == VoiceInputState.LISTENING || state == VoiceInputState.STOPPING)

    private fun setState(next: VoiceInputState) {
        if (state == next) return
        state = next
        listener.onStateChanged(next)
    }

    private fun resetBuffers() {
        completedSegments.clear()
        lastPartialText = null
    }

    /** 確定済み区切りと現在の末尾テキストを空白なしで連結する。 */
    private fun combineSegments(segments: List<String>, trailing: String?): String {
        if (segments.isEmpty()) return trailing.orEmpty()
        return buildString {
            for (segment in segments) {
                append(segment)
            }
            if (!trailing.isNullOrEmpty()) {
                append(trailing)
            }
        }
    }
}
