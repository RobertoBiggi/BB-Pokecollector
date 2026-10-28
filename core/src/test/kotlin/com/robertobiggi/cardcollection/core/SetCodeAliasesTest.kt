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
    fun `la sigla stampata vale anche quando Cardmarket ne usa un'altra`() {
        // Sigle di TCGdex (abbreviation.official) diverse da quelle dell'elenco Cardmarket/CardTrader.
        val sets = listOf(
            SetBriefDto("swsh10", "Astral Radiance"), SetBriefDto("swsh10tg", "Astral Radiance Trainer Gallery"),
            SetBriefDto("swsh11", "Lost Origin"), SetBriefDto("sm4", "Crimson Invasion"), SetBriefDto("hgss1", "HeartGold SoulSilver"),
            SetBriefDto("pl4", "Arceus"), SetBriefDto("pop5", "POP Series 5"), SetBriefDto("base5", "Team Rocket"),
            SetBriefDto("ex7", "Team Rocket Returns"), SetBriefDto("ecard1", "Expedition Base Set"),
        )
        fun ids(token: String) = aliases.setsFor(token, Language.EN, sets).map { it.id }
        assertEquals(listOf("swsh10", "swsh10tg"), ids("ASR"))
        assertEquals(listOf("swsh10", "swsh10tg"), ids("astr"))
        assertEquals(listOf("swsh11"), ids("LOR"))
        assertEquals(listOf("swsh11"), ids("lorg"))
        assertEquals(listOf("sm4"), ids("CIN"))
        assertEquals(listOf("hgss1"), ids("HS"))
        assertEquals(listOf("pl4"), ids("AR"))
        assertEquals(listOf("pop5"), ids("P5"))
        assertEquals(listOf("pop5"), ids("pop5"))
        // "TR" è Team Rocket Returns sulla carta e Team Rocket su Cardmarket: si cerca in entrambi.
        assertEquals(listOf("base5", "ex7"), ids("TR"))
        assertEquals(listOf("base5"), ids("RO"))
        // "EX" è stampato su Expedition, ma è una parola dei nomi delle carte.
        assertEquals(emptyList<String>(), ids("EX"))
        assertEquals(listOf("asr", "astr"), aliases.codesOf(Language.EN, "swsh10", "Astral Radiance"))
        assertEquals(listOf("dx"), aliases.codesOf(Language.EN, "ex8", "Deoxys"))
    }

    @Test
    fun `la sigla stampata dei set europei non vale per i cataloghi asiatici`() {
        assertNull(aliases.printedCodeOf(Language.JA, "swsh10"))
        assertEquals("asr", aliases.printedCodeOf(Language.EN, "swsh10"))
        assertEquals("asr", aliases.printedCodeOf(Language.IT, "swsh10"))
    }

    @Test
    fun `ogni sigla della tabella stampata trova il suo set`() {
        // Tutta la tabella inclusa nell'app (sigle TCGdex del 27/10/2026): ogni sigla deve portare al suo set,
        // tranne "EX" (Expedition) che è una parola dei nomi delle carte.
        val stream = SetCodeAliases::class.java.getResourceAsStream("tcgdex-printed-codes.tsv")!!
        val table = SetCodeAliases.parsePrintedCodes(stream.bufferedReader(Charsets.UTF_8).readLines())
        assertTrue(table.size >= 180, "tabella troppo corta: ${table.size}")
        val sets = table.keys.map { SetBriefDto(it, "") }
        val notFound = table.filter { (id, code) -> code != "EX" && id !in aliases.setsFor(code, Language.EN, sets).map { it.id } }
        assertEquals(emptyMap<String, String>(), notFound)
        assertEquals(listOf("ex8"), aliases.setsFor("DX", Language.EN, sets).map { it.id })
        assertEquals(listOf("sv03.5"), aliases.setsFor("MEW", Language.EN, sets).map { it.id })
        // "G1": id TCGdex di Generations e sigla stampata di Gym Heroes, si cerca in entrambi.
        assertEquals(listOf("g1", "gym1"), aliases.setsFor("G1", Language.EN, sets).map { it.id })
    }

    @Test
    fun `la tabella delle sigle stampate scarta le righe malformate`() {
        val parsed = SetCodeAliases.parsePrintedCodes(listOf("swsh10\tASR", "solo-id", "\tASR", "x\t", "y\t" + "A".repeat(40)))
        assertEquals(mapOf("swsh10" to "ASR"), parsed)
    }

    @Test
    fun `un set che l'elenco non conosce non ha sigla`() {
        assertNull(aliases.codeOf(Language.EN, "zz1", "Set Inventato"))
    }
}
