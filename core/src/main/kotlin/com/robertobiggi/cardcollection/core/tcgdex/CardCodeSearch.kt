package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.cardmarket.SetCodeAliases
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import java.io.IOException

/**
 * Ricerca scomposta a partire dal codice stampato sulla carta.
 *
 * @param number Int - numero della carta nel set (es. 117 da "117/080").
 * @param total Int? - numero ufficiale di carte del set, se indicato (es. 80 da "117/080").
 * @param tokens List<String> - altre parole: sigla del set (es. "M3"), parte del nome o testo
 *        da ignorare (es. la rarità "MUR").
 */
data class CardCodeQuery(val number: Int, val total: Int?, val tokens: List<String>)

/**
 * Trova carte TCGdex dal codice stampato in basso a sinistra, es. "M3 117/080", "117/080 MUR",
 * "MEG 046/132" o "SM10a-030". Il set si può indicare con l'id TCGdex ("ex8 107") o con la sigla
 * Cardmarket ("dx 107"). Le parole che non corrispondono a nulla vengono ignorate.
 */
object CardCodeSearch {

    private val ALLOWED = Regex("^[\\p{L}\\p{N} ./'\\-]{1,40}$")
    private val NUMBER = Regex("^(\\d{1,4})(?:/(\\d{1,4}))?$")
    private val SET_DASH_NUMBER = Regex("^([A-Za-z][A-Za-z0-9.\\-]{0,15})-(\\d{1,4})$")

    /** Massimo di parole aggiuntive considerate. */
    private const val MAX_TOKENS = 4

    /** Massimo di set di cui scaricare il dettaglio per confrontare la sigla. */
    private const val MAX_SET_DETAILS = 6

    /**
     * Riconosce un testo che contiene un numero di carta.
     * @param text String - testo inserito dall'utente.
     * @return CardCodeQuery? - ricerca scomposta, oppure null se il testo non contiene un numero
     *         di carta (in quel caso va cercato per nome) o ha caratteri non ammessi.
     */
    fun parse(text: String): CardCodeQuery? {
        val trimmed = text.trim()
        if (!ALLOWED.matches(trimmed)) return null
        var number: Int? = null
        var total: Int? = null
        val tokens = mutableListOf<String>()
        for (word in trimmed.split(' ').filter { it.isNotEmpty() }) {
            val plain = NUMBER.matchEntire(word)
            val dashed = SET_DASH_NUMBER.matchEntire(word)
            when {
                number == null && plain != null -> {
                    number = plain.groupValues[1].toInt()
                    total = plain.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                }
                number == null && dashed != null -> {
                    tokens += dashed.groupValues[1]
                    number = dashed.groupValues[2].toInt()
                }
                else -> tokens += word.take(30)
            }
        }
        val found = number ?: return null
        return CardCodeQuery(found, total, tokens.take(MAX_TOKENS))
    }

