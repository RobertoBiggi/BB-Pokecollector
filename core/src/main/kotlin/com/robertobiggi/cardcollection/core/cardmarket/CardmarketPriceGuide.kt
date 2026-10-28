package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.price.CardmarketPricingDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * Listino prezzi Pokémon che Cardmarket pubblica ogni notte per chiunque (nessun accesso al sito né credenziali).
 * Il file (circa 15 MB) viene scaricato solo alla prima richiesta, conservato su disco per [maxAgeMs]
 * e letto a flusso: in memoria restano solo gli otto prezzi di ogni prodotto.
 *
 * @param http client OkHttp condiviso.
 * @param cacheFile file in cui conservare il listino scaricato.
 * @param url indirizzo del listino; modificabile solo per i test con MockWebServer.
 * @param maxAgeMs età massima del file in cache prima di riscaricarlo.
 * @param now orologio (sostituibile nei test).
 * @param onDownload chiamata subito prima di scaricare il file, per mostrarlo all'utente (non quando il file in cache è valido).
 * @param onRead chiamata subito prima di leggere il file (la lettura richiede qualche secondo).
 */
class CardmarketPriceGuide(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val url: String = DEFAULT_URL,
    private val maxAgeMs: Long = TimeUnit.HOURS.toMillis(20),
    private val now: () -> Long = System::currentTimeMillis,
    private val onDownload: suspend () -> Unit = {},
    private val onRead: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var parsed: Parsed? = null
    private var failure: IOException? = null

    /** Prezzi di un prodotto nell'ordine di [FIELDS]; NaN dove il listino non ha il dato. */
    internal class Parsed(val createdAt: String?, val prices: Map<Int, DoubleArray>)

    /**
     * Cerca i prezzi di un prodotto Cardmarket.
     * @param productId Int - id prodotto Cardmarket (`idProduct`).
     * @return CardmarketPricingDto? - prezzi in euro, oppure null se il prodotto non è nel listino.
     * @throws IOException se il listino non è in cache e non si riesce a scaricarlo.
     */
    suspend fun lookup(productId: Int): CardmarketPricingDto? = mutex.withLock {
        failure?.let { throw it }
        val guide = parsed ?: try {
            load().also { parsed = it }
        } catch (e: IOException) {
            // Un solo tentativo per istanza: offline, ogni carta riproverebbe il download.
            failure = e
            throw e
        }
        val p = guide.prices[productId] ?: return null
        fun v(field: Int): Double? = p[field].takeUnless { it.isNaN() }
        CardmarketPricingDto(
            updated = guide.createdAt, unit = "EUR",
            avg = v(0), low = v(1), trend = v(2), avg30 = v(3),
            avgHolo = v(4), lowHolo = v(5), trendHolo = v(6), avg30Holo = v(7),
        )
    }

    private suspend fun load(): Parsed = withContext(Dispatchers.IO) {
        val fresh = cacheFile.exists() && now() - cacheFile.lastModified() < maxAgeMs
        if (!fresh) {
            try {
                onDownload()
                download()
            } catch (e: IOException) {
                // Un listino vecchio è meglio di nessun listino.
                if (!cacheFile.exists()) throw e
            }
        }
        onRead()
        cacheFile.bufferedReader(Charsets.UTF_8).use { parse(it) }
    }

    private fun download() {
        val patient = http.newBuilder().callTimeout(5, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build()
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            patient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Listino Cardmarket: HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("Listino Cardmarket vuoto")
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("Listino Cardmarket troppo grande")
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(cacheFile)) throw IOException("Listino Cardmarket non salvato")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_URL = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json"

        /** Campi del listino letti, nell'ordine in cui stanno in [Parsed.prices]. */
        private val FIELDS = listOf("avg", "low", "trend", "avg30", "avg-holo", "low-holo", "trend-holo", "avg30-holo")
        private const val MAX_BYTES = 60L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val CREATED_AT = Regex(""""createdAt"\s*:\s*"([^"]*)"""")

        /**
         * Legge il listino a flusso: i prodotti sono oggetti piatti dentro `priceGuides`, senza oggetti annidati.
         * @param reader Reader - contenuto del file.
         * @return Parsed - data di creazione e prezzi per `idProduct`.
         */
        internal fun parse(reader: Reader): Parsed {
            val prices = HashMap<Int, DoubleArray>()
            var createdAt: String? = null
            val head = StringBuilder()
            var inArray = false
            var inObject = false
            val obj = StringBuilder()
            val buffer = CharArray(BUFFER_SIZE)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buffer[i]
                    when {
                        !inArray -> {
                            if (head.length < HEAD_LIMIT) head.append(c)
                            if (c == '[') {
                                inArray = true
                                createdAt = CREATED_AT.find(head)?.groupValues?.get(1)
                            }
                        }
                        inObject -> if (c == '}') {
                            inObject = false
                            readProduct(obj.toString(), prices)
                        } else {
                            obj.append(c)
                        }
                        c == '{' -> {
                            inObject = true
                            obj.setLength(0)
                        }
                    }
                }
            }
            return Parsed(createdAt, prices)
        }

        private const val HEAD_LIMIT = 4096

        private fun readProduct(body: String, into: MutableMap<Int, DoubleArray>) {
            var id: Int? = null
            val values = DoubleArray(FIELDS.size) { Double.NaN }
            for (part in body.split(',')) {
                val colon = part.indexOf(':')
                if (colon < 2) continue
                val key = part.substring(0, colon).trim().trim('"')
                val raw = part.substring(colon + 1).trim()
                if (key == "idProduct") {
                    id = raw.toIntOrNull()
                } else {
                    val index = FIELDS.indexOf(key)
                    if (index >= 0) values[index] = raw.toDoubleOrNull() ?: Double.NaN
                }
            }
            if (id != null) into[id] = values
        }
    }
}
