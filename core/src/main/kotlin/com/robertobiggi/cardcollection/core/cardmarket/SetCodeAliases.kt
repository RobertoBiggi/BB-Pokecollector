package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.tcgdex.SetBriefDto
import java.util.concurrent.ConcurrentHashMap

/**
 * Sigle alternative dei set. Lo stesso set si scrive in modo diverso sulla carta, su Cardmarket/CardTrader e su TCGdex
 * (EX Deoxys: "DX" sulla carta e "ex8" su TCGdex; Astral Radiance: "ASR" sulla carta, "astr" su CardTrader, "swsh10"
 * su TCGdex): la ricerca accetta ognuna.
 * - La sigla Cardmarket/CardTrader viene dall'elenco dei set incluso nell'app ([CardmarketExpansions]), abbinato al set
 *   TCGdex con le stesse regole usate per i prezzi ([CardTraderMatcher.expansionFor]).
 * - La sigla stampata dei set europei viene da `tcgdex-printed-codes.tsv` (id TCGdex → `abbreviation.official`), perché
 *   l'elenco dei set di TCGdex non la riporta e scaricare il dettaglio di ogni set costerebbe centinaia di richieste.
 *   Nei cataloghi asiatici la sigla stampata è già l'id del set.
 *
 * @param expansions set dell'elenco Cardmarket/CardTrader (id, sigla, nome).
 * @param printedCodes sigla stampata per id TCGdex dei set europei.
 */
class SetCodeAliases(private val expansions: List<CtExpansionDto>, private val printedCodes: Map<String, String> = emptyMap()) {

    /** Sigla Cardmarket già calcolata per lingua, id e nome del set TCGdex ("" = nessuna). */
    private val cache = ConcurrentHashMap<Triple<Language, String, String>, String>()

    /** Sigle presenti negli elenchi, per scartare subito le parole che non sono la sigla di nessun set. */
    private val knownCodes: Set<String> by lazy {
        (expansions.mapNotNull { it.code?.let(::normalizeCode) } + printedCodes.values.map(::normalizeCode)).toSet()
    }

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
     * Sigla stampata di un set europeo, dalla tabella inclusa nell'app.
     * @param language Language - lingua del catalogo.
     * @param setId String - id TCGdex del set.
     * @return String? - sigla in minuscolo (es. "asr"; per le Trainer Gallery "asr" da "ASR:TG"), oppure null se non è nota
     *         o il catalogo è asiatico (lì la sigla è l'id).
     */
    fun printedCodeOf(language: Language, setId: String): String? =
        if (language.asianSets) null else printedCodes[setId]?.let(::normalizeCode)

    /**
     * Sigle con cui si può cercare un set oltre al suo id: quella stampata e quella Cardmarket, senza doppioni.
     * @param language Language - lingua del catalogo.
     * @param setId String - id TCGdex del set.
     * @param setName String - nome TCGdex del set.
     * @return List<String> - sigle in minuscolo, prima quella stampata; vuota se il set non ne ha di diverse dall'id.
     */
    fun codesOf(language: Language, setId: String, setName: String): List<String> =
        listOfNotNull(printedCodeOf(language, setId), codeOf(language, setId, setName))
            .distinct().filterNot { it.equals(setId, ignoreCase = true) }

    /**
     * Controlla se una parola cercata indica il set, con l'id TCGdex, la sigla stampata o la sigla Cardmarket.
     * Le parole che fanno parte dei nomi delle carte (es. "ex" di "Rayquaza ex", che è anche la sigla di Expedition)
     * valgono solo come id TCGdex.
     * @param token String - parola cercata (es. "dx", "EX8", "ASR").
     * @param language Language - lingua del catalogo.
     * @param setId String - id TCGdex del set.
     * @param setName String - nome TCGdex del set.
     * @return Boolean - true se la parola è l'id o una sigla del set.
     */
    fun matches(token: String, language: Language, setId: String, setName: String): Boolean {
        if (token.equals(setId, ignoreCase = true)) return true
        val code = normalizeCode(token)
        if (code in NAME_WORDS || code !in knownCodes) return false
        return code == printedCodeOf(language, setId) || code == codeOf(language, setId, setName)
    }

    /**
     * Set indicati da una parola: prima quelli con quell'id TCGdex, altrimenti quelli con quella sigla stampata o Cardmarket
     * (possono essere più d'uno: "TR" è Team Rocket Returns sulla carta e Team Rocket su Cardmarket).
     * @param token String - parola cercata.
     * @param language Language - lingua del catalogo.
     * @param sets List<SetBriefDto> - set del catalogo TCGdex.
     * @return List<SetBriefDto> - set indicati, vuota se la parola non è l'id o una sigla di nessun set.
     */
    fun setsFor(token: String, language: Language, sets: List<SetBriefDto>): List<SetBriefDto> =
        sets.filter { token.equals(it.id, ignoreCase = true) }
            .ifEmpty { sets.filter { matches(token, language, it.id, it.name) } }

    companion object {
        /** Sigle degli elenchi inclusi nell'app, con la cache condivisa da ricerca, scelta dei set e filtro della collezione. */
        val bundled: SetCodeAliases by lazy { SetCodeAliases(CardmarketExpansions.bundled.expansions, bundledPrintedCodes()) }

        /** Parole dei nomi delle carte che non vanno lette come sigla di un set. */
        private val NAME_WORDS = setOf("ex", "gx", "v", "vmax", "vstar", "break", "lv", "star", "prime", "tag")

        /** Suffisso di lingua che CardTrader aggiunge ad alcune sigle (es. "xy-en"), assente sulla carta. */
        private const val LANGUAGE_SUFFIX = "-en"

        /**
         * Legge `tcgdex-printed-codes.tsv` (righe "id TAB sigla").
         * @return Map<String, String> - sigla stampata per id TCGdex; vuota se il file manca.
         */
        private fun bundledPrintedCodes(): Map<String, String> {
            val stream = SetCodeAliases::class.java.getResourceAsStream("tcgdex-printed-codes.tsv") ?: return emptyMap()
            return parsePrintedCodes(stream.bufferedReader(Charsets.UTF_8).readLines())
        }

        /**
         * Legge le righe "id TAB sigla" della tabella delle sigle stampate.
         * @param lines List<String> - righe del file.
         * @return Map<String, String> - sigla per id; le righe malformate sono ignorate.
         */
        fun parsePrintedCodes(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
            val p = line.split('\t')
            val id = p.getOrNull(0)?.trim().orEmpty()
            val code = p.getOrNull(1)?.trim().orEmpty()
            if (id.isEmpty() || code.isEmpty() || code.length > MAX_CODE_LENGTH) null else id to code
        }.toMap()

        /** Lunghezza massima di una sigla stampata accettata dalla tabella. */
        private const val MAX_CODE_LENGTH = 16

        /**
         * Sigla in forma confrontabile: minuscola, senza spazi, senza suffisso di lingua e senza la parte dopo ":"
         * (le Trainer Gallery sono stampate "ASR:TG", sulla carta c'è "ASR").
         * @param code String - sigla letta dagli elenchi o scritta dall'utente.
         * @return String - sigla normalizzata.
         */
        private fun normalizeCode(code: String): String = code.trim().lowercase().substringBefore(':').removeSuffix(LANGUAGE_SUFFIX)
    }
}
