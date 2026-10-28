package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.CollectionLanguages
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.LanguageQuery
import com.robertobiggi.cardcollection.core.model.QueryKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectionLanguagesTest {

    private fun name(id: Long, lang: Language?, pattern: String) = LanguageQuery(id, lang, pattern, QueryKind.NAME)
    private fun set(id: Long, lang: Language, pattern: String) = LanguageQuery(id, lang, pattern, QueryKind.SET_MASTER)

    @Test
    fun `una lingua nuova copia le ricerche per nome`() {
        val queries = listOf(name(1, Language.EN, "Mawile"), name(2, Language.EN, "Sableye"))
        val plan = CollectionLanguages.plan(queries, setOf(Language.EN, Language.JA))
        assertEquals(listOf(Language.JA to "Mawile", Language.JA to "Sableye"), plan.add)
        assertTrue(plan.removeIds.isEmpty())
    }

    @Test
    fun `una lingua tolta elimina le sue ricerche e lascia le altre`() {
        val queries = listOf(name(1, Language.EN, "Mawile"), name(2, Language.JA, "Mawile"), set(3, Language.JA, "M3"))
        val plan = CollectionLanguages.plan(queries, setOf(Language.EN))
        assertEquals(listOf(2L, 3L), plan.removeIds)
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun `i set non si copiano nelle altre lingue`() {
        val queries = listOf(set(1, Language.EN, "base1"))
        assertFalse(CollectionLanguages.canAddLanguage(queries))
        assertTrue(CollectionLanguages.plan(queries, setOf(Language.EN, Language.JA)).add.isEmpty())
    }

    @Test
    fun `i nomi uguali in lingue diverse si copiano una volta sola`() {
        val queries = listOf(name(1, Language.EN, "Mawile"), name(2, Language.IT, "mawile"))
        val plan = CollectionLanguages.plan(queries, setOf(Language.EN, Language.IT, Language.FR))
        assertEquals(listOf(Language.FR to "Mawile"), plan.add)
    }

    @Test
    fun `senza cambiamenti non c'e niente da fare`() {
        val queries = listOf(name(1, Language.EN, "Mawile"), name(2, Language.JA, "Mawile"))
        val plan = CollectionLanguages.plan(queries, setOf(Language.EN, Language.JA))
        assertTrue(plan.add.isEmpty() && plan.removeIds.isEmpty())
        assertEquals(setOf(Language.EN, Language.JA), CollectionLanguages.languagesOf(queries))
    }

    @Test
    fun `una lingua non riconosciuta resta com'era`() {
        val queries = listOf(name(1, Language.EN, "Mawile"), name(2, null, "Mawile"))
        assertTrue(CollectionLanguages.plan(queries, setOf(Language.EN)).removeIds.isEmpty())
    }
}
