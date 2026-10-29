package com.robertobiggi.cardcollection.data.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.robertobiggi.cardcollection.core.validation.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Scarica e conserva in locale le immagini delle carte, così restano visibili offline.
 *
 * @param context contesto dell'applicazione.
 * @param http client OkHttp condiviso.
 */
class ImageStore(context: Context, private val http: OkHttpClient) {

    private val resolver: ContentResolver = context.contentResolver

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
     * Scarica l'immagine di una carta da CardTrader, la ricodifica in WebP e la salva in bassa e alta qualità.
     * @param cardId String - id locale della carta.
     * @param url String - indirizzo dell'immagine (host consentito, vedi [Validators.isAllowedCardTraderImageUrl]).
     * @return Pair<String, String>? - percorsi (bassa, alta qualità), oppure null se l'URL non è consentito o il download o la ricodifica falliscono.
     */
    suspend fun saveFromCardTrader(cardId: String, url: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!Validators.isAllowedCardTraderImageUrl(url)) return@withContext null
        val raw = try {
            downloadBytes(url, MAX_PICKED_BYTES)
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        val high = recode(raw, EXTERNAL_HIGH_SIDE) ?: return@withContext null
        val low = recode(raw, EXTERNAL_LOW_SIDE) ?: return@withContext null
        val highPath = write(cardId, Quality.HIGH, high) ?: return@withContext null
        val lowPath = write(cardId, Quality.LOW, low) ?: return@withContext null
        lowPath to highPath
    }

