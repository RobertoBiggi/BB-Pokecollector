package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.tcgdex.CardBriefDto
import com.robertobiggi.cardcollection.core.tcgdex.CardDto
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.SetDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient.Companion.JSON
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogMapperTest {

    private fun card(name: String) = JSON.decodeFromString(CardDto.serializer(), Fixtures.read(name))
    private fun set(name: String) = JSON.decodeFromString(SetDto.serializer(), Fixtures.read(name))

    @Test
    fun `carta EN recente ha sigla e numero stampati come sulla carta`() {
        val mapped = CatalogMapper.toCatalogCard(Language.EN, card("en_card_me03-032.json"), set("en_set_me03.json"))
        assertEquals("en:me03-032", mapped.id)
        assertEquals("POR", mapped.setCode)
        assertEquals("032/088", mapped.printedNumber)
        assertEquals("Mega Evolution", mapped.serieName)
        assertEquals("2026-03-27", mapped.releaseDate)
        assertEquals(listOf(VariantType.NORMAL, VariantType.REVERSE), mapped.variants.map { it.type })
        assertEquals("https://assets.tcgdex.net/en/me/me03/032", mapped.imageBaseUrl)
    }

    @Test
    fun `carta EN vecchia non ha sigla e il numero non viene allungato`() {
        val mapped = CatalogMapper.toCatalogCard(Language.EN, card("en_card_ex2-9.json"), null)
        assertNull(mapped.setCode)
        assertEquals("9/100", mapped.printedNumber)
        assertEquals(listOf(VariantType.HOLO, VariantType.REVERSE), mapped.variants.map { it.type })
    }

    @Test
    fun `carta JP usa l'id del set come sigla e scarta la rarita None`() {
        val gx = CatalogMapper.toCatalogCard(Language.JA, card("ja_card_SM10a-030.json"), set("ja_set_SM10a.json"))
        assertEquals("ja:SM10a-030", gx.id)
        assertEquals("SM10a", gx.setCode)
        assertEquals("030/054", gx.printedNumber)
        assertNull(gx.imageBaseUrl, "le carte JP senza immagine restano senza URL")
        val mc = CatalogMapper.toCatalogCard(Language.JA, card("ja_card_MC-754.json"), null)
        assertNull(mc.rarity)
        assertEquals("754/742", mc.printedNumber)
    }

    @Test
    fun `sigla localizzata e varianti tradotte`() {
        val base = card("en_card_me03-032.json")
        val italian = base.copy(variantsDetailed = base.variantsDetailed!!.map { it.copy(type = if (it.type == "normal") "Normale" else it.type) })
        assertEquals(listOf(VariantType.NORMAL, VariantType.REVERSE), CatalogMapper.variantsOf(italian).map { it.type })
        val frSet = set("en_set_me03.json").copy(abbreviation = com.robertobiggi.cardcollection.core.tcgdex.AbbreviationDto("POR", "ORP"))
        assertEquals("ORP", CatalogMapper.printedSetCode(Language.FR, frSet, "me03"))
        assertEquals("SI", CatalogMapper.printedSetCode(Language.ZH_TW, null, "SI"))
    }

    @Test
    fun `promo con numero alfanumerico resta invariato`() {
        assertEquals("XY103", CatalogMapper.printedNumber("XY103", 211))
    }

    @Test
    fun `tutte le carte fisiche vengono tracciate`() {
        assertTrue(CatalogMapper.isTrackable(card("en_card_me03-032.json"), null))
        assertTrue(CatalogMapper.isTrackable(card("en_card_xyp-XY105.json"), null), "gli Allenatori restano")
        val list = JSON.decodeFromString(ListSerializer(CardBriefDto.serializer()), Fixtures.read("en_list_mawile.json"))
        val pocket = list.filter { CatalogMapper.isDigitalOnly(it) }.map { it.id }
        assertEquals(listOf("A3b-047"), pocket)
    }

    @Test
    fun `le promo non hanno reverse holo anche con prezzi holo`() {
        val variants = CatalogMapper.variantsOf(card("en_card_xyp-XY105.json"), releaseDate = "2016-01-01")
        assertEquals(listOf(VariantType.NORMAL), variants.map { it.type })
    }

    @Test
    fun `id del set ricavato dall'id carta, anche con punto`() {
        assertEquals("me02.5", CatalogMapper.setIdFromCardId("me02.5-144"))
        assertEquals("swsh12tg", CatalogMapper.setIdFromCardId("swsh12tg-TG17"))
        assertNull(CatalogMapper.setIdFromCardId("senzatrattino"))
    }
}
