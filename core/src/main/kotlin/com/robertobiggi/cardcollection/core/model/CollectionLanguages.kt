package com.robertobiggi.cardcollection.core.model

/**
 * Ricerca di una collezione, ridotta a ciò che serve per cambiarne le lingue.
 * @property id id della ricerca.
 * @property language lingua della ricerca, null se il codice salvato non è riconosciuto.
 * @property pattern nome o id del set cercato.
 * @property kind tipo di ricerca.
 */
data class LanguageQuery(val id: Long, val language: Language?, val pattern: String, val kind: QueryKind)

/**
 * Modifiche da fare alle ricerche per portare una collezione alle lingue volute.
 * @property add ricerche per nome da aggiungere, come coppie lingua e testo.
 * @property removeIds id delle ricerche da eliminare (quelle delle lingue tolte).
 */
data class LanguagePlan(val add: List<Pair<Language, String>>, val removeIds: List<Long>)

/**
 * Le lingue di una collezione sono quelle delle sue ricerche: aggiungerne una copia le ricerche per nome nella nuova lingua,
 * toglierne una elimina le ricerche di quella lingua. I set non si copiano, perché l'id di un set vale per una lingua sola.
 */
object CollectionLanguages {

    /**
     * Lingue che hanno almeno una ricerca.
     * @param queries List<LanguageQuery> - ricerche della collezione.
     * @return Set<Language> - lingue presenti.
     */
    fun languagesOf(queries: List<LanguageQuery>): Set<Language> = queries.mapNotNull { it.language }.toSet()

    /**
     * Indica se si può aggiungere una lingua: serve almeno una ricerca per nome da copiare.
     * @param queries List<LanguageQuery> - ricerche della collezione.
     * @return Boolean - true se c'è una ricerca per nome.
     */
    fun canAddLanguage(queries: List<LanguageQuery>): Boolean = queries.any { it.kind == QueryKind.NAME }

    /**
     * Calcola cosa aggiungere e cosa togliere. Le ricerche con una lingua non riconosciuta restano com'erano.
     * @param queries List<LanguageQuery> - ricerche attuali.
     * @param wanted Set<Language> - lingue che la collezione deve avere.
     * @return LanguagePlan - ricerche da aggiungere e da eliminare.
     */
    fun plan(queries: List<LanguageQuery>, wanted: Set<Language>): LanguagePlan {
        val current = languagesOf(queries)
        val names = queries.filter { it.kind == QueryKind.NAME }.map { it.pattern }.distinctBy { it.lowercase() }
        val add = Language.entries.filter { it in wanted && it !in current }.flatMap { language -> names.map { language to it } }
        val remove = queries.filter { it.language != null && it.language !in wanted }.map { it.id }
        return LanguagePlan(add, remove)
    }
}
