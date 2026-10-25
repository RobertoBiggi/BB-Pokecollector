package com.robertobiggi.cardcollection.core.cardmarket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * Carta del catalogo Cardmarket: l'id prodotto, il nome senza attacchi (es. "Omastar Lv.53") e la sua forma
 * normalizzata per il confronto (es. "omastar", senza attacchi né livello). `metacard` è l'id che Cardmarket dà alla carta
 * in tutte le sue stampe (0 se ignoto): due prodotti dello stesso set con lo stesso valore sono due stampe della stessa carta.
 */
data class CatalogProduct(val idProduct: Int, val nameKey: String, val name: String = nameKey, val metacard: Int = 0)

/**
 * Catalogo delle carte Pokémon che Cardmarket pubblica per tutti (id prodotto, nome e set di ogni carta).
 * Non contiene il numero della carta. Come per il listino, il file (circa 14 MB) si scarica alla prima richiesta,
 * si conserva su disco e si legge a flusso.
 *
 * @param http client OkHttp condiviso.
 * @param cacheFile file in cui conservare il catalogo scaricato.
 * @param url indirizzo del catalogo; modificabile solo per i test con MockWebServer.
 * @param maxAgeMs età massima del file in cache; il catalogo cambia solo quando escono carte nuove.
 * @param now orologio (sostituibile nei test).
 * @param onDownload chiamata subito prima di scaricare il file, per mostrarlo all'utente (non quando il file in cache è valido).
 * @param onRead chiamata subito prima di leggere il file (la lettura richiede qualche secondo).
 */
