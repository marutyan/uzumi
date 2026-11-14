package dev.uzumi.ime.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechtranslation.SpeechTranslation
import com.google.mlkit.genai.speechtranslation.SpeechTranslator
import com.google.mlkit.genai.speechtranslation.SpeechTranslatorOptions
import com.google.mlkit.genai.speechtranslation.SpeechTranslatorRequest
import com.google.mlkit.genai.speechtranslation.SpeechTranslatorResponse
import com.google.mlkit.genai.speechtranslation.TranslationAudio
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * 端末内の音声翻訳（ML Kit GenAI Speech Translation）で、マイクの音声を訳して1回分の文にまとめる窓口。
 * 開始・停止・取り消し・破棄はメインスレッドで扱い、通知もメインスレッドで[listener]へ送る。
 * 通信はせず、翻訳モデルの取得は端末のAICoreが行う。モデルが未取得なら1回だけ取得を始め、その間は翻訳を始めない。
 *
 * 停止の扱い：翻訳器は発話の区切りごとに確定した訳文だけを返し、音声の流れを閉じると未確定の最後の文を捨てて
 * すぐ完了する（stopTranslationも同じ）。そのため停止の操作ではマイクだけを先に閉じ、無音を実時間で流してから
 * 流れを閉じて、最後の文が確定するのを待つ。
 */
