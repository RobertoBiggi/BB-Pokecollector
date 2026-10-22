package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.BallVariants
import com.robertobiggi.cardcollection.core.model.VariantType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BallVariantsTest {
    @Test
    fun readsVariantsAndProductsIgnoringCaseAndLeadingZeros() {
        val balls = BallVariants.parse(listOf("SV2a\t139\tPOKE_BALL\t837502", "SV2a\t139\tMASTER_BALL\t837503", "SV2a\t140\tREVERSE\t1", "bad"))
        assertEquals(setOf(VariantType.POKE_BALL, VariantType.MASTER_BALL), balls.variantsOf("sv2A", "0139"))
        assertEquals(837502, balls.productId("SV2a", "139", VariantType.POKE_BALL))
        assertEquals(emptySet<VariantType>(), balls.variantsOf("SV2a", "140"))
    }

    @Test
    fun bundledListHasOmastarOf151() {
        assertEquals(837503, BallVariants.bundled.productId("SV2a", "139", VariantType.MASTER_BALL))
        assertEquals(285498, BallVariants.bundled.blueprintId("SV2a", "139", VariantType.MASTER_BALL))
        assertEquals(359719, BallVariants.bundled.blueprintId("SV2a", "139", VariantType.POKE_BALL))
    }
}

class PokemonNamesInNameTest {
    @Test
    fun findsThePokemonNamedInACardName() {
        val farfetchd = com.robertobiggi.cardcollection.core.model.PokemonNames.dexIdsInName("Farfetch'd")
        assertEquals(true, 83 in farfetchd && 139 !in farfetchd)
        assertEquals(true, 139 in com.robertobiggi.cardcollection.core.model.PokemonNames.dexIdsInName("Brock's Omastar"))
        assertEquals(emptySet<Int>(), com.robertobiggi.cardcollection.core.model.PokemonNames.dexIdsInName("ダークオマスター"))
    }
}
