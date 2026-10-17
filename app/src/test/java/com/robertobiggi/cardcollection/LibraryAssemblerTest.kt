package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.repo.LibraryAssembler
import com.robertobiggi.cardcollection.ui.CollectionFilter
import com.robertobiggi.cardcollection.ui.OwnedFilter
import com.robertobiggi.cardcollection.ui.collection.applyFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryAssemblerTest {

    private fun card(id: String, name: String, code: String?) = CardEntity(
        id = id, language = "en", tcgdexId = id.substringAfter(':'), name = name, setId = "me03", setName = "Perfect Order",
        setCode = code, serieId = "me", serieName = "Mega Evolution", localId = "032", printedNumber = "032/088",
        rarity = "Common", releaseDate = "2026-03-27", illustrator = null, imageUrl = null, imageLowPath = null,
        imageHighPath = null, queryId = 1, catalogUpdatedAt = 0,
    )

    private fun snap(variantId: String, eur: Double?, usd: Double?, at: Long, source: String = "TCGdex") =
        PriceSnapshotEntity(0, variantId, source, eur, null, null, usd, null, null, at, "HIGH")

    @Test
    fun `assembla varianti, quantita e ultimo prezzo per valuta`() {
        val c = card("en:me03-032", "Mawile", "POR")
        val normal = "en:me03-032|NORMAL"
        val reverse = "en:me03-032|REVERSE"
        val result = LibraryAssembler.assemble(
            cards = listOf(c),
            variants = listOf(CardVariantEntity(reverse, c.id, VariantType.REVERSE.name), CardVariantEntity(normal, c.id, VariantType.NORMAL.name)),
            entries = listOf(CollectionEntryEntity(normal, quantity = 2), CollectionEntryEntity(reverse, wanted = false)),
            snapshots = listOf(snap(normal, 0.03, 0.11, 1), snap(normal, 0.05, null, 2, source = "Manuale")),
        ).single()
        assertEquals("POR 032/088", result.printedCode)
        assertEquals(listOf(VariantType.NORMAL, VariantType.REVERSE), result.variants.map { it.type })
        assertEquals(1, result.slots)
        assertEquals(1, result.ownedSlots)
        assertEquals(0.05, result.variants[0].latestEur?.eur)
        assertEquals(0.11, result.variants[0].latestUsd?.usd)
        assertNull(result.variants[1].latestEur)
    }

    @Test
    fun `filtro mancanti e ricerca per codice`() {
        val a = LibraryAssembler.assemble(
            listOf(card("en:me03-032", "Mawile", "POR"), card("en:ex2-9", "Mawile", null)),
            listOf(CardVariantEntity("en:me03-032|NORMAL", "en:me03-032", "NORMAL"), CardVariantEntity("en:ex2-9|HOLO", "en:ex2-9", "HOLO")),
            listOf(CollectionEntryEntity("en:me03-032|NORMAL", quantity = 1)),
            emptyList(),
        )
        assertEquals(listOf("en:ex2-9"), applyFilter(a, CollectionFilter(owned = OwnedFilter.MISSING)).map { it.card.id })
        assertEquals(listOf("en:me03-032"), applyFilter(a, CollectionFilter(query = "por 032")).map { it.card.id })
    }
}
