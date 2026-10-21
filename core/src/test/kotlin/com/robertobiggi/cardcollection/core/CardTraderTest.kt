package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderPicker
import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto
import com.robertobiggi.cardcollection.core.cardtrader.CtProductDto
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CardTraderTest {

    private fun products(json: String): List<CtProductDto> =
        TcgdexClient.JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CtProductDto.serializer()), json)

    /** Omastar: un solo blueprint, annunci normal e reverse mischiati. */
    private val omastar = products(
        """[
          {"id":1,"price_cents":250,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":2,"price_cents":90,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":3,"price_cents":600,"price_currency":"EUR","quantity":2,"properties_hash":{"condition":"Near Mint","pokemon_reverse":true,"pokemon_language":"en"}},
          {"id":4,"price_cents":450,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":true,"pokemon_language":"en"}},
          {"id":5,"price_cents":20,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Played","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":6,"price_cents":10,"price_currency":"EUR","quantity":1,"graded":true,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":7,"price_cents":30,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"it"}}
        ]""",
    )

    @Test
    fun `normal prende il piu basso tra gli annunci non reverse, Near Mint, nella lingua`() {
        assertEquals(90L, CardTraderPicker.lowest(omastar, VariantType.NORMAL, "en")?.cents)
    }

    @Test
    fun `reverse prende il piu basso tra i soli annunci reverse`() {
        assertEquals(450L, CardTraderPicker.lowest(omastar, VariantType.REVERSE, "en")?.cents)
    }

    @Test
    fun `senza annunci Near Mint accetta altre condizioni`() {
        val played = omastar.filter { it.id == 5L }
        assertEquals(20L, CardTraderPicker.lowest(played, VariantType.NORMAL, "en")?.cents)
    }

    @Test
    fun `nessun annuncio reverse restituisce null`() {
        assertNull(CardTraderPicker.lowest(omastar.filter { it.id != 3L && it.id != 4L }, VariantType.REVERSE, "en"))
    }

    @Test
    fun `numeri e nomi dei set vengono normalizzati`() {
        assertEquals("75", CardTraderMatcher.normalizeNumber("075/110"))
        assertEquals("SWSH001", CardTraderMatcher.normalizeNumber("SWSH001"))
        val exp = listOf(CtExpansionDto(1, 5, "lc", "Legendary Collection"), CtExpansionDto(2, 5, "sv2", "SV02: Paldea Evolved"))
        assertEquals(1, CardTraderMatcher.expansionFor(exp, "Legendary Collection", "base6", null)?.id)
        assertEquals(2, CardTraderMatcher.expansionFor(exp, "Paldea Evolved", "sv02", "PAL")?.id)
        assertNull(CardTraderMatcher.expansionFor(exp, "Evolving Skies", "swsh07", null))
    }

    private fun server(): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer secret") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                val body = when {
                    path.startsWith("/api/v2/games") -> """{"array":[{"id":1,"name":"Magic"},{"id":5,"name":"Pokémon"}]}"""
                    path.startsWith("/api/v2/expansions") ->
                        """[{"id":10,"game_id":1,"code":"lc","name":"Legendary Collection"},{"id":20,"game_id":5,"code":"lc","name":"Legendary Collection"}]"""
                    path.startsWith("/api/v2/blueprints/export") && path.contains("expansion_id=20") ->
                        """[{"id":300,"name":"Omastar","expansion_id":20,"fixed_properties":{"collector_number":"39/110"}},
                            {"id":301,"name":"Other","expansion_id":20,"fixed_properties":{"collector_number":"40/110"}}]"""
                    path.startsWith("/api/v2/marketplace/products") && path.contains("blueprint_id=300") && path.contains("language=en") ->
                        """{"300":[
                          {"id":1,"price":{"cents":90,"currency":"EUR"},"quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false}},
                          {"id":2,"price":{"cents":450,"currency":"EUR"},"quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":true}}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        s.start()
        return s
    }

    private fun request(variant: VariantType) = PriceRequest(
        Language.EN, "base6-39", variant, false,
        setId = "base6", setName = "Legendary Collection", setCode = null, localId = "039",
    )

    @Test
    fun `provider trova normal e reverse dello stesso blueprint`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "secret", s.url("/api/v2/").toString()))
            assertEquals(0.90, provider.fetch(request(VariantType.NORMAL))?.eur)
            assertEquals(4.50, provider.fetch(request(VariantType.REVERSE))?.eur)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `token errato diventa BlockedException`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "wrong", s.url("/api/v2/").toString()))
            assertThrows(BlockedException::class.java) { kotlinx.coroutines.runBlocking { provider.fetch(request(VariantType.NORMAL)) } }
        } finally {
            s.shutdown()
        }
    }
}
