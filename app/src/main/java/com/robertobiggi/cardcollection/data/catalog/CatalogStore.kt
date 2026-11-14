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
 * Card database installed on the phone.
 * @property generatedAt generation date of the file (ISO-8601).
 * @property languages languages contained.
 */
data class CatalogInfo(val generatedAt: String, val languages: Set<String>)

/** Outcome of downloading the card database. */
sealed interface CatalogDownloadResult {
    /** Database installed. */
    data class Installed(val info: CatalogInfo) : CatalogDownloadResult

    /** Invalid address (HTTPS required). */
    data object InvalidUrl : CatalogDownloadResult

    /** No network, server unreachable or file missing. */
    data object NetworkError : CatalogDownloadResult

    /** The downloaded file is not a card database the app can read. */
    data object InvalidFile : CatalogDownloadResult
}

/**
 * Keeps the `cards.sqlite` file in the app's storage: downloads it from an address (the GitHub release), checks it and replaces it in one go, so an interrupted or wrong download does not remove what was working.
 * Reads go through [use]. The file is downloaded automatically at first run (see MainViewModel).
 *
 * @param context application context.
 * @param http client for the download (HTTPS only).
 */
class CatalogStore(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.applicationContext.filesDir, "catalog")
    private val file = File(dir, FILE_NAME)
    private val mutex = Mutex()
    private var open: CatalogDb? = null

    private val _info = MutableStateFlow<CatalogInfo?>(null)

    /** The installed database, or null if missing (or not valid). Updated after every installation. */
    val info: StateFlow<CatalogInfo?> = _info

    /** Reads the information of the installed file, if any, at app start. */
    suspend fun load() {
        _info.value = use { CatalogInfo(it.generatedAt, it.languages) }
    }

    /**
     * Runs a read on the installed database.
     * @param block (CatalogDb) -> T - read; runs on the I/O thread.
     * @return T? - the result, or null if the database does not exist or is not valid.
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
     * Downloads the database from an address, checks it and installs it in place of the previous one.
     * @param url String - HTTPS address of the file.
     * @param progress suspend (Long, Long?) -> Unit - bytes downloaded and total (if the server declares them).
     * @return CatalogDownloadResult - outcome.
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

    /** The languages of the installed catalog that the app can use. */
    fun languages(): List<Language> = _info.value?.languages.orEmpty().mapNotNull { Language.fromCode(it) }

    private companion object {
        const val FILE_NAME = "cards.sqlite"
        const val BUFFER = 64 * 1024
        const val MAX_BYTES = 400L * 1024 * 1024
    }
}
