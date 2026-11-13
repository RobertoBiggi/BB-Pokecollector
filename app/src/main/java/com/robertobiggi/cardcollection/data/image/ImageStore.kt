package com.robertobiggi.cardcollection.data.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.robertobiggi.cardcollection.core.image.ImageFileNames
import com.robertobiggi.cardcollection.core.validation.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Downloads and keeps card images locally, so they stay visible offline.
 *
 * @param context application context.
 * @param http shared OkHttp client.
 */
/** Failed download attempt: [reason] is the HTTP code or the type of network error, [willRetry] says whether another will follow. */
data class DownloadIssue(val reason: String, val willRetry: Boolean)

class ImageStore(context: Context, private val http: OkHttpClient) {

    private val resolver: ContentResolver = context.contentResolver

    private val root: File = File(context.filesDir, "cards").apply { mkdirs() }
    private val rootPath: String = root.canonicalPath + File.separator

    /** Quality of the TCGdex image: "low" for the grid, "high" for the detail. */
    enum class Quality(val suffix: String) { LOW("low"), HIGH("high") }

    /**
     * Returns the local file of an image, checking that it stays inside the app's folder.
     * @param cardId String - local id of the card (e.g. "en:me01-094").
     * @param quality Quality - image quality.
     * @param source String? - id of the source the image comes from; it goes in the file name (see [ImageFileNames]).
     * @return File - path of the file (may not exist yet).
     * @throws SecurityException if the computed path leaves the images folder.
     */
    fun fileFor(cardId: String, quality: Quality, source: String? = null): File {
        val file = File(root, ImageFileNames.of(Validators.safeFileSegment(cardId), quality.suffix, source))
        if (!file.canonicalPath.startsWith(rootPath)) throw SecurityException("Percorso immagine non valido")
        return file
    }

    /**
     * Downloads an image if it is not already present.
     * @param cardId String - local id of the card.
     * @param imageBaseUrl String - TCGdex base URL of the image (without quality and extension).
     * @param quality Quality - quality to download.
     * @param onIssue (DownloadIssue) -> Unit - called on every failed attempt, with the reason and whether it will retry.
     * @return String? - absolute path of the saved file, or null if the URL is not allowed or the download fails.
     */
    suspend fun ensure(cardId: String, imageBaseUrl: String, quality: Quality, onIssue: (DownloadIssue) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val target = fileFor(cardId, quality, TCGDEX_SOURCE)
        if (target.isFile && target.length() > 0) return@withContext target.absolutePath
        val url = "$imageBaseUrl/${quality.suffix}.webp"
        if (!Validators.isAllowedImageUrl(url)) return@withContext null
        // A temporary block (429, 5xx) or a timeout must not leave the card without an image: it retries with increasing waits.
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            try {
                download(url, target)
                return@withContext target.absolutePath
            } catch (e: HttpStatusException) {
                val again = e.retryable && attempt < DOWNLOAD_ATTEMPTS
                onIssue(DownloadIssue("HTTP ${e.code}", again))
                if (!again) return@withContext null
                delay(e.retryAfterMs ?: (RETRY_BASE_MS * attempt))
            } catch (e: IOException) {
                val again = attempt < DOWNLOAD_ATTEMPTS
                onIssue(DownloadIssue(listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ").take(ISSUE_MAX_CHARS), again))
                if (!again) return@withContext null
                delay(RETRY_BASE_MS * attempt)
            }
        }
        null
    }

    /** Unsuccessful HTTP response; [retryAfterMs] is the wait requested by the server (Retry-After header), at most [MAX_RETRY_AFTER_MS]. */
    private class HttpStatusException(val code: Int, val retryAfterMs: Long?) : IOException("HTTP $code") {
        val retryable: Boolean get() = code == 429 || code == 408 || code >= 500
    }

    /**
     * Downloads the image of a card from CardTrader, re-encodes it as WebP and saves it in low and high quality.
     * @param cardId String - local id of the card.
     * @param url String - address of the image (allowed host, see [Validators.isAllowedCardTraderImageUrl]).
     * @return Pair<String, String>? - paths (low, high quality), or null if the URL is not allowed or the download or re-encoding fails.
     */
    suspend fun saveFromCardTrader(cardId: String, url: String): Pair<String, String>? =
        saveFromExternal(cardId, url, CARDTRADER_SOURCE, Validators::isAllowedCardTraderImageUrl)

    /**
     * Downloads the image of a card from an external source, re-encodes it as WebP and saves it in low and high quality.
     * @param cardId String - local id of the card.
     * @param url String - address of the image.
     * @param source String - id of the source, written in the file names so the app can say where the image comes from.
     * @param isAllowed (String) -> Boolean - tells whether the address is on the source's allowed host (checked before any request).
     * @return Pair<String, String>? - paths (low, high quality), or null if the URL is not allowed or the download or re-encoding fails.
     */
    suspend fun saveFromExternal(cardId: String, url: String, source: String, isAllowed: (String) -> Boolean): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!isAllowed(url)) return@withContext null
        val raw = try {
            downloadBytes(url, MAX_PICKED_BYTES)
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        val high = recode(raw, EXTERNAL_HIGH_SIDE) ?: return@withContext null
        val low = recode(raw, EXTERNAL_LOW_SIDE) ?: return@withContext null
        val highPath = write(cardId, Quality.HIGH, high, source) ?: return@withContext null
        val lowPath = write(cardId, Quality.LOW, low, source) ?: return@withContext null
        lowPath to highPath
    }

