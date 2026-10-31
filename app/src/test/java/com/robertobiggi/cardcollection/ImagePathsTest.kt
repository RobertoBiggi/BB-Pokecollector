package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.data.image.ImagePaths
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: after a reinstall the Android backup restores the database (with the image paths) but not
 * the images. Paths without a file must be forgotten, otherwise the sync never downloads those images again.
 */
class ImagePathsTest {

    private val low = "/data/user/0/app/files/cards/en_me01-046-low.webp"
    private val high = "/data/user/0/app/files/cards/en_me01-046-high.webp"

    @Test
    fun `i percorsi senza file vanno dimenticati`() {
        assertEquals(setOf(low, high), ImagePaths.missing(low, high) { false })
        assertEquals(setOf(high), ImagePaths.missing(low, high) { it == low })
    }

    @Test
    fun `i percorsi con il file restano`() {
        assertEquals(emptySet<String>(), ImagePaths.missing(low, high) { true })
        assertEquals(emptySet<String>(), ImagePaths.missing(null, null) { false })
    }

    @Test
    fun `un'immagine scelta a mano usata per entrambe le qualita conta una volta`() {
        val manual = "/data/user/0/app/files/cards/en_me01-046-manual-1700000000000.webp"
        assertEquals(setOf(manual), ImagePaths.missing(manual, manual) { false })
    }
}
