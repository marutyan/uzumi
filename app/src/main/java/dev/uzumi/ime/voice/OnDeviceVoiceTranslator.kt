package dev.uzumi.ime.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
        stopRequested = stopSignal
        setState(VoiceInputState.LISTENING)
        sessionJob = scope.launch { runSession(generation, direction, stopSignal) }
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
        return true
    }

    /** マイクと翻訳を止め、届いた訳文を入れずに捨てる。翻訳中・停止待ちでなければ何もしない。 */
    fun cancel(): Boolean {
        if (!isMainThread()) {
            postToMain { cancel() }
            return true
        }
        if (state != VoiceInputState.LISTENING && state != VoiceInputState.STOPPING) return false
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
    private suspend fun runSession(generation: Long, direction: VoiceTranslationDirection, stopSignal: AtomicBoolean) {
        val source = Locale.forLanguageTag(direction.sourceTag)
        val translator = createTranslator(direction)
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
            val segments = mutableListOf<String>()
            var errorResponses = 0
            val request = SpeechTranslatorRequest.builder(microphoneAudio(source, stopSignal, stats)).build()
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
            val raw = joinSegments(segments, direction)
            // 英語のフィラーは訳先が英語のときだけ消す。日本語の訳文には英語のフィラーの規則を当てない。
            val text = if (direction.targetTag.startsWith("en")) EnglishFillerRemover.removeFillers(raw) else raw
            postToMain {
                if (text.isEmpty() && errorResponses > 0) {
                    fail(generation, VoiceInputError.RECOGNIZER_ERROR)
                } else {
                    complete(generation, text)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "translation failed: direction=${direction.id}", error)
            val mapped = when (error) {
                is MicrophoneException -> VoiceInputError.AUDIO
                is SecurityException -> VoiceInputError.PERMISSION_DENIED
                else -> VoiceInputError.RECOGNIZER_ERROR
            }
            postToMain { fail(generation, mapped) }
        } finally {
            translator.close()
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
            listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.DOWNLOADING)
        }
        fail(generation, VoiceInputError.MODEL_DOWNLOADING)
    }

    /** [direction]のモデルの取得を始め、終わったら結果を知らせる。同じ向きの取得が進行中なら何もしない。 */
    private fun startDownload(direction: VoiceTranslationDirection) {
        if (state == VoiceInputState.DESTROYED || !downloadingDirections.add(direction)) return
        listener.onModelDownloadStateChanged(direction, VoiceModelDownloadState.DOWNLOADING)
        scope.launch {
            var completed = false
            val translator = createTranslator(direction)
            try {
                translator.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadCompleted -> completed = true
                        is DownloadStatus.DownloadFailed -> Log.w(TAG, "model download failed: direction=${direction.id}", status.e)
                        else -> Unit
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "model download failed: direction=${direction.id}", error)
            } finally {
                translator.close()
            }
            Log.i(TAG, "model download finished: direction=${direction.id} completed=$completed")
            postToMain {
                downloadingDirections.remove(direction)
                val result = if (completed) VoiceModelDownloadState.COMPLETED else VoiceModelDownloadState.FAILED
                listener.onModelDownloadStateChanged(direction, result)
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
        sessionGeneration++
        sessionJob = null
        stopRequested = null
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
     * 録音権限は[start]で確かめた後にだけ呼ばれるため、権限の確認漏れの警告を抑える。
     */
    @SuppressLint("MissingPermission")
    private fun microphoneAudio(
        source: Locale,
        stopSignal: AtomicBoolean,
        stats: MicrophoneStats,
    ): Flow<TranslationAudio> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(PCM_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 端末内認識と同じく音声認識向けの経路で録り、通話向けの加工（エコー消去など）を避ける。
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            PCM_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, PCM_CHUNK_BYTES * 4),
        )
        try {
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) throw MicrophoneException("AudioRecord not initialized")
            audioRecord.startRecording()
            val chunk = ByteArray(PCM_CHUNK_BYTES)
            while (!stopSignal.get()) {
                val read = audioRecord.read(chunk, 0, chunk.size)
                if (read < 0) throw MicrophoneException("AudioRecord.read returned $read")
                if (read == 0) continue
                stats.recordAudio(chunk, read)
                emit(TranslationAudio(source, chunk.copyOf(read)))
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
        stats.recordMicrophoneClosed()
        val silence = ByteArray(PCM_CHUNK_BYTES)
        repeat(TAIL_SILENCE_CHUNKS) {
            emit(TranslationAudio(source, silence))
            delay(PCM_CHUNK_MS)
        }
    }.flowOn(Dispatchers.IO)

    /** マイクを開けない、または読めないことを表す。[VoiceInputError.AUDIO]として知らせる。 */
    private class MicrophoneException(message: String) : Exception(message)

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

        /** PCM 16bit little endianの[length]バイト分を数える。 */
        @Synchronized
        fun recordAudio(pcm: ByteArray, length: Int) {
            var index = 0
            while (index + 1 < length) {
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
            return "audio_ms=${samples * 1000 / PCM_SAMPLE_RATE} peak=$peak rms=${"%.1f".format(Locale.ROOT, rms)} " +
                "last_segment_after_mic_close_ms=$lastSegmentAfterCloseMs"
        }
    }

    private companion object {
        const val TAG = "UzumiVoiceTranslate"

        // 翻訳器へ渡す音声の形式（PCM 16bit・16kHz・mono）と、1回に渡す長さ（100ミリ秒分）。
        const val PCM_SAMPLE_RATE = 16_000
        const val PCM_CHUNK_MS = 100L
        const val PCM_CHUNK_BYTES = (PCM_SAMPLE_RATE * 2 * PCM_CHUNK_MS / 1000).toInt()

        /**
         * 停止後に流す無音の長さ（2.5秒）を100ミリ秒の個数で表したもの。
         * 試作の実機計測（Pixel 10 Pro、16件）で、最後の文は録音の末尾から0.6〜2.2秒後に確定し、
         * 末尾の無音が0秒では7件、1.5秒では10件、4秒では16件で最後の文まで出た。最大の2.2秒に余裕を足して2.5秒とする。
         */
        const val TAIL_SILENCE_CHUNKS = 25
    }
}
