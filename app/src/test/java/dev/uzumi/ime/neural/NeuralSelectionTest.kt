package dev.uzumi.ime.neural

import dev.uzumi.ime.conversion.NeuralModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 初期モデルと、利用者・評価手順が明示したモデルの選択を区別する。 */
class NeuralSelectionTest {
    @Test
    fun defaultsToZenzXsmallOnlyWhenUnset() {
        assertEquals(NeuralModelSpec.ZENZ_XSMALL, resolveNeuralSelection(null))
        assertNull(resolveNeuralSelection("M"))
        assertNull(resolveNeuralSelection("unknown"))
        assertNull(resolveNeuralSelection(""))
    }

    @Test
    fun preservesEachExplicitModelSelection() {
        NeuralModelSpec.entries.forEach { spec ->
            assertEquals(spec, resolveNeuralSelection(spec.key))
        }
    }
}
