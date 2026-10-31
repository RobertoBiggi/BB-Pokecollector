package com.robertobiggi.cardcollection.core.backup

import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.validation.Validators
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Search saved in the file. [kind] is the value of [QueryKind] ("name", "set_base", "set_master"):
 * absent in earlier files, which only contain searches by name.
 */
@Serializable
data class BackupQuery(val language: String, val pattern: String, val enabled: Boolean = true, val kind: String = QueryKind.NAME.code)

/** State of a variant in the collection. */
@Serializable
data class BackupEntry(
    val variantId: String,
    val quantity: Int = 1,
    val condition: String = "NM",
    val wanted: Boolean = true,
    val notes: String? = null,
)

/** Manual price in the format of first-version backups (schema 1). */
@Serializable
data class BackupManualPrice(val variantId: String, val eur: Double, val fetchedAt: Long)

/**
 * Name, language and searches of the exported collection. [language] is the value of [CollectionLanguage]
 * (absent in files from earlier versions: in that case it is inferred from the cards).
 */
@Serializable
data class CollectionInfo(val name: String? = null, val queries: List<BackupQuery> = emptyList(), val language: String? = null)

/**
 * Catalog card included in the file, so the import also works offline.
 * [imageLow] and [imageHigh] are the base64 images, present only if exported "with images".
 */
@Serializable
data class CardRecord(
    val id: String,
    val language: String,
    val tcgdexId: String,
    val name: String? = null,
    val setId: String,
    val setName: String? = null,
    val setCode: String? = null,
    val serieId: String? = null,
    val serieName: String? = null,
    val localId: String? = null,
    val printedNumber: String? = null,
    val rarity: String? = null,
    val releaseDate: String? = null,
    val illustrator: String? = null,
    val imageUrl: String? = null,
    val variants: List<String>? = null,
    val hidden: Boolean = false,
    val imageLow: String? = null,
    val imageHigh: String? = null,
    val dexId: Int? = null,
)

/** Saved price (market or manual) included in the file. */
@Serializable
data class PriceRecord(
    val variantId: String,
    val source: String = CollectionFiles.IMPORTED_SOURCE,
    val eur: Double? = null,
    val eurLow: Double? = null,
    val eurAvg30: Double? = null,
    val usd: Double? = null,
    val usdLow: Double? = null,
    val sourceUpdatedAt: String? = null,
    val fetchedAt: Long = 0,
    /** A price imported without a declared reliability is "indicative": it is unknown which variant it refers to. */
    val confidence: String = "LOW",
)

/** File of a single collection (schema 2), with or without images. */
@Serializable
data class CollectionFileV2(
    val schema: Int = CollectionFiles.SCHEMA_COLLECTION,
    val app: String = CollectionFiles.APP_ID,
    val type: String = CollectionFiles.TYPE_COLLECTION,
    val exportedAt: String? = null,
    val info: CollectionInfo = CollectionInfo(),
    val cards: List<CardRecord>,
    val entries: List<BackupEntry>,
    val prices: List<PriceRecord> = emptyList(),
)

/**
 * Permissive container used when reading: accepts both schema 2 (collection) and schema 1
 * (first-version backup), which use different fields.
 */
@Serializable
private data class ImportEnvelope(
    val schema: Int = 0,
    val app: String = "",
    val type: String? = null,
    val exportedAt: String? = null,
    val info: CollectionInfo? = null,
    val cards: List<CardRecord> = emptyList(),
    val entries: List<BackupEntry> = emptyList(),
    val prices: List<PriceRecord> = emptyList(),
    val queries: List<BackupQuery> = emptyList(),
    val collection: List<BackupEntry> = emptyList(),
    val manualPrices: List<BackupManualPrice> = emptyList(),
)

/** Card ready for import, with images already decoded and verified. */
class ImportedCard(
    val card: CatalogCard,
    val hidden: Boolean,
    val imageLow: ByteArray?,
    val imageHigh: ByteArray?,
)

/**
 * Collection read from a file and cleaned of invalid entries.
 * [name] is null for first-version backups, which had no name: the app chooses one.
 * [language] is the value of [CollectionLanguage], read from the file or inferred from searches and cards.
 */
class ImportedCollection(
    val name: String?,
    val language: String,
    val queries: List<BackupQuery>,
    val cards: List<ImportedCard>,
    val entries: List<BackupEntry>,
    val prices: List<PriceRecord>,
)

/** Reason why a file cannot be imported; the UI translates it into a message. */
enum class ImportError { TOO_LARGE, INVALID_JSON, NOT_THIS_APP, UNSUPPORTED_VERSION, TOO_MANY_ITEMS, READ_FAILED }

