package dev.uzumi.ime.learning

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 学習キャッシュの順位、上限、記録の規則、ON/OFF、保存、削除を検証する。 */
class LearningStoreTest {
    private val directory: File = Files.createTempDirectory("learning-store-test").toFile()
    private val file = File(directory, "learning.tsv")
    private var now = 0L

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store(
        maxWords: Int = LearningStore.MAX_WORDS,
        maxPhrases: Int = LearningStore.MAX_PHRASES,
        clearEngineFiles: () -> Unit = {},
    ) = LearningStore(file, clock = { now }, maxWords = maxWords, maxPhrases = maxPhrases, clearEngineFiles = clearEngineFiles)

    /** 回数が同じなら最後に使った語が先頭になり、回数の差が大きければ少し古い語でも先頭に残る。 */
    @Test
    fun recencyRanksFirstAndFrequencyCorrectsIt() {
        val store = store()
        store.record("こうえんに", "公演に")
        now = HOUR
        store.record("こうえんに", "校園に")
        assertEquals(listOf("校園に", "公演に"), store.exactMatches("こうえんに").map { it.surface })

        // 公演にを10回使うと、ln(10)×1日≒55時間ぶん新しく扱われ、1時間後の校園によりも前になる。
        now = 0
        repeat(9) { store.record("こうえんに", "公演に") }
        assertEquals(listOf("公演に", "校園に"), store.exactMatches("こうえんに").map { it.surface })

        // その後に校園にを使い直せば、時刻の差が補正を上回り先頭へ戻る。
        now = 3 * DAY
        store.record("こうえんに", "校園に")
        assertEquals("校園に", store.exactMatches("こうえんに").first().surface)
    }

    /** 一つの読みから返す語は最大3件で、前方一致は読みがより長い語だけを返す。 */
    @Test
    fun matchesAreLimitedToThreeAndPrefixExcludesExactReading() {
        val store = store()
        listOf("公園", "公演", "講演", "後援").forEachIndexed { index, surface ->
            now = index * HOUR
            store.record("こうえん", surface)
        }
        now = 10 * HOUR
        store.record("こうえんに", "校園に")

        assertEquals(listOf("後援", "講演", "公演"), store.exactMatches("こうえん").map { it.surface })
        assertEquals(listOf("校園に"), store.prefixMatches("こうえん").map { it.surface })
        assertEquals(3, store.prefixMatches("こう").size)
    }

    /** 上限を超えたら、scoreが最も低い語（最も古く使った語）から捨てる。 */
    @Test
    fun oldestWordIsEvictedAtCapacity() {
        val store = store(maxWords = 3)
        listOf("かんじ" to "漢字", "かんじ" to "幹事", "てんき" to "天気", "はし" to "橋").forEachIndexed { index, (r, s) ->
            now = index * HOUR
            store.record(r, s)
        }

        assertEquals(listOf("橋", "天気", "幹事"), store.allWords().map { it.surface })
    }

    /**
     * 句は作ってよいときだけ新しく作り、既にある句は作らない指定でも更新する。segmentとして確定し直しても句のまま数える。
     * 句だけを引く参照はsegmentの語を返さず、句の印は保存して読み直しても残る。
     */
    @Test
    fun phrasesAreCreatedOnlyWhenAllowedAndKeepTheirKind() {
        val store = store()
        assertFalse(store.recordPhrase("こうえんにいく", "校園に行く", createIfMissing = false))
        assertTrue(store.recordPhrase("こうえんにいく", "校園に行く", createIfMissing = true))
        now = HOUR
        assertTrue(store.recordPhrase("こうえんにいく", "校園に行く", createIfMissing = false))
        store.record("こうえんにいく", "校園に行く")
        store.record("こうえんに", "校園に")

        assertEquals(listOf(LearnedWord("こうえんにいく", "校園に行く", HOUR, 3, isPhrase = true)), store.exactPhraseMatches("こうえんにいく"))
        assertTrue(store.exactPhraseMatches("こうえんに").isEmpty())
        // 句も読みの完全一致と前方一致では語と同じく返す。
        assertEquals(listOf("校園に行く"), store.exactMatches("こうえんにいく").map { it.surface })
        assertEquals(listOf("校園に行く"), store.prefixMatches("こうえんに").map { it.surface })
        // ひらがなだけの句は覚えない。
        assertFalse(store.recordPhrase("いいよ", "いいよ", createIfMissing = true))

        store.flush()
        assertEquals(
            listOf(true, false),
            store().allWords().sortedByDescending { it.reading.length }.map { it.isPhrase },
        )
    }

