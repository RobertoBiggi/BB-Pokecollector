package com.robertobiggi.cardcollection.core.tcgdex

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
 * "MEG 046/132" o "SM10a-030". Le parole che non corrispondono a nulla vengono ignorate.
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
     * Confronta il numero di una carta ignorando gli zeri iniziali ("046" e "46" coincidono).
     * @param localId String - numero della carta secondo TCGdex.
     * @param number Int - numero cercato.
     * @return Boolean - true se il numero è solo cifre e vale [number].
     */
    fun matchesNumber(localId: String, number: Int): Boolean =
        localId.isNotEmpty() && localId.length <= 6 && localId.all { it.isDigit() } && localId.toInt() == number
}