/** Outcome of reading a collection file. */
sealed interface CollectionImportResult {
    /** Valid file; [skipped] counts the discarded entries. */
    class Ok(val collection: ImportedCollection, val skipped: Int) : CollectionImportResult

    /** File not readable for the reason [error]. */
    class Error(val error: ImportError) : CollectionImportResult
}

/** How many images to include in the export. */
enum class ImageExport { NONE, LOW, LOW_AND_HIGH }

/** Reading and writing of collection files. */
@OptIn(ExperimentalSerializationApi::class)
object CollectionFiles {
    const val APP_ID = "card-collection"
    const val SCHEMA_V1 = 1
    const val SCHEMA_COLLECTION = 2
    const val TYPE_COLLECTION = "collection"

    /** Maximum size of a file to import (40 MB, images included). */
    const val MAX_FILE_BYTES = 40L * 1024 * 1024

    /** Maximum size of a single decoded image (3 MB). */
    const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private const val MAX_CARDS = 5_000
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PRICES = 50_000
    private const val MAX_QUERIES = 200
    private const val MAX_DEX_ID = 2_000

    /** Date assigned to undated prices when not even the file has one: the oldest possible. */
    private const val UNDATED_PRICE_TIME = 1L

    /** Control and text-direction characters (usable to disguise a name). */
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Writes the file of a collection as a stream.
     * @param file CollectionFileV2 - content to save.
     * @param output OutputStream - destination (not closed).
     * @return Unit
     */
    fun write(file: CollectionFileV2, output: OutputStream) {
        json.encodeToStream(CollectionFileV2.serializer(), file, output)
    }

