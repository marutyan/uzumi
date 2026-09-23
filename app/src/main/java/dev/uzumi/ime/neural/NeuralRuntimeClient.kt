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
import java.util.concurrent.atomic.AtomicLong

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

        // 結果を受け取った時刻（nanoTime）。期限を過ぎて届いた結果を使わないために見る。
        @Volatile var arrivedAtNanos: Long = Long.MAX_VALUE
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
                it.arrivedAtNanos = nanoTime()
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
            current.arrivedAtNanos = nanoTime()
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

    /**
     * プロンプト一つを推論する。結果を返す直前の一か所（[deadlineGate]）で期限を確かめ直し、期限を過ぎていれば、
     * cache hitでもserviceの結果でも使わず時間超過を返す（評価条件の「300 msを超えたらMozcの結果」）。
     * cache hitも同じ期限に従うのは、期限が入力の受理からの時間で、適用までの時間（cache hitを含む）の目標と同じ起点だからである。
     * 推論時間の母集団（送った要求だけ）は、この規則では変わらない。
     */
    private fun call(
        prompt: String,
        deadlineNanos: Long,
        owner: Long,
        isSuperseded: () -> Boolean,
        record: (NeuralCallRecord) -> Unit,
    ): NeuralModelOutput {
        val (output, callRecord) = produce(prompt, deadlineNanos, owner, isSuperseded)
        val (gated, gatedRecord) = deadlineGate(output, callRecord, deadlineNanos)
        record(gatedRecord)
        return gated
    }

    /**
     * 結果を返す直前の期限の関門。表示に使える結果（Completed）を、同じ時計で期限を確かめ直してから通す。
     * 期限を過ぎていれば時間超過に変える。失敗・中断はそのまま通す。
     */
    private fun deadlineGate(
        output: NeuralModelOutput,
        callRecord: NeuralCallRecord,
        deadlineNanos: Long,
    ): Pair<NeuralModelOutput, NeuralCallRecord> {
        if (output !is NeuralModelOutput.Completed || deadlineNanos - nanoTime() >= 0) return output to callRecord
        return NeuralModelOutput.Failed(NeuralFailure.TIMEOUT) to
            callRecord.copy(cacheHit = false, outcome = NeuralCallOutcome.TIMEOUT)
    }

    /** cacheか別プロセスから結果を得る。期限の最終の判定は[deadlineGate]が行い、ここでは送る前と待つ間だけ期限を見る。 */
    private fun produce(
        prompt: String,
        deadlineNanos: Long,
        owner: Long,
        isSuperseded: () -> Boolean,
    ): Pair<NeuralModelOutput, NeuralCallRecord> {
        // 期限を過ぎていれば送らない（関門でも時間超過になるが、推論の無駄を省く）。
        if (deadlineNanos - nanoTime() <= 0) {
            return NeuralModelOutput.Failed(NeuralFailure.TIMEOUT) to
                NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.TIMEOUT)
        }
        cache[prompt]?.let {
            return it to NeuralCallRecord(sent = false, cacheHit = true, millis = 0.0, outcome = NeuralCallOutcome.COMPLETED)
        }
        // 送る前の判定はlockの中で行い、UIスレッドの中断（cancelInFlight）と入れ違いにならないようにする。
        val (target, pending) = synchronized(lock) {
            if (isSuperseded()) {
                return NeuralModelOutput.Cancelled to
                    NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.CANCELLED)
            }
            val current = port?.takeIf { ready } ?: return NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE) to
                NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.UNAVAILABLE)
            val created = InFlight(REQUEST_IDS.incrementAndGet(), owner, nanoTime())
            inFlight = created
            current to created
        }
        val sent = runCatching {
            target.convert(pending.requestId, prompt.toByteArray(Charsets.UTF_8), spec.format.parseSpecial, NeuralInferenceSettings.MAX_TOKENS)
        }.isSuccess
        // 期限までに届き、かつ届いた時刻が期限以内の結果だけを使う。await(0)は期限後に届いた結果でも成功するため時刻も見る。
        val finished = sent &&
            pending.done.await((deadlineNanos - nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) &&
            (pending.cancelled || pending.arrivedAtNanos - deadlineNanos <= 0)
        synchronized(lock) { if (inFlight === pending) inFlight = null }
        val waitedMillis = (nanoTime() - pending.sentAtNanos) / 1_000_000.0
        return when {
            !sent -> NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE) to
                NeuralCallRecord(sent = false, cacheHit = false, millis = 0.0, outcome = NeuralCallOutcome.UNAVAILABLE)
            pending.cancelled -> NeuralModelOutput.Cancelled to
                NeuralCallRecord(sent = true, cacheHit = false, millis = waitedMillis, outcome = NeuralCallOutcome.CANCELLED)
            !finished -> {
                runCatching { target.cancel(pending.requestId) }
                NeuralModelOutput.Failed(NeuralFailure.TIMEOUT) to
                    NeuralCallRecord(sent = true, cacheHit = false, millis = waitedMillis, outcome = NeuralCallOutcome.TIMEOUT)
            }
            else -> {
                val output = NeuralTermination.toOutput(pending.termination, pending.text)
                // 期限内に届いた結果だけをcacheへ入れる。
                if (output is NeuralModelOutput.Completed) cache[prompt] = output
                output to NeuralCallRecord(
                    sent = true,
                    cacheHit = false,
                    millis = pending.inferenceMicros / 1000.0,
                    outcome = NeuralTermination.toCallOutcome(pending.termination),
                )
            }
        }
    }

    private companion object {
        /** cacheに残すプロンプトの数の既定値。1文の入力で作られる要求（数十件）が収まる大きさにする。 */
        const val DEFAULT_CACHE_CAPACITY = 256

        // 要求番号。接続やモデルごとではなくIMEのプロセスの中で単調に増やし、別プロセス（native）が覚えている
        // 中断済みの番号と、モデルを切り替えた後の新しい要求の番号が重ならないようにする。
        private val REQUEST_IDS = AtomicLong(0)
    }
}