class CardmarketCatalog(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val url: String = DEFAULT_URL,
    private val maxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    private val now: () -> Long = System::currentTimeMillis,
    private val onDownload: suspend () -> Unit = {},
    private val onRead: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var byExpansion: Map<Int, List<CatalogProduct>>? = null
    private var byProduct: Map<Int, Pair<Int, CatalogProduct>>? = null
    private var failure: IOException? = null

    /**
     * Elenca le carte di un set, nell'ordine di id (che segue l'ordine delle carte nel set).
     * @param idExpansion Int - id del set su Cardmarket.
     * @param onDownload suspend () -> Unit - chiamata prima di scaricare il catalogo, oltre a quella del costruttore.
     * @param onRead suspend () -> Unit - chiamata prima di leggere il catalogo, oltre a quella del costruttore.
     * @return List<CatalogProduct> - carte del set, vuoto se il set non è nel catalogo.
     * @throws IOException se il catalogo non è in cache e non si riesce a scaricarlo.
     */
    suspend fun productsOf(idExpansion: Int, onDownload: suspend () -> Unit = {}, onRead: suspend () -> Unit = {}): List<CatalogProduct> = mutex.withLock {
        failure?.let { throw it }
        val all = byExpansion ?: try {
            load(onDownload, onRead).also { byExpansion = it }
        } catch (e: IOException) {
            // Un solo tentativo per istanza: offline, ogni carta riproverebbe il download.
            failure = e
            throw e
        }
        all[idExpansion].orEmpty()
    }

    /**
     * Trova una carta dal suo id prodotto.
     * @param idProduct Int - id prodotto Cardmarket.
     * @param onDownload suspend () -> Unit - chiamata prima di scaricare il catalogo, oltre a quella del costruttore.
     * @param onRead suspend () -> Unit - chiamata prima di leggere il catalogo, oltre a quella del costruttore.
     * @return Pair<Int, CatalogProduct>? - `idExpansion` e prodotto, oppure null se il prodotto non è nel catalogo.
     * @throws IOException se il catalogo non è in cache e non si riesce a scaricarlo.
     */
    suspend fun locate(idProduct: Int, onDownload: suspend () -> Unit = {}, onRead: suspend () -> Unit = {}): Pair<Int, CatalogProduct>? {
        productsOf(0, onDownload, onRead)
        return mutex.withLock {
            val index = byProduct ?: HashMap<Int, Pair<Int, CatalogProduct>>().also { map ->
                byExpansion.orEmpty().forEach { (expansion, products) -> products.forEach { map[it.idProduct] = expansion to it } }
                byProduct = map
            }
            index[idProduct]
        }
    }

    private suspend fun load(extraDownload: suspend () -> Unit, extraRead: suspend () -> Unit): Map<Int, List<CatalogProduct>> = withContext(Dispatchers.IO) {
        val fresh = cacheFile.exists() && now() - cacheFile.lastModified() < maxAgeMs
        if (!fresh) {
            try {
                onDownload()
                extraDownload()
                download()
            } catch (e: IOException) {
                if (!cacheFile.exists()) throw e
            }
        }
        onRead()
        extraRead()
        cacheFile.bufferedReader(Charsets.UTF_8).use { parse(it) }
    }

    private fun download() {
        val patient = http.newBuilder().callTimeout(5, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build()
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            patient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Catalogo Cardmarket: HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("Catalogo Cardmarket vuoto")
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("Catalogo Cardmarket troppo grande")
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(cacheFile)) throw IOException("Catalogo Cardmarket non salvato")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_URL = "https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json"
        private const val MAX_BYTES = 60L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val LEVEL = Regex("""\s+Lv\.\s*\d+.*""")
        private val ONLINE_CODE = Regex("""\bcode card\b""", RegexOption.IGNORE_CASE)

        /**
         * Nome con cui cercare la carta su Cardmarket: quello inglese del catalogo, senza il livello ("Omastar Lv.53" → "Omastar").
         * @param name String - nome del prodotto, senza attacchi.
         * @return String - nome da cercare.
         */
        fun searchName(name: String): String = name.replace(LEVEL, "").trim()

        /**
         * Normalizza il nome di una carta per il confronto: toglie gli attacchi tra parentesi quadre e il livello,
         * poi tiene solo lettere e cifre in minuscolo ("Omastar Lv.53 [Primal Swirl | Send Back]" → "omastar").
         * @param name String - nome della carta.
         * @return String - chiave di confronto.
         */
        fun nameKey(name: String): String =
            name.substringBefore(" [").replace(LEVEL, "").lowercase().replace('♀', 'f').replace('♂', 'm').filter { it.isLetterOrDigit() }

        /**
         * Legge il catalogo a flusso. I nomi possono contenere virgole, parentesi graffe e virgolette, quindi
         * gli oggetti si separano tenendo conto delle stringhe, e ognuno è letto come JSON.
         * @param reader Reader - contenuto del file.
         * @return Map<Int, List<CatalogProduct>> - carte per `idExpansion`, in ordine di id.
         */
        internal fun parse(reader: Reader): Map<Int, List<CatalogProduct>> {
            val result = HashMap<Int, MutableList<CatalogProduct>>()
            val obj = StringBuilder()
            val buffer = CharArray(BUFFER_SIZE)
            var inArray = false
            var depth = 0
            var inString = false
            var escaped = false
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buffer[i]
                    if (!inArray) {
                        if (c == '[') inArray = true
                        continue
                    }
                    if (depth == 0) {
                        if (c == '{') {
                            depth = 1
                            obj.setLength(0)
                            obj.append(c)
                        }
                        continue
                    }
                    obj.append(c)
                    if (inString) {
                        when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '"' -> inString = false
                        }
                    } else when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> if (--depth == 0) readProduct(obj.toString(), result)
                    }
                }
            }
            return result.mapValues { (_, list) -> list.sortedBy { it.idProduct } }
        }

        private fun readProduct(text: String, into: MutableMap<Int, MutableList<CatalogProduct>>) {
            val o = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return
            val id = o["idProduct"]?.jsonPrimitive?.int ?: return
            val expansion = o["idExpansion"]?.jsonPrimitive?.int ?: return
            val name = o["name"]?.jsonPrimitive?.content ?: return
            // Le carte con codice per il gioco online non sono carte fisiche da collezionare.
            if (ONLINE_CODE.containsMatchIn(name)) return
            val metacard = o["idMetacard"]?.jsonPrimitive?.intOrNull ?: 0
            into.getOrPut(expansion) { ArrayList() } += CatalogProduct(id, nameKey(name), name.substringBefore(" [").trim(), metacard)
        }
    }
}
