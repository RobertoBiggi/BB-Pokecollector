package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeQuery
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeSearch
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

class CardCodeSearchTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TcgdexClient

    /** Risposte TCGdex simulate per percorso (ricavate dal probe reale del catalogo giapponese). */
    private val responses = mapOf(
        "/v2/ja/sets" to """[{"id":"XY2","name":"ワイルドブレイズ","cardCount":{"total":80,"official":80}},
            {"id":"M3","name":"ムニキスゼロ","cardCount":{"total":117,"official":80}},
            {"id":"SV7","name":"ステラミラクル","cardCount":{"total":136,"official":102}}]""",
        "/v2/ja/sets/M3" to """{"id":"M3","name":"ムニキスゼロ","cardCount":{"official":80,"total":117},
            "cards":[{"id":"M3-116","localId":"116","name":"ユカリ"},{"id":"M3-117","localId":"117","name":"メガジガルデex"}]}""",
        "/v2/ja/cards?localId=117" to """[{"id":"SV7-117","localId":"117","name":"テラパゴスex"},
            {"id":"M3-117","localId":"117","name":"メガジガルデex"},{"id":"SV7-1117","localId":"1117","name":"x"}]""",
        "/v2/en/sets" to """[{"id":"me01","name":"Mega Evolution","cardCount":{"official":132,"total":188}},
            {"id":"me02","name":"Phantasmal Flames","cardCount":{"official":94,"total":130}},
            {"id":"sv01","name":"Scarlet & Violet","cardCount":{"official":198,"total":258}},
            {"id":"ex8","name":"Deoxys","cardCount":{"official":107,"total":108}},
            {"id":"ecard1","name":"Expedition Base Set","cardCount":{"official":165,"total":165}},
            {"id":"swsh10","name":"Astral Radiance","cardCount":{"official":189,"total":216}}]""",
        "/v2/en/sets/swsh10" to """{"id":"swsh10","name":"Astral Radiance","cardCount":{"official":189,"total":216},
            "cards":[{"id":"swsh10-107","localId":"107","name":"Origin Forme Dialga"}]}""",
        "/v2/en/sets/ex8" to """{"id":"ex8","name":"Deoxys","cardCount":{"official":107,"total":108},
            "cards":[{"id":"ex8-102","localId":"102","name":"Rayquaza ex"},{"id":"ex8-107","localId":"107","name":"Rayquaza ☆"}]}""",
        "/v2/en/cards?localId=102" to """[{"id":"ex8-102","localId":"102","name":"Rayquaza ex"},
            {"id":"ecard1-102","localId":"102","name":"Bellsprout"}]""",
        "/v2/en/cards?localId=46" to """[{"id":"me01-046","localId":"046","name":"Mawile"},
            {"id":"sv01-046","localId":"46","name":"Pikachu"},{"id":"me02-146","localId":"146","name":"x"}]""",
        "/v2/en/sets/me01" to """{"id":"me01","name":"Mega Evolution","abbreviation":{"official":"MEG"}}""",
        "/v2/en/sets/sv01" to """{"id":"sv01","name":"Scarlet & Violet","abbreviation":{"official":"SVI"}}""",
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
    fun `parse riconosce le forme del codice`() {
        assertEquals(CardCodeQuery(117, 80, listOf("mur")), CardCodeSearch.parse("117/080 mur"))
        assertEquals(CardCodeQuery(117, 80, listOf("M3")), CardCodeSearch.parse(" M3  117/080 "))
        assertEquals(CardCodeQuery(30, null, listOf("SM10a")), CardCodeSearch.parse("SM10a-030"))
        assertEquals(CardCodeQuery(46, null, listOf("MEG")), CardCodeSearch.parse("MEG 046"))
    }

    @Test
    fun `parse lascia alla ricerca per nome i testi senza numero`() {
        assertNull(CardCodeSearch.parse("Mawile"))
        assertNull(CardCodeSearch.parse("Porygon2"))
        assertNull(CardCodeSearch.parse("117/080; DROP"))
        assertNull(CardCodeSearch.parse("../../117"))
    }

    @Test
    fun `numero confrontato senza zeri iniziali`() {
        assertTrue(CardCodeSearch.matchesNumber("046", 46))
        assertTrue(CardCodeSearch.matchesNumber("46", 46))
        assertFalse(CardCodeSearch.matchesNumber("146", 46))
        assertFalse(CardCodeSearch.matchesNumber("XY46", 46))
    }

    @Test
    fun `numero e totale trovano lo Zygarde ignorando la rarita`() = runTest {
        val hits = CardCodeSearch.find(client, Language.JA, CardCodeSearch.parse("117/080 MUR")!!)
        assertEquals(listOf("M3-117"), hits.map { it.id })
    }

    @Test
    fun `sigla giapponese cerca solo nel set indicato`() = runTest {
        val hits = CardCodeSearch.find(client, Language.JA, CardCodeSearch.parse("m3 117")!!)
        assertEquals(listOf("M3-117"), hits.map { it.id })
        assertTrue(server.requestCount == 2)
    }

    @Test
    fun `sigla inglese confrontata con l'abbreviazione del set`() = runTest {
        val hits = CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("MEG 046")!!)
        assertEquals(listOf("me01-046"), hits.map { it.id })
    }

    @Test
    fun `il set si indica sia con la sigla Cardmarket sia con l'id TCGdex`() = runTest {
        // EX Deoxys: "DX" su Cardmarket, "ex8" su TCGdex.
        assertEquals(listOf("ex8-107"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("dx 107")!!).map { it.id })
        assertEquals(listOf("ex8-107"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("DX 107/107")!!).map { it.id })
        assertEquals(listOf("ex8-107"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("ex8 107")!!).map { it.id })
        assertEquals(listOf("ex8-107"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("dx-107")!!).map { it.id })
    }

    @Test
    fun `il set si indica anche con la sigla stampata quando Cardmarket ne usa un'altra`() = runTest {
        // Astral Radiance: "ASR" sulla carta, "astr" su CardTrader, "swsh10" su TCGdex.
        assertEquals(listOf("swsh10-107"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("ASR 107/189")!!).map { it.id })
        assertEquals(listOf("swsh10-107"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("astr 107")!!).map { it.id })
    }

    @Test
    fun `ex nel nome della carta non e la sigla di Expedition`() = runTest {
        // "ex" su Cardmarket è Expedition Base Set: qui è parte del nome e la carta si trova per numero e nome.
        assertEquals(listOf("ex8-102"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("Rayquaza ex 102")!!).map { it.id })
        assertEquals(listOf("ex8-102"), CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("dx Rayquaza 102")!!).map { it.id })
    }

    @Test
    fun `solo numero restituisce tutte le carte con quel numero`() = runTest {
        val hits = CardCodeSearch.find(client, Language.EN, CardCodeSearch.parse("46")!!)
        assertEquals(listOf("me01-046", "sv01-046"), hits.map { it.id })
    }
}