    /**
     * Encodes an image as base64 for the export.
     * @param bytes ByteArray - content of the image file.
     * @return String - base64 text.
     */
    fun encodeImage(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * Reads and validates a collection file (schema 2) or a first-version backup (schema 1).
     * @param input InputStream - content of the file (not closed).
     * @return CollectionImportResult - cleaned collection or error with message.
     */
    fun read(input: InputStream): CollectionImportResult {
        val envelope = try {
            json.decodeFromStream(ImportEnvelope.serializer(), LimitedInputStream(input, MAX_FILE_BYTES))
        } catch (e: FileTooLargeException) {
            return CollectionImportResult.Error(ImportError.TOO_LARGE)
        } catch (e: SerializationException) {
            return CollectionImportResult.Error(ImportError.INVALID_JSON)
        } catch (e: IllegalArgumentException) {
            return CollectionImportResult.Error(ImportError.INVALID_JSON)
        } catch (e: IOException) {
            return CollectionImportResult.Error(ImportError.READ_FAILED)
        }
        if (envelope.app != APP_ID) return CollectionImportResult.Error(ImportError.NOT_THIS_APP)
        return when (envelope.schema) {
            SCHEMA_COLLECTION -> readV2(envelope)
            SCHEMA_V1 -> readV1(envelope)
            else -> CollectionImportResult.Error(ImportError.UNSUPPORTED_VERSION)
        }
    }

    /**
     * Validates a schema 2 collection file.
     * @param env ImportEnvelope - raw content.
     * @return CollectionImportResult - outcome.
     */
    private fun readV2(env: ImportEnvelope): CollectionImportResult {
        val info = env.info ?: CollectionInfo()
        if (env.cards.size > MAX_CARDS || env.entries.size > MAX_ENTRIES || env.prices.size > MAX_PRICES ||
            info.queries.size > MAX_QUERIES
        ) {
            return CollectionImportResult.Error(ImportError.TOO_MANY_ITEMS)
        }
        var skipped = 0
        val name = info.name?.let(::cleanName)
        val queries = validQueries(info.queries).also { skipped += info.queries.size - it.size }
        // Variants used by the entries, for the cards in the file that do not list their own.
        val typesInEntries = env.entries.groupBy({ it.variantId.substringBefore('|') }, { it.variantId.substringAfter('|') })
        val cards = env.cards.mapNotNull { record ->
            toImportedCard(record, typesInEntries[record.id].orEmpty()).also { if (it == null) skipped++ }
        }.distinctBy { it.card.id }
        val cardIds = cards.map { it.card.id }.toSet()
        val variantIds = cards.flatMap { c -> c.card.variants.map { Ids.variantId(c.card.id, it.type, it.print) } }.toSet()
        val entries = validEntries(env.entries) { it in variantIds }.also { skipped += env.entries.size - it.size }
        // Undated prices take the export date (or the oldest possible): this way they do not get ahead
        // of more recent prices and re-importing the same file does not create duplicates.
        val undatedTime = parseInstant(env.exportedAt) ?: UNDATED_PRICE_TIME
        val prices = env.prices.map { if (it.fetchedAt <= 0) it.copy(fetchedAt = undatedTime) else it }.filter { p ->
            validPrice(p, cardIds).also { if (!it) skipped++ }
        }
        val language = CollectionLanguage.sanitize(info.language)
            ?: CollectionLanguage.infer(queries.map { it.language } + cards.map { it.card.language.code })
        return CollectionImportResult.Ok(ImportedCollection(name, language, queries, cards, entries, prices), skipped)
    }

    /**
     * Converts a first-version backup into a collection to import (without catalog:
     * the cards arrive at the first "Update catalog").
     * @param env ImportEnvelope - raw content.
     * @return CollectionImportResult - outcome.
     */
    private fun readV1(env: ImportEnvelope): CollectionImportResult {
        if (env.queries.size + env.collection.size + env.manualPrices.size > MAX_ENTRIES) {
            return CollectionImportResult.Error(ImportError.TOO_MANY_ITEMS)
        }
        val queries = validQueries(env.queries)
        val entries = validEntries(env.collection) { true }
        val prices = env.manualPrices
            .filter { Ids.isValidVariantId(it.variantId) && it.eur.isFinite() && it.eur > 0 && it.eur < Validators.MAX_PRICE && it.fetchedAt > 0 }
            .map { PriceRecord(it.variantId, MANUAL_SOURCE, eur = it.eur, fetchedAt = it.fetchedAt, confidence = Confidence.MANUAL.name) }
        val skipped = env.queries.size - queries.size + env.collection.size - entries.size + env.manualPrices.size - prices.size
        val language = CollectionLanguage.infer(queries.map { it.language })
        return CollectionImportResult.Ok(ImportedCollection(null, language, queries, emptyList(), entries, prices), skipped)
    }

    /** Source used for manually entered prices. */
    const val MANUAL_SOURCE = "Manuale"

    /** Source assigned to imported prices with no source indication. */
    const val IMPORTED_SOURCE = "Importato"

    /**
     * Cleans the name of a collection.
     * @param raw String - name entered or read from a file.
     * @return String? - name without control characters, 1-60 characters long, or null if empty.
     */
    fun cleanName(raw: String): String? = raw.replace(CONTROL_CHARS, "").trim().take(60).ifEmpty { null }

    /**
     * Converts an ISO-8601 date (e.g. "2026-10-19T10:00:00Z") to milliseconds.
     * @param text String? - date read from the file.
     * @return Long? - milliseconds since 1970, or null if absent or invalid.
     */
    private fun parseInstant(text: String?): Long? = try {
        text?.take(40)?.let { Instant.parse(it).toEpochMilli() }?.takeIf { it > 0 }
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * Keeps only valid searches, without duplicates.
     * @param queries List<BackupQuery> - searches read from the file.
     * @return List<BackupQuery> - valid searches.
     */
    private fun validQueries(queries: List<BackupQuery>): List<BackupQuery> = queries
        .filter { q ->
            val kind = QueryKind.fromCode(q.kind)
            Language.fromCode(q.language) != null && kind != null && QueryKind.isValidPattern(kind, q.pattern)
        }
        .map { it.copy(pattern = it.pattern.trim()) }
        .distinctBy { it.language to it.pattern.lowercase() }

    /**
     * Keeps only valid collection entries.
     * @param entries List<BackupEntry> - entries read from the file.
     * @param belongs (String) -> Boolean - true if the variant belongs to a card in the file.
     * @return List<BackupEntry> - valid entries, one per variant.
     */
    private fun validEntries(entries: List<BackupEntry>, belongs: (String) -> Boolean): List<BackupEntry> {
        val conditions = Condition.entries.map { it.name }.toSet()
        return entries.filter {
            Ids.isValidVariantId(it.variantId) && belongs(it.variantId) &&
                it.quantity in 0..Validators.MAX_QUANTITY && (it.notes == null || it.notes.length <= 500)
        }.map { entry ->
            // Condition written in lowercase or unknown: the entry is kept with the default condition.
            val condition = entry.condition.uppercase().takeIf { it in conditions } ?: Condition.NM.name
            if (condition == entry.condition) entry else entry.copy(condition = condition)
        }.distinctBy { it.variantId }
    }

    /**
     * Checks a price read from a file.
     * @param p PriceRecord - price.
     * @param cardIds Set<String> - cards present in the file.
     * @return Boolean - true if the price is valid and refers to a card in the file.
     */
    private fun validPrice(p: PriceRecord, cardIds: Set<String>): Boolean {
        fun ok(v: Double?) = v == null || (v.isFinite() && v >= 0 && v < Validators.MAX_PRICE)
        return Ids.isValidVariantId(p.variantId) && p.variantId.substringBefore('|') in cardIds &&
            p.source.isNotBlank() && p.source.length <= 40 && p.fetchedAt > 0 &&
            Confidence.entries.any { it.name == p.confidence } &&
            ok(p.eur) && ok(p.eurLow) && ok(p.eurAvg30) && ok(p.usd) && ok(p.usdLow) &&
            (p.eur != null || p.usd != null) && (p.sourceUpdatedAt == null || p.sourceUpdatedAt.length <= 40)
    }

    /**
     * Validates a card read from a file and decodes its images.
     * @param r CardRecord - card.
     * @param typesInEntries List<String> - variant types used by the entries of the card, used if the
     *        file does not list the variants (if neither is present the card has only the normal variant).
     * @return ImportedCard? - ready card, or null if the essential data is not valid.
     */
    private fun toImportedCard(r: CardRecord, typesInEntries: List<String>): ImportedCard? {
        val language = Language.fromCode(r.language) ?: return null
        if (!Validators.isValidRemoteId(r.tcgdexId) || !Validators.isValidRemoteId(r.setId)) return null
        if (r.id != "${language.code}:${r.tcgdexId}") return null
        val variants = (r.variants ?: typesInEntries.ifEmpty { listOf(VariantType.NORMAL.name) })
            .mapNotNull { Ids.parseVariantKey(it) }.distinct()
        val name = r.name?.replace(CONTROL_CHARS, "")?.trim().orEmpty()
        if (variants.isEmpty() || name.isEmpty()) return null
        val localId = r.localId?.takeIf { it.isNotBlank() } ?: r.tcgdexId.substringAfterLast('-')
        val card = CatalogCard(
            id = r.id,
            language = language,
            tcgdexId = r.tcgdexId,
            name = name.take(120),
            setId = r.setId,
            setName = (r.setName?.takeIf { it.isNotBlank() } ?: r.setId).take(120),
            setCode = r.setCode?.take(16),
            serieId = r.serieId?.take(40),
            serieName = r.serieName?.take(80),
            localId = localId.take(16),
            printedNumber = (r.printedNumber?.takeIf { it.isNotBlank() } ?: localId).take(24),
            rarity = r.rarity?.take(60),
            releaseDate = r.releaseDate?.take(10),
            illustrator = r.illustrator?.take(80),
            imageBaseUrl = r.imageUrl?.takeIf { Validators.isAllowedImageUrl(it) },
            // The product of a separate print comes back with the next catalog update; that of the other Cardmarket prints is their stamp.
            variants = variants.map { (type, print) -> CatalogVariant(type, print.toIntOrNull()?.takeIf { type == VariantType.CARDMARKET_PRINT }, print) },
            dexId = r.dexId?.takeIf { it in 1..MAX_DEX_ID },
        )
        return ImportedCard(card, r.hidden, decodeImage(r.imageLow), decodeImage(r.imageHigh))
    }

    /**
     * Decodes a base64 image accepting only WebP, PNG or JPEG within [MAX_IMAGE_BYTES].
     * @param base64 String? - encoded image.
     * @return ByteArray? - bytes of the image, or null if absent or invalid.
     */
    fun decodeImage(base64: String?): ByteArray? {
        if (base64.isNullOrEmpty() || base64.length > MAX_IMAGE_BYTES / 3 * 4 + 8) return null
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.size in 12..MAX_IMAGE_BYTES && isSupportedImage(it) }
    }

    /**
     * Recognizes the image format from the first bytes.
     * @param b ByteArray - content of the file.
     * @return Boolean - true for WebP, PNG or JPEG.
     */
    private fun isSupportedImage(b: ByteArray): Boolean {
        val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        return webp || png || jpeg
    }
}

/** The file exceeds the maximum allowed size. */
class FileTooLargeException : IOException("File troppo grande")

/**
 * Stream that interrupts reading beyond [limit] bytes, to avoid loading huge files into memory.
 * @param input original stream.
 * @param limit maximum number of readable bytes.
 */
private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    /**
     * Reads a byte checking the limit.
     * @return Int - byte read, or -1 at end of stream.
     * @throws FileTooLargeException if the limit is exceeded.
     */
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    /**
     * Reads a block of bytes checking the limit.
     * @param b ByteArray - destination buffer.
     * @param off Int - starting position in the buffer.
     * @param len Int - maximum bytes to read.
     * @return Int - bytes read, or -1 at end of stream.
     * @throws FileTooLargeException if the limit is exceeded.
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    /**
     * Updates the count of bytes read.
     * @param n Long - bytes just read.
     * @return Unit
     * @throws FileTooLargeException if the limit is exceeded.
     */
    private fun check(n: Long) {
        count += n
        if (count > limit) throw FileTooLargeException()
    }
}
