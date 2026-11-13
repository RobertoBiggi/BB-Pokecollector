package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.price.TcgplayerPriceProvider
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvClient
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

class TcgplayerPriceProviderTest {
    private lateinit var server: MockWebServer

    @BeforeEach fun start() { server = MockWebServer().apply { start() } }

    @AfterEach fun stop() { server.shutdown() }

    private fun provider() = TcgplayerPriceProvider(TcgcsvClient(OkHttpClient(), server.url("/tcgplayer/").toString(), RateLimiter(0)))

    private fun request(ref: String?, variant: VariantType = VariantType.HOLO, language: Language = Language.EN) =
        PriceRequest(language, "base1-1", variant, cardHasNormalAndHolo = false, tcgplayerRef = ref)

    /** Rows shaped like the real TCGCSV answer for Base Set (group 604): Alakazam is holo only, a common has a normal and a reverse row. */
    private val prices = """{"success":true,"errors":[],"results":[
        {"productId":42346,"lowPrice":45.0,"midPrice":55.79,"highPrice":1499.99,"marketPrice":76.15,"directLowPrice":104.39,"subTypeName":"Holofoil"},
        {"productId":42348,"lowPrice":0.14,"midPrice":0.4,"highPrice":9.99,"marketPrice":0.51,"directLowPrice":null,"subTypeName":"Normal"},
        {"productId":42348,"lowPrice":0.3,"midPrice":0.9,"highPrice":9.99,"marketPrice":null,"directLowPrice":null,"subTypeName":"Reverse Holofoil"},
        {"productId":42349,"lowPrice":0.0,"midPrice":0.0,"highPrice":0.0,"marketPrice":0.0,"directLowPrice":null,"subTypeName":"Normal"}
    ]}"""

    @Test
    fun `the holofoil row of the product is the price, with its low`() = runTest {
        server.enqueue(MockResponse().setBody(prices))
        val quote = provider().fetch(request("604/42346"))
        assertEquals(76.15, quote!!.market)
        assertEquals(45.0, quote.low)
        assertEquals("/tcgplayer/3/604/prices", server.takeRequest().path)
    }

    @Test
    fun `japanese cards use the japanese category`() = runTest {
        server.enqueue(MockResponse().setBody(prices))
        provider().fetch(request("604/42346", language = Language.JA))
        assertEquals("/tcgplayer/85/604/prices", server.takeRequest().path)
    }

    @Test
    fun `each variant reads its own row, and the mid price stands in for a missing market price`() = runTest {
        server.enqueue(MockResponse().setBody(prices))
        val p = provider()
        assertEquals(0.51, p.fetch(request("604/42348", VariantType.NORMAL))!!.market)
        assertEquals(0.9, p.fetch(request("604/42348", VariantType.REVERSE))!!.market)
        assertNull(p.fetch(request("604/42348", VariantType.HOLO)))
        assertEquals(1, server.requestCount) // one file per set, kept for the other cards
    }

    @Test
    fun `no price when the value is zero, the product is missing or the variant is a separate print`() = runTest {
        server.enqueue(MockResponse().setBody(prices))
        val p = provider()
        assertNull(p.fetch(request("604/42349", VariantType.NORMAL)))
        assertNull(p.fetch(request("604/99999", VariantType.NORMAL)))
        assertNull(p.fetch(request("604/42346", VariantType.SHADOWLESS)))
    }

    @Test
    fun `cards without a product or in other languages cost no request`() = runTest {
        val p = provider()
        assertNull(p.fetch(request(null)))
        assertNull(p.fetch(request("not-a-ref")))
        assertNull(p.fetch(request("604/42346", language = Language.DE)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an HTTP error is thrown to the caller and not cached`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(prices))
        val p = provider()
        assertThrows(ApiException::class.java) { kotlinx.coroutines.runBlocking { p.fetch(request("604/42346")) } }
        assertEquals(76.15, p.fetch(request("604/42346"))!!.market)
    }

    @Test
    fun `references are parsed strictly`() {
        assertEquals(604L to 42346L, TcgplayerPriceProvider.parseRef("604/42346"))
        assertNull(TcgplayerPriceProvider.parseRef("604"))
        assertNull(TcgplayerPriceProvider.parseRef("604/x"))
        assertNull(TcgplayerPriceProvider.parseRef("0/5"))
        assertNull(TcgplayerPriceProvider.parseRef("1/2/3"))
    }
}
