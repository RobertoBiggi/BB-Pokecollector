package com.robertobiggi.cardcollection.core.backup

import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
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

/** Ricerca per nome salvata nel file. */
@Serializable
data class BackupQuery(val language: String, val pattern: String, val enabled: Boolean = true)

/** Stato di una variante nella collezione. */
@Serializable
data class BackupEntry(
    val variantId: String,
    val quantity: Int = 1,
    val condition: String = "NM",
    val wanted: Boolean = true,
    val notes: String? = null,
)

/** Prezzo manuale nel formato dei backup della prima versione (schema 1). */
@Serializable
data class BackupManualPrice(val variantId: String, val eur: Double, val fetchedAt: Long)

/** Nome e ricerche della collezione esportata. */
@Serializable
data class CollectionInfo(val name: String? = null, val queries: List<BackupQuery> = emptyList())

/**
 * Carta del catalogo inclusa nel file, così l'import funziona anche offline.
 * [imageLow] e [imageHigh] sono le immagini in base64, presenti solo se esportate "con immagini".
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

/** Prezzo salvato (di mercato o manuale) incluso nel file. */
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
    val confidence: String = "MEDIUM",
)

/** File di una singola collezione (schema 2), con o senza immagini. */
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
 * Contenitore permissivo usato in lettura: accetta sia lo schema 2 (collezione) sia lo schema 1
 * (backup della prima versione), che usano campi diversi.
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

/** Carta pronta per l'import, con le immagini già decodificate e verificate. */
class ImportedCard(
    val card: CatalogCard,
    val hidden: Boolean,
    val imageLow: ByteArray?,
    val imageHigh: ByteArray?,
)

/**
 * Collezione letta da file e ripulita dalle voci non valide.
 * [name] è null per i backup della prima versione, che non avevano un nome: lo sceglie l'app.
 */
class ImportedCollection(
    val name: String?,
    val queries: List<BackupQuery>,
    val cards: List<ImportedCard>,
    val entries: List<BackupEntry>,
    val prices: List<PriceRecord>,
)

/** Motivo per cui un file non può essere importato; la UI lo traduce in un messaggio. */
enum class ImportError { TOO_LARGE, INVALID_JSON, NOT_THIS_APP, UNSUPPORTED_VERSION, TOO_MANY_ITEMS, READ_FAILED }

/** Esito della lettura di un file di collezione. */
sealed interface CollectionImportResult {
    /** File valido; [skipped] conta le voci scartate. */
    class Ok(val collection: ImportedCollection, val skipped: Int) : CollectionImportResult

    /** File non leggibile per il motivo [error]. */
    class Error(val error: ImportError) : CollectionImportResult
}

/** Quante immagini includere nell'export. */
enum class ImageExport { NONE, LOW, LOW_AND_HIGH }

/** Lettura e scrittura dei file di collezione. */
@OptIn(ExperimentalSerializationApi::class)
object CollectionFiles {
    const val APP_ID = "card-collection"
    const val SCHEMA_V1 = 1
    const val SCHEMA_COLLECTION = 2
    const val TYPE_COLLECTION = "collection"

    /** Dimensione massima di un file da importare (40 MB, immagini comprese). */
    const val MAX_FILE_BYTES = 40L * 1024 * 1024

    /** Dimensione massima di una singola immagine decodificata (3 MB). */
    const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    private const val MAX_CARDS = 5_000
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PRICES = 50_000
    private const val MAX_QUERIES = 200
    private const val MAX_DEX_ID = 2_000

    /** Data assegnata ai prezzi senza data quando nemmeno il file ne ha una: la più vecchia possibile. */
    private const val UNDATED_PRICE_TIME = 1L

    /** Caratteri di controllo e di direzione del testo (usabili per camuffare un nome). */
    private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Scrive il file di una collezione in streaming.
     * @param file CollectionFileV2 - contenuto da salvare.
     * @param output OutputStream - destinazione (non viene chiusa).
     * @return Unit
     */
    fun write(file: CollectionFileV2, output: OutputStream) {
        json.encodeToStream(CollectionFileV2.serializer(), file, output)
    }

