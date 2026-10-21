package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.PokemonNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokemonNamesTest {

    @Test
    fun `il nome inglese trova il numero del Pokedex`() {
        assertTrue(139 in PokemonNames.dexIdsMatching("omastar"))
    }

    @Test
    fun `il nome giapponese e quello inglese portano allo stesso Pokemon`() {
        assertEquals(setOf(303), PokemonNames.dexIdsMatching("クチート"))
        assertEquals(setOf(303), PokemonNames.dexIdsMatching("mawile"))
    }

    @Test
    fun `i testi troppo corti non estendono la ricerca`() {
        assertTrue(PokemonNames.dexIdsMatching("om").isEmpty())
    }

    @Test
    fun `accenti, punteggiatura e maiuscole non contano`() {
        assertTrue(669 in PokemonNames.dexIdsMatching("flabebe"))
        assertTrue(133 in PokemonNames.dexIdsMatching("EVOLI"))
        assertTrue(122 in PokemonNames.dexIdsMatching("mr mime"))
    }

    @Test
    fun `hiragana trova il nome in katakana`() {
        assertEquals(setOf(303), PokemonNames.dexIdsMatching("くちーと"))
    }

    @Test
    fun `due caratteri cinesi bastano`() {
        assertTrue(133 in PokemonNames.dexIdsMatching("伊布"))
    }

    @Test
    fun `normalizzazione dei nomi`() {
        assertEquals("flabebe", PokemonNames.normalize("Flabébé"))
        assertEquals("mrmime", PokemonNames.normalize("Mr. Mime"))
        assertEquals(PokemonNames.normalize("クチート"), PokemonNames.normalize("くちーと"))
        assertEquals(PokemonNames.normalize("クチート"), PokemonNames.normalize("ｸﾁｰﾄ"))
    }
}
