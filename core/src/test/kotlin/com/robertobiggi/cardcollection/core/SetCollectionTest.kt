package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.core.model.SetCollectionPolicy
import com.robertobiggi.cardcollection.core.model.VariantType.CARDMARKET_PRINT
import com.robertobiggi.cardcollection.core.model.VariantType.FIRST_EDITION
import com.robertobiggi.cardcollection.core.model.VariantType.HOLO
import com.robertobiggi.cardcollection.core.model.VariantType.JUMBO
import com.robertobiggi.cardcollection.core.model.VariantType.MASTER_BALL
import com.robertobiggi.cardcollection.core.model.VariantType.NORMAL
import com.robertobiggi.cardcollection.core.model.VariantType.POKETOUR_99
import com.robertobiggi.cardcollection.core.model.VariantType.POKE_BALL
import com.robertobiggi.cardcollection.core.model.VariantType.RED_CHEEK
import com.robertobiggi.cardcollection.core.model.VariantType.REVERSE
import com.robertobiggi.cardcollection.core.model.VariantType.SHADOWLESS
import com.robertobiggi.cardcollection.core.model.VariantType.STAMPED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetCollectionTest {

    @Test
    fun `tipo di ricerca letto e validato`() {
        assertEquals(QueryKind.NAME, QueryKind.fromCode(null))
        assertEquals(QueryKind.SET_MASTER, QueryKind.fromCode("set_master"))
        assertNull(QueryKind.fromCode("altro"))
        assertTrue(QueryKind.isValidPattern(QueryKind.SET_BASE, "base1"))
        assertTrue(QueryKind.isValidPattern(QueryKind.SET_MASTER, "SV2a"))
        assertFalse(QueryKind.isValidPattern(QueryKind.SET_MASTER, "../x"))
        assertFalse(QueryKind.isValidPattern(QueryKind.SET_BASE, "Base Set"))
        assertTrue(QueryKind.isValidPattern(QueryKind.NAME, "Mawile"))
    }

    @Test
    fun `set base tiene solo la variante principale`() {
        assertEquals(setOf(REVERSE, POKE_BALL, MASTER_BALL), SetCollectionPolicy.unwantedVariants(listOf(NORMAL, REVERSE, POKE_BALL, MASTER_BALL), QueryKind.SET_BASE))
        assertEquals(setOf(FIRST_EDITION), SetCollectionPolicy.unwantedVariants(listOf(FIRST_EDITION, HOLO), QueryKind.SET_BASE))
        assertEquals(emptySet<Any>(), SetCollectionPolicy.unwantedVariants(listOf(REVERSE), QueryKind.SET_BASE))
        assertEquals(HOLO, SetCollectionPolicy.mainVariant(listOf(REVERSE, HOLO)))
    }

    @Test
    fun `master set esclude le stampe a parte come la prima edizione`() {
        // Pikachu del Set Base: normale, 1st Edition, Shadowless, Guance rosse, Pokétour '99, Jumbo e un timbro.
        val pikachu = listOf(NORMAL, FIRST_EDITION, SHADOWLESS, RED_CHEEK, POKETOUR_99, JUMBO, STAMPED, CARDMARKET_PRINT)
        assertEquals(setOf(FIRST_EDITION, SHADOWLESS, RED_CHEEK, POKETOUR_99, JUMBO, STAMPED, CARDMARKET_PRINT), SetCollectionPolicy.unwantedVariants(pikachu, QueryKind.SET_MASTER))
        assertEquals(pikachu.toSet() - NORMAL, SetCollectionPolicy.unwantedVariants(pikachu, QueryKind.SET_BASE))
        // Carta che esiste solo come stampa a parte: resta uno slot.
        assertEquals(emptySet<Any>(), SetCollectionPolicy.unwantedVariants(listOf(JUMBO), QueryKind.SET_MASTER))
        assertEquals(emptySet<Any>(), SetCollectionPolicy.unwantedVariants(listOf(JUMBO), QueryKind.SET_BASE))
    }

    @Test
    fun `master set esclude solo la prima edizione`() {
        assertEquals(setOf(FIRST_EDITION), SetCollectionPolicy.unwantedVariants(listOf(FIRST_EDITION, NORMAL, HOLO), QueryKind.SET_MASTER))
        assertEquals(emptySet<Any>(), SetCollectionPolicy.unwantedVariants(listOf(NORMAL, REVERSE, POKE_BALL), QueryKind.SET_MASTER))
        // Carta che esiste solo in 1st Edition: resta uno slot.
        assertEquals(emptySet<Any>(), SetCollectionPolicy.unwantedVariants(listOf(FIRST_EDITION), QueryKind.SET_MASTER))
        assertEquals(emptySet<Any>(), SetCollectionPolicy.unwantedVariants(listOf(FIRST_EDITION, NORMAL), QueryKind.NAME))
    }
}
