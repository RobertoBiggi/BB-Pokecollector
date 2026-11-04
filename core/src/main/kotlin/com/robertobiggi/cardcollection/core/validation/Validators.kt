package com.robertobiggi.cardcollection.core.validation

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/** Validation of inputs (user and remote responses) used across the app. */
object Validators {

    private val QUERY_PATTERN = Regex("^[\\p{L}\\p{N} '.\\-&]{2,40}$")
    private val REMOTE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,39}$")
    private val MANUAL_PRICE = Regex("^\\d{1,5}([.,]\\d{1,2})?$")
    private val UNSAFE_FILE_CHARS = Regex("[^a-z0-9_\\-]")

    /** Hosts from which card images may be downloaded. */
    val ALLOWED_IMAGE_HOSTS = setOf("assets.tcgdex.net")

    /** Hosts from which CardTrader images may be downloaded ("cardtrader.com" redirects to "www"). */
    val ALLOWED_CARDTRADER_IMAGE_HOSTS = setOf("www.cardtrader.com", "cardtrader.com")

    /** Maximum number of copies per variant. */
    const val MAX_QUANTITY = 999

    /** Maximum accepted price: above it the value is certainly wrong. */
    const val MAX_PRICE = 1_000_000.0

    /**
     * Checks a search text entered by the user (e.g. "Mawile", "クチート").
     * @param pattern String - text to validate.
     * @return Boolean - true if it has 2-40 characters among letters, digits, space, apostrophe, period, hyphen and &.
     */
    fun isValidQueryPattern(pattern: String): Boolean = QUERY_PATTERN.matches(pattern.trim())

    /** Maximum number of names in a comma-separated list. */
    const val MAX_QUERY_PATTERNS = 20

    /**
     * Splits a list of comma-separated names ("Moltres, Zapdos, Articuno"). Empty entries are ignored and duplicates
     * (case-insensitive) are kept only once.
     * @param raw String - text entered.
     * @return List<String>? - names, empty if the text is empty; null if an entry is not valid or there are more than [MAX_QUERY_PATTERNS].
     */
    fun parseQueryPatterns(raw: String): List<String>? {
        val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (names.size > MAX_QUERY_PATTERNS || !names.all { isValidQueryPattern(it) }) return null
        return names
    }

    /**
     * Checks an id received from TCGdex before using it in a URL or a file name.
     * @param id String - card or set id (e.g. "me02.5-144", "SM10a").
     * @return Boolean - true if it contains only safe characters and no ".." sequence.
     */
    fun isValidRemoteId(id: String): Boolean = REMOTE_ID.matches(id) && !id.contains("..")

    /**
     * Converts a manually entered price (accepts comma or period as decimal separator).
     * @param input String - text entered, e.g. "2,50".
     * @return Double? - price rounded to the cent, or null if not valid or not positive.
     */
    fun parseManualPrice(input: String): Double? {
        val text = input.trim()
        if (!MANUAL_PRICE.matches(text)) return null
        val value = BigDecimal(text.replace(',', '.')).setScale(2, RoundingMode.HALF_UP)
        return if (value.signum() > 0) value.toDouble() else null
    }

    /**
     * Reduces any text to a safe file name segment (no path separators).
     * @param input String - starting text, e.g. "en:me02.5-144".
     * @return String - segment with only [a-z0-9_-] characters, at most 80 characters long and never empty.
     */
    fun safeFileSegment(input: String): String {
        val cleaned = input.lowercase().replace(UNSAFE_FILE_CHARS, "_").take(80)
        return cleaned.ifEmpty { "_" }
    }

    /**
     * Checks that an image URL is HTTPS and belongs to an allowed host.
     * @param url String - URL to check.
     * @return Boolean - true if the URL is safe to download.
     */
    fun isAllowedImageUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host in ALLOWED_IMAGE_HOSTS
    }

    /**
     * Checks the URL of a CardTrader image.
     * @param url String - full address.
     * @return Boolean - true if it is HTTPS and the host is in [ALLOWED_CARDTRADER_IMAGE_HOSTS].
     */
    fun isAllowedCardTraderImageUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host in ALLOWED_CARDTRADER_IMAGE_HOSTS
    }

    /**
     * Limits a quantity to the allowed range.
     * @param quantity Int - requested quantity.
     * @return Int - quantity between 0 and [MAX_QUANTITY].
     */
    fun clampQuantity(quantity: Int): Int = quantity.coerceIn(0, MAX_QUANTITY)
}
