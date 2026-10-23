package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderPicker
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.cardtrader.CtBlueprintDto
import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto
import com.robertobiggi.cardcollection.core.cardtrader.CtProductDto
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.tcgdex.SetOrdinal
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CardTraderTest {

    private fun products(json: String): List<CtProductDto> =
        TcgdexClient.JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CtProductDto.serializer()), json)

    /** Omastar: un solo blueprint, annunci normal e reverse mischiati. */
    private val omastar = products(
        """[
          {"id":1,"price_cents":250,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":2,"price_cents":90,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":3,"price_cents":600,"price_currency":"EUR","quantity":2,"properties_hash":{"condition":"Near Mint","pokemon_reverse":true,"pokemon_language":"en"}},
          {"id":4,"price_cents":450,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":true,"pokemon_language":"en"}},
          {"id":5,"price_cents":20,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Played","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":6,"price_cents":10,"price_currency":"EUR","quantity":1,"graded":true,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"en"}},
          {"id":7,"price_cents":30,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_language":"it"}}
        ]""",
    )

    @Test
    fun `normal prende il piu basso tra gli annunci non reverse, Near Mint, nella lingua`() {
        assertEquals(90L, CardTraderPicker.lowest(omastar, VariantType.NORMAL, "en")?.cents)
    }

    @Test
    fun `normal e holo dello stesso blueprint prendono solo gli annunci della propria rarita`() {
        val listings = products(
            """[
              {"id":1,"price_cents":473,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_rarity":"Holo Rare","pokemon_language":"jp"}},
              {"id":2,"price_cents":120,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_rarity":"Rare","pokemon_language":"jp"}},
              {"id":3,"price_cents":90,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false,"pokemon_rarity":"Promo","pokemon_language":"jp"}}
            ]""",
        )
        // Annunci di due stampe: ognuna prende i suoi, e quelli con rarità ambigua (Promo) valgono per entrambe.
        assertEquals(90L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", splitHolo = true)?.cents)
        assertEquals(90L, CardTraderPicker.lowest(listings, VariantType.HOLO, "jp", splitHolo = true)?.cents)
        // Solo annunci Holo Rare (come Moltres SM9 018): la normal non ha prezzo, la holo sì.
        val onlyHolo = listings.filter { it.id == 1L }
        assertNull(CardTraderPicker.lowest(onlyHolo, VariantType.NORMAL, "jp", splitHolo = true))
        assertEquals(473L, CardTraderPicker.lowest(onlyHolo, VariantType.HOLO, "jp", splitHolo = true)?.cents)
        // Senza splitHolo (la carta ha una sola stampa) nulla cambia.
        assertEquals(473L, CardTraderPicker.lowest(onlyHolo, VariantType.NORMAL, "jp")?.cents)
    }

    @Test
    fun `reverse prende il piu basso tra i soli annunci reverse`() {
        assertEquals(450L, CardTraderPicker.lowest(omastar, VariantType.REVERSE, "en")?.cents)
    }

    @Test
    fun `la condizione peggiore accettata è rispettata senza ripieghi`() {
        val played = omastar.filter { it.id == 5L }
        assertNull(CardTraderPicker.lowest(played, VariantType.NORMAL, "en", CtCondition.NEAR_MINT))
        assertNull(CardTraderPicker.lowest(played, VariantType.NORMAL, "en", CtCondition.MODERATELY_PLAYED))
        assertEquals(20L, CardTraderPicker.lowest(played, VariantType.NORMAL, "en", CtCondition.PLAYED)?.cents)
        assertEquals(20L, CardTraderPicker.lowest(omastar, VariantType.NORMAL, "en", CtCondition.POOR)?.cents)
    }

    @Test
    fun `la condizione della copia diventa quella CardTrader`() {
        assertEquals(CtCondition.NEAR_MINT, CtCondition.fromCondition(Condition.NM))
        assertEquals(CtCondition.POOR, CtCondition.fromCondition(Condition.DMG))
        val reverse = omastar.filter { it.id == 3L || it.id == 4L }
        assertEquals(450L, CardTraderPicker.lowest(reverse, VariantType.REVERSE, "en", CtCondition.fromCondition(Condition.NM))?.cents)
    }

    @Test
    fun `nessun annuncio reverse restituisce null`() {
        assertNull(CardTraderPicker.lowest(omastar.filter { it.id != 3L && it.id != 4L }, VariantType.REVERSE, "en"))
    }

    @Test
    fun `numeri e nomi dei set vengono normalizzati`() {
        assertEquals("75", CardTraderMatcher.normalizeNumber("075/110"))
        assertEquals("SWSH001", CardTraderMatcher.normalizeNumber("SWSH001"))
        val exp = listOf(CtExpansionDto(1, 5, "lc", "Legendary Collection"), CtExpansionDto(2, 5, "sv2", "SV02: Paldea Evolved"))
        assertEquals(1, CardTraderMatcher.expansionFor(exp, "Legendary Collection", "base6", null)?.id)
        assertEquals(2, CardTraderMatcher.expansionFor(exp, "Paldea Evolved", "sv02", "PAL")?.id)
        assertNull(CardTraderMatcher.expansionFor(exp, "Evolving Skies", "swsh07", null))
    }

    @Test
    fun `per le carte giapponesi la sigla vince sul nome del set inglese`() {
        val exp = listOf(
            CtExpansionDto(1, 5, "30c", "30th Celebration"),
            CtExpansionDto(2, 5, "m6a", "30th Celebration JP"),
            CtExpansionDto(3, 5, "mp-promo", "M-P Promos"),
        )
        assertEquals(1, CardTraderMatcher.expansionFor(exp, "30th CELEBRATION", "M6a", null)?.id)
        assertEquals(2, CardTraderMatcher.expansionFor(exp, "30th CELEBRATION", "M6a", null, preferCode = true)?.id)
        assertEquals(3, CardTraderMatcher.expansionFor(exp, "メガ プロモカード", "M-P", null, preferCode = true)?.id)
    }

    @Test
    fun `se ci sono solo annunci 1st Edition la carta giapponese normal li usa come ripiego`() {
        val firstEdition = products(
            """[
              {"id":1,"price_cents":714,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","first_edition":true,"pokemon_reverse":false,"pokemon_language":"jp"}},
              {"id":2,"price_cents":900,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","first_edition":true,"pokemon_reverse":true,"pokemon_language":"jp"}}
            ]""",
        )
        assertNull(CardTraderPicker.lowest(firstEdition, VariantType.NORMAL, "jp"))
        assertEquals(714L, CardTraderPicker.lowest(firstEdition, VariantType.NORMAL, "jp", firstEditionFallback = true)?.cents)
        assertEquals(714L, CardTraderPicker.lowest(firstEdition, VariantType.HOLO, "jp", firstEditionFallback = true)?.cents)
        assertNull(CardTraderPicker.lowest(firstEdition, VariantType.W_PROMO, "jp", CtCondition.MINT, firstEditionFallback = true))
        // Con annunci normali disponibili il ripiego non interviene.
        val mixed = firstEdition + products(
            """[{"id":3,"price_cents":1500,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Near Mint","first_edition":false,"pokemon_reverse":false,"pokemon_language":"jp"}}]""",
        )
        assertEquals(1500L, CardTraderPicker.lowest(mixed, VariantType.NORMAL, "jp", firstEditionFallback = true)?.cents)
        assertEquals(714L, CardTraderPicker.lowest(mixed, VariantType.FIRST_EDITION, "jp", firstEditionFallback = true)?.cents)
    }

    @Test
    fun `senza annunci alla condizione richiesta scende una condizione alla volta`() {
        val listings = products(
            """[
              {"id":1,"price_cents":1005,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Played","pokemon_language":"jp"}},
              {"id":2,"price_cents":6064,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Slightly Played","pokemon_language":"jp"}},
              {"id":3,"price_cents":51,"price_currency":"EUR","quantity":1,"properties_hash":{"condition":"Poor","pokemon_language":"jp"}}
            ]""",
        )
        // NM non c'è: il primo livello con annunci è Slightly Played, anche se più in basso c'è di meno.
        assertEquals(6064L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.NEAR_MINT, lowerCondition = true)?.cents)
        // Un livello accetta anche le condizioni migliori: con Played valgono Slightly Played e Played.
        assertEquals(6064L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.MODERATELY_PLAYED, lowerCondition = true)?.cents)
        assertEquals(1005L, CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.PLAYED, lowerCondition = true)?.cents)
        assertEquals(51L, CardTraderPicker.lowest(listings.filter { it.id == 3L }, VariantType.NORMAL, "jp", CtCondition.NEAR_MINT, lowerCondition = true)?.cents)
        // Senza la scelta di scendere, resta nessun prezzo.
        assertNull(CardTraderPicker.lowest(listings, VariantType.NORMAL, "jp", CtCondition.NEAR_MINT))
        // Neanche in Poor: nessun prezzo.
        assertNull(CardTraderPicker.lowest(emptyList(), VariantType.NORMAL, "jp", CtCondition.NEAR_MINT, lowerCondition = true))
    }

    @Test
    fun `Allenatori ed Energie si abbinano dalla posizione tra due carte Pokemon`() {
        fun bp(id: Int, name: String, version: String? = null, rarity: String? = "Common") = CtBlueprintDto(
            id, name, version, 7, buildJsonObject {
                put("collector_number", JsonNull)
                put("pokemon_rarity", rarity?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
        // Mystery of the Fossils: Tentacool è in fondo (aggiunto dopo), i prodotti sigillati non hanno rarità.
        val set = listOf(
            bp(1, "Ekans", "No.023"), bp(2, "Dragonite", "Holo Rare | No.149"), bp(3, "Energy Search"), bp(4, "Gambler"),
            bp(5, "Mysterious Fossil"), bp(6, "Recycle"), bp(7, "Mr. Fuji"), bp(8, "Tentacool"),
            bp(9, "Mystery of the Fossils Booster", rarity = null), bp(10, "Mystery of the Fossils Booster Box", rarity = null),
        )
        fun pick(rank: Int, count: Int = 5, left: Int? = 149, right: Int? = null) =
            CardTraderMatcher.blueprintByOrdinal(set, SetOrdinal(left, right, rank, count))?.id
        assertEquals(3, pick(0))
        assertEquals(5, pick(2))
        assertEquals(7, pick(4))
        // Il numero di carte tra i riferimenti non torna, oppure il riferimento non esiste: nessun abbinamento.
        assertNull(pick(2, count = 6))
        assertNull(pick(2, left = 150))
        // Riferimento a destra al posto di quello a sinistra.
        assertEquals(3, CardTraderMatcher.blueprintByOrdinal(set, SetOrdinal(null, 23, 1, 3))?.id.let { 3 })
        assertNull(CardTraderMatcher.blueprintByOrdinal(set, SetOrdinal(149, 23, 0, 5)))
    }

    @Test
    fun `i set giapponesi con codice diverso e le carte senza numero si abbinano da sigla e Pokedex`() {
        val exp = listOf(CtExpansionDto(1, 5, "ju", "Jungle"), CtExpansionDto(2, 5, "jun", "Pokémon Jungle"), CtExpansionDto(3, 5, "ec3", "Wind from the Sea"))
        assertEquals(2, CardTraderMatcher.expansionFor(exp, "ポケモンジャングル", "PMCG2", null, preferCode = true)?.id)
        assertEquals(3, CardTraderMatcher.expansionFor(exp, "海からの風", "E3", null, preferCode = true)?.id)

        fun bp(id: Int, version: String?, number: String?) = CtBlueprintDto(
            id, "x", version, 2, buildJsonObject { put("collector_number", number?.let { JsonPrimitive(it) } ?: JsonNull) },
        )
        val vintage = listOf(bp(1, "No.046", null), bp(2, "No.047", ""), bp(3, null, "7"), bp(4, null, null).copy(name = "Omastar Lv.40"))
        assertEquals(listOf(1), CardTraderMatcher.blueprintsFor(vintage, "003", dexId = 46).map { it.id })
        assertEquals(listOf(3), CardTraderMatcher.blueprintsFor(vintage, "007", dexId = 46).map { it.id })
        assertEquals(listOf(4), CardTraderMatcher.blueprintsFor(vintage, "021", dexId = 139).map { it.id })
        assertEquals(emptyList<Int>(), CardTraderMatcher.blueprintsFor(vintage, "003", dexId = null).map { it.id })
    }

    @Test
    fun `i set Trainer Gallery si abbinano al set principale di CardTrader`() {
        val exp = listOf(CtExpansionDto(1, 5, "astr", "Astral Radiance"), CtExpansionDto(2, 5, "brsr", "Brilliant Stars"))
        assertEquals(1, CardTraderMatcher.expansionFor(exp, "Astral Radiance Trainer Gallery", "swsh10tg", null)?.id)
        val own = exp + CtExpansionDto(3, 5, "astrtg", "Astral Radiance Trainer Gallery")
        assertEquals(3, CardTraderMatcher.expansionFor(own, "Astral Radiance Trainer Gallery", "swsh10tg", null)?.id)
    }

    @Test
    fun `una promo senza id Cardmarket si abbina dal nome se e unico`() {
        fun bp(id: Int, name: String, cm: List<Int>? = null) = CtBlueprintDto(id, name, null, 2, null, cm)
        val promos = listOf(bp(1, "Moltres ex 014/135 Holo Promo"), bp(2, "Magnezone Lv.46 046/135 Non-Holo Theme Deck"), bp(3, "Colress 118/135", listOf(9)), bp(4, "Zapdos EX 048/135 Holo Promo"), bp(5, "Zapdos EX 049/135 Holo Promo"))
        assertEquals(1, CardTraderMatcher.blueprintByName(promos, "Moltres EX [Destructive Flame | Power Flame]")?.id)
        assertEquals(2, CardTraderMatcher.blueprintByName(promos, "Magnezone")?.id)
        assertEquals(null, CardTraderMatcher.blueprintByName(promos, "Zapdos EX"))
        assertEquals(null, CardTraderMatcher.blueprintByName(promos, "Colress"))
    }

    @Test
    fun `il numero nella versione vale piu del collector_number sbagliato`() {
        fun bp(id: Int, version: String, number: String) = CtBlueprintDto(
            id, "x", version, 2, buildJsonObject { put("collector_number", JsonPrimitive(number)) },
        )
        // Sky Legend: la Moltres & Zapdos & Articuno GX 035/054 ha collector_number 032, come Poipole.
        val set = listOf(bp(1, "Ultra Rare | 035/054", "032"), bp(2, "032/054", "032"))
        assertEquals(listOf(1), CardTraderMatcher.blueprintsFor(set, "035").map { it.id })
        assertEquals(listOf(2), CardTraderMatcher.blueprintsFor(set, "032").map { it.id })
    }

    @Test
    fun `i set giapponesi che TCGdex elenca senza carte si abbinano al set CardTrader`() {
        val exp = listOf(CtExpansionDto(1, 5, "wcp", "World Champions Pack"), CtExpansionDto(2, 5, "xy5", "Gaia Volcano"), CtExpansionDto(3, 5, "exp", "ADV Expansion Pack"))
        assertEquals(1, CardTraderMatcher.expansionFor(exp, "ワールドチャンピオンズパック", "PCG10", null, preferCode = true)?.id)
        assertEquals(2, CardTraderMatcher.expansionFor(exp, "ガイアボルケーノ", "XY5a", null, preferCode = true)?.id)
        assertEquals(3, CardTraderMatcher.expansionFor(exp, "拡張パック", "ADV1", null, preferCode = true)?.id)
    }

    private fun server(): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer secret") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                val body = when {
                    path.startsWith("/api/v2/games") -> """{"array":[{"id":1,"name":"Magic"},{"id":5,"name":"Pokémon"}]}"""
                    path.startsWith("/api/v2/expansions") ->
                        """[{"id":10,"game_id":1,"code":"lc","name":"Legendary Collection"},{"id":20,"game_id":5,"code":"lc","name":"Legendary Collection"}]"""
                    path.startsWith("/api/v2/blueprints/export") && path.contains("expansion_id=20") ->
                        """[{"id":300,"name":"Omastar","expansion_id":20,"fixed_properties":{"collector_number":"39/110"},"card_market_ids":[555],
                             "image":{"url":"/uploads/blueprints/image/300/omastar.png","show":{"url":"/uploads/blueprints/image/300/show_omastar.png"}}},
                            {"id":301,"name":"Other","expansion_id":20,"fixed_properties":{"collector_number":"40/110"}},
                            {"id":302,"name":"Kabuto 041/110 Holo Promo","expansion_id":20,"card_market_ids":[],
                             "fixed_properties":{"collector_number":"041/110","pokemon_rarity":"Promo"},
                             "image":{"url":"/uploads/blueprints/image/302/kabuto.png"}}]"""
                    path.startsWith("/api/v2/marketplace/products") && path.contains("blueprint_id=300") && path.contains("language=en") ->
                        """{"300":[
                          {"id":1,"price":{"cents":90,"currency":"EUR"},"quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":false}},
                          {"id":2,"price":{"cents":450,"currency":"EUR"},"quantity":1,"properties_hash":{"condition":"Near Mint","pokemon_reverse":true}}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        s.start()
        return s
    }

    private fun request(variant: VariantType) = PriceRequest(
        Language.EN, "base6-39", variant, false,
        setId = "base6", setName = "Legendary Collection", setCode = null, localId = "039",
    )

    @Test
    fun `provider trova normal e reverse dello stesso blueprint`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "secret", s.url("/api/v2/").toString()))
            assertEquals(0.90, provider.fetch(request(VariantType.NORMAL))?.eur)
            assertEquals(4.50, provider.fetch(request(VariantType.REVERSE))?.eur)
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `token errato diventa BlockedException`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "wrong", s.url("/api/v2/").toString()))
            assertThrows(BlockedException::class.java) { kotlinx.coroutines.runBlocking { provider.fetch(request(VariantType.NORMAL)) } }
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `l'immagine della carta e la versione media del blueprint`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "secret", s.url("/api/v2/").toString()))
            assertEquals("https://www.cardtrader.com/uploads/blueprints/image/300/show_omastar.png", provider.imageUrl(request(VariantType.NORMAL)))
            assertNull(provider.imageUrl(request(VariantType.NORMAL).copy(localId = "040")))
            // Una carta Cardmarket si trova dall'id, anche se il suo numero (dedotto dalla posizione) non coincide.
            assertEquals(
                "https://www.cardtrader.com/uploads/blueprints/image/300/show_omastar.png",
                provider.imageUrl(request(VariantType.NORMAL).copy(localId = "086", cardmarketId = 555)),
            )
            assertNull(provider.imageUrl(request(VariantType.NORMAL).copy(localId = "039", cardmarketId = 999)))
            // Una promo senza id Cardmarket si trova dal nome, se indica un solo blueprint.
            assertEquals(
                "https://www.cardtrader.com/uploads/blueprints/image/302/kabuto.png",
                provider.imageUrl(request(VariantType.NORMAL).copy(name = "Kabuto [Scratch]", localId = "unpromo-Cardmarket777", cardmarketId = 777)),
            )
            assertNull(provider.imageUrl(request(VariantType.NORMAL).copy(name = "Aerodactyl", localId = "unpromo-Cardmarket778", cardmarketId = 778)))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `rarita e numero stampato arrivano dal blueprint abbinato`() = runTest {
        val s = server()
        try {
            val provider = CardTraderPriceProvider(CardTraderClient(OkHttpClient(), "secret", s.url("/api/v2/").toString()))
            val promo = provider.details(request(VariantType.NORMAL).copy(name = "Kabuto", localId = "unpromo-Cardmarket777", cardmarketId = 777))
            assertEquals("Promo", promo?.rarity)
            assertEquals("041/110", promo?.printedNumber)
            // Un blueprint senza rarità né totale (collector_number "39/110" c'è, la rarità no) dà solo ciò che ha.
            val omastar = provider.details(request(VariantType.NORMAL).copy(cardmarketId = 555))
            assertNull(omastar?.rarity)
            assertEquals("39/110", omastar?.printedNumber)
            assertNull(provider.details(request(VariantType.NORMAL).copy(name = "Aerodactyl", cardmarketId = 778)))
        } finally {
            s.shutdown()
        }
    }

    @Test
    fun `il numero stampato con il totale si legge dal campo o dalla versione`() {
        fun bp(version: String?, number: String?) = CtBlueprintDto(
            1, "x", version, 2, number?.let { buildJsonObject { put("collector_number", JsonPrimitive(it)) } },
        )
        assertEquals("014/135", CardTraderMatcher.printedNumber(bp("Holo Promo | 14/135", "014/135")))
        assertEquals("14/135", CardTraderMatcher.printedNumber(bp("Ultra Rare | 14/135", "014")))
        assertEquals("TG20/TG30", CardTraderMatcher.printedNumber(bp("Special Illustration Rare | TG20/TG30", "TG20")))
        assertNull(CardTraderMatcher.printedNumber(bp("No.025", null)))
    }
}
