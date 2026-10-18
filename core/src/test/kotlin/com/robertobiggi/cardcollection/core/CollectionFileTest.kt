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
        assertEquals(ImportError.MISSING_INFO, error("""{"schema":2,"app":"card-collection"}"""))
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
}
