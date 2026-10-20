package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CollectionLanguageTest {

    @Test
    fun `lingue fisse e collezione mista`() {
        assertEquals("en", CollectionLanguage.codeOf(Language.EN))
        assertEquals("ja", CollectionLanguage.codeOf(Language.JA))
        assertEquals(CollectionLanguage.MIXED, CollectionLanguage.codeOf(null))
        assertEquals(CollectionLanguage.MIXED, CollectionLanguage.codeOf(Language.IT))
        assertEquals(Language.JA, CollectionLanguage.fixedLanguage("ja"))
        assertNull(CollectionLanguage.fixedLanguage(CollectionLanguage.MIXED))
        assertNull(CollectionLanguage.fixedLanguage("fr"))
    }

    @Test
    fun `deduzione dalle lingue trovate`() {
        assertEquals("en", CollectionLanguage.infer(listOf("en", "en")))
        assertEquals(CollectionLanguage.MIXED, CollectionLanguage.infer(listOf("en", "ja")))
        assertEquals(CollectionLanguage.MIXED, CollectionLanguage.infer(emptyList()))
        assertEquals(CollectionLanguage.MIXED, CollectionLanguage.infer(listOf("xx")))
        assertNull(CollectionLanguage.sanitize("it"))
        assertEquals(CollectionLanguage.MIXED, CollectionLanguage.sanitize("mixed"))
    }
}
