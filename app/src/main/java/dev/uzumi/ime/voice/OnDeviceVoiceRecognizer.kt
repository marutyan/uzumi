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
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
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
    // 停止の後、認識器が確定を返さないまま止まった場合に備える見張り。[STOP_TIMEOUT_MILLIS]後に途中結果で確定させる。
    private var stopWatchdog: Runnable? = null

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
        val pipeCapture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { PcmPipeCapture.open() }.getOrNull() ?: run {
                destroy()
                return false
            }
        } else {
            null
        }
        created.setRecognitionListener(createRecognitionListener(callbacks, pipeCapture))
        capture = pipeCapture
        val started = runCatching { created.startListening(createRecognitionIntent(pipeCapture)) }.isSuccess
        if (!started) {
            destroy()
            return false
        }
        pipeCapture?.start(onMicrophoneError = { runOnMain { callbacks.onError(VoiceInputError.AUDIO) } })
        activeCallbacks = callbacks
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
        val callbacks = activeCallbacks ?: return
        cancelStopWatchdog()
        // 確定が届けば、セッションの終わりでdestroyが呼ばれて見張りは外れる。届かなければNO_MATCHとして扱い、
        // セッション側が受け取り済みの途中結果で確定する（途中結果が無ければ「聞き取れなかった」になる）。
        val watchdog = Runnable { callbacks.onError(VoiceInputError.NO_MATCH) }
        stopWatchdog = watchdog
        mainHandler.postAtTime(watchdog, handlerToken, SystemClock.uptimeMillis() + STOP_TIMEOUT_MILLIS)
    }

    private var activeCallbacks: VoiceRecognizerCallbacks? = null

    private fun cancelStopWatchdog() {
        stopWatchdog?.let(mainHandler::removeCallbacks)
        stopWatchdog = null
    }

    override fun cancel() {
        cancelStopWatchdog()
        activeCallbacks = null
        capture?.abort()
        capture = null
        val current = recognizer ?: return
        recognizer = null
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    override fun destroy() {
        cancelStopWatchdog()
        activeCallbacks = null
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

    /**
     * 認識器の通知を受ける。[pipeCapture]はこのセッションのpipeで、取り消し直後に次のセッションが始まっても、
     * 遅れて届いた古い通知が新しいpipeを閉じないよう、現在のpipeではなくこの値を使う。
     */
    private fun createRecognitionListener(
        callbacks: VoiceRecognizerCallbacks,
        pipeCapture: PcmPipeCapture?,
    ): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // 認識器が読み口を受け取った後なので、こちらの複製を閉じる。持ち続けると、認識器が読み口を閉じても
                // 書き込み側がエラーにならず、pipeの詰まりに気づけない。
                pipeCapture?.releaseReadSide()
            }
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

        /**
         * 停止から確定を待つ上限（ミリ秒）。実機では、末尾に1.5秒の無音を足して閉じてから0.2秒以内に確定した。
         * 認識器が止まった場合でも停止待ちの表示が残り続けないよう、余裕を持たせて打ち切る。
         */
        const val STOP_TIMEOUT_MILLIS = 8_000L
    }
}

/**
 * マイクの音を録音threadで読み、認識器へ渡したpipeへ書き込む。
 * 停止の操作では、マイクを閉じた後に無音を足してからpipeを閉じる。試作で、音の末尾ですぐ閉じると最後の語が欠け、
 * 1.5秒の無音を足すと戻ったためである（docs/research/voice-input.md）。
 * pipeへの書き込みは待たずに戻る形にし、待つ時間に上限を置く。認識器が読まなくなっても録音threadが止まらず、
 * マイクを必ず閉じられるようにするためである。
 */
private class PcmPipeCapture private constructor(
    /** 認識器へ渡す読み口。認識器のprocessへ複製されるので、こちらの複製は受け渡しの後に閉じる。 */
    val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
) {
    private val stopSignal = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private var thread: Thread? = null

    /** 録音threadを始める。マイクを開けない・読めない、またはpipeが詰まったときは[onMicrophoneError]を録音threadから呼ぶ。 */
    fun start(onMicrophoneError: () -> Unit) {
        thread = Thread({ pump(onMicrophoneError) }, "UzumiVoicePcm").also { it.start() }
    }

    /** マイクを閉じ、末尾の無音を書いてからpipeを閉じる（認識は確定へ進む）。 */
    fun finish() {
        stopSignal.set(true)
    }

    /** こちらが持つ読み口の複製を閉じる。認識器が受け取った後に呼ぶ。 */
    fun releaseReadSide() {
        closeQuietly(readSide)
    }

    /** マイクを閉じ、無音を書かずにpipeを閉じる（認識の取り消し・破棄用）。書き込み側は録音threadが閉じる。 */
    fun abort() {
        aborted.set(true)
        stopSignal.set(true)
        closeQuietly(readSide)
        // 録音threadを始める前（認識の開始に失敗したとき）は、書き込み側を閉じる者がいないのでここで閉じる。
        if (thread == null) closeQuietly(writeSide)
    }

    private fun pump(onMicrophoneError: () -> Unit) {
        val fd = writeSide.fileDescriptor
        try {
            Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            MicrophonePcm.read(stopSignal) { chunk ->
                if (!writeWithin(fd, chunk, WRITE_TIMEOUT_MILLIS)) throw PipeStalledException()
            }
            if (!aborted.get()) writeWithin(fd, ByteArray(TAIL_SILENCE_BYTES), WRITE_TIMEOUT_MILLIS)
        } catch (error: MicrophoneException) {
            Log.w(TAG, "microphone failed", error)
            if (!aborted.get()) onMicrophoneError()
        } catch (error: PipeStalledException) {
            // 認識器が読み口を閉じた、または読まなくなった。マイクは閉じ済みなので、エラーとして知らせて終える。
            if (!aborted.get()) {
                Log.w(TAG, "recognizer stopped reading audio")
                onMicrophoneError()
            }
        } catch (error: ErrnoException) {
            Log.w(TAG, "pipe setup failed", error)
            if (!aborted.get()) onMicrophoneError()
        } finally {
            closeQuietly(writeSide)
            closeQuietly(readSide)
        }
    }

    /**
     * [data]を全部書く。pipeが満杯なら少し待って書き直し、[timeoutMillis]を超えるか取り消されたらfalseを返す。
     * 読み口が閉じられた（EPIPE）などの書き込みエラーもfalseとする。
     */
    private fun writeWithin(fd: java.io.FileDescriptor, data: ByteArray, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var offset = 0
        while (offset < data.size) {
            if (aborted.get()) return false
            try {
                offset += Os.write(fd, data, offset, data.size - offset)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) return false
                if (SystemClock.uptimeMillis() >= deadline) return false
                SystemClock.sleep(WRITE_RETRY_MILLIS)
            } catch (error: java.io.InterruptedIOException) {
                return false
            }
        }
        return true
    }

    /** 認識器がpipeを読まなくなったことを表す。 */
    private class PipeStalledException : Exception()

    companion object {
        private const val TAG = "UzumiVoiceInput"

        /** 停止後に足す無音の長さ（1.5秒分のバイト数）。 */
        private const val TAIL_SILENCE_BYTES = MicrophonePcm.SAMPLE_RATE * 2 * 3 / 2

        /** pipeが満杯のまま待つ上限（ミリ秒）。認識器は実時間より速く読むので、これを超えたら読んでいないとみなす。 */
        private const val WRITE_TIMEOUT_MILLIS = 2_000L

        /** pipeが満杯のときに書き直すまでの間隔（ミリ秒）。 */
        private const val WRITE_RETRY_MILLIS = 10L

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
