package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.tcgdex.SetBriefDto
import java.util.concurrent.ConcurrentHashMap

/**
 * Sigle alternative dei set. Lo stesso set si scrive in modo diverso sulla carta o su Cardmarket/CardTrader e su TCGdex
 * (EX Deoxys è "DX" su Cardmarket e "ex8" su TCGdex; Base Set "BS" e "base1"): la ricerca accetta l'una e l'altra.
 * La sigla Cardmarket viene dall'elenco dei set incluso nell'app ([CardmarketExpansions]), abbinato al set TCGdex
 * con le stesse regole usate per i prezzi ([CardTraderMatcher.expansionFor]).
 *
 * @param expansions set dell'elenco Cardmarket/CardTrader (id, sigla, nome).
 */
class SetCodeAliases(private val expansions: List<CtExpansionDto>) {

    /** Sigla Cardmarket già calcolata per lingua, id e nome del set TCGdex ("" = nessuna). */
    private val cache = ConcurrentHashMap<Triple<Language, String, String>, String>()

    /** Sigle presenti nell'elenco, per scartare subito le parole che non sono la sigla di nessun set. */
    private val knownCodes: Set<String> by lazy { expansions.mapNotNull { it.code?.let(::normalizeCode) }.toSet() }

    /**
     * Sigla Cardmarket/CardTrader di un set TCGdex.
     * @param language Language - lingua del catalogo (per i cataloghi asiatici si confronta prima la sigla, i nomi sono tradotti).
     * @param setId String - id TCGdex del set (es. "ex8").
     * @param setName String - nome TCGdex del set (es. "Deoxys").
     * @return String? - sigla in minuscolo senza suffisso di lingua (es. "dx", "xy" da "xy-en"), oppure null se il set non è nell'elenco.
     */
    fun codeOf(language: Language, setId: String, setName: String): String? =
        cache.getOrPut(Triple(language, setId, setName)) {
            CardTraderMatcher.expansionFor(expansions, setName, setId, null, preferCode = language.asianSets)
                ?.code?.let(::normalizeCode).orEmpty()
        }.ifEmpty { null }

    /**
     * Controlla se una parola cercata indica il set, con l'id TCGdex o con la sigla Cardmarket.
     * Le parole che fanno parte dei nomi delle carte (es. "ex" di "Rayquaza ex", che su Cardmarket è anche Expedition)
     * valgono solo come id TCGdex.
     * @param token String - parola cercata (es. "dx", "EX8").
     * @param language Language - lingua del catalogo.
     * @param setId String - id TCGdex del set.
     * @param setName String - nome TCGdex del set.
     * @return Boolean - true se la parola è l'id o la sigla del set.
     */
    fun matches(token: String, language: Language, setId: String, setName: String): Boolean {
        if (token.equals(setId, ignoreCase = true)) return true
        val code = normalizeCode(token)
        if (code in NAME_WORDS || code !in knownCodes) return false
        return codeOf(language, setId, setName) == code
    }

    /**
     * Set indicati da una parola: prima quelli con quell'id TCGdex, altrimenti quelli con quella sigla Cardmarket.
     * @param token String - parola cercata.
     * @param language Language - lingua del catalogo.
     * @param sets List<SetBriefDto> - set del catalogo TCGdex.
     * @return List<SetBriefDto> - set indicati, vuota se la parola non è l'id o la sigla di nessun set.
     */
    fun setsFor(token: String, language: Language, sets: List<SetBriefDto>): List<SetBriefDto> =
        sets.filter { token.equals(it.id, ignoreCase = true) }
            .ifEmpty { sets.filter { matches(token, language, it.id, it.name) } }

    companion object {
        /** Sigle dell'elenco incluso nell'app, con la cache condivisa da ricerca, scelta dei set e filtro della collezione. */
        val bundled: SetCodeAliases by lazy { SetCodeAliases(CardmarketExpansions.bundled.expansions) }

        /** Parole dei nomi delle carte che non vanno lette come sigla di un set. */
        private val NAME_WORDS = setOf("ex", "gx", "v", "vmax", "vstar", "break", "lv", "star", "prime", "tag")

        /** Suffisso di lingua che CardTrader aggiunge ad alcune sigle (es. "xy-en"), assente sulla carta. */
        private const val LANGUAGE_SUFFIX = "-en"

        /**
         * Sigla in forma confrontabile: minuscola, senza spazi e senza suffisso di lingua.
         * @param code String - sigla letta dall'elenco o scritta dall'utente.
         * @return String - sigla normalizzata.
         */
        private fun normalizeCode(code: String): String = code.trim().lowercase().removeSuffix(LANGUAGE_SUFFIX)
    }
}
