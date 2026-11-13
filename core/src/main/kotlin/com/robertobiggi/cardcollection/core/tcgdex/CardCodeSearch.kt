package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import java.io.IOException

/**
 * Search broken down from the code printed on the card.
 *
 * @param number Int - number of the card in the set (e.g. 117 from "117/080").
 * @param total Int? - official number of cards in the set, if given (e.g. 80 from "117/080").
 * @param tokens List<String> - other words: set code (e.g. "M3"), part of the name or text
 *        to ignore (e.g. the rarity "MUR").
 */
data class CardCodeQuery(val number: Int, val total: Int?, val tokens: List<String>)

/**
 * Finds TCGdex cards from the code printed at the bottom left, e.g. "M3 117/080", "117/080 MUR",
 * "MEG 046/132" or "SM10a-030". Words that match nothing are ignored.
 */
object CardCodeSearch {

    private val ALLOWED = Regex("^[\\p{L}\\p{N} ./'\\-]{1,40}$")
    private val NUMBER = Regex("^(\\d{1,4})(?:/(\\d{1,4}))?$")
    private val SET_DASH_NUMBER = Regex("^([A-Za-z][A-Za-z0-9.\\-]{0,15})-(\\d{1,4})$")

    /** Maximum number of additional words considered. */
    private const val MAX_TOKENS = 4

    /**
     * Recognizes a text that contains a card number.
     * @param text String - text entered by the user.
     * @return CardCodeQuery? - broken-down search, or null if the text does not contain a card
     *         number (in that case it must be searched by name) or has disallowed characters.
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
}
