package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalogIdSource
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExpansions
import com.robertobiggi.cardcollection.core.cardmarket.CatalogProduct
import com.robertobiggi.cardcollection.core.cardmarket.productByOrdinal
import com.robertobiggi.cardcollection.core.cardmarket.productByPosition
import com.robertobiggi.cardcollection.core.tcgdex.SetOrdinal
import com.robertobiggi.cardcollection.core.cardmarket.orElse
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.CardmarketIdSource
import com.robertobiggi.cardcollection.core.price.PriceRequest
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

class CardmarketCatalogTest {

    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer

    /** Set 10 (Awakening): due Omastar, uno comune alla posizione 2 e uno holo alla 4. Set 20: un solo Omastar. */
    private val body = """{"version":1,"createdAt":"2026-10-20T14:24:46+0200","products":[
        {"idProduct":100,"name":"Omanyte [Hydro Pump]","idCategory":51,"idExpansion":10,"idMetacard":1},
        {"idProduct":101,"name":"Omastar [Pull Down | Hydrocannon]","idCategory":51,"idExpansion":10,"idMetacard":2},
        {"idProduct":102,"name":"Pikachu [Thunder, Jolt | \"Zap\" {x}]","idCategory":51,"idExpansion":10,"idMetacard":3},
        {"idProduct":103,"name":"Omastar [Pull Down | Hydrocannon]","idCategory":51,"idExpansion":10,"idMetacard":2},
        {"idProduct":200,"name":"Omastar BREAK [Dangerous Tentacle]","idCategory":51,"idExpansion":20,"idMetacard":4}]}"""

    private val expansions = CardmarketExpansions.parse(
        listOf("1\tawk\tAwakening\t10", "2\tbrk\tBreak Set\t20", "bad line", "3\tzzz\tNessuno\tx"),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(body))
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun source() = CardmarketCatalogIdSource(
        CardmarketCatalog(OkHttpClient(), File(dir, "c.json"), server.url("/c.json").toString()), expansions,
    )

    private fun request(setId: String, localId: String, name: String = "", dex: Int? = null, setName: String = "") =
        PriceRequest(Language.EN, "$setId-$localId", VariantType.NORMAL, false, setId = setId, setName = setName, localId = localId, name = name, dexId = dex)

    @Test
    fun `i nomi del catalogo si normalizzano senza attacchi, livello e simboli`() {
        assertEquals("omastar", CardmarketCatalog.nameKey("Omastar Lv.53 [Primal Swirl | Send Back]"))
        assertEquals("omastarbreak", CardmarketCatalog.nameKey("Omastar BREAK [Dangerous Tentacle]"))
        assertEquals("nidoranf", CardmarketCatalog.nameKey("Nidoran ♀ [Tackle]"))
        assertEquals("mrfuji", CardmarketCatalog.nameKey("Mr. Fuji"))
    }

    @Test
    fun `le carte con codice per il gioco online non entrano nel catalogo`() {
        val json = """{"products":[
            {"idProduct":1,"name":"Live Code Card (Pikachu Tin)","idExpansion":5,"idMetacard":1},
            {"idProduct":2,"name":"Pikachu","idExpansion":5,"idMetacard":2}]}"""
        assertEquals(listOf(2), CardmarketCatalog.parse(json.reader())[5]!!.map { it.idProduct })
    }

    @Test
    fun `il catalogo si legge anche con virgole, virgolette e graffe nei nomi`() = runTest {
        val catalog = CardmarketCatalog(OkHttpClient(), File(dir, "c.json"), server.url("/c.json").toString())
        val set = catalog.productsOf(10)
        assertEquals(listOf(100, 101, 102, 103), set.map { it.idProduct })
        assertEquals("pikachu", set[2].nameKey)
        assertEquals(emptyList<Int>(), catalog.productsOf(999).map { it.idProduct })
    }

    @Test
    fun `un nome unico nel set basta`() = runTest {
        assertEquals(200, source().cardmarketProductId(request("brk", "017", name = "Omastar Break")))
    }

