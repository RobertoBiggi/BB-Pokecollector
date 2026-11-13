package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderPicker
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.cardtrader.CtBlueprintDto
import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto
import com.robertobiggi.cardcollection.core.cardtrader.CtProductDto
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.net.Http
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        Http.JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CtProductDto.serializer()), json)

    /** Omastar: a single blueprint, normal and reverse listings mixed. */
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
    fun `normal e holo dello stesso blueprint prendono solo gli annunci della propria rarita`() {
        val listings = products(
            """[
              {"id":1,"price_cents":473,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_rarity":"Holo Rare","pokemon_language":"jp"}},
              {"id":2,"price_cents":120,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_rarity":"Rare","pokemon_language":"jp"}},
              {"id":3,"price_cents":90,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_rarity":"Promo","pokemon_language":"jp"}}
            ]""",
        )
        // Listings of two prints: each takes its own, and those with an ambiguous rarity (Promo) count for both.
        assertEquals(90L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", splitHolo = true)?.cents)
        assertEquals(90L, CardTraderPicker.lowest(listings, VariantType.HOLO, "jp", splitHolo = true)?.cents)
        // Only Holo Rare listings (like Moltres SM9 018): the normal has no price, the holo does.
        val onlyHolo = listings.filter { it.id == 1L }
        assertNull(CardTraderPicker.lowest(onlyHolo, VariantType.NORMAL, "jp", splitHolo = true))
        assertEquals(473L, CardTraderPicker.lowest(onlyHolo, VariantType.HOLO, "jp", splitHolo = true)?.cents)
        // Without splitHolo (the card has a single print) nothing changes.
        assertEquals(473L, CardTraderPicker.lowest(onlyHolo, VariantType.NORMAL, "jp")?.cents)
    }

    @Test
    fun `l'oversize col suo blueprint prende il piu basso tra tutti gli annunci non 1st Edition`() {
        assertEquals(90L, CardTraderPicker.lowest(omastar, VariantType.JUMBO, "en")?.cents)
        assertNull(CardTraderPicker.lowest(omastar, VariantType.SHADOWLESS, "en"))
    }

    @Test
    fun `reverse prende il piu basso tra i soli annunci reverse`() {
        assertEquals(450L, CardTraderPicker.lowest(omastar, VariantType.REVERSE, "en")?.cents)
    }

    @Test
    fun `la condizione peggiore accettata è rispettata senza ripieghi`() {
        val played = omastar.filter { it.id == 5L }
        assertNull(CardTraderPicker.lowest(played, VariantType.NORMAL, "en", CtCondition.NEAR_MINT))
        assertNull(CardTraderPicker.lowest(played, VariantType.NORMAL, "en", CtCondition.MODERATELY_PLAYED))
        assertEquals(20L, CardTraderPicker.lowest(played, VariantType.NORMAL, "en", CtCondition.PLAYED)?.cents)
        assertEquals(20L, CardTraderPicker.lowest(omastar, VariantType.NORMAL, "en", CtCondition.POOR)?.cents)
    }

    @Test
    fun `la condizione della copia diventa quella CardTrader`() {
        assertEquals(CtCondition.NEAR_MINT, CtCondition.fromCondition(Condition.NM))
        assertEquals(CtCondition.POOR, CtCondition.fromCondition(Condition.DMG))
        val reverse = omastar.filter { it.id == 3L || it.id == 4L }
        assertEquals(450L, CardTraderPicker.lowest(reverse, VariantType.REVERSE, "en", CtCondition.fromCondition(Condition.NM))?.cents)
    }

    @Test
    fun `nessun annuncio reverse restituisce null`() {
        assertNull(CardTraderPicker.lowest(omastar.filter { it.id != 3L && it.id != 4L }, VariantType.REVERSE, "en"))
    }










    @Test
    fun `se ci sono solo annunci 1st Edition la carta giapponese normal li usa come ripiego`() {
        val firstEdition = products(
            """[
              {"id":1,"price_cents":714,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","first_edition":true,"pokemon_reverse":false,"pokemon_language":"jp"}},
              {"id":2,"price_cents":900,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","first_edition":true,"pokemon_reverse":true,"pokemon_language":"jp"}}
            ]""",
        )
        assertNull(CardTraderPicker.lowest(firstEdition, VariantType.NORMAL, "jp"))
        assertEquals(714L, CardTraderPicker.lowest(firstEdition, VariantType.NORMAL, "jp", firstEditionFallback = true)?.cents)
        assertEquals(714L, CardTraderPicker.lowest(firstEdition, VariantType.HOLO, "jp", firstEditionFallback = true)?.cents)
        assertNull(CardTraderPicker.lowest(firstEdition, VariantType.W_PROMO, "jp", CtCondition.MINT, firstEditionFallback = true))
        // With normal listings available the fallback does not kick in.
        val mixed = firstEdition + products(
            """[{"id":3,"price_cents":1500,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","first_edition":false,"pokemon_reverse":false,"pokemon_language":"jp"}}]""",
        )
        assertEquals(1500L, CardTraderPicker.lowest(mixed, VariantType.NORMAL, "jp", firstEditionFallback = true)?.cents)
        assertEquals(714L, CardTraderPicker.lowest(mixed, VariantType.FIRST_EDITION, "jp", firstEditionFallback = true)?.cents)
    }

    @Test
    fun `senza annunci alla condizione richiesta scende una condizione alla volta`() {
        val listings = products(
            """[
              {"id":1,"price_cents":1005,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Played","pokemon_language":"jp"}},
              {"id":2,"price_cents":6064,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Slightly Played","pokemon_language":"jp"}},
              {"id":3,"price_cents":51,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Poor","pokemon_language":"jp"}}
            ]""",
        )
        // NM is missing: the first level with listings is Slightly Played, even if further down there is less.
        assertEquals(6064L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.NEAR_MINT, lowerCondition = true)?.cents)
        // A level also accepts better conditions: with Played, Slightly Played and Played count.
        assertEquals(6064L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.MODERATELY_PLAYED, lowerCondition = true)?.cents)
        assertEquals(1005L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.PLAYED, lowerCondition = true)?.cents)
        assertEquals(51L, CardTraderPicker.lowest(listings.filter { it.id == 3L }, VariantType.NORMAL, "jp", CtCondition.NEAR_MINT, lowerCondition = true)?.cents)
        // Without the choice to go down, there is no price.
        assertNull(CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.NEAR_MINT))
        // Not even in Poor: no price.
        assertNull(CardTraderPicker.lowest(emptyList(), VariantType.NORMAL, "jp", CtCondition.NEAR_MINT, lowerCondition = true))
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
                        """[{"id":300,"name":"Omastar","expansion_id":20,"fixed_properties":{"collector_number":"39/110"},"card_market_ids":[555],
                             "image":{"url":"/uploads/blueprints/image/300/omastar.png","show":{"url":"/uploads/blueprints/image/300/show_omastar.png"}}},
                            {"id":301,"name":"Other","expansion_id":20,"fixed_properties":{"collector_number":"40/110"}},
                            {"id":302,"name":"Kabuto 041/110 Holo Promo","expansion_id":20,"card_market_ids":[],
                             "fixed_properties":{"collector_number":"041/110","pokemon_rarity":"Promo"},
                             "image":{"url":"/uploads/blueprints/image/302/kabuto.png"}}]"""
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
        setId = "base6", localId = "039", cardTraderBlueprintId = 300,
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

    @Test
    fun `senza blueprint non c'è prezzo e non si chiede nulla`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "secret", s.url("/api/v2/").toString()))
            assertNull(provider.fetch(request(VariantType.NORMAL).copy(cardTraderBlueprintId = null)))
            assertEquals(0, s.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `l'immagine si prende dal blueprint dell'espansione`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "secret", s.url("/api/v2/").toString()))
            assertEquals("https://www.cardtrader.com/uploads/blueprints/image/300/show_omastar.png", provider.imageUrl(20, 300))
            assertNull(provider.imageUrl(20, 999))
        } finally {
            s.shutdown()
        }
    }
}
