package dev.uzumi.ime.neural

import dev.uzumi.ime.conversion.NeuralBackend
import dev.uzumi.ime.conversion.NeuralCallOutcome
import dev.uzumi.ime.conversion.NeuralCallRecord
import dev.uzumi.ime.conversion.NeuralFailure
import dev.uzumi.ime.conversion.NeuralInferenceSettings
import dev.uzumi.ime.conversion.NeuralKanaKanjiModel
import dev.uzumi.ime.conversion.NeuralModelOutput
import dev.uzumi.ime.conversion.NeuralModelSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * IMEのプロセスで、別プロセスの推論serviceとの受け渡しを行う。変換workerのthreadから[modelFor]のモデルを呼ぶと、
 * プロンプトを組み立てて要求番号付きで送り、結果か期限まで待つ。期限を過ぎたら中断を送って時間超過を返し、
 * 呼び出し側はMozcの結果を使う。結果は同じプロンプトのcacheに残し、打鍵ごとに同じ部分を推論し直さない。
 * 推論は一度に一つだけで、新しい要求が来たら古い要求を中断する（[cancelInFlight]）。
 */
class NeuralRuntimeClient(
    override val spec: NeuralModelSpec,
    // 単調な時計（ナノ秒）。JVMテストで差し替える。
    private val nanoTime: () -> Long = System::nanoTime,
    // cacheに残すプロンプトの数。
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
) : NeuralBackend, NeuralRuntimeListener {
    private val lock = Any()

    // 以下はlockの中だけで読み書きする。
    private var port: NeuralRuntimePort? = null
    private var ready = false
    private var nextRequestId = 1L
    private var inFlight: InFlight? = null

    // プロンプトごとの結果。変換workerのthreadだけから使う。greedyのため同じプロンプトは同じ結果になる。
    private val cache = object : LinkedHashMap<String, NeuralModelOutput.Completed>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NeuralModelOutput.Completed>?): Boolean =
            size > cacheCapacity
    }

    /** 送った要求一件の待ち合わせ。結果はbinderのthreadから書かれ、[done]で変換workerへ知らせる。 */
    private class InFlight(val requestId: Long, val owner: Long, val sentAtNanos: Long) {
        val done = CountDownLatch(1)

        @Volatile var termination: Int = NeuralTermination.DECODE_ERROR

        @Volatile var text: ByteArray = ByteArray(0)

        @Volatile var inferenceMicros: Long = 0

        @Volatile var cancelled: Boolean = false
    }

    /** モデルを読み込み、結果を受けられる状態か。 */
    val isReady: Boolean
        get() = synchronized(lock) { ready && port != null }

    /** serviceへ接続した。モデルの読み込みが終わる（[onLoaded]）まではモデルを使わない。 */
    fun attach(port: NeuralRuntimePort) {
        synchronized(lock) {
            this.port = port
            ready = false
        }
    }

    /** serviceとの接続が切れた。待っている要求はモデルが使えない結果で終える。 */
    fun detach() {
        synchronized(lock) {
            port = null
            ready = false
            inFlight?.let {
                it.termination = NeuralTermination.NOT_LOADED
                it.done.countDown()
            }
        }
    }

    override fun onLoaded(ok: Boolean) {
        synchronized(lock) { ready = ok && port != null }
    }

    override fun onResult(requestId: Long, termination: Int, text: ByteArray, inferenceMicros: Long) {
        synchronized(lock) {
            val current = inFlight?.takeIf { it.requestId == requestId } ?: return
            current.termination = termination
            current.text = text
            current.inferenceMicros = inferenceMicros
            current.done.countDown()
        }
    }

    override fun cancelInFlight(newOwner: Long) {
        val target = synchronized(lock) {
            val current = inFlight?.takeIf { it.owner < newOwner } ?: return
            current.cancelled = true
            current.done.countDown()
            port?.let { it to current.requestId }
        } ?: return
        runCatching { target.first.cancel(target.second) }
    }

    override fun modelFor(
        deadlineNanos: Long,
        owner: Long,
        isSuperseded: () -> Boolean,
        record: (NeuralCallRecord) -> Unit,
    ): NeuralKanaKanjiModel = NeuralKanaKanjiModel { reading, leftContext ->
        call(spec.format.build(reading, leftContext), deadlineNanos, owner, isSuperseded, record)
    }

    /** プロンプト一つを推論する。cacheにあればそれを返し、無ければ送って期限まで待つ。 */
    private fun call(
        prompt: String,
        deadlineNanos: Long,
        owner: Long,
        isSuperseded: () -> Boolean,
        record: (NeuralCallRecord) -> Unit,
    ): NeuralModelOutput {
        cache[prompt]?.let {
            record(NeuralCallRecord(sent = false, cacheHit = true, millis = 0.0, outcome = NeuralCallOutcome.COMPLETED))
            return it
        }
        // 送る前の判定はlockの中で行い、UIスレッドの中断（cancelInFlight）と入れ違いにならないようにする。
        val (target, pending) = synchronized(lock) {
            if (isSuperseded()) {
                record(NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.CANCELLED))
                return NeuralModelOutput.Cancelled
            }
            val current = port?.takeIf { ready } ?: run {
                record(NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.UNAVAILABLE))
                return NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE)
            }
            val now = nanoTime()
            if (deadlineNanos - now <= 0) {
                record(NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.TIMEOUT))
                return NeuralModelOutput.Failed(NeuralFailure.TIMEOUT)
            }
            val created = InFlight(nextRequestId++, owner, now)
            inFlight = created
            current to created
        }
        val sent = runCatching {
            target.convert(pending.requestId, prompt.toByteArray(Charsets.UTF_8), spec.format.parseSpecial, NeuralInferenceSettings.MAX_TOKENS)
        }.isSuccess
        val finished = sent && pending.done.await((deadlineNanos - nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        synchronized(lock) { if (inFlight === pending) inFlight = null }
        val waitedMillis = (nanoTime() - pending.sentAtNanos) / 1_000_000.0
        return when {
            !sent -> {
                record(NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.UNAVAILABLE))
                NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE)
            }
            pending.cancelled -> {
                record(NeuralCallRecord(sent = true, cacheHit = false, millis = waitedMillis, outcome = NeuralCallOutcome.CANCELLED))
                NeuralModelOutput.Cancelled
            }
            !finished -> {
                runCatching { target.cancel(pending.requestId) }
                record(NeuralCallRecord(sent = true, cacheHit = false, millis = waitedMillis, outcome = NeuralCallOutcome.TIMEOUT))
                NeuralModelOutput.Failed(NeuralFailure.TIMEOUT)
            }
            else -> {
                val outcome = NeuralTermination.toCallOutcome(pending.termination)
                record(NeuralCallRecord(sent = true, cacheHit = false, millis = pending.inferenceMicros / 1000.0, outcome = outcome))
                NeuralTermination.toOutput(pending.termination, pending.text).also { output ->
                    if (output is NeuralModelOutput.Completed) cache[prompt] = output
                }
            }
        }
    }

    private companion object {
        /** cacheに残すプロンプトの数の既定値。1文の入力で作られる要求（数十件）が収まる大きさにする。 */
        const val DEFAULT_CACHE_CAPACITY = 256
    }
}
