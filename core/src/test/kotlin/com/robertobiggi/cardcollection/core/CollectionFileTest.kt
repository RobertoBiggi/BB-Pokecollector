package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.backup.BackupEntry
import com.robertobiggi.cardcollection.core.backup.BackupQuery
import com.robertobiggi.cardcollection.core.backup.CardRecord
import com.robertobiggi.cardcollection.core.backup.CollectionFileV2
import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.core.backup.CollectionImportResult
import com.robertobiggi.cardcollection.core.backup.CollectionInfo
import com.robertobiggi.cardcollection.core.backup.ImportError
import com.robertobiggi.cardcollection.core.backup.PriceRecord
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class CollectionFileTest {

    private val webp = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0,
        'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(), 1, 2, 3)

    private fun record(id: String, image: String? = null) = CardRecord(
        id = "en:$id", language = "en", tcgdexId = id, name = "Mawile", setId = id.substringBeforeLast('-'),
        setName = "Perfect Order", setCode = "POR", localId = "032", printedNumber = "032/088",
        imageUrl = "https://assets.tcgdex.net/en/me/me03/032", variants = listOf("NORMAL", "REVERSE", "SHINY"), imageLow = image,
    )

    private fun roundTrip(file: CollectionFileV2): CollectionImportResult {
        val out = ByteArrayOutputStream()
        CollectionFiles.write(file, out)
        return CollectionFiles.read(out.toByteArray().inputStream())
    }

    @Test
    fun `collezione con immagini andata e ritorno`() {
        val file = CollectionFileV2(
            exportedAt = "2026-10-18T10:00:00Z",
            info = CollectionInfo("  Mawile  ", listOf(BackupQuery("en", "Mawile", true), BackupQuery("ja", "クチート", true))),
            cards = listOf(record("me03-032", CollectionFiles.encodeImage(webp))),
            entries = listOf(BackupEntry("en:me03-032|NORMAL", 2), BackupEntry("en:altra-1|NORMAL", 1)),
            prices = listOf(PriceRecord("en:me03-032|NORMAL", "TCGdex", eur = 0.03, usd = 0.11, fetchedAt = 10, confidence = "HIGH")),
        )
        val result = roundTrip(file) as CollectionImportResult.Ok
        val c = result.collection
        assertEquals("Mawile", c.name)
        assertEquals(2, c.queries.size)
        val card = c.cards.single()
        assertEquals(listOf("NORMAL", "REVERSE"), card.card.variants.map { it.type.name })
        assertArrayEquals(webp, card.imageLow)
        assertNull(card.imageHigh)
        assertEquals(listOf("en:me03-032|NORMAL"), c.entries.map { it.variantId }, "le voci di carte assenti dal file vengono scartate")
        assertEquals(1, c.prices.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `immagini non valide e carte malformate vengono scartate`() {
        assertNull(CollectionFiles.decodeImage("non-base64!!"))
        assertNull(CollectionFiles.decodeImage(CollectionFiles.encodeImage("<html>ciao</html>".toByteArray())))
        val bad = record("me03-032").copy(id = "en:../x")
        val file = CollectionFileV2(exportedAt = "x", info = CollectionInfo("A"), cards = listOf(bad, record("me03-032")), entries = emptyList())
        val result = roundTrip(file) as CollectionImportResult.Ok
        assertEquals(1, result.collection.cards.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `backup della prima versione diventa una collezione senza nome`() {
        val legacy = """{"schema":1,"app":"card-collection","exportedAt":"x",
            "queries":[{"language":"en","pattern":"Mawile","enabled":true},{"language":"xx","pattern":"Mawile","enabled":true}],
            "collection":[{"variantId":"en:me03-032|NORMAL","quantity":3},{"variantId":"en:me03-032|REVERSE","quantity":5000}],
            "manualPrices":[{"variantId":"en:me03-032|NORMAL","eur":2.5,"fetchedAt":5},{"variantId":"en:x|NORMAL","eur":-1,"fetchedAt":5}]}"""
        val result = CollectionFiles.read(legacy.byteInputStream()) as CollectionImportResult.Ok
        assertNull(result.collection.name)
        assertEquals(1, result.collection.queries.size)
        assertEquals(3, result.collection.entries.single().quantity)
        assertEquals("Manuale", result.collection.prices.single().source)
        assertTrue(result.collection.cards.isEmpty())
        assertEquals(3, result.skipped)
    }

    @Test
    fun `file estranei rifiutati con il motivo`() {
        fun error(text: String) = (CollectionFiles.read(text.byteInputStream()) as CollectionImportResult.Error).error
        assertEquals(ImportError.NOT_THIS_APP, error("{}"))
        assertEquals(ImportError.INVALID_JSON, error("non json"))
        assertEquals(ImportError.UNSUPPORTED_VERSION, error("""{"schema":9,"app":"card-collection"}"""))
        assertNull(CollectionFiles.cleanName("   \u0000 "))
    }

    @Test
    fun `lingue europee e asiatiche negli id`() {
        assertTrue(com.robertobiggi.cardcollection.core.model.Ids.isValidVariantId("zh-tw:SI-295|NORMAL"))
        assertTrue(com.robertobiggi.cardcollection.core.model.Ids.isValidVariantId("it:swsh12tg-TG17|HOLO"))
        val file = CollectionFileV2(
            exportedAt = "x", info = CollectionInfo("Mysdibule"),
            cards = listOf(record("me03-032").copy(id = "fr:me03-032", language = "fr")), entries = emptyList(),
        )
        assertEquals("fr:me03-032", (roundTrip(file) as CollectionImportResult.Ok).collection.cards.single().card.id)
    }

    @Test
    fun `campi nulli o assenti ricevono valori di ripiego`() {
        val text = """{"schema":2,"app":"card-collection","exportedAt":null,"info":{"name":null,"queries":null},
            "cards":[{"id":"en:me03-032","language":"en","tcgdexId":"me03-032","name":"Mawile","setId":"me03","setName":null,
                      "localId":null,"printedNumber":null,"rarity":null,"imageUrl":null,"variants":null,"imageLow":null}],
            "entries":[{"variantId":"en:me03-032|NORMAL","quantity":null,"condition":null,"notes":null}],
            "prices":[{"variantId":"en:me03-032|NORMAL","source":null,"eur":1.5,"fetchedAt":null,"confidence":null}]}"""
        val c = (CollectionFiles.read(text.byteInputStream()) as CollectionImportResult.Ok).collection
        assertNull(c.name)
        assertEquals("032", c.cards.single().card.localId)
        assertEquals("me03", c.cards.single().card.setName)
        assertEquals(listOf("NORMAL"), c.cards.single().card.variants.map { it.type.name })
        assertEquals(1, c.entries.single().quantity)
        assertEquals("NM", c.entries.single().condition)
        assertEquals("Importato", c.prices.single().source)
        assertEquals(1L, c.prices.single().fetchedAt)
    }

    @Test
    fun `prezzi senza data prendono la data dell'export`() {
        val text = """{"schema":2,"app":"card-collection","exportedAt":"2026-10-19T10:00:00Z",
            "cards":[{"id":"en:me03-032","language":"en","tcgdexId":"me03-032","name":"Mawile","setId":"me03","variants":["NORMAL"]}],
            "entries":[],"prices":[{"variantId":"en:me03-032|NORMAL","eur":1.5}]}"""
        val c = (CollectionFiles.read(text.byteInputStream()) as CollectionImportResult.Ok).collection
        assertEquals(java.time.Instant.parse("2026-10-19T10:00:00Z").toEpochMilli(), c.prices.single().fetchedAt)
    }

    @Test
    fun `voci di varianti non dichiarate scartate, varianti dedotte se mancano`() {
        val text = """{"schema":2,"app":"card-collection",
            "cards":[{"id":"en:me03-032","language":"en","tcgdexId":"me03-032","name":"Mawile","setId":"me03","variants":["NORMAL"]},
                     {"id":"en:me03-033","language":"en","tcgdexId":"me03-033","name":"Mawile ex","setId":"me03"}],
            "entries":[{"variantId":"en:me03-032|HOLO","quantity":2},
                       {"variantId":"en:me03-033|REVERSE","quantity":1,"condition":"lp"}]}"""
        val r = CollectionFiles.read(text.byteInputStream()) as CollectionImportResult.Ok
        assertEquals(listOf("en:me03-033|REVERSE"), r.collection.entries.map { it.variantId })
        assertEquals("LP", r.collection.entries.single().condition)
        assertEquals(listOf("REVERSE"), r.collection.cards.first { it.card.id == "en:me03-033" }.card.variants.map { it.type.name })
        assertEquals(1, r.skipped)
    }

    @Test
    fun `file senza info e senza carte e una collezione vuota`() {
        val r = CollectionFiles.read("""{"schema":2,"app":"card-collection"}""".byteInputStream()) as CollectionImportResult.Ok
        assertNull(r.collection.name)
        assertTrue(r.collection.cards.isEmpty())
    }

    @Test
    fun `il numero del Pokedex viene letto solo se plausibile`() {
        fun dex(value: Int): Int? {
            val text = """{"schema":2,"app":"card-collection","cards":[{"id":"ja:SV2a-139","language":"ja","tcgdexId":"SV2a-139","name":"オムスター","setId":"SV2a","dexId":$value}],"entries":[]}"""
            return (CollectionFiles.read(text.byteInputStream()) as CollectionImportResult.Ok).collection.cards.single().card.dexId
        }
        assertEquals(139, dex(139))
        assertNull(dex(99999))
    }
}
