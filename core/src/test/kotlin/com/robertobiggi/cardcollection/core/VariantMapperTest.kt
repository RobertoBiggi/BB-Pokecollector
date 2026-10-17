package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.VariantMapper
import com.robertobiggi.cardcollection.core.tcgdex.CardDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient.Companion.JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VariantMapperTest {

    private fun card(name: String) = JSON.decodeFromString(CardDto.serializer(), Fixtures.read(name))

    @Test
    fun `holo rare usa i prezzi base, reverse usa i campi holo`() {
        val c = card("en_card_ex2-9.json")
        val holo = VariantMapper.quote(VariantType.HOLO, c.pricing, cardHasNormalAndHolo = false, source = "TCGdex")!!
        assertEquals(16.37, holo.eur)
        assertEquals(65.0, holo.usd)
        assertEquals(Confidence.HIGH, holo.confidence)
        val reverse = VariantMapper.quote(VariantType.REVERSE, c.pricing, false, "TCGdex")!!
        assertEquals(41.31, reverse.eur)
        assertEquals(60.74, reverse.usd)
        assertEquals(7.98, reverse.eurLow)
    }

    @Test
    fun `comune normal e reverse`() {
        val c = card("en_card_me03-032.json")
        val normal = VariantMapper.quote(VariantType.NORMAL, c.variantsDetailed!![0].pricing, false, "TCGdex")!!
        assertEquals(0.03, normal.eur)
        assertEquals(0.11, normal.usd)
        val reverse = VariantMapper.quote(VariantType.REVERSE, c.variantsDetailed!![1].pricing, false, "TCGdex")!!
        assertEquals(0.09, reverse.eur)
        assertEquals(0.2, reverse.usd)
    }

    @Test
    fun `carta JP ha solo Cardmarket e lo zero vale come assente`() {
        val c = card("ja_card_MC-754.json")
        val normal = VariantMapper.quote(VariantType.NORMAL, c.variantsDetailed!![0].pricing, false, "TCGdex")!!
        assertEquals(18.01, normal.eur)
        assertNull(normal.usd)
        assertNull(VariantMapper.quote(VariantType.REVERSE, c.variantsDetailed!![0].pricing, false, "TCGdex"))
    }

    @Test
    fun `holo con anche normal ha affidabilita media`() {
        val c = card("ja_card_SM10a-030.json")
        val q = VariantMapper.quote(VariantType.HOLO, c.pricing, cardHasNormalAndHolo = true, source = "TCGdex")!!
        assertEquals(Confidence.MEDIUM, q.confidence)
        assertNull(VariantMapper.quote(VariantType.HOLO, null, false, "TCGdex"))
    }
}
