package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExtraSets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardmarketExtraSetsTest {
    @Test
    fun readsSetsAndPrintedNumbers() {
        val extra = CardmarketExtraSets.parse(
            listOf(
                "S\tja\tPt4\tPt4\tAdvent of Arceus\t4280\t1",
                "S\tja\tunpromo\t\tUnnumbered Promos\t4170\t0",
                "S\tfr\tpl4\t\tArceus\t1\t1",
                "C\tja\tPt4\t023\t676156",
                "C\tja\tunpromo\t\t571821",
                "malformed",
            ),
        )
        assertEquals(listOf("Pt4", "unpromo"), extra.sets.map { it.setId })
        assertEquals("Pt4", extra.sets[0].setCode)
        assertEquals(null, extra.sets[1].setCode)
        assertTrue(extra.sets[0].whole)
        assertEquals("023", extra.sets[0].numbers[676156])
        assertEquals("", extra.sets[1].numbers[571821])
    }

    @Test
    fun bundledListHasTheJapaneseSetsTcgdexLacks() {
        val sets = CardmarketExtraSets.bundled.sets.associateBy { it.setId }
        assertEquals("023", sets.getValue("Pt4").numbers[676156])
        assertTrue(571821 in sets.getValue("unpromo").numbers)
    }
}
