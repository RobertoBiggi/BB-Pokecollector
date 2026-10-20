package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.price.TcgcsvPriceProvider
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvCardRef
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvCatalog
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvClient
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvGroupDto
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvProductDto
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvExtendedDto
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TcgcsvTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: TcgcsvPriceProvider

    /** Risposte ridotte da quelle reali di TCGCSV (probe del 20/10/2026). */
    private val responses = mapOf(
        "/tcgplayer/85/groups" to """{"success":true,"errors":[],"results":[
            {"groupId":24600,"name":"M3: Nihil Zero","abbreviation":"M3"},
            {"groupId":23703,"name":"SM10a: GG End","abbreviation":"SM10a"}]}""",
        "/tcgplayer/85/24600/products" to """{"results":[
            {"productId":674320,"name":"Spinarak","imageUrl":"https://tcgplayer-cdn.tcgplayer.com/product/674320_200w.jpg",
             "extendedData":[{"name":"Rarity","value":"Common"},{"name":"Number","value":"001/080"}]},
            {"productId":674436,"name":"Mega Zygarde ex - 117/080","extendedData":[{"name":"Number","value":"117/080"}]},
            {"productId":999,"name":"Nihil Zero Booster Box","extendedData":[]}]}""",
        "/tcgplayer/85/24600/prices" to """{"results":[
            {"productId":674320,"lowPrice":0.02,"midPrice":0.15,"highPrice":1.0,"marketPrice":0.09,"directLowPrice":null,"subTypeName":"Normal"},
            {"productId":674436,"lowPrice":158.76,"midPrice":177.47,"highPrice":218.0,"marketPrice":169.02,"directLowPrice":null,"subTypeName":"Holofoil"}]}""",
        "/tcgplayer/3/groups" to """{"results":[
            {"groupId":24380,"name":"ME01: Mega Evolution","abbreviation":"MEG"},
            {"groupId":3118,"name":"SWSH11: Lost Origin","abbreviation":"SWSH11"},
            {"groupId":3172,"name":"SWSH11: Lost Origin Trainer Gallery","abbreviation":"SWSH11: TG"}]}""",
        "/tcgplayer/3/24380/products" to """{"results":[
            {"productId":654385,"name":"Magneton","extendedData":[{"name":"Number","value":"046/132"}]},
            {"productId":654386,"name":"Mawile - 047/132","extendedData":[{"name":"Number","value":"047/132"}]},
            {"productId":654387,"name":"Mawile (Poke Ball Pattern)","extendedData":[{"name":"Number","value":"047/132"}]}]}""",
        "/tcgplayer/3/24380/prices" to """{"results":[
            {"productId":654385,"lowPrice":0.05,"midPrice":0.25,"marketPrice":0.23,"subTypeName":"Reverse Holofoil"},
            {"productId":654385,"lowPrice":0.01,"midPrice":0.2,"marketPrice":0.19,"subTypeName":"Normal"},
            {"productId":654386,"lowPrice":0.1,"midPrice":0.3,"marketPrice":0,"subTypeName":"Normal"}]}""",
        "/tcgplayer/3/3172/products" to """{"results":[
            {"productId":5001,"name":"Gardevoir - TG05/TG30","extendedData":[{"name":"Number","value":"TG05/TG30"}]}]}""",
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    responses[request.path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
            start()
        }
        val client = TcgcsvClient(OkHttpClient(), server.url("/tcgplayer/").toString(), RateLimiter(0))
        provider = TcgcsvPriceProvider(TcgcsvCatalog(client))
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    /**
     * Richiesta di prezzo con i dati che il catalogo salva per una carta.
     * @return PriceRequest - richiesta pronta.
     */
    private fun request(language: Language, setCode: String?, setName: String, localId: String, name: String, variant: VariantType) =
        PriceRequest(language, "x", variant, false, setCode = setCode, setName = setName, localId = localId, name = name)

    @Test
    fun `zygarde giapponese trovato per sigla e numero`() = runTest {
        val q = provider.fetch(request(Language.JA, "M3", "ムニキスゼロ", "117", "メガジガルデex", VariantType.HOLO))!!
        assertEquals(169.02, q.usd)
        assertEquals(158.76, q.usdLow)
        assertNull(q.eur)
        assertEquals(Confidence.HIGH, q.confidence)
        // Unica versione in vendita: vale anche per la normal di TCGdex, con affidabilità media.
        val normal = provider.fetch(request(Language.JA, "M3", "x", "117", "x", VariantType.NORMAL))!!
        assertEquals(Confidence.MEDIUM, normal.confidence)
        assertNull(provider.fetch(request(Language.JA, "M3", "x", "117", "x", VariantType.REVERSE)))
        // Set e prodotti scaricati una sola volta.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `carta inglese con varianti separate`() = runTest {
        assertEquals(0.19, provider.fetch(request(Language.EN, "MEG", "Mega Evolution", "046", "Magneton", VariantType.NORMAL))!!.usd)
        assertEquals(0.23, provider.fetch(request(Language.EN, "MEG", "Mega Evolution", "46", "Magneton", VariantType.REVERSE))!!.usd)
        // Prezzo di mercato a zero: si usa il medio.
        assertEquals(0.3, provider.fetch(request(Language.EN, "MEG", "Mega Evolution", "047", "Mawile", VariantType.NORMAL))!!.usd)
        // Nome diverso: nessun abbinamento.
        assertNull(provider.fetch(request(Language.EN, "MEG", "Mega Evolution", "046", "Pikachu", VariantType.NORMAL)))
    }

    @Test
    fun `set inglese con sigla diversa trovato per nome`() = runTest {
        val q = provider.fetch(request(Language.EN, "LOR", "Lost Origin Trainer Gallery", "TG05", "Gardevoir", VariantType.HOLO))
        // Il set esiste ma senza prezzi (404): nessun dato, nessun errore.
        assertNull(q)
        assertTrue(server.requestCount >= 2)
    }

    @Test
    fun `lingue non vendute su TCGplayer non fanno richieste`() = runTest {
        assertNull(provider.fetch(request(Language.IT, "MEG", "Mega Evoluzione", "046", "Magneton", VariantType.NORMAL)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `abbinamento di set, numero e nome`() {
        assertTrue(TcgcsvCatalog.matchesNumber("046/132", "46"))
        assertTrue(TcgcsvCatalog.matchesNumber("TG17/TG30", "tg17"))
        assertFalse(TcgcsvCatalog.matchesNumber("146/132", "46"))
        assertFalse(TcgcsvCatalog.matchesNumber("SWSH001", "1"))
        assertEquals("Mega Zygarde ex", TcgcsvCatalog.baseName("Mega Zygarde ex - 117/080"))
        assertEquals("Exeggcute", TcgcsvCatalog.baseName("Exeggcute (Poke Ball Pattern)"))
        val groups = listOf(
            TcgcsvGroupDto(1, "XY - Phantom Forces", "PHF"),
            TcgcsvGroupDto(2, "SWSH11: Lost Origin", "SWSH11"),
            TcgcsvGroupDto(3, "EX Team Rocket Returns", "RR"),
        )
        fun ids(code: String?, setName: String) = TcgcsvCatalog.matchingGroups(groups, TcgcsvCardRef(Language.EN, code, setName, "1", "x")).map { it.groupId }
        assertEquals(listOf(1L), ids("phf", "Phantom Forces"))
        assertEquals(listOf(2L), ids("LOR", "Lost Origin"))
        assertEquals(listOf(3L), ids(null, "Team Rocket Returns"))
        assertEquals(emptyList<Long>(), ids("XYZ", "Nessun set"))
        val products = listOf(
            TcgcsvProductDto(1, "Pikachu", extendedData = listOf(TcgcsvExtendedDto("Number", "025/131"))),
            TcgcsvProductDto(2, "Pikachu (Master Ball Pattern)", extendedData = listOf(TcgcsvExtendedDto("Number", "025/131"))),
        )
        assertEquals(1L, TcgcsvCatalog.pickProduct(products, TcgcsvCardRef(Language.EN, "PRE", "x", "025", "Pikachu"))?.productId)
        assertEquals(
            "https://tcgplayer-cdn.tcgplayer.com/product/674436_in_1000x1000.jpg",
            TcgcsvCatalog.imageUrl(674436, high = true),
        )
    }
}
