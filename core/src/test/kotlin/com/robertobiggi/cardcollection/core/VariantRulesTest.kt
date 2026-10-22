package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalogIdSource
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExpansions
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderPicker
import com.robertobiggi.cardcollection.core.cardtrader.CtProductDto
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.CardmarketGuidePriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.VariantMapper
import com.robertobiggi.cardcollection.core.tcgdex.CardDto
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.PricingDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient.Companion.JSON
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Regressioni emerse cercando "Omastar": varianti dedotte dai prezzi, Poké Ball/Master Ball, prezzi delle carte Cardmarket. */
class VariantRulesTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun card(name: String, flags: String, trend: Double?, trendHolo: Double?, id: String = "x-1", set: String = "x"): CardDto =
        JSON.decodeFromString(
            CardDto.serializer(),
            """{"id":"$id","localId":"1","name":"$name","category":"Pokemon","set":{"id":"$set","name":"S"},"variants":$flags,
            "pricing":{"cardmarket":{"unit":"EUR","trend":${trend ?: "null"},"trend-holo":${trendHolo ?: "null"}}}}""",
        )

    private val normalOnly = """{"normal":true,"reverse":false,"holo":false}"""
    private val holoOnly = """{"normal":false,"reverse":false,"holo":true}"""

    private fun types(card: CardDto, date: String?, balls: Set<VariantType> = emptySet()) = CatalogMapper.variantsOf(card, date, balls).map { it.type }

    @Test
    fun `la reverse holo si deduce dal trend base e dal trend holo di Cardmarket`() {
        // Omastar Team Up: TCGdex non ha il flag reverse, Cardmarket quota la reverse holo.
        assertEquals(listOf(VariantType.NORMAL, VariantType.REVERSE), types(card("Omastar", normalOnly, 1.78, 3.37), "2019-01-31"))
        // Omastar Tag Bolt (holo): ha il prezzo base e quello holo, quindi anche la reverse.
        assertEquals(listOf(VariantType.HOLO, VariantType.REVERSE), types(card("オムスター", holoOnly, 0.43, 0.55), "2018-12-07"))
        // Senza trend holo, o senza trend base, nessuna reverse.
        assertEquals(listOf(VariantType.NORMAL), types(card("Omastar", normalOnly, 1.78, null), "2019-01-31"))
        assertEquals(listOf(VariantType.HOLO), types(card("Omastar", holoOnly, null, 3.0), "2019-01-31"))
    }

    @Test
    fun `prima delle reverse holo e per le BREAK i prezzi holo non dicono niente`() {
        // Fossil 1999 e Neo Discovery 2001: i prezzi "-holo" sono altro (1ª edizione), la reverse non esiste.
        assertEquals(listOf(VariantType.NORMAL), types(card("Omastar", normalOnly, 2.08, 1.94), "1999-10-10"))
        assertEquals(listOf(VariantType.NORMAL), types(card("Omastar", normalOnly, 0.44, 3.9), "2001-06-01"))
        // Omastar BREAK (Fates Collide) non ha la reverse anche se Cardmarket ha il prezzo.
        assertEquals(listOf(VariantType.NORMAL), types(card("Omastar BREAK", normalOnly, 2.71, 3.67), "2016-05-02"))
        // Senza la data del set non si deduce nulla.
        assertEquals(listOf(VariantType.NORMAL), types(card("Omastar", normalOnly, 1.78, 3.37), null))
    }

    @Test
    fun `con Poké Ball e Master Ball la reverse generica non esiste`() {
        val balls = setOf(VariantType.POKE_BALL, VariantType.MASTER_BALL)
        val omastar = card("オムスター", holoOnly, 0.43, 0.55, id = "SV2a-139", set = "SV2a")
        assertEquals(listOf(VariantType.HOLO, VariantType.POKE_BALL, VariantType.MASTER_BALL), types(omastar, "2023-06-16", balls))
    }

    @Test
    fun `Poké Ball e Master Ball non si prezzano dalla scheda della carta base`() {
        val pricing = PricingDto(cardmarket = JSON.decodeFromString(com.robertobiggi.cardcollection.core.tcgdex.CardmarketPricingDto.serializer(), """{"unit":"EUR","trend":1.0,"trend-holo":2.0}"""))
        assertNull(VariantMapper.quote(VariantType.POKE_BALL, pricing, false, "TCGdex"))
        assertNull(VariantMapper.quote(VariantType.MASTER_BALL, pricing, false, "TCGdex"))
        assertEquals(2.0, VariantMapper.quote(VariantType.REVERSE, pricing, false, "TCGdex")!!.eur)
    }

    /** Listino: la Poké Ball ha il prezzo nei campi "-holo" (trend base 0), la carta senza numero ha solo il trend base. */
    private val guideBody = """{"version":1,"createdAt":"2026-10-21T02:48:17+0200","priceGuides":[
        {"idProduct":837502,"idCategory":51,"avg":null,"low":0.1,"trend":0,"avg1":null,"avg7":null,"avg30":null,"avg-holo":0.63,"low-holo":0.1,"trend-holo":0.5,"avg1-holo":0.5,"avg7-holo":0.57,"avg30-holo":0.68},
        {"idProduct":548776,"idCategory":51,"avg":74.95,"low":29.99,"trend":47.79,"avg1":null,"avg7":null,"avg30":null,"avg-holo":null,"low-holo":null,"trend-holo":null,"avg1-holo":null,"avg7-holo":null,"avg30-holo":null}]}"""

    private fun provider(): CardmarketGuidePriceProvider {
        server.enqueue(MockResponse().setBody(guideBody))
        val guide = CardmarketPriceGuide(OkHttpClient(), File(dir, "g.json"), server.url("/g.json").toString())
        return CardmarketGuidePriceProvider({ null }, guide)
    }

    @Test
    fun `la Poké Ball ha il suo prodotto Cardmarket e il prezzo dai campi holo`() = runTest {
        val request = PriceRequest(Language.JA, "SV2a-139", VariantType.POKE_BALL, false, setId = "SV2a", localId = "139")
        val quote = provider().fetch(request)!!
        assertEquals(0.5, quote.eur)
        assertEquals(0.1, quote.eurLow)
    }

    @Test
    fun `una carta del catalogo Cardmarket si prezza dal suo prodotto, anche senza numero`() = runTest {
        // Omanyte di Expansion Sheet: id "exsh-Cardmarket548776", nessun numero, nessun abbinamento per set e numero.
        val request = PriceRequest(Language.JA, "exsh-Cardmarket548776", VariantType.NORMAL, false, setId = "exsh", localId = "Cardmarket548776", cardmarketId = 548776)
        assertEquals(47.79, provider().fetch(request)!!.eur)
    }

    @Test
    fun `i nomi Cardmarket piu lunghi si abbinano se nel set c'è una sola carta`() = runTest {
        val body = """{"version":1,"createdAt":"2026-10-20T14:24:46+0200","products":[
            {"idProduct":300,"name":"Brock's Omastar [Stretch Tentacle | Poison Tentacle]","idCategory":51,"idExpansion":30},
            {"idProduct":301,"name":"Pikachu","idCategory":51,"idExpansion":30},
            {"idProduct":310,"name":"Omastar δ Delta Species [Bind | Vengeful Spikes]","idCategory":51,"idExpansion":31},
            {"idProduct":320,"name":"Omastar [A]","idCategory":51,"idExpansion":32},
            {"idProduct":321,"name":"Omastar Lv.10 [B]","idCategory":51,"idExpansion":32}]}"""
        server.enqueue(MockResponse().setBody(body))
        val source = CardmarketCatalogIdSource(
            CardmarketCatalog(OkHttpClient(), File(dir, "c.json"), server.url("/c.json").toString()),
            CardmarketExpansions.parse(listOf("1\tvs1\tVS\t30", "2\thof\tHolon Phantoms\t31", "3\tdup\tDoppio\t32")),
        )
        fun req(set: String, local: String, name: String) =
            PriceRequest(Language.JA, "$set-$local", VariantType.NORMAL, false, setId = set, localId = local, name = name, dexId = 139)
        // Brock's Omastar (VS1-068) e Omastar δ: il nome Cardmarket aggiunge parole, ma nel set c'è una sola carta Omastar.
        assertEquals(300, source.cardmarketProductId(req("vs1", "068", "ブロックのomastar")))
        assertEquals(310, source.cardmarketProductId(req("hof", "027", "OMASTAR（デルタ種）")))
        // Due Omastar con lo stesso nome base: senza posizione sicura, nessun abbinamento.
        assertNull(source.cardmarketProductId(req("dup", "099", "Omastar")))
    }

    private fun listing(language: String, cents: Long): CtProductDto = JSON.decodeFromString(
        CtProductDto.serializer(),
        """{"id":$cents,"price_cents":$cents,"price_currency":"EUR","quantity":1,
        "properties_hash":{"condition":"Near Mint","pokemon_language":"$language","pokemon_reverse":false}}""",
    )

    @Test
    fun `gli annunci in un'altra lingua si ignorano`() {
        val listings = listOf(listing("kr", 10), listing("jp", 50), listing("zh-TW", 20))
        assertEquals(50, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp")?.cents)
        assertEquals(20, CardTraderPicker.lowest(listings, VariantType.NORMAL, "zh-tw")?.cents)
        assertNull(CardTraderPicker.lowest(listings, VariantType.NORMAL, "en"))
    }

    @Test
    fun `i prodotti Poké Ball sono tutti annunci della variante, non della carta base`() {
        val listings = listOf(listing("jp", 40))
        assertEquals(40, CardTraderPicker.lowest(listings, VariantType.POKE_BALL, "jp")?.cents)
    }

    @Test
    fun `un prodotto solo foil ha il prezzo nei campi holo e un trend a zero non nasconde la media`() {
        fun pricing(json: String) = PricingDto(cardmarket = JSON.decodeFromString(com.robertobiggi.cardcollection.core.tcgdex.CardmarketPricingDto.serializer(), json))
        // Moltres del Special Battle Set: trend base 0, solo "-holo" pieni.
        val foilOnly = pricing("""{"unit":"EUR","trend":0,"low":22.99,"trend-holo":30.15,"low-holo":22.99,"avg30-holo":33.84}""")
        assertEquals(30.15, VariantMapper.quote(VariantType.NORMAL, foilOnly, false, "Cardmarket")!!.eur)
        assertEquals(22.99, VariantMapper.quote(VariantType.NORMAL, foilOnly, false, "Cardmarket")!!.eurLow)
        // Trend a 0 ma media valida: vale la media.
        assertEquals(4.5, VariantMapper.quote(VariantType.NORMAL, pricing("""{"unit":"EUR","trend":0,"avg":4.5}"""), false, "Cardmarket")!!.eur)
        // Con il prezzo base presente, quello holo resta della reverse.
        val both = pricing("""{"unit":"EUR","trend":1.0,"trend-holo":2.0}""")
        assertEquals(1.0, VariantMapper.quote(VariantType.NORMAL, both, false, "Cardmarket")!!.eur)
        assertNull(VariantMapper.quote(VariantType.NORMAL, pricing("""{"unit":"EUR","trend":0}"""), false, "Cardmarket"))
    }
}
