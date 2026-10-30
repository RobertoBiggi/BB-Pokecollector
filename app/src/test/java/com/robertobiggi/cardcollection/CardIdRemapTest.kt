package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.CardIdRemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ritrovamento nel database di una carta salvata con un id che non esiste più. */
class CardIdRemapTest {
    private fun card(id: String, localId: String, printed: String = "") = CatalogCard(
        id = id, language = Language.JA, tcgdexId = id.substringAfter(':'), name = "Omanyte", setId = id.substringAfter(':').substringBeforeLast('-'),
        setName = "Set", setCode = null, serieId = null, serieName = null, localId = localId, printedNumber = printed, rarity = null,
        releaseDate = null, illustrator = null, imageBaseUrl = null, variants = emptyList(),
    )

    @Test
    fun `un solo prodotto uguale basta`() {
        assertEquals("ja:ADV4-023", CardIdRemap.pick("023", "ADVex1023", listOf(card("ja:ADV4-023", "023")), emptyList())?.id)
    }

    @Test
    fun `con più carte dello stesso prodotto decide il numero locale`() {
        val both = listOf(card("ja:A-001", "001"), card("ja:B-002", "002"))
        assertEquals("ja:B-002", CardIdRemap.pick("002", "", both, emptyList())?.id)
        assertNull(CardIdRemap.pick("003", "", both, emptyList()))
    }

    @Test
    fun `senza prodotto si usa il numero stampato solo se è unico`() {
        val same = listOf(card("ja:A-023", "023", "023/080"), card("ja:B-023", "023", "023/086"))
        assertEquals("ja:A-023", CardIdRemap.pick("023", "023/080", emptyList(), same)?.id)
        assertNull(CardIdRemap.pick("023", "023/999", emptyList(), same))
        assertNull(CardIdRemap.pick("023", "", emptyList(), same))
        assertNull(CardIdRemap.pick("023", "023/080", emptyList(), same + card("ja:C-023", "023", "023/080")))
    }
}