    @Test
    fun `con piu carte dello stesso nome decide la posizione data dal numero`() = runTest {
        val s = source()
        assertEquals(101, s.cardmarketProductId(request("awk", "002", dex = 139)))
        assertEquals(103, s.cardmarketProductId(request("awk", "004", dex = 139)))
        // La posizione 3 è un Pikachu, la 9 non esiste, un numero non numerico non dice niente: nessuna risposta.
        assertNull(s.cardmarketProductId(request("awk", "003", dex = 139)))
        assertNull(s.cardmarketProductId(request("awk", "009", dex = 139)))
        assertNull(s.cardmarketProductId(request("awk", "SWSH001", dex = 139)))
    }

    @Test
    fun `un Allenatore giapponese si trova dalla posizione tra due Pokemon`() = runTest {
        // Set 30 come Mystery of the Fossils: Omanyte, Omastar, due Allenatori, Kabuto. I nomi giapponesi non coincidono con quelli inglesi.
        val products = listOf(
            CatalogProduct(1, "omanyte"), CatalogProduct(2, "omastar"), CatalogProduct(3, "mysteriousfossil", "Mysterious Fossil"),
            CatalogProduct(4, "bill", "Bill"), CatalogProduct(5, "kabuto"),
        )
        assertEquals(3, productByOrdinal(products, SetOrdinal(leftDex = 139, rightDex = 140, rank = 0, count = 2))?.idProduct)
        assertEquals(4, productByOrdinal(products, SetOrdinal(leftDex = 139, rightDex = 140, rank = 1, count = 2))?.idProduct)
        // Un numero di carte diverso da quello di TCGdex, o un riferimento che non c'è, non si indovina.
        assertNull(productByOrdinal(products, SetOrdinal(leftDex = 139, rightDex = 140, rank = 0, count = 3)))
        assertNull(productByOrdinal(products, SetOrdinal(leftDex = 25, rightDex = 140, rank = 0, count = 2)))
    }

    @Test
    fun `un fossile giapponese si trova dal numero, verificato dai Pokemon di riferimento`() {
        // Come Mirage Forest: l'ultimo Pokémon è "Registeel Gold Star" (079 = Pokédex 379), poi fossili e Allenatori fino alla fine.
        val products = listOf(
            CatalogProduct(1, "aron"), CatalogProduct(2, "registeelgoldstar", "Registeel Gold Star"), CatalogProduct(3, "clawfossil", "Claw Fossil"),
            CatalogProduct(4, "mysteriousfossil", "Mysterious Fossil"), CatalogProduct(5, "rootfossil", "Root Fossil"), CatalogProduct(6, "fieldworker", "Fieldworker"),
        )
        val ordinal = SetOrdinal(leftDex = 379, rightDex = null, rank = 1, count = 4)
        assertEquals(4, productByPosition(products, ordinal, 4)?.idProduct)
        // Numero o ordine che non tornano con i riferimenti: nessuna risposta.
        assertNull(productByPosition(products, ordinal, 5))
        assertNull(productByPosition(products, ordinal.copy(leftDex = 25), 4))
        assertNull(productByPosition(products, ordinal.copy(count = 3), 4))
        assertNull(productByPosition(products + CatalogProduct(7, "extra"), ordinal, 4))
        // Carta in cima al set: nessun Pokémon prima.
        assertEquals(1, productByPosition(products, SetOrdinal(null, 379, 0, 1), 1)?.idProduct)
    }

    @Test
    fun `senza set noto o senza carta con quel nome non risponde`() = runTest {
        val s = source()
        assertNull(s.cardmarketProductId(request("xxx", "001", name = "Omastar", setName = "Sconosciuto")))
        assertNull(s.cardmarketProductId(request("awk", "001", name = "Charizard")))
        assertNull(s.cardmarketProductId(request("zzz", "001", name = "Omastar")))
    }

    @Test
    fun `la fonte di ripiego si usa se la prima non trova la carta o non e disponibile`() = runTest {
        val fallback = CardmarketIdSource { 7 }
        assertEquals(5, CardmarketIdSource { 5 }.orElse(fallback).cardmarketProductId(request("a", "1")))
        assertEquals(7, CardmarketIdSource { null }.orElse(fallback).cardmarketProductId(request("a", "1")))
        assertEquals(7, CardmarketIdSource { error("token non valido") }.orElse(fallback).cardmarketProductId(request("a", "1")))
    }
}
