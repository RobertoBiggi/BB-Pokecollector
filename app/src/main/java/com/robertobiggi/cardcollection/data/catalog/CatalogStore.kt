package com.robertobiggi.cardcollection.data.catalog

import android.content.Context
import com.robertobiggi.cardcollection.core.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Database delle carte installato sul telefono.
 * @property generatedAt data di generazione del file (ISO-8601).
 * @property languages lingue contenute.
 */
data class CatalogInfo(val generatedAt: String, val languages: Set<String>)

/** Esito dello scaricamento del database delle carte. */
sealed interface CatalogDownloadResult {
    /** Database installato. */
    data class Installed(val info: CatalogInfo) : CatalogDownloadResult

    /** Indirizzo non valido (serve HTTPS). */
    data object InvalidUrl : CatalogDownloadResult

    /** Rete assente, server non raggiungibile o file mancante. */
    data object NetworkError : CatalogDownloadResult

    /** Il file scaricato non è un database delle carte che l'app sa leggere. */
    data object InvalidFile : CatalogDownloadResult
}

/**
 * Tiene il file `cards.sqlite` nella memoria dell'app: lo scarica da un indirizzo scelto dall'utente, lo controlla e lo sostituisce
 * in un colpo solo, così un download interrotto o sbagliato non toglie quello che funzionava. Le letture passano da [use].
 *
 * @param context contesto dell'applicazione.
 * @param http client senza elenco di host consentiti: l'indirizzo lo sceglie l'utente (solo HTTPS).
 */
class CatalogStore(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.applicationContext.filesDir, "catalog")
    private val file = File(dir, FILE_NAME)
    private val mutex = Mutex()
    private var open: CatalogDb? = null

    private val _info = MutableStateFlow<CatalogInfo?>(null)

    /** Il database installato, oppure null se manca (o non è valido). Si aggiorna dopo ogni installazione. */
    val info: StateFlow<CatalogInfo?> = _info

    /** Legge le informazioni del file già presente, all'avvio dell'app. */
    suspend fun load() {
        _info.value = use { CatalogInfo(it.generatedAt, it.languages) }
    }

    /**
     * Esegue una lettura sul database installato.
     * @param block (CatalogDb) -> T - lettura; gira sul thread di I/O.
     * @return T? - il risultato, oppure null se il database non c'è o non è valido.
     */
    suspend fun <T> use(block: (CatalogDb) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = open ?: if (file.exists()) {
                try {
                    CatalogDb.open(file).also { open = it }
                } catch (e: InvalidCatalogException) {
                    null
                }
            } else {
                null
            }
            db?.let(block)
        }
    }

    /**
     * Scarica il database da un indirizzo, lo controlla e lo installa al posto del precedente.
     * @param url String - indirizzo HTTPS del file.
     * @param progress suspend (Long, Long?) -> Unit - byte scaricati e totali (se il server li dichiara).
     * @return CatalogDownloadResult - esito.
     */
    suspend fun download(url: String, progress: suspend (Long, Long?) -> Unit = { _, _ -> }): CatalogDownloadResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext CatalogDownloadResult.InvalidUrl
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            return@withContext CatalogDownloadResult.InvalidUrl
        }
        dir.mkdirs()
        val part = File(dir, "$FILE_NAME.part")
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CatalogDownloadResult.NetworkError
                val body = response.body ?: return@withContext CatalogDownloadResult.NetworkError
                val total = body.contentLength().takeIf { it > 0 }
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > MAX_BYTES) return@withContext CatalogDownloadResult.InvalidFile
                            out.write(buffer, 0, n)
                            progress(done, total)
                        }
                    }
                }
            }
            val info = try {
                CatalogDb.open(part).use { CatalogInfo(it.generatedAt, it.languages) }
            } catch (e: InvalidCatalogException) {
                return@withContext CatalogDownloadResult.InvalidFile
            }
            mutex.withLock {
                open?.close()
                open = null
                if (!part.renameTo(file)) return@withContext CatalogDownloadResult.NetworkError
            }
            _info.value = info
            CatalogDownloadResult.Installed(info)
        } catch (e: IOException) {
            CatalogDownloadResult.NetworkError
        } finally {
            part.delete()
        }
    }

    /** Le lingue del catalogo installato che l'app sa usare. */
    fun languages(): List<Language> = _info.value?.languages.orEmpty().mapNotNull { Language.fromCode(it) }

    private companion object {
        const val FILE_NAME = "cards.sqlite"
        const val BUFFER = 64 * 1024
        const val MAX_BYTES = 400L * 1024 * 1024
    }
}
