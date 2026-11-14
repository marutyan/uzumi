package dev.uzumi.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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
        val started = runCatching { created.startListening(createRecognitionIntent()) }.isSuccess
        if (!started) {
            destroy()
            return false
        }
        return true
    }

    override fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    override fun destroy() {
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.destroy() }
    }

    /** 試作（VoiceProbeSession）と同じ条件で日本語の端末内認識用 Intent を組み立てる。 */
    private fun createRecognitionIntent(): Intent =
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
