package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.stats.CollectionStats
import com.robertobiggi.cardcollection.core.stats.SlotInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectionStatsTest {

    @Test
    fun `le varianti possedute contano sempre, le mancanti solo se desiderate`() {
        val s = CollectionStats.compute(
            listOf(
                SlotInput(wanted = true, quantity = 2, latestEur = 1.0),
                SlotInput(wanted = true, quantity = 0, latestEur = 3.0),
                SlotInput(wanted = true, quantity = 0, latestEur = null),
                SlotInput(wanted = false, quantity = 1, latestEur = 10.0),
                SlotInput(wanted = false, quantity = 0, latestEur = 5.0),
            ),
        )
        assertEquals(4, s.slots)
        assertEquals(2, s.owned)
        assertEquals(3, s.copies)
        assertEquals(12.0, s.valueEur, 1e-9)
        assertEquals(2, s.missing)
        assertEquals(3.0, s.missingEur, 1e-9)
        assertEquals(1, s.missingWithoutPrice)
        assertEquals(50, s.completionPercent())
    }
}
