package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketLinks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class CardmarketLinksTest {
    @Test
    fun `le carte Cardmarket senza numero si riconoscono dall'id`() {
        org.junit.jupiter.api.Assertions.assertTrue(com.robertobiggi.cardcollection.core.model.Ids.isUnnumberedCardmarket("en:s-p-Cardmarket786632"))
        org.junit.jupiter.api.Assertions.assertFalse(com.robertobiggi.cardcollection.core.model.Ids.isUnnumberedCardmarket("en:s-p-103"))
    }

    @Test
    fun `la ricerca e limitata al set se noto`() {
        assertEquals("https://www.cardmarket.com/it/Pokemon/Products/Search?idExpansion=5142&searchString=Omastar+V", CardmarketLinks.search("Omastar V", 5142))
        assertEquals("https://www.cardmarket.com/it/Pokemon/Products/Search?searchString=Omastar", CardmarketLinks.search("Omastar", null))
    }
}
