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
 * Pokémon price list that Cardmarket publishes every night for anyone (no site access or credentials).
 * The file (about 15 MB) is downloaded only on the first request, kept on disk for [maxAgeMs]
 * and read as a stream: only the eight prices of each product stay in memory.
 *
 * @param http shared OkHttp client.
 * @param cacheFile file where the downloaded price list is kept.
 * @param url address of the price list; changeable only for tests with MockWebServer.
 * @param maxAgeMs maximum age of the cached file before downloading it again.
 * @param now clock (replaceable in tests).
 * @param onDownload called right before downloading the file, to show it to the user (not when the cached file is valid).
 * @param onRead called right before reading the file (reading takes a few seconds).
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

    /** Prices of a product in the order of [FIELDS]; NaN where the price list has no value. */
    internal class Parsed(val createdAt: String?, val prices: Map<Int, DoubleArray>)

    /**
     * Looks up the prices of a Cardmarket product.
     * @param productId Int - Cardmarket product id (`idProduct`).
     * @return CardmarketPricingDto? - prices in euros, or null if the product is not in the price list.
     * @throws IOException if the price list is not cached and cannot be downloaded.
     */
    suspend fun lookup(productId: Int): CardmarketPricingDto? = mutex.withLock {
        failure?.let { throw it }
        val guide = parsed ?: try {
            load().also { parsed = it }
        } catch (e: IOException) {
            // One attempt per instance: offline, every card would retry the download.
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
                // An old price list is better than no price list.
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

        /** Fields of the price list that are read, in the order they have in [Parsed.prices]. */
        private val FIELDS = listOf("avg", "low", "trend", "avg30", "avg-holo", "low-holo", "trend-holo", "avg30-holo")
        private const val MAX_BYTES = 60L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val CREATED_AT = Regex(""""createdAt"\s*:\s*"([^"]*)"""")

        /**
         * Reads the price list as a stream: products are flat objects inside `priceGuides`, with no nested objects.
         * @param reader Reader - content of the file.
         * @return Parsed - creation date and prices by `idProduct`.
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