class OnDeviceVoiceTranslator(
    context: Context,
    private val listener: VoiceInputListener,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlerToken = Any()
    // 翻訳とモデル取得のcoroutine。破棄でまとめて止める。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 現在の状態（メインスレッドから参照する）。 */
    var state: VoiceInputState = VoiceInputState.IDLE
        private set

    // 取り消し・終了後に遅れて届く通知を捨てるための世代番号。メインスレッドだけで読み書きする。
    private var sessionGeneration = 0L
    private var sessionJob: Job? = null
    // 停止の操作でマイクを閉じる合図。録音のthreadが読む。
    private var stopRequested: AtomicBoolean? = null
    // 停止から確定を待つ上限を超えたことを、取り消された翻訳のcoroutineへ伝える。立っていれば届いた訳文で確定する。
    private var stopTimedOut: AtomicBoolean? = null
    // 停止の後に確定が届かないまま止まった場合に備える見張り。
    private var stopWatchdog: Runnable? = null
    // このIMEが取得を始め、まだ終わっていない向き。同じ向きの取得を何度も始めないために持つ。
    private val downloadingDirections = mutableSetOf<VoiceTranslationDirection>()

    /**
     * [direction]の向きで翻訳を始める。モデルの状態の確認は非同期で行い、使えなければ[VoiceInputListener.onError]で知らせる。
     * メインスレッド以外から呼ばれた場合はメインスレッドへ投入し[VoiceStartStatus.DISPATCHED]を返す。
     */
    fun start(direction: VoiceTranslationDirection): VoiceStartStatus {
        if (!isMainThread()) {
            postToMain { start(direction) }
            return VoiceStartStatus.DISPATCHED
        }
        if (state == VoiceInputState.DESTROYED) return VoiceStartStatus.DESTROYED
        if (state != VoiceInputState.IDLE) return VoiceStartStatus.ALREADY_ACTIVE
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(VoiceInputError.PERMISSION_DENIED)
            return VoiceStartStatus.PERMISSION_DENIED
        }
        val generation = ++sessionGeneration
        val stopSignal = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        stopRequested = stopSignal
        stopTimedOut = timedOut
        setState(VoiceInputState.LISTENING)
        sessionJob = scope.launch { runSession(generation, direction, stopSignal, timedOut) }
        return VoiceStartStatus.STARTED
    }

    /** マイクを閉じ、無音を流して最後の文が確定するのを待つ。翻訳中でなければ何もしない。 */
    fun stop(): Boolean {
        if (!isMainThread()) {
            postToMain { stop() }
            return true
        }
        if (state != VoiceInputState.LISTENING) return false
        stopRequested?.set(true)
        setState(VoiceInputState.STOPPING)
        val generation = sessionGeneration
        val timedOut = stopTimedOut
        // 翻訳器が完了を返さないまま止まっても停止待ちが残り続けないよう、上限を超えたら届いた訳文で確定させる。
        val watchdog = Runnable {
            if (generation != sessionGeneration || state != VoiceInputState.STOPPING) return@Runnable
            timedOut?.set(true)
            sessionJob?.cancel()
        }
        stopWatchdog = watchdog
        mainHandler.postAtTime(watchdog, handlerToken, SystemClock.uptimeMillis() + STOP_TIMEOUT_MILLIS)
        return true
    }

    /** マイクと翻訳を止め、届いた訳文を入れずに捨てる。翻訳中・停止待ちでなければ何もしない。 */
    fun cancel(): Boolean {
        if (!isMainThread()) {
            postToMain { cancel() }
            return true
        }
        if (state != VoiceInputState.LISTENING && state != VoiceInputState.STOPPING) return false
        // 録音のループはcoroutineの取り消しでは止まらない（AudioRecordの読み出しで塞がる）ので、合図でマイクを閉じさせる。
        stopRequested?.set(true)
        sessionJob?.cancel()
        endSession()
        listener.onCancelled()
        return true
    }

    /** 翻訳とモデルの取得をすべて止める。2回目以降の呼び出しは無視する。 */
    fun destroy() {
        if (!isMainThread()) {
            postToMain { destroy() }
            return
        }
        if (state == VoiceInputState.DESTROYED) return
        mainHandler.removeCallbacksAndMessages(handlerToken)
        stopRequested?.set(true)
        sessionGeneration++
        sessionJob = null
        stopRequested = null
        scope.cancel()
        setState(VoiceInputState.DESTROYED)
    }

    /**
     * 1回分の翻訳を行う。モデルの確認、マイクの音声の受け渡し、訳文の集約までをcoroutineで進め、
     * 結果はメインスレッドへ戻して世代番号が一致するときだけ通知する。
     */
    private suspend fun runSession(
        generation: Long,
        direction: VoiceTranslationDirection,
        stopSignal: AtomicBoolean,
        timedOut: AtomicBoolean,
    ) {
        val source = Locale.forLanguageTag(direction.sourceTag)
        val translator = createTranslator(direction)
        val segments = mutableListOf<String>()
        var errorResponses = 0
        try {
            when (val status = translator.checkStatus()) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    postToMain { onModelNotReady(generation, direction, status == FeatureStatus.DOWNLOADABLE) }
                    return
                }
                else -> {
                    Log.w(TAG, "translation unavailable: direction=${direction.id} status=$status")
                    postToMain { fail(generation, VoiceInputError.UNAVAILABLE) }
                    return
                }
            }
            val stats = MicrophoneStats()
            // 翻訳器が音声を受け取らなくなったら、受け渡し側は止まったまま例外を読めないので、ここから失敗として終える。
            val onStalled = {
                postToMain {
                    if (isActiveGeneration(generation)) {
                        sessionJob?.cancel()
                        fail(generation, VoiceInputError.AUDIO)
                    }
                }
            }
            val request = SpeechTranslatorRequest.builder(microphoneAudio(source, stopSignal, stats, onStalled)).build()
            translator.startTranslation(request)
                // 完了の応答の後に流れが閉じない場合に備え、完了を受け取った時点で読むのをやめる。
                .transformWhile { response ->
                    emit(response)
                    response !is SpeechTranslatorResponse.CompletedResponse
                }
                .collect { response ->
                    when (response) {
                        is SpeechTranslatorResponse.Text -> {
                            val segment = response.translatedText.trim()
                            if (segment.isEmpty()) return@collect
                            segments += segment
                            stats.recordSegment()
                            val joined = joinSegments(segments, direction)
                            postToMain { notifyPartial(generation, joined) }
                        }
                        is SpeechTranslatorResponse.ErrorResponse -> {
                            errorResponses++
                            Log.w(TAG, "translation error response: code=${response.e.errorCode}", response.e)
                        }
                        else -> Unit
                    }
                }
            // 実機で、IMEのprocessから録った音が無音へ置き換えられていないかと、最後の文が無音の間に届いたかを確かめるための記録。
            // 訳文そのものは記録しない。
            Log.i(TAG, "session finished: direction=${direction.id} ${stats.summary()} segments=${segments.size} errors=$errorResponses")
            finishWith(generation, direction, segments, errorResponses)
        } catch (error: CancellationException) {
            // 停止の上限を超えて取り消した場合は、そこまでに届いた訳文で確定する。利用者の取り消しでは何も入れない。
            if (timedOut.get()) {
                Log.w(TAG, "translation did not complete after stop: direction=${direction.id} segments=${segments.size}")
                finishWith(generation, direction, segments, errorResponses)
            }
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "translation failed: direction=${direction.id}", error)
            val mapped = when (error) {
                is MicrophoneException, is TranslatorStalledException -> VoiceInputError.AUDIO
                is SecurityException -> VoiceInputError.PERMISSION_DENIED
                else -> VoiceInputError.RECOGNIZER_ERROR
            }
            postToMain { fail(generation, mapped) }
        } finally {
            // 翻訳器の誤りなどで受け渡しが先に終わった場合も、録音のループを止めてマイクを閉じる。
            stopSignal.set(true)
            translator.close()
        }
    }

    /**
     * 届いた訳文[segments]をつなぎ、訳先が英語ならフィラーを消して、メインスレッドで確定または失敗として知らせる。
     */
    private fun finishWith(
        generation: Long,
        direction: VoiceTranslationDirection,
        segments: List<String>,
        errorResponses: Int,
    ) {
        val raw = joinSegments(segments, direction)
        // 英語のフィラーは訳先が英語のときだけ消す。日本語の訳文には英語のフィラーの規則を当てない。
        val text = if (direction.targetTag.startsWith("en")) EnglishFillerRemover.removeFillers(raw) else raw
        // 聞き取れなかった（またはフィラーだけだった）ときは、空の確定ではなくNO_MATCHとして知らせ、UIが出し分けられるようにする。
        // 翻訳器が誤りを返していた場合は、聞き取れなかったのではなく処理の失敗として知らせる。
        postToMain {
            when {
                text.isNotBlank() -> complete(generation, text)
                errorResponses > 0 -> fail(generation, VoiceInputError.RECOGNIZER_ERROR)
                else -> fail(generation, VoiceInputError.NO_MATCH)
            }
        }
    }

    /**
     * モデルが未取得または取得中のため翻訳を始められないことを知らせる。[canStartDownload]なら、
     * まだこのIMEが始めていない向きに限って取得を1回だけ始める。
     */
    private fun onModelNotReady(generation: Long, direction: VoiceTranslationDirection, canStartDownload: Boolean) {
        if (canStartDownload) {
            startDownload(direction)
        } else if (direction !in downloadingDirections) {
            // 端末側ですでに取得が進んでいる。取得中であることだけを知らせる。
            listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.DOWNLOADING, null)
        }
        fail(generation, VoiceInputError.MODEL_DOWNLOADING)
    }

    /** [direction]のモデルの取得を始め、終わったら結果を知らせる。同じ向きの取得が進行中なら何もしない。 */
    private fun startDownload(direction: VoiceTranslationDirection) {
        if (state == VoiceInputState.DESTROYED || !downloadingDirections.add(direction)) return
        listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.DOWNLOADING, null)
        scope.launch {
            var completed = false
            // 取得の開始で知らされる全体の大きさと、最後に知らせた百分率。進み具合の通知を1%刻みに抑えるために持つ。
            var bytesToDownload = 0L
            var lastPercent = -1
            val translator = createTranslator(direction)
            try {
                translator.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> bytesToDownload = status.bytesToDownload
                        is DownloadStatus.DownloadProgress -> {
                            if (bytesToDownload <= 0) return@collect
                            val fraction = (status.totalBytesDownloaded.toDouble() / bytesToDownload).coerceIn(0.0, 1.0)
                            val percent = (fraction * 100).toInt()
                            if (percent == lastPercent) return@collect
                            lastPercent = percent
                            postToMain {
                                listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.DOWNLOADING, fraction.toFloat())
                            }
                        }
                        is DownloadStatus.DownloadCompleted -> completed = true
                        is DownloadStatus.DownloadFailed -> Log.w(TAG, "model download failed: direction=${direction.id}", status.e)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "model download failed: direction=${direction.id}", error)
            } finally {
                translator.close()
            }
            Log.i(TAG, "model download finished: direction=${direction.id} completed=$completed bytes_to_download=$bytesToDownload")
            postToMain {
                downloadingDirections.remove(direction)
                if (completed) {
                    listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.COMPLETED, 1f)
                } else {
                    listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.FAILED, null)
                }
            }
        }
    }

    /** 届いた訳文をつないだ全体を、現在の翻訳の途中経過として知らせる。 */
    private fun notifyPartial(generation: Long, text: String) {
        if (isActiveGeneration(generation)) listener.onPartialResult(text)
    }

    /** 翻訳を終えて、入力する文を知らせる。 */
    private fun complete(generation: Long, text: String) {
        if (!isActiveGeneration(generation)) return
        endSession()
        listener.onFinalResult(text)
    }

    /** 翻訳を失敗として終える。 */
    private fun fail(generation: Long, error: VoiceInputError) {
        if (!isActiveGeneration(generation)) return
        endSession()
        listener.onError(error)
    }

    /** 進行中の翻訳の記録を捨てて待機状態へ戻す。以降に届く同じ回の通知は世代番号で捨てる。 */
    private fun endSession() {
        stopWatchdog?.let(mainHandler::removeCallbacks)
        stopWatchdog = null
        sessionGeneration++
        sessionJob = null
        stopRequested = null
        stopTimedOut = null
        setState(VoiceInputState.IDLE)
    }

    private fun isActiveGeneration(generation: Long): Boolean =
        generation == sessionGeneration && (state == VoiceInputState.LISTENING || state == VoiceInputState.STOPPING)

    private fun setState(next: VoiceInputState) {
        if (state == next) return
        state = next
        listener.onStateChanged(next)
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun postToMain(action: () -> Unit) {
        mainHandler.postAtTime(action, handlerToken, SystemClock.uptimeMillis())
    }

    /** [direction]の向きの翻訳器を作る。使い終えたら呼び出し側で閉じる。 */
    private fun createTranslator(direction: VoiceTranslationDirection): SpeechTranslator =
        SpeechTranslation.getClient(
            SpeechTranslatorOptions.builder(
                Locale.forLanguageTag(direction.sourceTag),
                Locale.forLanguageTag(direction.targetTag),
            ).build(),
        )

    /** 区切りごとの訳文を、訳先の言語の書き方に合わせてつなぐ。英語は空白で、日本語は詰めてつなぐ。 */
    private fun joinSegments(segments: List<String>, direction: VoiceTranslationDirection): String =
        segments.joinToString(if (direction.targetTag.startsWith("en")) " " else "")

    /**
     * マイクの音声を100ミリ秒ずつ翻訳器へ渡す流れ。[stopSignal]が立ったらマイクを閉じ、
     * 最後の文を確定させるための無音を実時間の速さで流してから流れを閉じる。
     * 録音は翻訳器への受け渡しと切り離し、間に上限つきの待ち行列を置く。翻訳器が受け取りを止めても録音のthreadは塞がらず、
     * 待ち行列があふれた時点でマイクを閉じ、[onStalled]で失敗として終える（マイクが開いたまま残らないようにするため）。
     * 録音権限は[start]で確かめた後にだけ呼ばれる。
     */
    private fun microphoneAudio(
        source: Locale,
        stopSignal: AtomicBoolean,
        stats: MicrophoneStats,
        onStalled: () -> Unit,
    ): Flow<TranslationAudio> = channelFlow {
        val chunks = Channel<ByteArray>(MICROPHONE_QUEUE_CHUNKS)
        launch(Dispatchers.IO) {
            try {
                MicrophonePcm.read(stopSignal) { chunk ->
                    stats.recordAudio(chunk)
                    if (chunks.trySend(chunk).isFailure) throw TranslatorStalledException()
                }
                chunks.close()
            } catch (error: TranslatorStalledException) {
                chunks.close(error)
                onStalled()
            } catch (error: Throwable) {
                chunks.close(error)
            }
        }
        for (chunk in chunks) send(TranslationAudio(source, chunk))
        stats.recordMicrophoneClosed()
        val silence = ByteArray(MicrophonePcm.CHUNK_BYTES)
        repeat(TAIL_SILENCE_CHUNKS) {
            send(TranslationAudio(source, silence))
            delay(MicrophonePcm.CHUNK_MS)
        }
    }

    /** 翻訳器が音声を受け取らなくなり、録音の待ち行列があふれたことを表す。[VoiceInputError.AUDIO]として知らせる。 */
    private class TranslatorStalledException : Exception("translator stopped consuming audio")

    /**
     * 録った音の大きさ（最大値とRMS）と、マイクを閉じてから最後の訳文が届くまでの時間を数える。
     * IMEのprocessから録った音が無音へ置き換えられていないか、停止後の無音の長さが足りたかをログで確かめるために使う。
     * 録音のthreadと翻訳の結果を受けるthreadから呼ばれるため、更新は同期する。
     */
    private class MicrophoneStats {
        private var samples = 0L
        private var peak = 0
        private var sumSquares = 0.0
        private var microphoneClosedAt = -1L
        private var lastSegmentAt = -1L

        /** PCM 16bit little endianの[pcm]を数える。 */
        @Synchronized
        fun recordAudio(pcm: ByteArray) {
            var index = 0
            while (index + 1 < pcm.size) {
                val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
                val magnitude = kotlin.math.abs(sample)
                if (magnitude > peak) peak = magnitude
                sumSquares += sample.toDouble() * sample
                samples++
                index += 2
            }
        }

        @Synchronized
        fun recordMicrophoneClosed() {
            microphoneClosedAt = SystemClock.elapsedRealtime()
        }

        @Synchronized
        fun recordSegment() {
            lastSegmentAt = SystemClock.elapsedRealtime()
        }

        /** ログ用の要約。最後の訳文がマイクを閉じた何ミリ秒後に届いたか（負なら閉じる前）も含める。 */
        @Synchronized
        fun summary(): String {
            val rms = if (samples == 0L) 0.0 else kotlin.math.sqrt(sumSquares / samples)
            val lastSegmentAfterCloseMs =
                if (microphoneClosedAt < 0 || lastSegmentAt < 0) "none" else (lastSegmentAt - microphoneClosedAt).toString()
            return "audio_ms=${samples * 1000 / MicrophonePcm.SAMPLE_RATE} peak=$peak rms=${"%.1f".format(Locale.ROOT, rms)} " +
                "last_segment_after_mic_close_ms=$lastSegmentAfterCloseMs"
        }
    }

    private companion object {
        const val TAG = "UzumiVoiceTranslate"

        /**
         * 停止後に流す無音の長さ（2.5秒）を[MicrophonePcm.CHUNK_MS]（100ミリ秒）の個数で表したもの。
         * 試作の実機計測（Pixel 10 Pro、16件）で、最後の文は録音の末尾から0.6〜2.2秒後に確定し、
         * 末尾の無音が0秒では7件、1.5秒では10件、4秒では16件で最後の文まで出た。最大の2.2秒に余裕を足して2.5秒とする。
         * 本体での実機確認（2026-11-14、V09）でも、最後の文はマイクを閉じてから0.5秒後に届き、この無音の間に確定した。
         */
        const val TAIL_SILENCE_CHUNKS = 25

        /** 録音と翻訳器の受け渡しの間に置く待ち行列の長さ（100ミリ秒の個数、5秒分）。 */
        const val MICROPHONE_QUEUE_CHUNKS = 50

        /** 停止から確定を待つ上限（ミリ秒）。末尾の無音2.5秒と、最後の文の確定（実機で0.5〜2.2秒）に余裕を足す。 */
        const val STOP_TIMEOUT_MILLIS = 9_000L
    }
}