    /**
     * Downloads an image only to show it (nothing is saved).
     * @param url String - address of the image.
     * @param isAllowed (String) -> Boolean - tells whether the address is on the source's allowed host (checked before any request).
     * @return ByteArray? - the image, or null if the address is not allowed or the download fails.
     */
    suspend fun preview(url: String, isAllowed: (String) -> Boolean): ByteArray? = withContext(Dispatchers.IO) {
        if (!isAllowed(url)) return@withContext null
        try {
            downloadBytes(url, MAX_PICKED_BYTES)
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Downloads an image into memory, checking HTTP code, content type and maximum size.
     * @param url String - already validated URL.
     * @param limit Long - maximum bytes accepted.
     * @return ByteArray? - content, or null if it exceeds the limit.
     * @throws IOException if the response is not an image.
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
     * Saves an image received from an imported file (already validated as WebP, PNG or JPEG).
     * @param cardId String - local id of the card.
     * @param quality Quality - image quality.
     * @param bytes ByteArray - content of the image.
     * @param source String? - id of the source for the file name, or null when unknown (imported from a file).
     * @return String? - absolute path of the saved file, or null if saving fails.
     */
    suspend fun write(cardId: String, quality: Quality, bytes: ByteArray, source: String? = null): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
        val target = fileFor(cardId, quality, source)
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
     * Converts an image chosen by the user (gallery or file) into a resized WebP.
     * Re-encoding drops the metadata (e.g. GPS position) and any content that is not an image.
     * @param uri Uri - image chosen with the system picker.
     * @return ByteArray? - WebP image with long side at most [MANUAL_MAX_SIDE] px, or null
     *         if the file is not readable, is not an image or is too large.
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
     * Re-encodes an image as a resized WebP: drops metadata and anything that is not an image.
     * @param raw ByteArray - original image (JPEG, PNG, WebP…).
     * @param maxSide Int - maximum long side in pixels.
     * @return ByteArray? - WebP image, or null if the content cannot be decoded or the result exceeds [MAX_BYTES].
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
     * Saves the image chosen by the user with a new name (so the UI does not show the old one from the
     * cache) and deletes the previous manual one.
     * @param cardId String - local id of the card.
     * @param bytes ByteArray - image prepared with [prepareManual].
     * @param previous String? - current path of the card's image.
     * @return String? - absolute path of the new file, or null if saving fails.
     */
    suspend fun writeManual(cardId: String, bytes: ByteArray, previous: String?): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
        val target = File(root, "${Validators.safeFileSegment(cardId)}$MANUAL_MARKER${System.currentTimeMillis()}.webp")  // read back by ImageFileNames.sourceOf
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
     * Deletes a manually chosen image, only if the path is inside the app's folder and is a manual file.
     * @param path String? - path saved in the database.
     * @return Unit
     */
    suspend fun deleteManual(path: String?) = withContext(Dispatchers.IO) {
        if (path == null) return@withContext
        val file = File(path)
        if (file.canonicalPath.startsWith(rootPath) && file.name.contains(MANUAL_MARKER)) file.delete()
    }

    /**
     * Deletes the saved images of cards that are no longer needed (also manually chosen ones), only inside the app's folder.
     * @param paths Collection<String?> - paths saved in the database.
     * @return Unit
     */
    suspend fun deleteFiles(paths: Collection<String?>) = withContext(Dispatchers.IO) {
        for (path in paths.filterNotNull()) {
            val file = File(path)
            if (file.canonicalPath.startsWith(rootPath)) file.delete()
        }
    }

    /**
     * Checks that a path saved in the database really matches an image on disk.
     * To be called off the main thread (accesses the disk).
     * @param path String? - absolute path saved in the database.
     * @return Boolean - true if the file exists, is not empty and is in the app's images folder.
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
     * Reads a saved image, only if it is in the app's images folder.
     * @param path String? - absolute path saved in the database.
     * @return ByteArray? - content of the file, or null if absent, outside the folder or too large.
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
     * Downloads a file checking HTTP code, content type and maximum size,
     * writing first to a temporary file and then renaming it.
     * @param url String - already validated URL.
     * @param target File - final destination.
     * @return Unit
     * @throws IOException if the response is not a valid image or exceeds [MAX_BYTES].
     */
    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val wait = response.header("Retry-After")?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(0, MAX_RETRY_AFTER_MS) }
                throw HttpStatusException(response.code, wait)
            }
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
                // After a successful rename the temporary file no longer exists; in the other cases it must be removed.
                tmp.delete()
            }
        }
    }

    /**
     * Reads a stream up to a byte limit.
     * @param input InputStream - stream to read.
     * @param limit Long - maximum bytes accepted.
     * @return ByteArray? - content, or null if it exceeds the limit.
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
     * WebP format for Android 8-10, where WEBP_LOSSY does not exist yet.
     * @return Bitmap.CompressFormat - legacy WebP format.
     */
    @Suppress("DEPRECATION")
    private fun legacyWebp(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP

    companion object {
        /** Maximum size of an image (3 MB). */
        const val MAX_BYTES = 3L * 1024 * 1024
        private const val DOWNLOAD_ATTEMPTS = 4
        private const val ISSUE_MAX_CHARS = 80
        private const val RETRY_BASE_MS = 1_500L
        private const val MAX_RETRY_AFTER_MS = 15_000L

        /** Maximum size of the file chosen by the user before re-encoding (20 MB). */
        private const val MAX_PICKED_BYTES = 20L * 1024 * 1024

        /** Maximum long side of the image chosen by the user, in pixels. */
        private const val MANUAL_MAX_SIDE = 1200

        private const val MANUAL_QUALITY = 88

        /** Long side of images downloaded from other sources: high quality for the detail, low for the grid. */
        private const val EXTERNAL_HIGH_SIDE = 900
        private const val EXTERNAL_LOW_SIDE = 400

        /** Ids of the two sources that are not in the card database (the others are the ids of the `CatalogImageSource`s). */
        const val TCGDEX_SOURCE = "tcgdex"
        const val CARDTRADER_SOURCE = "cardtrader"

        /** Part of the name that distinguishes manually chosen images. */
        private const val MANUAL_MARKER = "-manual-"
    }
}
