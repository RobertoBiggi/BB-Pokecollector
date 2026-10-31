package com.robertobiggi.cardcollection.core.model

/**
 * Search of a collection, reduced to what is needed to change its languages.
 * @property id id of the search.
 * @property language language of the search, null if the saved code is not recognized.
 * @property pattern name or id of the set searched.
 * @property kind type of search.
 */
data class LanguageQuery(val id: Long, val language: Language?, val pattern: String, val kind: QueryKind)

/**
 * Changes to make to the searches to bring a collection to the wanted languages.
 * @property add searches by name to add, as language and text pairs.
 * @property removeIds ids of the searches to delete (those of the removed languages).
 */
data class LanguagePlan(val add: List<Pair<Language, String>>, val removeIds: List<Long>)

/**
 * The languages of a collection are those of its searches: adding one copies the searches by name into the new language,
 * removing one deletes the searches of that language. Sets are not copied, because a set id is valid for one language only.
 */
object CollectionLanguages {

    /**
     * Languages that have at least one search.
     * @param queries List<LanguageQuery> - searches of the collection.
     * @return Set<Language> - languages present.
     */
    fun languagesOf(queries: List<LanguageQuery>): Set<Language> = queries.mapNotNull { it.language }.toSet()

    /**
     * Tells whether a language can be added: at least one search by name to copy is needed.
     * @param queries List<LanguageQuery> - searches of the collection.
     * @return Boolean - true if there is a search by name.
     */
    fun canAddLanguage(queries: List<LanguageQuery>): Boolean = queries.any { it.kind == QueryKind.NAME }

    /**
     * Computes what to add and what to remove. Searches with an unrecognized language stay as they were.
     * @param queries List<LanguageQuery> - current searches.
     * @param wanted Set<Language> - languages the collection must have.
     * @return LanguagePlan - searches to add and to delete.
     */
    fun plan(queries: List<LanguageQuery>, wanted: Set<Language>): LanguagePlan {
        val current = languagesOf(queries)
        val names = queries.filter { it.kind == QueryKind.NAME }.map { it.pattern }.distinctBy { it.lowercase() }
        val add = Language.entries.filter { it in wanted && it !in current }.flatMap { language -> names.map { language to it } }
        val remove = queries.filter { it.language != null && it.language !in wanted }.map { it.id }
        return LanguagePlan(add, remove)
    }
}
