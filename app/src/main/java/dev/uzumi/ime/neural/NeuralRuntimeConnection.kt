package dev.uzumi.ime.neural

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.uzumi.ime.conversion.NeuralInferenceSettings
import dev.uzumi.ime.conversion.NeuralModelSpec
import java.io.File

/**
 * IMEのプロセスから`:neural`の推論serviceへbindし、AIDLの窓口を[NeuralRuntimeClient]へつなぐ。
 * 接続したらモデルを読み込ませ、別プロセスが終了したら間隔を空けて回数を限って接続し直す。
 * 接続・読み込みの前と失敗の間、クライアントはモデルを使えない結果を返し、IMEはMozcだけで入力を続ける。
 * UIスレッドから作り、[close]で切る。
 */
class NeuralRuntimeConnection(
    private val context: Context,
    val spec: NeuralModelSpec,
) {
    /** 変換workerへ渡すクライアント。 */
    val client = NeuralRuntimeClient(spec)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bound = false
    private var closed = false
    private var restarts = 0
    private var runtime: INeuralRuntime? = null

    // bindを始めた時刻と、最初の推論結果が届くまでの時間（cold start、ミリ秒）。評価用で、未計測なら-1。
    private var bindStartedAt = 0L

    @Volatile
    var coldStartMillis: Long = -1
        private set

    // 最後に受け取った`:neural`のPSS（KB）。評価用で、未取得なら-1。
    @Volatile
    var lastNeuralPssKb: Int = -1
        private set

    // モデルの読み込みの結果の番号（NeuralRuntimeServiceの定数）。未完了なら-1。
    @Volatile
    var loadReason: Int = -1
        private set

    // serviceからの通知。binderのthreadで受け、クライアントへ渡す。
    private val callback = object : INeuralRuntimeCallback.Stub() {
        override fun onLoaded(requestId: Long, ok: Boolean, reason: Int) {
            loadReason = reason
            client.onLoaded(ok)
        }

        override fun onResult(requestId: Long, termination: Int, text: ByteArray, inferenceMicros: Long) {
            if (coldStartMillis < 0) coldStartMillis = SystemClock.elapsedRealtime() - bindStartedAt
            client.onResult(requestId, termination, text, inferenceMicros)
        }

        override fun onMemory(pssKb: Int) {
            lastNeuralPssKb = pssKb
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val proxy = INeuralRuntime.Stub.asInterface(service) ?: return
            runtime = proxy
            client.attach(AidlPort(proxy))
            runCatching {
                service?.linkToDeath({ mainHandler.post(::onRuntimeDied) }, 0)
                proxy.load(0, modelFile().absolutePath, spec.sha256, NeuralInferenceSettings.N_CTX, NeuralInferenceSettings.THREADS, callback)
            }.onFailure { onRuntimeDied() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runtime = null
            client.detach()
        }
    }

    /** AIDLのproxyを[NeuralRuntimePort]として包む。onewayのため呼び出しはすぐ戻る。 */
    private inner class AidlPort(private val proxy: INeuralRuntime) : NeuralRuntimePort {
        override fun convert(requestId: Long, prompt: ByteArray, parseSpecial: Boolean, maxTokens: Int) {
            proxy.convert(requestId, prompt, parseSpecial, maxTokens, callback)
        }

        override fun cancel(requestId: Long) {
            proxy.cancel(requestId)
        }
    }

    /** serviceへbindしてモデルの準備を始める。 */
    fun open() {
        if (closed || bound) return
        bindStartedAt = SystemClock.elapsedRealtime()
        bound = context.bindService(Intent(context, NeuralRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** `:neural`のPSSを問い合わせる。結果は後から[lastNeuralPssKb]へ入る。評価用。 */
    fun requestMemory() {
        runCatching { runtime?.reportMemory(callback) }
    }

    /** 接続を切り、モデルを解放させる。 */
    fun close() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { runtime?.close() }
        client.detach()
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        runtime = null
    }

    /** 別プロセスが終了した。モデルを使えない状態にし、上限までは間隔を空けて接続し直す。 */
    private fun onRuntimeDied() {
        client.detach()
        runtime = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        if (closed || restarts >= MAX_RESTARTS) return
        restarts += 1
        mainHandler.postDelayed(::open, RESTART_DELAY_MILLIS * restarts)
    }

    /** モデルを置く場所。debugではadbでこの場所へ置く（`docs/phase3a-neural-android.md`）。 */
    private fun modelFile(): File = modelDirectory(context).resolve(spec.fileName)

    companion object {
        /** 別プロセスの終了後に接続し直す回数の上限。超えたらこの入力の間はMozcだけで続ける。 */
        const val MAX_RESTARTS = 3

        /** 接続し直すまでの間隔（ミリ秒）。回数に比例して延ばす。 */
        const val RESTART_DELAY_MILLIS = 1_000L

        /** モデルを置くディレクトリ（アプリのfilesの下）。 */
        fun modelDirectory(context: Context): File = File(context.filesDir, "neural")
    }
}
