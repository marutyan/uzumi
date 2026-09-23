package dev.uzumi.ime.neural

import dev.uzumi.ime.conversion.NeuralCallOutcome
import dev.uzumi.ime.conversion.NeuralCallRecord
import dev.uzumi.ime.conversion.NeuralFailure
import dev.uzumi.ime.conversion.NeuralModelOutput
import dev.uzumi.ime.conversion.NeuralModelSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 別プロセスの推論serviceとの受け渡しを、別threadで結果を返すfakeのserviceで検証する。
 * 要求番号の照合、cache、時間超過での中断、新しい入力での中断、接続が切れた場合を固定する。
 */
class NeuralRuntimeClientTest {
    private val service = FakeRuntime()
    private val client = NeuralRuntimeClient(NeuralModelSpec.JINEN_XSMALL).also { service.listener = it }
    private val records = CopyOnWriteArrayList<NeuralCallRecord>()

    @After
    fun tearDown() {
        service.shutdown()
    }

    /** 期限（System.nanoTimeの値）を今からmillisミリ秒後にしたモデル。 */
    private fun model(millis: Long, owner: Long = 1, superseded: () -> Boolean = { false }) =
        client.modelFor(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis), owner, superseded) { records += it }

    /** 接続と読み込みを済ませる。 */
    private fun connect() {
        client.attach(service)
        client.onLoaded(true)
    }

    /** jinenの形式で組み立てたプロンプトを送り、別threadから届いた結果をそのまま返す。同じプロンプトはcacheから返す。 */
    @Test
    fun roundTripUsesPromptFormatAndCache() {
        connect()
        service.reply = { _ -> NeuralTermination.EOG to "今日" }

        val first = model(1_000).convert("きょう", "")
        val second = model(1_000).convert("きょう", "")

        assertEquals(NeuralModelOutput.Completed("今日"), first)
        assertEquals(first, second)
        assertEquals(listOf("キョウ"), service.prompts)
        assertEquals(listOf(true), service.parseSpecial)
        assertEquals(listOf(false, true), records.map { it.cacheHit })
        assertEquals(NeuralCallOutcome.COMPLETED, records.first().outcome)
    }

    /** token上限での打ち切りは検査2の材料として渡し、壊れたUTF-8は置換文字にして検査1へ渡す。 */
    @Test
    fun truncatedAndInvalidOutputsAreMarked() {
        connect()
        service.reply = { prompt -> if ("キョウ" in prompt) NeuralTermination.TOKEN_LIMIT to "今日今日" else NeuralTermination.EOG to null }

        assertEquals(NeuralModelOutput.Completed("今日今日", truncated = true), model(1_000).convert("きょう", ""))
        val invalid = model(1_000).convert("あ", "") as NeuralModelOutput.Completed
        assertTrue(invalid.text.contains('�'))
    }

    /** 期限までに結果が無ければ時間超過を返し、serviceへ中断を送る。遅れて届いた結果は捨てる。 */
    @Test
    fun timeoutCancelsAndIgnoresLateResult() {
        connect()
        service.delayMillis = 300
        service.reply = { _ -> NeuralTermination.EOG to "遅い" }

        val started = System.nanoTime()
        val result = model(50).convert("おそい", "")
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(NeuralModelOutput.Failed(NeuralFailure.TIMEOUT), result)
        assertTrue("waited $waited ms", waited in 40..250)
        assertEquals(listOf(1L), service.cancels)
        assertEquals(NeuralCallOutcome.TIMEOUT, records.single().outcome)
        // 遅れた結果が届いた後の次の要求は、番号が合う自分の結果だけを受け取る。
        service.delayMillis = 0
        service.reply = { _ -> NeuralTermination.EOG to "次" }
        Thread.sleep(350)
        assertEquals(NeuralModelOutput.Completed("次"), model(1_000).convert("つぎ", ""))
    }

    /** 新しい入力の要求（大きい通し番号）で、待っている古い要求を中断する。同じ要求の番号では中断しない。 */
    @Test
    fun newerRequestCancelsInFlightInference() {
        connect()
        service.delayMillis = 2_000
        service.reply = { _ -> NeuralTermination.EOG to "古い" }
        val canceller = Executors.newSingleThreadScheduledExecutor()
        canceller.schedule({
            client.cancelInFlight(newOwner = 5)
            client.cancelInFlight(newOwner = 6)
        }, 50, TimeUnit.MILLISECONDS)
        client.cancelInFlight(newOwner = 1)

        val result = model(5_000, owner = 5).convert("ふるい", "")
        canceller.shutdown()

        assertEquals(NeuralModelOutput.Cancelled, result)
        assertEquals(listOf(1L), service.cancels)
        assertEquals(NeuralCallOutcome.CANCELLED, records.single().outcome)
    }

    /** 新しい要求が既に待っていれば送らずに中断を返す。接続前や切れた後はモデルが使えない結果を返す。 */
    @Test
    fun supersededOrDisconnectedRequestsAreNotSent() {
        assertEquals(NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE), model(1_000).convert("あ", ""))
        connect()
        assertEquals(NeuralModelOutput.Cancelled, model(1_000, superseded = { true }).convert("あ", ""))
        client.detach()
        assertEquals(NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE), model(1_000).convert("い", ""))
        assertTrue(service.prompts.isEmpty())
    }

    /** 接続が切れたら、待っている要求はモデルが使えない結果で終わる。 */
    @Test
    fun detachWakesWaitingRequest() {
        connect()
        service.delayMillis = 5_000
        service.reply = { _ -> NeuralTermination.EOG to "来ない" }
        val detacher = Executors.newSingleThreadScheduledExecutor()
        detacher.schedule({ client.detach() }, 50, TimeUnit.MILLISECONDS)

        val result = model(5_000).convert("こない", "")
        detacher.shutdown()

        assertEquals(NeuralModelOutput.Failed(NeuralFailure.UNAVAILABLE), result)
    }

    /**
     * 別threadから結果を返すfakeの推論service。binderのthreadの代わりに一つのthreadで、指定の遅れの後に返す。
     * replyはプロンプトから終わり方と出力（nullなら壊れたUTF-8）を決める。
     */
    private class FakeRuntime : NeuralRuntimePort {
        lateinit var listener: NeuralRuntimeListener
        var reply: (String) -> Pair<Int, String?> = { _ -> NeuralTermination.EOG to "" }

        @Volatile var delayMillis = 0L
        val prompts = CopyOnWriteArrayList<String>()
        val parseSpecial = CopyOnWriteArrayList<Boolean>()
        val cancels = CopyOnWriteArrayList<Long>()
        private val binder = Executors.newSingleThreadScheduledExecutor()

        override fun convert(requestId: Long, prompt: ByteArray, parseSpecial: Boolean, maxTokens: Int) {
            val text = String(prompt, Charsets.UTF_8)
            prompts += text
            this.parseSpecial += parseSpecial
            val (termination, output) = reply(text)
            val bytes = output?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(0xE3.toByte(), 0x81.toByte())
            binder.schedule({ listener.onResult(requestId, termination, bytes, 1_500) }, delayMillis, TimeUnit.MILLISECONDS)
        }

        override fun cancel(requestId: Long) {
            cancels += requestId
        }

        fun shutdown() {
            binder.shutdownNow()
        }
    }
}
