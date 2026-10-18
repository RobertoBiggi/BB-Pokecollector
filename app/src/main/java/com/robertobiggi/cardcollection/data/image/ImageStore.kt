package com.robertobiggi.cardcollection.data.image

import android.content.Context
import com.robertobiggi.cardcollection.core.validation.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Scarica e conserva in locale le immagini delle carte, così restano visibili offline.
 *
 * @param context contesto dell'applicazione.
 * @param http client OkHttp condiviso.
 */
class ImageStore(context: Context, private val http: OkHttpClient) {

    private val root: File = File(context.filesDir, "cards").apply { mkdirs() }
    private val rootPath: String = root.canonicalPath + File.separator

    /** Qualità dell'immagine TCGdex: "low" per la griglia, "high" per il dettaglio. */
    enum class Quality(val suffix: String) { LOW("low"), HIGH("high") }

    /**
     * Restituisce il file locale di un'immagine, verificando che resti dentro la cartella dell'app.
     * @param cardId String - id locale della carta (es. "en:me01-094").
     * @param quality Quality - qualità dell'immagine.
     * @return File - percorso del file (può non esistere ancora).
     * @throws SecurityException se il percorso calcolato esce dalla cartella delle immagini.
     */
    fun fileFor(cardId: String, quality: Quality): File {
        val file = File(root, "${Validators.safeFileSegment(cardId)}-${quality.suffix}.webp")
        if (!file.canonicalPath.startsWith(rootPath)) throw SecurityException("Percorso immagine non valido")
        return file
    }

    /**
     * Scarica un'immagine se non è già presente.
     * @param cardId String - id locale della carta.
     * @param imageBaseUrl String - URL base TCGdex dell'immagine (senza qualità ed estensione).
     * @param quality Quality - qualità da scaricare.
     * @return String? - percorso assoluto del file salvato, oppure null se l'URL non è consentito o il download fallisce.
     */
    suspend fun ensure(cardId: String, imageBaseUrl: String, quality: Quality): String? = withContext(Dispatchers.IO) {
        val target = fileFor(cardId, quality)
        if (target.isFile && target.length() > 0) return@withContext target.absolutePath
        val url = "$imageBaseUrl/${quality.suffix}.webp"
        if (!Validators.isAllowedImageUrl(url)) return@withContext null
        try {
            download(url, target)
            target.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Salva un'immagine ricevuta da un file importato (già validata come WebP, PNG o JPEG).
     * @param cardId String - id locale della carta.
     * @param quality Quality - qualità dell'immagine.
     * @param bytes ByteArray - contenuto dell'immagine.
     * @return String? - percorso assoluto del file salvato, oppure null se il salvataggio fallisce.
     */
    suspend fun write(cardId: String, quality: Quality, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
        val target = fileFor(cardId, quality)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext null
            }
            target.absolutePath
        } catch (e: IOException) {
            tmp.delete()
            null
        }
    }

    /**
     * Legge un'immagine salvata, solo se si trova nella cartella delle immagini dell'app.
     * @param path String? - percorso assoluto salvato nel database.
     * @return ByteArray? - contenuto del file, oppure null se assente, fuori cartella o troppo grande.
     */
    suspend fun read(path: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (path == null) return@withContext null
        val file = File(path)
        if (!file.canonicalPath.startsWith(rootPath) || !file.isFile || file.length() > MAX_BYTES) return@withContext null
        try {
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Scarica un file controllando codice HTTP, tipo di contenuto e dimensione massima,
     * scrivendo prima su un file temporaneo e poi rinominandolo.
     * @param url String - URL già validato.
     * @param target File - destinazione finale.
     * @return Unit
     * @throws IOException se la risposta non è un'immagine valida o supera [MAX_BYTES].
     */
    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Corpo vuoto")
            val type = body.contentType()
            if (type == null || type.type != "image") throw IOException("Non è un'immagine")
            if (body.contentLength() > MAX_BYTES) throw IOException("Immagine troppo grande")
            val tmp = File(target.parentFile, target.name + ".tmp")
            var written = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_BYTES) {
                            output.close()
                            tmp.delete()
                            throw IOException("Immagine troppo grande")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (written == 0L || !tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Salvataggio non riuscito")
            }
        }
    }

    companion object {
        /** Dimensione massima di un'immagine (3 MB). */
        const val MAX_BYTES = 3L * 1024 * 1024
    }
}