    /** 句の印が無い4列の行（句を導入する前の保存ファイル）は、segmentの語として読む。 */
    @Test
    fun fourColumnLinesLoadAsWords() {
        file.writeText("uzumi-learning-v1\tenabled\nかんじ\t幹事\t5\t1\nこうえんにいく\t校園に行く\t6\t1\tphrase\n")

        val words = store().allWords()

        assertEquals(listOf("校園に行く" to true, "幹事" to false), words.map { it.surface to it.isPhrase })
    }

    /** 句が上限を超えたら、segmentの語がより古くても、scoreが最も低い句から捨てる。全体の上限では最も低い語を捨てる。 */
    @Test
    fun phraseLimitEvictsPhrasesBeforeWords() {
        val store = store(maxWords = 4, maxPhrases = 2)
        store.record("かんじ", "幹事")
        now = HOUR
        store.record("てんき", "天気")
        listOf("きょうはいい" to "今日はいい", "こうえんにいく" to "校園に行く", "はしをわたる" to "橋を渡る")
            .forEachIndexed { index, (reading, surface) ->
                now = (2 + index) * HOUR
                store.recordPhrase(reading, surface, createIfMissing = true)
            }
        assertEquals(listOf("橋を渡る", "校園に行く", "天気", "幹事"), store.allWords().map { it.surface })

        now = 10 * HOUR
        store.record("はし", "箸")
        assertEquals(listOf("箸", "橋を渡る", "校園に行く", "天気"), store.allWords().map { it.surface })
    }

    /** ひらがなだけ・ASCIIだけ・読みと同じ・50文字を超える表記は覚えない。 */
    @Test
    fun unlearnableSurfacesAreNotRecorded() {
        val store = store()
        assertFalse(store.record("いい", "いい"))
        assertFalse(store.record("よろしく", "よろしくー"))
        assertFalse(store.record("うずみ", "Uzumi"))
        assertFalse(store.record("ながい", "長".repeat(LearningRules.MAX_SURFACE_LENGTH + 1)))
        assertTrue(store.record("ながい", "長".repeat(LearningRules.MAX_SURFACE_LENGTH)))
        assertTrue(store.record("うずみ", "ウズミ"))
        assertEquals(2, store.allWords().size)
    }

    /** OFFの間は記録も参照もせず、ONに戻せば保存済みの語を再び返す。ON/OFFは保存される。 */
    @Test
    fun disabledStoreNeitherRecordsNorReturnsWords() {
        val store = store()
        store.record("かんじ", "幹事")
        store.setEnabled(false)

        assertFalse(store.record("てんき", "天気"))
        assertTrue(store.exactMatches("かんじ").isEmpty())
        assertTrue(store.prefixMatches("かん").isEmpty())
        assertFalse(store().isEnabled)

        store.setEnabled(true)
        assertEquals(listOf("幹事"), store.exactMatches("かんじ").map { it.surface })
    }

    /** 記録だけでは保存せず、flushで保存する。読み直すと時刻と回数を含めて同じ語を返す。 */
    @Test
    fun recordsAreSavedOnFlushAndRestored() {
        val store = store()
        now = 5 * HOUR
        store.record("こうえんに", "校園に")
        store.record("こうえんに", "校園に")
        assertFalse(file.exists())

        store.flush()

        assertEquals(listOf(LearnedWord("こうえんに", "校園に", 5 * HOUR, 2)), store().allWords())
    }

    /** 一語ずつの削除はすぐに保存し、他の語は残す。 */
    @Test
    fun removeDeletesOneWordAndSaves() {
        val store = store()
        store.record("かんじ", "幹事")
        store.record("かんじ", "漢字")

        assertTrue(store.remove("かんじ", "幹事"))
        assertFalse(store.remove("かんじ", "幹事"))

        assertEquals(listOf("漢字"), store().allWords().map { it.surface })
    }

    /** 全消去は保存ファイルを空にし、エンジンが動いていればエンジンへ、動いていなければファイルの消去を依頼する。 */
    @Test
    fun clearAllAlsoClearsEngineLearning() {
        var fileClears = 0
        var engineClears = 0
        val store = store(clearEngineFiles = { fileClears += 1 })
        store.record("かんじ", "幹事")

        store.clearAll()
        assertEquals(1, fileClears)

        store.setEngineClearer { engineClears += 1; true }
        store.record("かんじ", "幹事")
        store.clearAll()
        assertEquals(1, engineClears)
        assertEquals(1, fileClears)

        // workerが止まっていてエンジンへ依頼できなければ、ファイルを消す。
        store.setEngineClearer { false }
        store.clearAll()
        assertEquals(2, fileClears)
        assertTrue(store().allWords().isEmpty())
        assertTrue(store.isEnabled)
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
