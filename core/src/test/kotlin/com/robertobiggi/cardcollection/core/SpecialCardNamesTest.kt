package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalogIdSource
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExpansions
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.PriceRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Regressione: le carte con simboli speciali (Gold Star ☆/★, Delta Species δ, Prism Star ◇) non devono prendere il
 * prodotto Cardmarket della versione normale. Nomi e id presi dal catalogo e da TCGdex reali (probe del 25/10/2026):
 * TCGdex scrive "Rayquaza ☆" (U+2606) nei set EX e "Espeon ★" (U+2605) in POP 5, Cardmarket "Rayquaza Gold Star".
 */
class SpecialCardNamesTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer

    /** Estratti dei set EX Deoxys (1546), EX Holon Phantoms (1551), EX Dragon Frontiers (1553), POP 5 (1617), Ultra Prism (2065). */
    private val body = """{"version":1,"createdAt":"2026-10-25T10:00:00+0200","products":[
        {"idProduct":276425,"name":"Rayquaza [Dragon Aura | Tumbling Attack]","idCategory":51,"idExpansion":1546,"idMetacard":1},
        {"idProduct":276510,"name":"Rayquaza ex [Dragon Burst]","idCategory":51,"idExpansion":1546,"idMetacard":2},
        {"idProduct":276513,"name":"Rayquaza Gold Star [Rage Aura | Sky-high Claws]","idCategory":51,"idExpansion":1546,"idMetacard":3},
        {"idProduct":277013,"name":"Omastar δ Delta Species [Collect | Water Arrow]","idCategory":51,"idExpansion":1551,"idMetacard":4},
        {"idProduct":277068,"name":"Rainbow Energy Delta","idCategory":51,"idExpansion":1551,"idMetacard":5},
        {"idProduct":277072,"name":"Gyarados Gold Star δ Delta Species [Spiral Growth | All-out Blast]","idCategory":51,"idExpansion":1551,"idMetacard":6},
        {"idProduct":277074,"name":"Pikachu Gold Star [Thundershock | Spring Back]","idCategory":51,"idExpansion":1551,"idMetacard":7},
        {"idProduct":277049,"name":"Pikachu δ Delta Species [Tail Whap | Steel Headbutt]","idCategory":51,"idExpansion":1551,"idMetacard":8},
        {"idProduct":277261,"name":"Nidoran [F] δ Delta Species [Tail Whip | Scratch]","idCategory":51,"idExpansion":1553,"idMetacard":9},
        {"idProduct":277262,"name":"Nidoran [M] δ Delta Species [Peck | Poison Horn]","idCategory":51,"idExpansion":1553,"idMetacard":10},
        {"idProduct":277498,"name":"Espeon Gold Star [Purple Ray | Psychic Boom]","idCategory":51,"idExpansion":1617,"idMetacard":11},
        {"idProduct":315124,"name":"Lunala ◇ [Full Moon Star | Psystorm | Prism]","idCategory":51,"idExpansion":2065,"idMetacard":12},
        {"idProduct":315200,"name":"Lunala-GX [Moonsault | Prismatic Burst]","idCategory":51,"idExpansion":2065,"idMetacard":13}]}"""

    private val expansions = CardmarketExpansions.parse(
        listOf(
            "1503\tdx\tEX Deoxys\t1546",
            "1511\thp\tEX Holon Phantoms\t1551",
            "1514\tdf\tEX Dragon Frontiers\t1553",
            "1516\tpop5\tPOP Series 5\t1617",
            "1599\tupr\tUltra Prism\t2065",
        ),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(body))
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private val source by lazy {
        CardmarketCatalogIdSource(CardmarketCatalog(OkHttpClient(), File(dir, "c.json"), server.url("/c.json").toString()), expansions)
    }

    /**
     * Richiesta come la costruisce l'aggiornamento prezzi per una carta TCGdex.
     * @return PriceRequest - richiesta pronta.
     */
    private fun request(setName: String, setId: String, localId: String, name: String, dex: Int?) =
        PriceRequest(Language.EN, "$setId-$localId", VariantType.HOLO, false, setId = setId, setName = setName, localId = localId, name = name, dexId = dex)

    @Test
    fun `simboli e parole di Cardmarket danno la stessa chiave`() {
        assertEquals(CardmarketCatalog.nameKey("Rayquaza ☆"), CardmarketCatalog.nameKey("Rayquaza Gold Star [Rage Aura | Sky-high Claws]"))
        assertEquals(CardmarketCatalog.nameKey("Espeon ★"), CardmarketCatalog.nameKey("Espeon Gold Star [Purple Ray | Psychic Boom]"))
        assertEquals(CardmarketCatalog.nameKey("Omastar δ"), CardmarketCatalog.nameKey("Omastar δ Delta Species [Collect | Water Arrow]"))
        assertEquals(CardmarketCatalog.nameKey("Gyarados ☆ δ"), CardmarketCatalog.nameKey("Gyarados Gold Star δ Delta Species [Spiral Growth]"))
        assertEquals(CardmarketCatalog.nameKey("δ Rainbow Energy"), CardmarketCatalog.nameKey("Rainbow Energy Delta"))
        assertEquals(CardmarketCatalog.nameKey("Nidoran♀ δ"), CardmarketCatalog.nameKey("Nidoran [F] δ Delta Species [Tail Whip | Scratch]"))
        assertEquals(CardmarketCatalog.nameKey("Lunala ◇"), CardmarketCatalog.nameKey("Lunala ◇ [Full Moon Star | Psystorm | Prism]"))
        assertEquals("gyarados~delta~goldstar", CardmarketCatalog.nameKey("Gyarados ☆ δ"))
    }

    @Test
    fun `la versione normale non coincide con quella speciale`() {
        assertFalse(CardmarketCatalog.nameKey("Rayquaza") == CardmarketCatalog.nameKey("Rayquaza Gold Star"))
        assertFalse(CardmarketCatalog.containsKey(CardmarketCatalog.nameKey("Rayquaza Gold Star"), "rayquaza"))
        assertTrue(CardmarketCatalog.containsKey(CardmarketCatalog.nameKey("Brock's Omastar δ Delta Species"), CardmarketCatalog.nameKey("Omastar δ")))
        assertEquals(listOf("delta", "goldstar"), CardmarketCatalog.markers("Gyarados ☆ δ"))
        assertEquals(emptyList<String>(), CardmarketCatalog.markers("Holon Lake [Delta Call]"))
        // Restano invariati i nomi senza simboli (chiavi già salvate e usate altrove).
        assertEquals("omastar", CardmarketCatalog.nameKey("Omastar Lv.53 [Primal Swirl | Send Back]"))
        assertEquals("nidoranf", CardmarketCatalog.nameKey("Nidoran ♀ [Tackle]"))
    }

    @Test
    fun `Rayquaza Gold Star di Deoxys prende il suo prodotto, non quello del Rayquaza normale`() = runTest {
        assertEquals(276513, source.cardmarketProductId(request("Deoxys", "ex8", "107", "Rayquaza ☆", 384)))
        assertEquals(276425, source.cardmarketProductId(request("Deoxys", "ex8", "22", "Rayquaza", 384)))
        assertEquals(276510, source.cardmarketProductId(request("Deoxys", "ex8", "102", "Rayquaza ex", 384)))
    }

    @Test
    fun `Gold Star, Delta Species e Prism Star si distinguono tra loro`() = runTest {
        assertEquals(277074, source.cardmarketProductId(request("Holon Phantoms", "ex13", "104", "Pikachu ☆", 25)))
        assertEquals(277049, source.cardmarketProductId(request("Holon Phantoms", "ex13", "79", "Pikachu δ", 25)))
        assertEquals(277072, source.cardmarketProductId(request("Holon Phantoms", "ex13", "102", "Gyarados ☆ δ", 130)))
        assertEquals(277068, source.cardmarketProductId(request("Holon Phantoms", "ex13", "98", "δ Rainbow Energy", null)))
        assertEquals(277261, source.cardmarketProductId(request("Dragon Frontiers", "ex15", "56", "Nidoran♀ δ", 29)))
        assertEquals(277262, source.cardmarketProductId(request("Dragon Frontiers", "ex15", "57", "Nidoran♂ δ", 32)))
        assertEquals(277498, source.cardmarketProductId(request("POP Series 5", "pop5", "16", "Espeon ★", 196)))
        assertEquals(315124, source.cardmarketProductId(request("Ultra Prism", "sm5", "62", "Lunala ◇", 792)))
    }

    @Test
    fun `i set EX inglesi prendono il set Cardmarket inglese anche con l'elenco completo`() {
        // Con l'elenco incluso nell'app "Holon Phantoms" è anche un set giapponese e "Deoxys" il nome di diversi mazzi.
        val bundled = CardmarketExpansions.bundled
        fun cardmarketIdOf(name: String, id: String) =
            CardTraderMatcher.expansionFor(bundled.expansions, name, id, null)?.let { bundled.cardmarketId(it.id) }
        assertEquals(1546, cardmarketIdOf("Deoxys", "ex8"))
        assertEquals(1551, cardmarketIdOf("Holon Phantoms", "ex13"))
        assertEquals(1553, cardmarketIdOf("Dragon Frontiers", "ex15"))
        assertEquals(1541, cardmarketIdOf("Dragon", "ex3"))
        assertEquals(1539, cardmarketIdOf("Ruby & Sapphire", "ex1"))
    }

    @Test
    fun `una carta normale senza prodotto normale nel set non prende quello speciale`() = runTest {
        // In POP 5 c'è solo "Espeon Gold Star": un Espeon normale non deve abbinarsi a quello.
        assertNull(source.cardmarketProductId(request("POP Series 5", "pop5", "1", "Espeon", 196)))
    }
}