    /**
     * Scarica un'immagine in memoria, controllando codice HTTP, tipo di contenuto e dimensione massima.
     * @param url String - URL già validato.
     * @param limit Long - byte massimi accettati.
     * @return ByteArray? - contenuto, oppure null se supera il limite.
     * @throws IOException se la risposta non è un'immagine.
     */
    private fun downloadBytes(url: String, limit: Long): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Corpo vuoto")
            if (body.contentType()?.type != "image") throw IOException("Non è un'immagine")
            if (body.contentLength() > limit) return null
            return readAtMost(body.byteStream(), limit)
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
     * Converte un'immagine scelta dall'utente (galleria o file) in una WebP ridimensionata.
     * La ricodifica scarta i metadati (es. posizione GPS) e qualsiasi contenuto che non sia un'immagine.
     * @param uri Uri - immagine scelta con il selettore di sistema.
     * @return ByteArray? - immagine WebP con lato lungo al massimo [MANUAL_MAX_SIDE] px, oppure null
     *         se il file non è leggibile, non è un'immagine o è troppo grande.
     */
    suspend fun prepareManual(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val raw = try {
            resolver.openInputStream(uri)?.use { readAtMost(it, MAX_PICKED_BYTES) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return@withContext null
        recode(raw, MANUAL_MAX_SIDE)
    }

    /**
     * Ricodifica un'immagine in WebP ridimensionata: scarta metadati e tutto ciò che non è un'immagine.
     * @param raw ByteArray - immagine originale (JPEG, PNG, WebP…).
     * @param maxSide Int - lato lungo massimo in pixel.
     * @return ByteArray? - immagine WebP, oppure null se il contenuto non è decodificabile o il risultato supera [MAX_BYTES].
     */
    private fun recode(raw: ByteArray, maxSide: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else legacyWebp()
        val ok = bitmap.compress(format, MANUAL_QUALITY, out)
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return out.toByteArray().takeIf { ok && it.isNotEmpty() && it.size <= MAX_BYTES }
    }

    /**
     * Salva l'immagine scelta dall'utente con un nome nuovo (così la UI non mostra la vecchia dalla
     * cache) e cancella quella manuale precedente.
     * @param cardId String - id locale della carta.
     * @param bytes ByteArray - immagine preparata con [prepareManual].
     * @param previous String? - percorso attuale dell'immagine della carta.
     * @return String? - percorso assoluto del nuovo file, oppure null se il salvataggio fallisce.
     */
    suspend fun writeManual(cardId: String, bytes: ByteArray, previous: String?): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
        val target = File(root, "${Validators.safeFileSegment(cardId)}$MANUAL_MARKER${System.currentTimeMillis()}.webp")
        if (!target.canonicalPath.startsWith(rootPath)) throw SecurityException("Percorso immagine non valido")
        try {
            target.writeBytes(bytes)
        } catch (e: IOException) {
            target.delete()
            return@withContext null
        }
        deleteManual(previous)
        target.absolutePath
    }

    /**
     * Cancella un'immagine scelta a mano, solo se il percorso è dentro la cartella dell'app ed è un file manuale.
     * @param path String? - percorso salvato nel database.
     * @return Unit
     */
    suspend fun deleteManual(path: String?) = withContext(Dispatchers.IO) {
        if (path == null) return@withContext
        val file = File(path)
        if (file.canonicalPath.startsWith(rootPath) && file.name.contains(MANUAL_MARKER)) file.delete()
    }

    /**
     * Cancella le immagini salvate di carte che non servono più (anche quelle scelte a mano), solo dentro la cartella dell'app.
     * @param paths Collection<String?> - percorsi salvati nel database.
     * @return Unit
     */
    suspend fun deleteFiles(paths: Collection<String?>) = withContext(Dispatchers.IO) {
        for (path in paths.filterNotNull()) {
            val file = File(path)
            if (file.canonicalPath.startsWith(rootPath)) file.delete()
        }
    }

    /**
     * Indica se un percorso è un'immagine scelta a mano dall'utente.
     * @param path String? - percorso salvato nel database.
     * @return Boolean - true se è un file manuale.
     */
    fun isManual(path: String?): Boolean = path != null && File(path).name.contains(MANUAL_MARKER)

    /**
     * Controlla che un percorso salvato nel database corrisponda davvero a un'immagine sul disco.
     * Da chiamare fuori dal thread principale (accede al disco).
     * @param path String? - percorso assoluto salvato nel database.
     * @return Boolean - true se il file esiste, non è vuoto e sta nella cartella delle immagini dell'app.
     */
    fun isSaved(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        return try {
            file.canonicalPath.startsWith(rootPath) && file.isFile && file.length() > 0
        } catch (e: IOException) {
            false
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
            try {
                var written = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_BYTES) throw IOException("Immagine troppo grande")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (written == 0L || !tmp.renameTo(target)) throw IOException("Salvataggio non riuscito")
            } finally {
                // Dopo un rename riuscito il file temporaneo non esiste più; negli altri casi va tolto.
                tmp.delete()
            }
        }
    }

    /**
     * Legge uno stream fino a un limite di byte.
     * @param input InputStream - stream da leggere.
     * @param limit Long - byte massimi accettati.
     * @return ByteArray? - contenuto, oppure null se supera il limite.
     */
    private fun readAtMost(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Formato WebP per Android 8-10, dove WEBP_LOSSY non esiste ancora.
     * @return Bitmap.CompressFormat - formato WebP storico.
     */
    @Suppress("DEPRECATION")
    private fun legacyWebp(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP

    companion object {
        /** Dimensione massima di un'immagine (3 MB). */
        const val MAX_BYTES = 3L * 1024 * 1024

        /** Dimensione massima del file scelto dall'utente prima della ricodifica (20 MB). */
        private const val MAX_PICKED_BYTES = 20L * 1024 * 1024

        /** Lato lungo massimo dell'immagine scelta dall'utente , in pixel. */
        private const val MANUAL_MAX_SIDE = 1200

        private const val MANUAL_QUALITY = 88

        /** Lato lungo delle immagini scaricate da altre fonti: alta qualità per il dettaglio, bassa per la griglia. */
        private const val EXTERNAL_HIGH_SIDE = 900
        private const val EXTERNAL_LOW_SIDE = 400

        /** Parte del nome che distingue le immagini scelte a mano. */
        private const val MANUAL_MARKER = "-manual-"
    }
}
