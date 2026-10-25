package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardNumberResolver
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCardSearch
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExpansions
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketExtraSets
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
            {"id":"ZZZ","name":"知らないセット","cardCount":{"total":3,"official":3}},
            {"id":"HF","name":"隠れた運命","cardCount":{"total":2,"official":2}},
            {"id":"SK","name":"スカイリッジ","cardCount":{"total":3,"official":3}},
            {"id":"S5A","name":"双璧のファイター","cardCount":{"total":3,"official":3}}]""",
        "/v2/ja/sets/S5A" to """{"id":"S5A","name":"双璧のファイター","cardCount":{"total":3,"official":3},"cards":[]}""",
        "/v2/en/sets" to "[]",
        "/v2/ja/sets/SK" to """{"id":"SK","name":"スカイリッジ","cardCount":{"total":3,"official":3},
            "cards":[{"id":"SK-001","localId":"001","name":"ピカチュウ"},{"id":"SK-002","localId":"002","name":"Farfetch'd"},
            {"id":"SK-003","localId":"003","name":"ダークオマスター"}]}""",
        "/v2/ja/sets/HF" to """{"id":"HF","name":"隠れた運命","cardCount":{"total":2,"official":2},
            "cards":[{"id":"HF-001","localId":"001","name":"ピカチュウ"}]}""",
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
            {"idProduct":30,"name":"Omastar [Solo]","idCategory":51,"idExpansion":300},
            {"idProduct":50,"name":"Pikachu [Thunder Shock]","idCategory":51,"idExpansion":500},
            {"idProduct":51,"name":"Omastar [Pull Down]","idCategory":51,"idExpansion":500},
            {"idProduct":52,"name":"Dark Omastar [Tentacle]","idCategory":51,"idExpansion":500},
            {"idProduct":60,"name":"Omastar Lv.55 [Time Spiral | Primal Tentacles]","idCategory":51,"idExpansion":600},
            {"idProduct":61,"name":"Omastar [Tentacle Grip]","idCategory":51,"idExpansion":600},
            {"idProduct":62,"name":"Pikachu [Thunder Shock]","idCategory":51,"idExpansion":600},
            {"idProduct":70,"name":"Karen's Conviction","idCategory":51,"idExpansion":700},
            {"idProduct":71,"name":"Galarian Moltres V [Direflame Wings]","idCategory":51,"idExpansion":700},
            {"idProduct":72,"name":"Brawly","idCategory":51,"idExpansion":700},
            {"idProduct":73,"name":"Heracross [Horn Attack]","idCategory":51,"idExpansion":700},
            {"idProduct":74,"name":"Petilil [Seed Bomb]","idCategory":51,"idExpansion":700},
            {"idProduct":75,"name":"Lilligant [Dizzying Flower]","idCategory":51,"idExpansion":700},
            {"idProduct":76,"name":"Larvesta [Flame Charge]","idCategory":51,"idExpansion":700},
            {"idProduct":77,"name":"Volcarona [Fire Spin]","idCategory":51,"idExpansion":700},
            {"idProduct":78,"name":"Rare Candy","idCategory":51,"idExpansion":700},
            {"idProduct":80,"name":"Articuno, Moltres, and Zapdos [Big Bang]","idCategory":51,"idExpansion":800},
            {"idProduct":40,"name":"Pikachu","idCategory":51,"idExpansion":400},
            {"idProduct":41,"name":"Raichu","idCategory":51,"idExpansion":400},
            {"idProduct":42,"name":"Mew","idCategory":51,"idExpansion":400},
            {"idProduct":43,"name":"Mewtwo","idCategory":51,"idExpansion":400},
            {"idProduct":44,"name":"Eevee","idCategory":51,"idExpansion":400},
            {"idProduct":45,"name":"Vaporeon","idCategory":51,"idExpansion":400},
            {"idProduct":46,"name":"Jolteon","idCategory":51,"idExpansion":400},
            {"idProduct":47,"name":"Flareon","idCategory":51,"idExpansion":400},
            {"idProduct":48,"name":"Ditto","idCategory":51,"idExpansion":400},
            {"idProduct":90,"name":"Charizard [Collect Fire]","idCategory":51,"idExpansion":900,"idMetacard":5},
            {"idProduct":91,"name":"Charizard [Collect Fire]","idCategory":51,"idExpansion":900,"idMetacard":5},
            {"idProduct":92,"name":"Charizard Lv.76 [Solo Flame]","idCategory":51,"idExpansion":900,"idMetacard":6},
            {"idProduct":110,"name":"Squirtle [Water Gun]","idCategory":51,"idExpansion":1000,"idMetacard":7},
            {"idProduct":111,"name":"Squirtle [Water Gun]","idCategory":51,"idExpansion":1000,"idMetacard":7},
            {"idProduct":112,"name":"Squirtle [Water Gun]","idCategory":51,"idExpansion":1000,"idMetacard":7}]}""",
    )

    private val expansions = CardmarketExpansions.parse(
        listOf("1\txy10\tAwakening Psychic King\t100", "2\tsm1\tSun & Moon\t200", "3\tsmx\tAltro\t300", "4\thf\t隠れた運命\t400", "5\tsk\tスカイリッジ\t500", "6\ts5a\t双璧のファイター\t700"),
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

    /** Set giapponesi solo Cardmarket: Advent of Arceus (Omastar Lv.55 è la 023, l'altro Omastar non ha numero). */
    private val extraSets = CardmarketExtraSets.parse(
        listOf(
            "S\tja\tPt4\tPt4\tAdvent of Arceus\t600\t1", "C\tja\tPt4\t023\t60",
            "S\ten\tpr\t\tMiscellaneous Promos\t800\t0", "C\ten\tpr\t\t80",
            "S\ten\tdr\t\tEX Dragon\t900\t1", "C\ten\tdr\t100\t90",
            "S\tja\tpxy\t\tXY Promos\t1000\t1", "C\tja\tpxy\t064/XY-Pdp\t110", "C\tja\tpxy\t064/XY-P\t111", "C\tja\tpxy\t064/XY-Pmc\t112",
        ),
    )

    private fun search(extra: CardmarketExtraSets = CardmarketExtraSets(emptyList())) = CardmarketCardSearch(
        CardmarketCatalog(OkHttpClient(), File(dir, "c.json"), server.url("/v2/catalog").toString()),
        TcgdexClient(OkHttpClient(), server.url("/v2/").toString()),
        expansions,
        extra,
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
    fun `un set con molti piu prodotti delle carte e già in parte su TCGdex non si indovina dalla posizione`() = runTest {
        val s = search()
        // HF ha 2 carte ma 9 prodotti: le posizioni slittano (come in Hidden Fates), quindi nessuna carta inventata.
        assertEquals(emptyList<String>(), s.find(Language.JA, "Raichu").map { it.id })
        assertNull(s.cardFor(Language.JA, "HF", "2"))
    }

    @Test
    fun `con il numero vero da CardTrader si recuperano le carte fuori posizione, senza doppioni`() = runTest {
        val resolver = CardNumberResolver { _, _, _, product -> mapOf(41 to "2", 42 to "1", 43 to "SM210")[product.idProduct] }
        val s = search()
        assertEquals(listOf("HF-002"), s.find(Language.JA, "Raichu", resolver = resolver).map { it.tcgdexId })
        assertEquals(41, s.find(Language.JA, "Raichu", resolver = resolver).single().cardmarketId)
        // Il numero 1 c'è già su TCGdex e "SM210" non è un numero semplice: niente carta.
        assertEquals(emptyList<String>(), s.find(Language.JA, "Mew", resolver = resolver).map { it.id })
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

    @Test
    fun `i set solo Cardmarket si cercano per nome, col numero stampato o col prodotto Cardmarket`() = runTest {
        val found = search(extraSets).find(Language.JA, "Omastar").filter { it.setId == "Pt4" }
        assertEquals(listOf("Pt4-023", "Pt4-Cardmarket61"), found.map { it.tcgdexId })
        val numbered = found.first()
        assertEquals("ja:Pt4-023", numbered.id)
        assertEquals("023", numbered.printedNumber)
        assertEquals("Pt4", numbered.setCode)
        assertEquals("Advent of Arceus", numbered.setName)
        assertEquals(60, numbered.cardmarketId)
        assertEquals(139, numbered.dexId)
        // Senza numero la carta si riconosce dal prodotto e non ha un numero stampato inventato.
        assertEquals("", found.last().printedNumber)
        assertEquals(61, found.last().cardmarketId)
        // Solo il catalogo giapponese li usa.
        assertEquals(emptyList<String>(), search(extraSets).find(Language.FR, "Omastar").map { it.id })
    }

    @Test
    fun `le carte dei set solo Cardmarket si ricostruiscono dall'id`() = runTest {
        val s = search(extraSets)
        assertEquals(60, s.cardFor(Language.JA, "Pt4", "023")?.cardmarketId)
        assertEquals(61, s.cardFor(Language.JA, "Pt4", "Cardmarket61")?.cardmarketId)
        assertNull(s.cardFor(Language.JA, "Pt4", "099"))
        assertNull(s.cardFor(Language.EN, "Pt4", "023"))
    }

    @Test
    fun `Cardmarket indica le carte TCGdex con nome diverso, ma non quelle di un altro Pokemon`() = runTest {
        val known = mutableListOf<String>()
        search().find(Language.JA, "Omastar", known = known)
        // SK posizione 3: ダークオマスター non nomina nessun Pokémon noto, quindi vale (Dark Omastar).
        // SK posizione 2: "Farfetch'd" è un altro Pokémon, quindi no. SM1-002 è l'Omastar giapponese vero.
        assertEquals(true, "SK-003" in known)
        assertEquals(false, "SK-002" in known)
        assertEquals(true, "SM1-002" in known)
    }

    @Test
    fun `un set elencato senza carte ma con molti prodotti in piu non si indovina dalla posizione`() = runTest {
        // Matchless Fighters: 3 carte ufficiali e 9 prodotti, Moltres V alla posizione 2. Il numero vero è un altro (045/070).
        assertEquals(emptyList<String>(), search().find(Language.JA, "Moltres").map { it.id })
        // Con il numero vero di CardTrader, invece, la carta c'è.
        val resolver = CardNumberResolver { _, _, _, product -> if (product.idProduct == 71) "045" else null }
        assertEquals(listOf("S5A-045"), search().find(Language.JA, "Moltres", resolver = resolver).map { it.tcgdexId })
    }

    @Test
    fun `le promo inglesi che TCGdex non ha si trovano nel catalogo Cardmarket`() = runTest {
        val found = search(extraSets).find(Language.EN, "Moltres")
        assertEquals(listOf("pr-Cardmarket80"), found.map { it.tcgdexId })
        assertEquals("en:pr-Cardmarket80", found.single().id)
        assertEquals(Language.EN, found.single().language)
        assertEquals("", found.single().printedNumber)
        assertEquals(80, found.single().cardmarketId)
        // Il set inglese non compare nel catalogo giapponese.
        assertEquals(emptyList<String>(), search(extraSets).find(Language.JA, "Moltres").filter { it.setId == "pr" }.map { it.id })
    }

    @Test
    fun `stampe diverse della stessa carta con lo stesso numero sono carte diverse`() = runTest {
        val found = search(extraSets).find(Language.JA, "Squirtle").filter { it.setId == "pxy" }
        // 111 ("064/XY-P") tiene il numero, le altre due aggiungono il prodotto Cardmarket.
        assertEquals(listOf("pxy-064", "pxy-064-110", "pxy-064-112"), found.map { it.tcgdexId }.sorted())
        // Ognuna si ricostruisce dal suo id.
        assertEquals(110, search(extraSets).cardFor(Language.JA, "pxy", "064-110")?.cardmarketId)
        assertEquals(111, search(extraSets).cardFor(Language.JA, "pxy", "064")?.cardmarketId)
    }

    @Test
    fun `un prodotto senza numero che e un'altra stampa di una carta numerata del set non e una carta a parte`() = runTest {
        val found = search(extraSets).find(Language.EN, "Charizard").filter { it.setId == "dr" }
        // 90 è la 100; 91 ha la stessa metacard (altra stampa) e non conta; 92 è un'altra carta del set e senza numero resta.
        assertEquals(listOf("dr-100", "dr-Cardmarket92"), found.map { it.tcgdexId }.sorted())
    }
}
