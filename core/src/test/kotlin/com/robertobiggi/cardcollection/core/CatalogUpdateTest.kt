package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.CatalogUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUpdateTest {

    @Test
    fun `una collezione mai aggiornata ha bisogno dell'aggiornamento`() {
        assertTrue(CatalogUpdate.needsUpdate(null, "2026-11-05T17:00:00Z"))
    }

    @Test
    fun `con lo stesso database installato non serve`() {
        assertFalse(CatalogUpdate.needsUpdate("2026-11-05T17:00:00Z", "2026-11-05T17:00:00Z"))
    }

    @Test
    fun `un database diverso richiede l'aggiornamento, anche piu vecchio`() {
        assertTrue(CatalogUpdate.needsUpdate("2026-11-04T17:00:00Z", "2026-11-05T17:00:00Z"))
        assertTrue(CatalogUpdate.needsUpdate("2026-11-06T17:00:00Z", "2026-11-05T17:00:00Z"))
    }

    @Test
    fun `senza database installato non c'e niente da aggiornare`() {
        assertFalse(CatalogUpdate.needsUpdate(null, null))
        assertFalse(CatalogUpdate.needsUpdate("2026-11-05T17:00:00Z", null))
    }

    @Test
    fun `le immagini si riscaricano solo se la chiave registrata e cambiata`() {
        assertFalse(CatalogUpdate.imagesChanged(null, "a|b"))
        assertFalse(CatalogUpdate.imagesChanged("a|b", "a|b"))
        assertTrue(CatalogUpdate.imagesChanged("a|b", "a|c"))
    }

    @Test
    fun `la chiave delle immagini tiene l'ordine delle fonti`() {
        assertEquals("https://t/x|card-images/1_a||", CatalogUpdate.imageKey("https://t/x", listOf("card-images/1_a", null, null)))
        assertEquals("||p", CatalogUpdate.imageKey(null, listOf(null, "p")))
    }
}