    /**
     * Cerca le carte che corrispondono al codice.
     *
     * Se una parola coincide con l'id di un set (nei cataloghi asiatici è la sigla stampata) o con la sua
     * sigla Cardmarket (es. "dx" per "ex8") cerca solo lì. Altrimenti parte da tutte le carte con quel numero
     * e restringe per totale del set, nome e sigla ufficiale; un filtro che non lascerebbe nessuna carta viene saltato.
     * @param client TcgdexClient - client TCGdex.
     * @param language Language - lingua del catalogo.
     * @param query CardCodeQuery - ricerca già scomposta con [parse].
     * @param aliases SetCodeAliases - sigle Cardmarket dei set.
     * @return List<CardBriefDto> - carte trovate (possono essere anche Allenatori o carte digitali).
     * @throws ApiException in caso di errore HTTP sulle richieste principali.
     */
    suspend fun find(
        client: TcgdexClient,
        language: Language,
        query: CardCodeQuery,
        aliases: SetCodeAliases = SetCodeAliases.bundled,
    ): List<CardBriefDto> {
        val sets = client.listSets(language)
        val named = query.tokens.flatMap { aliases.setsFor(it, language, sets) }.distinctBy { it.id }.take(3)
        if (named.isNotEmpty()) {
            var hits = named.flatMap { client.getSet(language, it.id).cards }.filter { matchesNumber(it.localId, query.number) }
            // Le altre parole possono essere il nome (es. "dx Rayquaza 107"): restringono se qualche carta le contiene.
            val words = query.tokens.filterNot { token -> named.any { aliases.matches(token, language, it.id, it.name) } }
            for (word in words) hits = hits.narrow { it.name.contains(word, ignoreCase = true) }
            if (hits.isNotEmpty()) return hits
        }

        var hits = client.cardsByLocalId(language, query.number.toString()).filter { matchesNumber(it.localId, query.number) }
        // Il set indicato non ha dato l'elenco delle carte: tra quelle con il numero valgono comunque le sue.
        val namedIds = named.map { it.id }.toSet()
        if (namedIds.isNotEmpty()) hits = hits.narrow { CatalogMapper.setIdFromCardId(it.id) in namedIds }
        query.total?.let { total ->
            val official = sets.associate { it.id to it.cardCount?.official }
            hits = hits.narrow { card -> official[CatalogMapper.setIdFromCardId(card.id)]?.let { it == total } ?: true }
        }
        val leftover = query.tokens.filterNot { token -> named.any { aliases.matches(token, language, it.id, it.name) } }
        for (token in leftover) hits = hits.narrow { it.name.contains(token, ignoreCase = true) }
        if (!language.asianSets && leftover.isNotEmpty()) {
            hits = narrowByAbbreviation(client, language, hits, leftover)
        }
        return hits
    }

    /**
     * Restringe ai set la cui sigla ufficiale o localizzata (es. "MEG") è tra le parole cercate.
     * Lo fa solo con pochi set candidati, per non moltiplicare le richieste.
     * @param client TcgdexClient - client TCGdex.
     * @param language Language - lingua del catalogo.
     * @param hits List<CardBriefDto> - carte candidate.
     * @param tokens List<String> - parole da confrontare con la sigla.
     * @return List<CardBriefDto> - carte dei set con sigla corrispondente, oppure [hits] se nessuna corrisponde.
     */
    private suspend fun narrowByAbbreviation(
        client: TcgdexClient,
        language: Language,
        hits: List<CardBriefDto>,
        tokens: List<String>,
    ): List<CardBriefDto> {
        val setIds = hits.mapNotNull { CatalogMapper.setIdFromCardId(it.id) }.distinct()
        if (setIds.size !in 2..MAX_SET_DETAILS) return hits
        val matching = setIds.filter { id ->
            val abbreviation = try {
                client.getSet(language, id).abbreviation
            } catch (e: ApiException) {
                null
            } catch (e: InvalidResponseException) {
                null
            } catch (e: IOException) {
                null
            }
            tokens.any { it.equals(abbreviation?.official, ignoreCase = true) || it.equals(abbreviation?.localized, ignoreCase = true) }
        }.toSet()
        return hits.narrow { CatalogMapper.setIdFromCardId(it.id) in matching }
    }

    /**
     * Confronta il numero di una carta ignorando gli zeri iniziali ("046" e "46" coincidono).
     * @param localId String - numero della carta secondo TCGdex.
     * @param number Int - numero cercato.
     * @return Boolean - true se il numero è solo cifre e vale [number].
     */
    fun matchesNumber(localId: String, number: Int): Boolean =
        localId.isNotEmpty() && localId.length <= 6 && localId.all { it.isDigit() } && localId.toInt() == number

    /**
     * Applica un filtro solo se lascia almeno un elemento.
     * @param predicate (T) -> Boolean - condizione da applicare.
     * @return List<T> - elementi filtrati, oppure la lista intera se il filtro la svuoterebbe.
     */
    private fun <T> List<T>.narrow(predicate: (T) -> Boolean): List<T> = filter(predicate).ifEmpty { this }
}