    /**
     * Codifica un'immagine in base64 per l'export.
     * @param bytes ByteArray - contenuto del file immagine.
     * @return String - testo base64.
     */
    fun encodeImage(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * Legge e valida un file di collezione (schema 2) o un backup della prima versione (schema 1).
     * @param input InputStream - contenuto del file (non viene chiuso).
     * @return CollectionImportResult - collezione ripulita oppure errore con messaggio.
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
     * Valida un file di collezione schema 2.
     * @param env ImportEnvelope - contenuto grezzo.
     * @return CollectionImportResult - esito.
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
        // Varianti usate dalle voci, per le carte del file che non elencano le proprie.
        val typesInEntries = env.entries.groupBy({ it.variantId.substringBefore('|') }, { it.variantId.substringAfter('|') })
        val cards = env.cards.mapNotNull { record ->
            toImportedCard(record, typesInEntries[record.id].orEmpty()).also { if (it == null) skipped++ }
        }.distinctBy { it.card.id }
        val cardIds = cards.map { it.card.id }.toSet()
        val variantIds = cards.flatMap { c -> c.card.variants.map { Ids.variantId(c.card.id, it.type) } }.toSet()
        val entries = validEntries(env.entries) { it in variantIds }.also { skipped += env.entries.size - it.size }
        // I prezzi senza data prendono quella dell'export (o la più vecchia possibile): così non passano
        // davanti ai prezzi più recenti e reimportare lo stesso file non crea doppioni.
        val undatedTime = parseInstant(env.exportedAt) ?: UNDATED_PRICE_TIME
        val prices = env.prices.map { if (it.fetchedAt <= 0) it.copy(fetchedAt = undatedTime) else it }.filter { p ->
            validPrice(p, cardIds).also { if (!it) skipped++ }
        }
        return CollectionImportResult.Ok(ImportedCollection(name, queries, cards, entries, prices), skipped)
    }

    /**
     * Converte un backup della prima versione in una collezione da importare (senza catalogo:
     * le carte arrivano al primo "Aggiorna catalogo").
     * @param env ImportEnvelope - contenuto grezzo.
     * @return CollectionImportResult - esito.
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
        return CollectionImportResult.Ok(ImportedCollection(null, queries, emptyList(), entries, prices), skipped)
    }

    /** Fonte usata per i prezzi inseriti a mano. */
    const val MANUAL_SOURCE = "Manuale"

    /** Fonte assegnata ai prezzi importati senza indicazione della fonte. */
    const val IMPORTED_SOURCE = "Importato"

    /**
     * Pulisce il nome di una collezione.
     * @param raw String - nome inserito o letto da file.
     * @return String? - nome senza caratteri di controllo, lungo 1-60 caratteri, oppure null se vuoto.
     */
    fun cleanName(raw: String): String? = raw.replace(CONTROL_CHARS, "").trim().take(60).ifEmpty { null }

