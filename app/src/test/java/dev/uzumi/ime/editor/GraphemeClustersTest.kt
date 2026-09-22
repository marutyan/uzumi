package dev.uzumi.ime.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 書記素境界とAndroidのUTF-16 offset変換を検証する。 */
class GraphemeClustersTest {
    /** 結合文字、絵文字列、国旗を一つの表示単位として扱う。 */
    @Test
    fun splitKeepsCombinedSequencesTogether() {
        assertEquals(listOf("か\u3099", "👩‍💻", "🇯🇵"), GraphemeClusters.split("か\u3099👩‍💻🇯🇵"))
        assertEquals(5, GraphemeClusters.previousUtf16Length("A👩‍💻"))
    }

    /** 書記素の途中にあるUTF-16 offsetは安全な境界として返さない。 */
    @Test
    fun clusterIndexRejectsOffsetInsideCluster() {
        assertEquals(1, GraphemeClusters.clusterIndexAtUtf16("A😀B", 1))
        assertNull(GraphemeClusters.clusterIndexAtUtf16("A😀B", 2))
        assertEquals(2, GraphemeClusters.clusterIndexAtUtf16("A😀B", 3))
        assertNull(GraphemeClusters.clusterIndexAtUtf16("A😀B", -1))
        assertNull(GraphemeClusters.clusterIndexAtUtf16("A😀B", 5))
    }
}
