package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCardSearch
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExpansions
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.model.VariantType
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CardmarketCardSearchTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer

    /**
     * TCGdex giapponese: XY10 è elencato con 3 carte ufficiali ma senza carte; SM1 ha già la carta 2 (Omastar);
     * SMX è elencato ma ha meno carte ufficiali dei prodotti Cardmarket mancanti; ZZZ non si abbina a nessun set Cardmarket.
     */
    private val responses = mapOf(
        "/v2/ja/sets" to """[
            {"id":"XY10","name":"めざめる超王","cardCount":{"total":3,"official":3}},
            {"id":"SM1","name":"サン&ムーン","cardCount":{"total":3,"official":3}},
            {"id":"SMX","name":"別のセット","cardCount":{"total":9,"official":9}},
            {"id":"ZZZ","name":"知らないセット","cardCount":{"total":3,"official":3}}]""",
        "/v2/ja/sets/XY10" to """{"id":"XY10","name":"めざめる超王","releaseDate":"2016-03-18","serie":{"id":"XYb","name":"XY BREAK"},
            "cardCount":{"total":3,"official":3},"cards":[]}""",
        "/v2/ja/sets/SM1" to """{"id":"SM1","name":"サン&ムーン","cardCount":{"total":3,"official":3},
            "cards":[{"id":"SM1-002","localId":"002","name":"オムスター"}]}""",
        "/v2/catalog" to """{"version":1,"createdAt":"2026-10-20T14:24:46+0200","products":[
            {"idProduct":10,"name":"Omanyte [Water Gun]","idCategory":51,"idExpansion":100},
            {"idProduct":11,"name":"Omastar [Restoring Beam]","idCategory":51,"idExpansion":100},
            {"idProduct":12,"name":"Omastar BREAK [Dangerous Tentacle]","idCategory":51,"idExpansion":100},
            {"idProduct":20,"name":"Omanyte [Hydro Pump]","idCategory":51,"idExpansion":200},
            {"idProduct":21,"name":"Omastar [Pull Down]","idCategory":51,"idExpansion":200},
            {"idProduct":22,"name":"Pikachu [Thunder Shock]","idCategory":51,"idExpansion":200},
            {"idProduct":30,"name":"Omastar [Solo]","idCategory":51,"idExpansion":300}]}""",
    )

    private val expansions = CardmarketExpansions.parse(
        listOf("1\txy10\tAwakening Psychic King\t100", "2\tsm1\tSun & Moon\t200", "3\tsmx\tAltro\t300"),
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
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun search() = CardmarketCardSearch(
        CardmarketCatalog(OkHttpClient(), File(dir, "c.json"), server.url("/v2/catalog").toString()),
        TcgdexClient(OkHttpClient(), server.url("/v2/").toString()),
        expansions,
    )

    @Test
    fun `trova le carte dei set che TCGdex elenca senza carte, con la forma delle carte TCGdex`() = runTest {
        val found = search().find(Language.JA, "Omastar")
        // XY10: Omastar alla posizione 2 e BREAK alla 3. SM1: la posizione 2 c'è già su TCGdex. SMX: meno prodotti che carte ufficiali.
        assertEquals(listOf("XY10-002", "XY10-003"), found.map { it.tcgdexId })
        val brk = found.last()
        assertEquals("ja:XY10-003", brk.id)
        assertEquals("Omastar BREAK", brk.name)
        assertEquals("めざめる超王", brk.setName)
        assertEquals("XY10", brk.setCode)
        assertEquals("003/003", brk.printedNumber)
        assertEquals("2016-03-18", brk.releaseDate)
        assertEquals("XY BREAK", brk.serieName)
        assertEquals(139, brk.dexId)
        assertNull(brk.imageBaseUrl)
        assertEquals(listOf(VariantType.NORMAL), brk.variants.map { it.type })
    }

    @Test
    fun `un testo troppo corto o una lingua non supportata non cercano niente`() = runTest {
        assertEquals(emptyList<String>(), search().find(Language.JA, "Om").map { it.id })
        assertEquals(emptyList<String>(), search().find(Language.FR, "Omastar").map { it.id })
    }

    @Test
    fun `una carta si ricostruisce dall'id solo se TCGdex non ce l'ha`() = runTest {
        val s = search()
        assertEquals("Omanyte", s.cardFor(Language.JA, "XY10", "001")?.name)
        assertEquals("Omastar BREAK", s.cardFor(Language.JA, "XY10", "3")?.name)
        assertNull(s.cardFor(Language.JA, "SM1", "002"))
        assertNull(s.cardFor(Language.JA, "XY10", "009"))
        assertNull(s.cardFor(Language.JA, "ZZZ", "001"))
        assertNull(s.cardFor(Language.JA, "XY10", "ABC"))
    }

    @Test
    fun `il codice stampato trova la carta nel set indicato`() = runTest {
        val query = CardCodeSearch.parse("XY10 002")!!
        assertEquals(listOf("XY10-002"), search().findByCode(Language.JA, query).map { it.tcgdexId })
    }

    @Test
    fun `il Pokedex si ricava dal nome della carta`() {
        assertEquals(139, PokemonNames.dexIdOfName("Omastar BREAK"))
        assertEquals(139, PokemonNames.dexIdOfName("Dark Omastar Lv.33"))
        assertEquals(139, PokemonNames.dexIdOfName("Brock's Omastar"))
        assertNull(PokemonNames.dexIdOfName("Mysterious Fossil"))
        assertNull(PokemonNames.dexIdOfName("Omanyte & Omastar"))
    }
}
