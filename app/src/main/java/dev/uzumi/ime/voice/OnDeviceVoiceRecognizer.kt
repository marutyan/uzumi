package dev.uzumi.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端末内音声認識（[SpeechRecognizer.createOnDeviceSpeechRecognizer]）の開始・停止・取り消し・破棄を
 * メインスレッド上で安全に管理する窓口。
 * サーバーへ送信する `SpeechRecognizer.createSpeechRecognizer` は使用せず、端末内認識器が使えない場合は
 * [VoiceInputError.UNAVAILABLE] を通知して終了する。
 */
class OnDeviceVoiceRecognizer(
    context: Context,
    private val listener: VoiceInputListener,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlerToken = Any()
    private val backend = AndroidVoiceRecognizerBackend(appContext, mainHandler, handlerToken)
    private val engine = VoiceSessionEngine(backend, listener)

    /** 現在の状態（メインスレッドから参照することを想定）。 */
    val state: VoiceInputState
        get() = engine.state

    /**
     * 端末内の音声認識を開始する。
     * メインスレッド以外から呼ばれた場合はメインスレッドへ投入し [VoiceStartStatus.DISPATCHED] を返す。
     */
    fun start(): VoiceStartStatus {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return engine.start()
        }
        postToMain { engine.start() }
        return VoiceStartStatus.DISPATCHED
    }

    /** 発話終了を認識器へ伝え、確定結果の通知を待つ。 */
    fun stop(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return engine.stop()
        }
        postToMain { engine.stop() }
        return true
    }

    /** 実行中の認識を取り消し、途中結果や確定結果を破棄する。 */
    fun cancel(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return engine.cancel()
        }
        postToMain { engine.cancel() }
        return true
    }

    /** 停止前であっても進行中の認識を安全に取り消して認識器を破棄する。 */
    fun destroy() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler.removeCallbacksAndMessages(handlerToken)
            engine.destroy()
            return
        }
        postToMain {
            mainHandler.removeCallbacksAndMessages(handlerToken)
            engine.destroy()
        }
    }

    private fun postToMain(action: () -> Unit) {
        mainHandler.postAtTime(action, handlerToken, SystemClock.uptimeMillis())
    }
}

/**
 * Android の [SpeechRecognizer] を呼び出すバックエンド実装。すべての操作をメインスレッドで行う。
 */
private class AndroidVoiceRecognizerBackend(
    private val context: Context,
    private val mainHandler: Handler,
    private val handlerToken: Any,
) : VoiceRecognizerBackend {
    private var recognizer: SpeechRecognizer? = null
    // Uzumiが自分で録った音を認識器へ渡す経路（API 33以上）。録音threadと、認識器へ渡したpipeの読み口を持つ。
    private var capture: PcmPipeCapture? = null

    override fun isOnDeviceAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    }

    override fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun startListening(callbacks: VoiceRecognizerCallbacks): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        destroy()
        val created = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?: return false
        recognizer = created
        created.setRecognitionListener(createRecognitionListener(callbacks))
        val pipeCapture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { PcmPipeCapture.open() }.getOrNull() ?: run {
                destroy()
                return false
            }
        } else {
            null
        }
        capture = pipeCapture
        val started = runCatching { created.startListening(createRecognitionIntent(pipeCapture)) }.isSuccess
        if (!started) {
            destroy()
            return false
        }
        pipeCapture?.start(onMicrophoneError = { runOnMain { callbacks.onError(VoiceInputError.AUDIO) } })
        return true
    }

    override fun stopListening() {
        val current = capture
        if (current != null) {
            // 自分で録っている場合は、pipeを閉じることが発話の終わりの合図になる（閉じるまで認識は続く）。
            current.finish()
        } else {
            runCatching { recognizer?.stopListening() }
        }
    }

    override fun cancel() {
        capture?.abort()
        capture = null
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    override fun destroy() {
        capture?.abort()
        capture = null
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.destroy() }
    }

    /**
     * 日本語の端末内認識用 Intent を組み立てる。[pipeCapture]があれば、その音を区切りつきの連続認識で渡す。
     */
    private fun createRecognitionIntent(pipeCapture: PcmPipeCapture?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_JAPANESE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
            }
            if (pipeCapture != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 認識器にマイクを任せると、話の途中の短い間で認識が終わり（2026-11-14、Pixel 10 Proで約8秒の時点）、
                // 区切りの付け直しで前の文が途中結果から消えた。無音の長さで終える区切りつきの連続認識の指定も効かなかった。
                // そのため、Uzumiが録った音をpipeで渡し、pipeを閉じるまで続く区切りつきの連続認識にする。
                // 区切りごとの確定文はonSegmentResultsで届く。
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeCapture.readSide)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicrophonePcm.SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }

    private fun createRecognitionListener(callbacks: VoiceRecognizerCallbacks): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = extractTexts(partialResults)
                runOnMain { callbacks.onPartialResults(texts) }
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                val texts = extractTexts(segmentResults)
                runOnMain { callbacks.onSegmentResults(texts) }
            }

            override fun onEndOfSegmentedSession() {
                runOnMain { callbacks.onEndOfSegmentedSession() }
            }

            override fun onResults(results: Bundle?) {
                val texts = extractTexts(results)
                runOnMain { callbacks.onResults(texts) }
            }

            override fun onError(error: Int) {
                val mapped = mapSpeechError(error)
                runOnMain { callbacks.onError(mapped) }
            }
        }

    private fun extractTexts(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.postAtTime(action, handlerToken, SystemClock.uptimeMillis())
        }
    }

    private fun mapSpeechError(error: Int): VoiceInputError = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> VoiceInputError.NO_MATCH
        SpeechRecognizer.ERROR_AUDIO -> VoiceInputError.AUDIO
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceInputError.BUSY
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        ) {
            VoiceInputError.LANGUAGE_UNAVAILABLE
        } else {
            VoiceInputError.RECOGNIZER_ERROR
        }
    }

    private companion object {
        const val LANGUAGE_JAPANESE = "ja-JP"
    }
}

