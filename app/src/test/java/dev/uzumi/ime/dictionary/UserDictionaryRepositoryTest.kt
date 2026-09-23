package dev.uzumi.ime.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ユーザー辞書の登録・検索・編集・削除・import、保存ファイルからの復元と書込み失敗時の保護を検証する。 */
class UserDictionaryRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "user_dictionary.tsv")

    private fun open(): UserDictionaryRepository =
        UserDictionaryRepository.open(UserDictionaryFileStore(file), clock = { 1234L })

    /** 追加・編集・削除した内容が、開き直した辞書へ復元される。 */
    @Test
    fun persistsChangesAcrossReopen() {
        val dictionary = open()
        assertNull(dictionary.add(UserDictionaryEntry("うずみ", "渦巻")))
        assertNull(dictionary.add(UserDictionaryEntry("うずみ", "Uzumi", UserDictionaryCategory.PROPER_NOUN)))
        assertNull(dictionary.add(UserDictionaryEntry("やまだ", "山田")))
        assertNull(dictionary.update(UserDictionaryEntry("やまだ", "山田"), UserDictionaryEntry("やまだ", "山田", UserDictionaryCategory.PERSON_NAME)))
        assertNull(dictionary.remove(UserDictionaryEntry("うずみ", "渦巻")))

        val reopened = open()
        assertEquals(
            listOf(
                UserDictionaryEntry("うずみ", "Uzumi", UserDictionaryCategory.PROPER_NOUN),
                UserDictionaryEntry("やまだ", "山田", UserDictionaryCategory.PERSON_NAME),
            ),
            reopened.allEntries(),
        )
        assertFalse(reopened.hadLoadProblem)
    }

    /** 同じ読みと表記の組は分類が違っても重複として拒否し、編集で別項目と重なる場合も拒否する。 */
    @Test
    fun rejectsDuplicatesAndMissingTargets() {
        val dictionary = open()
        assertNull(dictionary.add(UserDictionaryEntry("はし", "橋")))
        assertNull(dictionary.add(UserDictionaryEntry("はし", "箸")))
        assertEquals(
            UserDictionaryError.DUPLICATE,
            dictionary.add(UserDictionaryEntry(" はし ", "橋", UserDictionaryCategory.PLACE_NAME)),
        )
        assertEquals(
            UserDictionaryError.DUPLICATE,
            dictionary.update(UserDictionaryEntry("はし", "箸"), UserDictionaryEntry("はし", "橋")),
        )
        assertEquals(UserDictionaryError.NOT_FOUND, dictionary.remove(UserDictionaryEntry("はし", "端")))
        assertEquals(
            UserDictionaryError.READING_NOT_HIRAGANA,
            dictionary.update(UserDictionaryEntry("はし", "箸"), UserDictionaryEntry("ハシ", "箸")),
        )
        assertEquals(listOf("橋", "箸"), dictionary.allEntries().map(UserDictionaryEntry::surface))
    }

    /** 完全一致は登録順、前方一致は読みの辞書順で返し、変更ごとに世代が増える。 */
    @Test
    fun looksUpByExactAndPrefix() {
        val dictionary = open()
        val initialGeneration = dictionary.generation
        dictionary.add(UserDictionaryEntry("かんじ", "漢字"))
        dictionary.add(UserDictionaryEntry("かん", "缶"))
        dictionary.add(UserDictionaryEntry("かんじ", "幹事"))
        dictionary.add(UserDictionaryEntry("きかん", "期間"))
        assertEquals(initialGeneration + 4, dictionary.generation)

        assertEquals(listOf("漢字", "幹事"), dictionary.exactMatches("かんじ").map(UserDictionaryEntry::surface))
        assertEquals(listOf("漢字", "幹事"), dictionary.exactMatches("かんし\u3099").map(UserDictionaryEntry::surface))
        assertTrue(dictionary.exactMatches("か").isEmpty())
        assertEquals(
            listOf("缶", "漢字", "幹事"),
            dictionary.prefixMatches("かん", limit = 10).map(UserDictionaryEntry::surface),
        )
        assertEquals(listOf("缶", "漢字"), dictionary.prefixMatches("か", limit = 2).map(UserDictionaryEntry::surface))
        assertTrue(dictionary.prefixMatches("さ", limit = 10).isEmpty())
        assertEquals(listOf("期間"), dictionary.search("期").map(UserDictionaryEntry::surface))
    }

    /** importは正しい行だけを追加し、重複と失敗行を数える。既存項目は残る。 */
    @Test
    fun importsValidLinesAndKeepsExistingEntries() {
        val dictionary = open()
        dictionary.add(UserDictionaryEntry("うずみ", "渦巻"))
        val summary = dictionary.import(
            UserDictionaryTsv.parse("うずみ\t渦巻\nやまだ\t山田\t人名\nやまだ\t山田\nABC\t不正\n"),
        )
        assertEquals(UserDictionaryImportSummary(1, 2, 0, listOf(TsvLineFailure(4, UserDictionaryError.READING_NOT_HIRAGANA)), false), summary)
        assertEquals(listOf("渦巻", "山田"), open().allEntries().map(UserDictionaryEntry::surface))
    }

    /** 書込みに失敗した変更はメモリにも保存ファイルにも反映しない。 */
    @Test
    fun keepsExistingDataWhenSaveFails() {
        val dictionary = open()
        dictionary.add(UserDictionaryEntry("うずみ", "渦巻"))
        val before = file.readText()
        // 一時ファイルの位置に空でないディレクトリを置き、書込みを必ず失敗させる。
        File(file.path + ".tmp").apply { mkdirs() }.resolve("block").writeText("x")

        assertEquals(UserDictionaryError.STORAGE_FAILED, dictionary.add(UserDictionaryEntry("やまだ", "山田")))
        val summary = dictionary.import(UserDictionaryTsv.parse("たなか\t田中\n"))
        assertTrue(summary.storageFailed)
        assertEquals(0, summary.added)
        assertEquals(listOf("渦巻"), dictionary.allEntries().map(UserDictionaryEntry::surface))
        assertEquals(before, file.readText())
    }

    /** 異常終了で残った一時ファイルは読まず、保存済みのファイルから復元する。 */
    @Test
    fun ignoresLeftoverTemporaryFile() {
        open().add(UserDictionaryEntry("うずみ", "渦巻"))
        File(file.path + ".tmp").writeText("やまだ\t山")

        val reopened = open()
        assertEquals(listOf("渦巻"), reopened.allEntries().map(UserDictionaryEntry::surface))
        assertNull(reopened.add(UserDictionaryEntry("やまだ", "山田")))
        assertEquals(listOf("渦巻", "山田"), open().allEntries().map(UserDictionaryEntry::surface))
    }

    /** 保存ファイルに読めない行があれば、最初の上書き前に原本を別名で残す。 */
    @Test
    fun preservesUnreadableFileBeforeOverwriting() {
        val original = "うずみ\t渦巻\n壊れた行\n"
        file.writeText(original)

        val dictionary = open()
        assertTrue(dictionary.hadLoadProblem)
        assertEquals(listOf("渦巻"), dictionary.allEntries().map(UserDictionaryEntry::surface))
        assertNull(dictionary.add(UserDictionaryEntry("やまだ", "山田")))

        assertEquals(original, File(file.path + ".unreadable-1234").readText())
        assertEquals(listOf("渦巻", "山田"), open().allEntries().map(UserDictionaryEntry::surface))
    }
}
