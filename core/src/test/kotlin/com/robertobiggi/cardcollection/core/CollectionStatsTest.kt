package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.stats.CollectionStats
import com.robertobiggi.cardcollection.core.stats.SlotInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectionStatsTest {

    @Test
    fun `statistiche contano solo gli slot desiderati per completamento`() {
        val s = CollectionStats.compute(
            listOf(
                SlotInput(wanted = true, quantity = 2, latestEur = 1.0, latestUsd = 1.5),
                SlotInput(wanted = true, quantity = 0, latestEur = 3.0, latestUsd = null),
                SlotInput(wanted = true, quantity = 0, latestEur = null, latestUsd = null),
                SlotInput(wanted = false, quantity = 1, latestEur = 10.0, latestUsd = null),
            ),
        )
        assertEquals(3, s.slots)
        assertEquals(1, s.owned)
        assertEquals(3, s.copies)
        assertEquals(12.0, s.valueEur, 1e-9)
        assertEquals(3.0, s.valueUsd, 1e-9)
        assertEquals(2, s.missing)
        assertEquals(3.0, s.missingEur, 1e-9)
        assertEquals(1, s.missingWithoutPrice)
        assertEquals(33, s.completionPercent())
    }
}
