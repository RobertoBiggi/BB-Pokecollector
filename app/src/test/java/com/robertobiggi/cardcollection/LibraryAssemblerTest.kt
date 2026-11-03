package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.repo.EntryMerge
import com.robertobiggi.cardcollection.data.repo.LibraryAssembler
import com.robertobiggi.cardcollection.ui.CollectionFilter
import com.robertobiggi.cardcollection.ui.OwnedFilter
import com.robertobiggi.cardcollection.ui.collection.applyFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryAssemblerTest {

    private fun card(
        id: String,
        name: String,
        code: String?,
        setId: String = "me03",
        setName: String = "Perfect Order",
        localId: String = "032",
        printedNumber: String = "032/088",
    ) = CardEntity(
        id = id, language = "en", tcgdexId = id.substringAfter(':'), name = name, setId = setId, setName = setName,
        setCode = code, serieId = "me", serieName = "Mega Evolution", localId = localId, printedNumber = printedNumber,
        rarity = "Common", releaseDate = "2026-03-27", illustrator = null, imageUrl = null, imageLowPath = null,
        imageHighPath = null, queryId = 1, catalogUpdatedAt = 0,
    )

    private fun snap(variantId: String, eur: Double?, usd: Double?, at: Long, source: String = "TCGdex") =
        PriceSnapshotEntity(0, variantId, source, eur, null, null, usd, null, null, at, "HIGH")

    @Test
    fun `nelle carte solo holo la variante prende il nome della rarita`() {
        fun labelOf(rarity: String?, types: List<VariantType>): String? {
            val c = card("en:sv10-031", "Moltres ex", "DRI").copy(rarity = rarity)
            val ui = LibraryAssembler.assemble(listOf(c), types.map { CardVariantEntity("${c.id}|${it.name}", c.id, it.name) }, emptyList(), emptyList()).single()
            return ui.rarityLabelFor(ui.variants.first { it.type == VariantType.HOLO })
        }
        assertEquals("Double rare", labelOf("Double rare", listOf(VariantType.HOLO)))
        assertNull(labelOf("Rare Holo", listOf(VariantType.HOLO)))
        assertNull(labelOf(null, listOf(VariantType.HOLO)))
        assertNull(labelOf("Fixed", listOf(VariantType.HOLO)))
        assertNull(labelOf("Rare", listOf(VariantType.HOLO)))
        assertNull(labelOf("Rare", listOf(VariantType.NORMAL, VariantType.HOLO)))
    }

    @Test
    fun `lo spostamento di una variante somma le copie e unisce le note`() {
        val reverse = CollectionEntryEntity(1, "c|REVERSE", quantity = 1, wanted = false, notes = "da vendere")
        assertEquals(CollectionEntryEntity(1, "c|HOLO", quantity = 1, wanted = false, notes = "da vendere"), EntryMerge.move(reverse, "c|HOLO", null))
        val holo = CollectionEntryEntity(1, "c|HOLO", quantity = 2, wanted = true, notes = null)
        val merged = EntryMerge.move(reverse, "c|HOLO", holo)
        assertEquals(3, merged.quantity)
        assertEquals(true, merged.wanted)
        assertEquals("da vendere", merged.notes)
    }

    @Test
    fun `una variante non piu elencata passa a quella che la sostituisce`() {
        val normalReverse = listOf(VariantType.NORMAL, VariantType.REVERSE)
        assertEquals(VariantType.NORMAL, EntryMerge.replacement(VariantType.HOLO, normalReverse))
        assertEquals(VariantType.HOLO, EntryMerge.replacement(VariantType.REVERSE, listOf(VariantType.NORMAL, VariantType.HOLO)))
        assertEquals(VariantType.NORMAL, EntryMerge.replacement(VariantType.REVERSE, listOf(VariantType.NORMAL)))
        assertNull(EntryMerge.replacement(VariantType.REVERSE, normalReverse))
        assertEquals(VariantType.REVERSE, EntryMerge.replacement(VariantType.HOLO, listOf(VariantType.REVERSE)))
        assertEquals(VariantType.HOLO, EntryMerge.replacement(VariantType.NORMAL, listOf(VariantType.HOLO)))
        assertEquals(VariantType.HOLO, EntryMerge.replacement(VariantType.NORMAL, listOf(VariantType.HOLO, VariantType.REVERSE)))
        assertNull(EntryMerge.replacement(VariantType.FIRST_EDITION, listOf(VariantType.NORMAL)))
        assertNull(EntryMerge.replacement(VariantType.HOLO, listOf(VariantType.FIRST_EDITION)))
    }

    @Test
    fun `assembla varianti, quantita e ultimo prezzo in euro`() {
        val c = card("en:me03-032", "Mawile", "POR")
        val normal = "en:me03-032|NORMAL"
        val reverse = "en:me03-032|REVERSE"
        val result = LibraryAssembler.assemble(
            cards = listOf(c),
            variants = listOf(CardVariantEntity(reverse, c.id, VariantType.REVERSE.name), CardVariantEntity(normal, c.id, VariantType.NORMAL.name)),
            entries = listOf(CollectionEntryEntity(1, normal, quantity = 2), CollectionEntryEntity(1, reverse, wanted = false)),
            snapshots = listOf(snap(normal, 0.03, 0.11, 1), snap(normal, 0.05, null, 2, source = "Manuale")),
        ).single()
        assertEquals("POR 032/088", result.printedCode)
        assertEquals(listOf(VariantType.NORMAL, VariantType.REVERSE), result.variants.map { it.type })
        assertEquals(1, result.slots)
        assertEquals(1, result.ownedSlots)
        assertEquals(0.05, result.variants[0].latestEur?.eur)
        assertNull(result.variants[1].latestEur)
    }

    @Test
    fun `variante posseduta conta come slot anche senza la voglio`() {
        val c = card("ja:M3-117", "メガジガルデex", "M3")
        val holo = "ja:M3-117|HOLO"
        val result = LibraryAssembler.assemble(
            cards = listOf(c),
            variants = listOf(CardVariantEntity(holo, c.id, VariantType.HOLO.name)),
            entries = listOf(CollectionEntryEntity(1, holo, quantity = 1, wanted = false)),
            snapshots = emptyList(),
        ).single()
        assertEquals(1, result.slots)
        assertEquals(1, result.ownedSlots)
        assertEquals(1, result.copies)
    }

    @Test
    fun `visibilita per collezione, ricerche disattivate e carte tolte`() {
        val queries = listOf(
            com.robertobiggi.cardcollection.data.local.TrackedQueryEntity(1, 1, "en", "Mawile", true),
            com.robertobiggi.cardcollection.data.local.TrackedQueryEntity(2, 1, "ja", "クチート", false),
        )
        val links = listOf(
            com.robertobiggi.cardcollection.data.local.CollectionCardEntity(1, "en:a-1", 1),
            com.robertobiggi.cardcollection.data.local.CollectionCardEntity(1, "ja:b-1", 2),
            com.robertobiggi.cardcollection.data.local.CollectionCardEntity(1, "en:c-1", null),
            com.robertobiggi.cardcollection.data.local.CollectionCardEntity(1, "en:d-1", 1, hidden = true),
            com.robertobiggi.cardcollection.data.local.CollectionCardEntity(2, "en:e-1", null),
        )
        assertEquals(setOf("en:a-1", "en:c-1"), LibraryAssembler.visibleCardIds(1, links, queries))
        assertEquals(setOf("en:e-1"), LibraryAssembler.visibleCardIds(2, links, queries))
    }

    @Test
    fun `filtro mancanti e ricerca per codice`() {
        val a = LibraryAssembler.assemble(
            listOf(card("en:me03-032", "Mawile", "POR"), card("en:ex2-9", "Mawile", null, "ex2", "Sandstorm", "9", "9/100")),
            listOf(CardVariantEntity("en:me03-032|NORMAL", "en:me03-032", "NORMAL"), CardVariantEntity("en:ex2-9|HOLO", "en:ex2-9", "HOLO")),
            listOf(CollectionEntryEntity(1, "en:me03-032|NORMAL", quantity = 1)),
            emptyList(),
        )
        assertEquals(listOf("en:ex2-9"), applyFilter(a, CollectionFilter(owned = OwnedFilter.MISSING)).map { it.card.id })
        assertEquals(listOf("en:me03-032"), applyFilter(a, CollectionFilter(query = "por 032")).map { it.card.id })
    }
}
