package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.SetCodeAliases
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.tcgdex.SetBriefDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Sigle Cardmarket dei set TCGdex, con l'elenco incluso nell'app: id e nomi sono quelli del catalogo TCGdex inglese.
 */
class SetCodeAliasesTest {

    private val aliases = SetCodeAliases.bundled

    /** Set TCGdex inglesi (id, nome) con la sigla Cardmarket attesa. */
    private val expected = listOf(
        Triple("base1", "Base Set", "bs"),
        Triple("base2", "Jungle", "ju"),
        Triple("base3", "Fossil", "fo"),
        Triple("base5", "Team Rocket", "tr"),
        Triple("neo1", "Neo Genesis", "n1"),
        Triple("ecard1", "Expedition Base Set", "ex"),
        Triple("ex1", "Ruby & Sapphire", "rs"),
        Triple("ex8", "Deoxys", "dx"),
        Triple("ex13", "Holon Phantoms", "hp"),
        Triple("ex15", "Dragon Frontiers", "df"),
        Triple("dp1", "Diamond & Pearl", "dp"),
        Triple("pl1", "Platinum", "pl"),
        Triple("bw1", "Black & White", "blw"),
        Triple("xy1", "XY", "xy"),
        Triple("sm1", "Sun & Moon", "sum"),
        Triple("sm5", "Ultra Prism", "upr"),
        Triple("swsh7", "Evolving Skies", "evs"),
        Triple("sv02", "Paldea Evolved", "pal"),
        Triple("sv03", "Obsidian Flames", "obf"),
        Triple("sv08", "Surging Sparks", "ssp"),
    )

    @Test
    fun `ogni set TCGdex ha la sua sigla Cardmarket`() {
        val wrong = expected.filter { (id, name, code) -> aliases.codeOf(Language.EN, id, name) != code }
            .map { (id, name, code) -> "$id/$name: atteso $code, trovato ${aliases.codeOf(Language.EN, id, name)}" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `Deoxys si trova sia con dx sia con ex8`() {
        val sets = expected.map { SetBriefDto(it.first, it.second) }
        assertEquals(listOf("ex8"), aliases.setsFor("dx", Language.EN, sets).map { it.id })
        assertEquals(listOf("ex8"), aliases.setsFor("DX", Language.EN, sets).map { it.id })
        assertEquals(listOf("ex8"), aliases.setsFor("ex8", Language.EN, sets).map { it.id })
        assertEquals(listOf("xy1"), aliases.setsFor("XY", Language.EN, sets).map { it.id })
        assertTrue(aliases.matches("Dx", Language.EN, "ex8", "Deoxys"))
    }

    @Test
    fun `le parole dei nomi delle carte non sono sigle`() {
        // "ex" su Cardmarket è Expedition, ma in "Rayquaza ex 102" è parte del nome.
        assertFalse(aliases.matches("ex", Language.EN, "ecard1", "Expedition Base Set"))
        assertTrue(aliases.matches("ecard1", Language.EN, "ecard1", "Expedition Base Set"))
        assertFalse(aliases.matches("Rayquaza", Language.EN, "ex8", "Deoxys"))
        assertEquals(emptyList<String>(), aliases.setsFor("zzz", Language.EN, listOf(SetBriefDto("ex8", "Deoxys"))).map { it.id })
    }

    @Test
    fun `un set che l'elenco non conosce non ha sigla`() {
        assertNull(aliases.codeOf(Language.EN, "zz1", "Set Inventato"))
    }
}
