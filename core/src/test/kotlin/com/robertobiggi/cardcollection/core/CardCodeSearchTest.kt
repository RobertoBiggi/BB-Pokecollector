package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeQuery
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardCodeSearchTest {

    @Test
    fun `parse riconosce le forme del codice`() {
        assertEquals(CardCodeQuery(117, 80, listOf("mur")), CardCodeSearch.parse("117/080 mur"))
        assertEquals(CardCodeQuery(117, 80, listOf("M3")), CardCodeSearch.parse(" M3  117/080 "))
        assertEquals(CardCodeQuery(30, null, listOf("SM10a")), CardCodeSearch.parse("SM10a-030"))
        assertEquals(CardCodeQuery(46, null, listOf("MEG")), CardCodeSearch.parse("MEG 046"))
    }

    @Test
    fun `parse lascia alla ricerca per nome i testi senza numero`() {
        assertNull(CardCodeSearch.parse("Mawile"))
        assertNull(CardCodeSearch.parse("Porygon2"))
        assertNull(CardCodeSearch.parse("117/080; DROP"))
        assertNull(CardCodeSearch.parse("../../117"))
    }

    @Test
    fun `numero confrontato senza zeri iniziali`() {
        assertTrue(CardCodeSearch.matchesNumber("046", 46))
        assertTrue(CardCodeSearch.matchesNumber("46", 46))
        assertFalse(CardCodeSearch.matchesNumber("146", 46))
        assertFalse(CardCodeSearch.matchesNumber("XY46", 46))
    }




}