/**
 * マイクの音を録音threadで読み、認識器へ渡したpipeへ書き込む。
 * 停止の操作では、マイクを閉じた後に無音を足してからpipeを閉じる。試作で、音の末尾ですぐ閉じると最後の語が欠け、
 * 1.5秒の無音を足すと戻ったためである（docs/research/voice-input.md）。
 */
private class PcmPipeCapture private constructor(
    /** 認識器へ渡す読み口。認識器のprocessへ複製されるので、こちらの複製は終了時に閉じる。 */
    val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
) {
    private val stopSignal = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 録音threadを始める。マイクを開けない・読めないときは[onMicrophoneError]を録音threadから呼ぶ。 */
    fun start(onMicrophoneError: () -> Unit) {
        thread = Thread({ pump(onMicrophoneError) }, "UzumiVoicePcm").also { it.start() }
    }

    /** マイクを閉じ、末尾の無音を書いてからpipeを閉じる（認識は確定へ進む）。 */
    fun finish() {
        stopSignal.set(true)
    }

    /** マイクを閉じ、無音を書かずにpipeをすぐ閉じる（認識の取り消し・破棄用）。 */
    fun abort() {
        aborted.set(true)
        stopSignal.set(true)
        closeQuietly(writeSide)
        closeQuietly(readSide)
    }

    private fun pump(onMicrophoneError: () -> Unit) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                MicrophonePcm.read(stopSignal) { chunk -> output.write(chunk) }
                if (!aborted.get()) output.write(ByteArray(TAIL_SILENCE_BYTES))
            }
        } catch (error: MicrophoneException) {
            Log.w(TAG, "microphone failed", error)
            if (!aborted.get()) onMicrophoneError()
        } catch (error: IOException) {
            // 取り消しでpipeを先に閉じた場合や、認識器が読み口を閉じた場合に起きる。どちらも認識側の通知で扱う。
            if (!aborted.get()) Log.w(TAG, "pipe closed while writing", error)
        } finally {
            closeQuietly(readSide)
        }
    }

    companion object {
        private const val TAG = "UzumiVoiceInput"

        /** 停止後に足す無音の長さ（1.5秒分のバイト数）。 */
        private const val TAIL_SILENCE_BYTES = MicrophonePcm.SAMPLE_RATE * 2 * 3 / 2

        /** 認識器へ渡すpipeを作る。 */
        fun open(): PcmPipeCapture {
            val (read, write) = ParcelFileDescriptor.createPipe()
            return PcmPipeCapture(read, write)
        }

        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            runCatching { descriptor.close() }
        }
    }
}
