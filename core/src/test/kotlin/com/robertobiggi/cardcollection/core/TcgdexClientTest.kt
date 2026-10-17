package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.TcgdexPriceProvider
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TcgdexClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TcgdexClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = TcgdexClient(OkHttpClient(), server.url("/v2/").toString())
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `ricerca codifica il nome giapponese e usa la lingua giusta`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"SM10a-030","localId":"030","name":"クチートGX"}]"""))
        val list = client.searchCards(Language.JA, "クチート")
        assertEquals("SM10a-030", list.single().id)
        val path = server.takeRequest().requestUrl!!
        assertEquals(listOf("v2", "ja", "cards"), path.pathSegments)
        assertEquals("クチート", path.queryParameter("name"))
    }

    @Test
    fun `dettaglio carta reale viene decodificato`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("en_card_me03-032.json")))
        val card = client.getCard(Language.EN, "me03-032")
        assertEquals("Perfect Order", card.set.name)
        assertEquals(2, card.variantsDetailed!!.size)
    }

    @Test
    fun `429 e 403 diventano eccezioni tipizzate`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
        val e = assertThrows(RateLimitedException::class.java) { kotlinx.coroutines.runBlocking { client.getCard(Language.EN, "x-1") } }
        assertEquals(3000L, e.retryAfterMs)
        server.enqueue(MockResponse().setResponseCode(403))
        assertThrows(BlockedException::class.java) { kotlinx.coroutines.runBlocking { client.getCard(Language.EN, "x-1") } }
    }

    @Test
    fun `id malevoli vengono rifiutati prima della chiamata`() {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { client.getCard(Language.EN, "../sets") } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `JSON non valido`() {
        server.enqueue(MockResponse().setBody("{non json"))
        assertThrows(InvalidResponseException::class.java) { kotlinx.coroutines.runBlocking { client.getCard(Language.EN, "x-1") } }
    }

    @Test
    fun `provider scarica la carta una volta sola per piu varianti`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("en_card_ex2-9.json")))
        val provider = TcgdexPriceProvider(client)
        val holo = provider.fetch(PriceRequest(Language.EN, "ex2-9", VariantType.HOLO, false))
        val reverse = provider.fetch(PriceRequest(Language.EN, "ex2-9", VariantType.REVERSE, false))
        assertEquals(16.37, holo?.eur)
        assertEquals(41.31, reverse?.eur)
        assertEquals(1, server.requestCount)
    }
}
