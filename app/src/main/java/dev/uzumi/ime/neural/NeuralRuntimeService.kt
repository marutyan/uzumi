package dev.uzumi.ime.neural

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 別プロセス（`:neural`）でllama.cppの推論を行うbound service。exportせず、同じアプリのIMEだけがbindする。
 * モデルの異常や低メモリでの強制終了がIMEのプロセスを巻き込まないように分けている。
 * 推論は専用の一つのthreadで順に行い、binderのthreadでは要求番号の更新と中断だけを行う。
 * 入力・出力の文字列は記録しない。
 */
class NeuralRuntimeService : Service() {
    // 推論とモデルの読み込みを順に行う一つのthread。
    private lateinit var inference: ExecutorService

    override fun onCreate() {
        super.onCreate()
        inference = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "uzumi-neural") }
    }

    override fun onDestroy() {
        inference.execute { NativeNeuralBridge.close() }
        inference.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : INeuralRuntime.Stub() {
        override fun load(
            requestId: Long,
            modelPath: String,
            sha256: String,
            nCtx: Int,
            nThreads: Int,
            callback: INeuralRuntimeCallback,
        ) {
            inference.execute {
                val reason = loadModel(modelPath, sha256, nCtx, nThreads)
                runCatching { callback.onLoaded(requestId, reason == LOAD_OK, reason) }
            }
        }

        override fun convert(
            requestId: Long,
            prompt: ByteArray,
            parseSpecial: Boolean,
            maxTokens: Int,
            callback: INeuralRuntimeCallback,
        ) {
            // 新しい要求の番号を先に知らせ、実行中の古い推論をllama.cppの中断callbackで止める。
            NativeNeuralBridge.markLatest(requestId)
            inference.execute {
                val started = System.nanoTime()
                val raw = NativeNeuralBridge.generate(requestId, prompt, parseSpecial, maxTokens)
                val micros = (System.nanoTime() - started) / 1_000
                val termination = raw.firstOrNull()?.toInt() ?: NeuralTermination.DECODE_ERROR
                runCatching { callback.onResult(requestId, termination, raw.copyOfRange(minOf(1, raw.size), raw.size), micros) }
            }
        }

        override fun cancel(requestId: Long) {
            NativeNeuralBridge.cancel(requestId)
        }

        override fun reportMemory(callback: INeuralRuntimeCallback) {
            inference.execute { runCatching { callback.onMemory(Debug.getPss().toInt()) } }
        }

        override fun close() {
            inference.execute { NativeNeuralBridge.close() }
        }
    }

    /** モデルのファイルのSHA-256を確かめてから読み込み、結果の番号を返す。 */
    private fun loadModel(modelPath: String, sha256: String, nCtx: Int, nThreads: Int): Int {
        if (!NativeNeuralBridge.isAvailable) return LOAD_NO_LIBRARY
        val file = File(modelPath)
        if (!file.isFile) return LOAD_NO_FILE
        if (!sha256Of(file).equals(sha256, ignoreCase = true)) return LOAD_HASH_MISMATCH
        return if (NativeNeuralBridge.load(file.absolutePath, nCtx, nThreads)) LOAD_OK else LOAD_FAILED
    }

    /** ファイルのSHA-256を16進で返す。 */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        /** モデルの読み込みの結果の番号。onLoadedのreasonで返す。 */
        const val LOAD_OK = 0
        const val LOAD_NO_LIBRARY = 1
        const val LOAD_NO_FILE = 2
        const val LOAD_HASH_MISMATCH = 3
        const val LOAD_FAILED = 4
    }
}