    /**
     * Converte una data ISO-8601 (es. "2026-10-19T10:00:00Z") in millisecondi.
     * @param text String? - data letta dal file.
     * @return Long? - millisecondi dal 1970, oppure null se assente o non valida.
     */
    private fun parseInstant(text: String?): Long? = try {
        text?.take(40)?.let { Instant.parse(it).toEpochMilli() }?.takeIf { it > 0 }
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * Tiene solo le ricerche valide e senza duplicati.
     * @param queries List<BackupQuery> - ricerche lette da file.
     * @return List<BackupQuery> - ricerche valide.
     */
    private fun validQueries(queries: List<BackupQuery>): List<BackupQuery> = queries
        .filter { Language.fromCode(it.language) != null && Validators.isValidQueryPattern(it.pattern) }
        .map { it.copy(pattern = it.pattern.trim()) }
        .distinctBy { it.language to it.pattern.lowercase() }

    /**
     * Tiene solo le voci della collezione valide.
     * @param entries List<BackupEntry> - voci lette da file.
     * @param belongs (String) -> Boolean - true se la variante appartiene a una carta del file.
     * @return List<BackupEntry> - voci valide, una per variante.
     */
    private fun validEntries(entries: List<BackupEntry>, belongs: (String) -> Boolean): List<BackupEntry> {
        val conditions = Condition.entries.map { it.name }.toSet()
        return entries.filter {
            Ids.isValidVariantId(it.variantId) && belongs(it.variantId) &&
                it.quantity in 0..Validators.MAX_QUANTITY && (it.notes == null || it.notes.length <= 500)
        }.map { entry ->
            // Condizione scritta in minuscolo o sconosciuta: si tiene la voce con la condizione di default.
            val condition = entry.condition.uppercase().takeIf { it in conditions } ?: Condition.NM.name
            if (condition == entry.condition) entry else entry.copy(condition = condition)
        }.distinctBy { it.variantId }
    }

    /**
     * Controlla un prezzo letto da file.
     * @param p PriceRecord - prezzo.
     * @param cardIds Set<String> - carte presenti nel file.
     * @return Boolean - true se il prezzo è valido e riferito a una carta del file.
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
     * Valida una carta letta da file e ne decodifica le immagini.
     * @param r CardRecord - carta.
     * @param typesInEntries List<String> - tipi di variante usati dalle voci della carta, usati se il
     *        file non elenca le varianti (in mancanza di entrambi la carta ha solo la variante normale).
     * @return ImportedCard? - carta pronta, oppure null se i dati essenziali non sono validi.
     */
    private fun toImportedCard(r: CardRecord, typesInEntries: List<String>): ImportedCard? {
        val language = Language.fromCode(r.language) ?: return null
        if (!Validators.isValidRemoteId(r.tcgdexId) || !Validators.isValidRemoteId(r.setId)) return null
        if (r.id != "${language.code}:${r.tcgdexId}") return null
        val variants = (r.variants ?: typesInEntries.ifEmpty { listOf(VariantType.NORMAL.name) })
            .mapNotNull { v -> VariantType.entries.firstOrNull { it.name == v } }.distinct()
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
            variants = variants.map { CatalogVariant(it) },
            dexId = r.dexId?.takeIf { it in 1..MAX_DEX_ID },
        )
        return ImportedCard(card, r.hidden, decodeImage(r.imageLow), decodeImage(r.imageHigh))
    }

    /**
     * Decodifica un'immagine base64 accettando solo WebP, PNG o JPEG entro [MAX_IMAGE_BYTES].
     * @param base64 String? - immagine codificata.
     * @return ByteArray? - byte dell'immagine, oppure null se assente o non valida.
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
     * Riconosce il formato dell'immagine dai primi byte.
     * @param b ByteArray - contenuto del file.
     * @return Boolean - true per WebP, PNG o JPEG.
     */
    private fun isSupportedImage(b: ByteArray): Boolean {
        val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        return webp || png || jpeg
    }
}

/** Il file supera la dimensione massima consentita. */
class FileTooLargeException : IOException("File troppo grande")

/**
 * Flusso che interrompe la lettura oltre [limit] byte, per non caricare file enormi in memoria.
 * @param input flusso originale.
 * @param limit numero massimo di byte leggibili.
 */
private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    /**
     * Legge un byte controllando il limite.
     * @return Int - byte letto, oppure -1 a fine flusso.
     * @throws FileTooLargeException se si supera il limite.
     */
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    /**
     * Legge un blocco di byte controllando il limite.
     * @param b ByteArray - buffer di destinazione.
     * @param off Int - posizione iniziale nel buffer.
     * @param len Int - byte massimi da leggere.
     * @return Int - byte letti, oppure -1 a fine flusso.
     * @throws FileTooLargeException se si supera il limite.
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    /**
     * Aggiorna il conteggio dei byte letti.
     * @param n Long - byte appena letti.
     * @return Unit
     * @throws FileTooLargeException se si supera il limite.
     */
    private fun check(n: Long) {
        count += n
        if (count > limit) throw FileTooLargeException()
    }
}
