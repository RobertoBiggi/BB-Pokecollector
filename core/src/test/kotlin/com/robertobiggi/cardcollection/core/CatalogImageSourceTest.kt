package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.image.CatalogImageSource
import com.robertobiggi.cardcollection.core.image.CatalogImageSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogImageSourceTest {

    @Test
    fun `rebuilds the address from the saved path`() {
        assertEquals(
            "https://den-cards.pokellector.com/316/Brocks-Zubat.B05.1.38325.png",
            CatalogImageSources.POKELLECTOR.urlOf("316/Brocks-Zubat.B05.1.38325"),
        )
        assertEquals(
            "https://bills-archive.nyc3.cdn.digitaloceanspaces.com/tcgdex_cards_jp/e1/e1-18_abra.webp",
            CatalogImageSources.BILLS_ARCHIVE.urlOf("e1/e1-18_abra"),
        )
    }

    @Test
    fun `rebuilds a Pokoin address from the saved path`() {
        assertEquals(
            "https://cdn.pokoin.com/card-images/656670_bulbasaur_homepage.webp",
            CatalogImageSources.POKOIN.urlOf("card-images/656670_bulbasaur_homepage"),
        )
    }

    @Test
    fun `refuses paths that could leave the source's folder or break the address`() {
        val source = CatalogImageSources.POKELLECTOR
        for (bad in listOf("", "../x", "a/../b", "/abs", "a//b", "a b", "a?x=1", "a#f", "http://evil/x", "a\\b", "x".repeat(201))) {
            assertNull(source.urlOf(bad), "path: $bad")
        }
    }

    @Test
    fun `accepts only https on the source host`() {
        val source = CatalogImageSources.POKELLECTOR
        assertTrue(source.isAllowedUrl("https://den-cards.pokellector.com/1/a.png"))
        assertFalse(source.isAllowedUrl("http://den-cards.pokellector.com/1/a.png"))
        assertFalse(source.isAllowedUrl("https://den-cards.pokellector.com.evil.net/1/a.png"))
        assertFalse(source.isAllowedUrl("https://billsarchive.com/1/a.png"))
        assertFalse(source.isAllowedUrl("not a url"))
    }

    @Test
    fun `the column must be a plain lowercase word, because it goes into a query`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogImageSource("x", "a; DROP TABLE card", "https://x.example/", ".png", "X") }
        assertThrows(IllegalArgumentException::class.java) { CatalogImageSource("x", "ok", "http://x.example/", ".png", "X") }
    }

    @Test
    fun `hosts of all the sources are listed for the app's network filter`() {
        assertEquals(setOf("cdn.pokoin.com", "den-cards.pokellector.com", "bills-archive.nyc3.cdn.digitaloceanspaces.com"), CatalogImageSources.hosts)
        assertEquals(listOf("pokoin", "pokellector", "billsarchive"), CatalogImageSources.ALL.map { it.id })
        assertEquals(listOf("pokoin"), CatalogImageSources.BEFORE_CARDTRADER.map { it.id })
    }
}
