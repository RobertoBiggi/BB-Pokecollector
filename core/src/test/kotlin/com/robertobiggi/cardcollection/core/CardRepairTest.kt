package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.tcgdex.CardRepair
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
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

class CardRepairTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TcgdexClient

    private fun card(id: String, name: String, illustrator: String?, dex: Int) =
        """{"id":"$id","localId":"1","name":"$name","illustrator":${illustrator?.let { "\"$it\"" }},"dexId":[$dex],"set":{"id":"s","name":"S"}}"""

    private val responses = mapOf(
        "/v2/en/cards?name=Omastar%20Break" to """[{"id":"xy10-19","localId":"19","name":"Omastar BREAK"}]""",
        "/v2/en/cards/xy10-19" to card("xy10-19", "Omastar BREAK", "5ban Graphics", 139),
        "/v2/en/cards?name=Omastar" to """[{"id":"a-1","localId":"1","name":"Omastar"},{"id":"b-2","localId":"2","name":"Omastar"}]""",
        "/v2/en/cards/a-1" to card("a-1", "Omastar", "Artist One", 139),
        "/v2/en/cards/b-2" to card("b-2", "Omastar", "Artist Two", 139),
        "/v2/en/cards?name=Sconosciuta" to "[]",
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
        client = TcgdexClient(OkHttpClient(), server.url("/v2/").toString())
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `una carta senza dettaglio TCGdex e uno scheletro`() {
        assertTrue(CardRepair.isStub(null, null, null, hasTcgdexImage = false))
        assertFalse(CardRepair.isStub("Rare", null, null, hasTcgdexImage = false))
        assertFalse(CardRepair.isStub(null, null, null, hasTcgdexImage = true))
    }

    @Test
    fun `l'illustratore arriva dalla carta inglese con lo stesso nome e Pokedex`() = runTest {
        assertEquals("5ban Graphics", CardRepair.illustratorFromEnglish(client, "Omastar Break", 139))
    }

    @Test
    fun `con illustratori diversi, Pokedex diverso o assente non si indovina`() = runTest {
        assertNull(CardRepair.illustratorFromEnglish(client, "Omastar", 139))
        assertNull(CardRepair.illustratorFromEnglish(client, "Omastar Break", 140))
        assertNull(CardRepair.illustratorFromEnglish(client, "Omastar Break", null))
        assertNull(CardRepair.illustratorFromEnglish(client, "Sconosciuta", 139))
    }
}
