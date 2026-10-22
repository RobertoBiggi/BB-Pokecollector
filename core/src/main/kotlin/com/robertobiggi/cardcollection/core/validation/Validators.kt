package com.robertobiggi.cardcollection.core.validation

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/** Validazioni degli input (utente e risposte remote) usate da tutta l'app. */
object Validators {

    private val QUERY_PATTERN = Regex("^[\\p{L}\\p{N} '.\\-&]{2,40}$")
    private val REMOTE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,39}$")
    private val MANUAL_PRICE = Regex("^\\d{1,5}([.,]\\d{1,2})?$")
    private val UNSAFE_FILE_CHARS = Regex("[^a-z0-9_\\-]")

    /** Host da cui è consentito scaricare le immagini delle carte. */
    val ALLOWED_IMAGE_HOSTS = setOf("assets.tcgdex.net")

    /** Host da cui è consentito scaricare le immagini di CardTrader ("cardtrader.com" rimanda a "www"). */
    val ALLOWED_CARDTRADER_IMAGE_HOSTS = setOf("www.cardtrader.com", "cardtrader.com")

    /** Quantità massima di copie per variante. */
    const val MAX_QUANTITY = 999

    /** Prezzo massimo accettato (EUR o USD): oltre è certamente un dato sbagliato. */
    const val MAX_PRICE = 1_000_000.0

    /**
     * Controlla un testo di ricerca inserito dall'utente (es. "Mawile", "クチート").
     * @param pattern String - testo da validare.
     * @return Boolean - true se ha 2-40 caratteri tra lettere, cifre, spazio, apostrofo, punto, trattino e &.
     */
    fun isValidQueryPattern(pattern: String): Boolean = QUERY_PATTERN.matches(pattern.trim())

    /** Numero massimo di nomi in un elenco separato da virgole. */
    const val MAX_QUERY_PATTERNS = 20

    /**
     * Scompone un elenco di nomi separati da virgole ("Moltres, Zapdos, Articuno"). Le voci vuote si ignorano e i doppioni
     * (senza distinguere maiuscole) si tengono una volta sola.
     * @param raw String - testo inserito.
     * @return List<String>? - nomi, vuota se il testo è vuoto; null se una voce non è valida o ce ne sono più di [MAX_QUERY_PATTERNS].
     */
    fun parseQueryPatterns(raw: String): List<String>? {
        val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (names.size > MAX_QUERY_PATTERNS || !names.all { isValidQueryPattern(it) }) return null
        return names
    }

    /**
     * Controlla un id ricevuto da TCGdex prima di usarlo in un URL o in un nome di file.
     * @param id String - id di carta o set (es. "me02.5-144", "SM10a").
     * @return Boolean - true se contiene solo caratteri sicuri e nessuna sequenza "..".
     */
    fun isValidRemoteId(id: String): Boolean = REMOTE_ID.matches(id) && !id.contains("..")

    /**
     * Converte un prezzo inserito a mano (accetta virgola o punto come separatore decimale).
     * @param input String - testo inserito, es. "2,50".
     * @return Double? - prezzo arrotondato al centesimo, oppure null se non valido o non positivo.
     */
    fun parseManualPrice(input: String): Double? {
        val text = input.trim()
        if (!MANUAL_PRICE.matches(text)) return null
        val value = BigDecimal(text.replace(',', '.')).setScale(2, RoundingMode.HALF_UP)
        return if (value.signum() > 0) value.toDouble() else null
    }

    /**
     * Riduce un testo qualsiasi a un segmento di nome file sicuro (niente separatori di percorso).
     * @param input String - testo di partenza, es. "en:me02.5-144".
     * @return String - segmento con soli caratteri [a-z0-9_-], lungo al massimo 80 caratteri e mai vuoto.
     */
    fun safeFileSegment(input: String): String {
        val cleaned = input.lowercase().replace(UNSAFE_FILE_CHARS, "_").take(80)
        return cleaned.ifEmpty { "_" }
    }

    /**
     * Controlla che un URL di immagine sia HTTPS e appartenga a un host consentito.
     * @param url String - URL da controllare.
     * @return Boolean - true se l'URL è sicuro da scaricare.
     */
    fun isAllowedImageUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host in ALLOWED_IMAGE_HOSTS
    }

    /**
     * Controlla l'URL di un'immagine di CardTrader.
     * @param url String - indirizzo completo.
     * @return Boolean - true se è HTTPS e l'host è in [ALLOWED_CARDTRADER_IMAGE_HOSTS].
     */
    fun isAllowedCardTraderImageUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host in ALLOWED_CARDTRADER_IMAGE_HOSTS
    }

    /**
     * Limita una quantità all'intervallo consentito.
     * @param quantity Int - quantità richiesta.
     * @return Int - quantità compresa tra 0 e [MAX_QUANTITY].
     */
    fun clampQuantity(quantity: Int): Int = quantity.coerceIn(0, MAX_QUANTITY)
}
