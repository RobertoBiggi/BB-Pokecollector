package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.CardSearchWords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardSearchWordsTest {
    private val pokemon = setOf("pikachu", "mr mime", "charizard")
    private val isPokemon: (String) -> Boolean = { it.lowercase() in pokemon }

    @Test
    fun `splits the Pokémon from the set words, first or last`() {
        assertEquals("pikachu" to listOf("2010"), CardSearchWords.split(listOf("pikachu", "2010"), isPokemon))
        assertEquals("Pikachu" to listOf("world", "collection", "2010"), CardSearchWords.split(listOf("Pikachu", "world", "collection", "2010"), isPokemon))
        assertEquals("charizard" to listOf("base"), CardSearchWords.split(listOf("base", "charizard"), isPokemon))
        assertEquals("mr mime" to listOf("jungle"), CardSearchWords.split(listOf("mr", "mime", "jungle"), isPokemon))
    }

    @Test
    fun `gives nothing for one word or no Pokémon`() {
        assertNull(CardSearchWords.split(listOf("pikachu"), isPokemon))
        assertNull(CardSearchWords.split(listOf("unidentified", "fossil"), isPokemon))
    }

    @Test
    fun `a set fits when every word is in its name, id, code, series or release year`() {
        fun fits(vararg words: String) = CardSearchWords.matchesSet(words.toList(), "Pikachu World Collection 2010", "wc2010", null, "Other", "2010-04-01")
        assertTrue(fits("2010"))
        assertTrue(fits("world", "collection"))
        assertTrue(fits("WC2010"))
        assertTrue(fits("other"))
        assertFalse(fits("2011"))
        assertFalse(fits("world", "jungle"))
        assertFalse(CardSearchWords.matchesSet(listOf("2010"), "Jungle", "base2", "JU", null, "1999-06-16"))
        assertTrue(CardSearchWords.matchesSet(listOf("1999"), "Jungle", "base2", "JU", null, "1999-06-16"))
    }
}
