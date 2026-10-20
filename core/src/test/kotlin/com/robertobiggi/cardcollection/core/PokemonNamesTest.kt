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
}
