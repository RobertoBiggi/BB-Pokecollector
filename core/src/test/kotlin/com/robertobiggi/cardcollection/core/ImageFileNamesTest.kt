package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.image.ImageFileNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImageFileNamesTest {

    @Test
    fun `the source is part of the name and is read back`() {
        val name = ImageFileNames.of("ja_sml-016", "high", "pokellector")
        assertEquals("ja_sml-016-high-pokellector.webp", name)
        assertEquals("pokellector", ImageFileNames.sourceOf(name))
        assertEquals("tcgdex", ImageFileNames.sourceOf(ImageFileNames.of("en_me01-094", "low", "tcgdex")))
    }

    @Test
    fun `a name without source says nothing about the origin`() {
        assertEquals("en_me01-094-low.webp", ImageFileNames.of("en_me01-094", "low", null))
        assertNull(ImageFileNames.sourceOf("en_me01-094-low.webp"))
        assertNull(ImageFileNames.sourceOf("something-else.webp"))
    }

    @Test
    fun `images picked by the user are recognised`() {
        assertEquals(ImageFileNames.MANUAL, ImageFileNames.sourceOf("ja_sml-016-manual-1730624000000.webp"))
    }

    @Test
    fun `a card id that looks like a source does not fool the reader`() {
        // The id is only read from the end of the name.
        assertEquals("billsarchive", ImageFileNames.sourceOf(ImageFileNames.of("ja_pcg-low-x", "high", "billsarchive")))
    }

    @Test
    fun `a source with other characters is refused because it would end up in a path`() {
        for (bad in listOf("../x", "a/b", "A", "a b", "", "x".repeat(21))) {
            assertThrows(IllegalArgumentException::class.java) { ImageFileNames.of("id", "low", bad) }
        }
    }
}
