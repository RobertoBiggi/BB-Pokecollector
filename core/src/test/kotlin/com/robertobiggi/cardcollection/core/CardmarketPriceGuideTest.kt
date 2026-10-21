package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.CardmarketGuidePriceProvider
import com.robertobiggi.cardcollection.core.price.CardmarketIdSource
import com.robertobiggi.cardcollection.core.price.PriceRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class CardmarketPriceGuideTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer

    /** Due prodotti nel formato reale del listino: il primo senza dati holo, il secondo con reverse holo. */
    private val body = """{"version":1,"createdAt":"2026-10-21T02:48:17+0200","priceGuides":[
        {"idProduct":765692,"idCategory":51,"avg":33.33,"low":25,"trend":20.68,"avg1":33.33,"avg7":15.73,"avg30":15.73,"avg-holo":null,"low-holo":null,"trend-holo":0,"avg1-holo":null,"avg7-holo":null,"avg30-holo":null},
        {"idProduct":271439,"idCategory":52,"avg":1.49,"low":0.15,"trend":2.79,"avg1":null,"avg7":null,"avg30":null,"avg-holo":3.1,"low-holo":2.5,"trend-holo":3.93,"avg1-holo":null,"avg7-holo":null,"avg30-holo":3.6}]}"""

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun guide(file: File = File(dir, "guide.json"), now: () -> Long = System::currentTimeMillis) =
        CardmarketPriceGuide(OkHttpClient(), file, server.url("/guide.json").toString(), now = now)

    @Test
    fun `legge i prezzi di un prodotto dal listino`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        val g = guide()
        val p = g.lookup(765692)!!
        assertEquals(20.68, p.trend)
        assertEquals(25.0, p.low)
        assertEquals(33.33, p.avg)
        assertEquals(15.73, p.avg30)
        assertNull(p.avgHolo)
        assertEquals("2026-10-21T02:48:17+0200", p.updated)
        assertEquals("EUR", p.unit)
        assertEquals(3.93, g.lookup(271439)!!.trendHolo)
        assertNull(g.lookup(1))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `un listino recente su disco non si riscarica`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        guide().lookup(765692)
        assertEquals(20.68, guide().lookup(765692)!!.trend)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `un listino vecchio si riscarica, e se la rete manca resta buono quello vecchio`() = runTest {
        val file = File(dir, "guide.json")
        server.enqueue(MockResponse().setBody(body))
        guide(file).lookup(765692)
        val later = { file.lastModified() + 25L * 3_600_000 }
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(20.68, guide(file, later).lookup(765692)!!.trend)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `senza listino su disco e senza rete e un errore`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { guide().lookup(765692) } }
        assertEquals(false, File(dir, "guide.json").exists())
        assertEquals(false, File(dir, "guide.json.tmp").exists())
    }

    @Test
    fun `il prezzo di una variante viene dal prodotto trovato tramite CardTrader`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        val request = PriceRequest(Language.JA, "WCP-017", VariantType.NORMAL, false, setId = "WCP", localId = "017")
        val provider = CardmarketGuidePriceProvider(CardmarketIdSource { 765692 }, guide())
        val q = provider.fetch(request)!!
        assertEquals("Cardmarket", q.source)
        assertEquals(20.68, q.eur)
        assertEquals(25.0, q.eurLow)
        assertEquals(15.73, q.eurAvg30)
        // La reverse holo usa i campi "-holo": qui trend-holo è 0, cioè nessun dato.
        assertNull(provider.fetch(request.copy(variant = VariantType.REVERSE)))
        // Senza abbinamento a CardTrader, o fuori dal listino, nessun prezzo.
        assertNull(CardmarketGuidePriceProvider({ null }, guide()).fetch(request))
        assertNull(CardmarketGuidePriceProvider({ 42 }, guide()).fetch(request))
    }

    @Test
    fun `l'avviso di scaricamento parte solo quando si scarica davvero`() = runTest {
        server.enqueue(MockResponse().setBody(body))
        var downloads = 0
        var reads = 0
        val file = File(dir, "guide.json")
        fun counting() = CardmarketPriceGuide(OkHttpClient(), file, server.url("/guide.json").toString(), onDownload = { downloads++ }, onRead = { reads++ })
        counting().lookup(765692)
        assertEquals(1, downloads)
        assertEquals(1, reads)
        // File recente in cache: nessun avviso di scaricamento, ma la lettura (che richiede tempo) sì.
        counting().lookup(765692)
        assertEquals(1, downloads)
        assertEquals(2, reads)
    }

    @Test
    fun `se lo scaricamento fallisce non si riprova per ogni carta`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        var downloads = 0
        val g = CardmarketPriceGuide(OkHttpClient(), File(dir, "guide.json"), server.url("/guide.json").toString(), onDownload = { downloads++ })
        repeat(3) { assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { g.lookup(765692) } } }
        assertEquals(1, downloads)
        assertEquals(1, server.requestCount)
    }
}
