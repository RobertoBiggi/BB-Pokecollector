package com.robertobiggi.cardcollection.core.backup

import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.validation.Validators
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ricerca tracciata salvata nel backup. */
@Serializable
data class BackupQuery(val language: String, val pattern: String, val enabled: Boolean)

/** Stato di una variante nella collezione. */
@Serializable
data class BackupEntry(
    val variantId: String,
    val quantity: Int,
    val condition: String = "NM",
    val wanted: Boolean = true,
    val notes: String? = null,
)

/** Prezzo inserito a mano dall'utente. */
@Serializable
data class BackupManualPrice(val variantId: String, val eur: Double, val fetchedAt: Long)

/**
 * Backup completo dei dati dell'utente. Non contiene il catalogo (si riscarica) né segreti.
 */
@Serializable
data class BackupV1(
    val schema: Int = SCHEMA,
    val app: String = APP_ID,
    val exportedAt: String,
    val queries: List<BackupQuery>,
    val collection: List<BackupEntry>,
    val manualPrices: List<BackupManualPrice> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
        const val APP_ID = "card-collection"
    }
}

/** Esito della lettura di un backup. */
sealed interface BackupDecodeResult {
    /** Backup valido; [skipped] conta le voci scartate perché non valide. */
    data class Ok(val backup: BackupV1, val skipped: Int) : BackupDecodeResult

    /** Backup non leggibile; [message] spiega il motivo all'utente. */
    data class Error(val message: String) : BackupDecodeResult
}

/** Serializzazione e validazione dei backup JSON. */
object BackupCodec {

    /** Dimensione massima accettata per un file di backup (2 MB). */
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_ITEMS = 20_000

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Serializza un backup in JSON leggibile.
     * @param backup BackupV1 - dati da salvare.
     * @return String - JSON formattato.
     */
    fun encode(backup: BackupV1): String = json.encodeToString(BackupV1.serializer(), backup)

    /**
     * Legge e valida un backup. Le voci non valide vengono scartate e contate, non applicate.
     * @param text String - contenuto del file.
     * @return BackupDecodeResult - backup ripulito oppure errore con messaggio.
     */
    fun decode(text: String): BackupDecodeResult {
        if (text.isBlank()) return BackupDecodeResult.Error("Il file è vuoto.")
        if (text.length > MAX_BYTES) return BackupDecodeResult.Error("Il file è troppo grande (massimo 2 MB).")
        val raw = try {
            json.decodeFromString(BackupV1.serializer(), text)
        } catch (e: SerializationException) {
            return BackupDecodeResult.Error("Il file non è un backup valido.")
        } catch (e: IllegalArgumentException) {
            return BackupDecodeResult.Error("Il file non è un backup valido.")
        }
        if (raw.app != BackupV1.APP_ID || raw.schema != BackupV1.SCHEMA) {
            return BackupDecodeResult.Error("Backup non riconosciuto (serve schema ${BackupV1.SCHEMA} di Card Collection).")
        }
        if (raw.queries.size + raw.collection.size + raw.manualPrices.size > MAX_ITEMS) {
            return BackupDecodeResult.Error("Il backup contiene troppe voci.")
        }
        var skipped = 0
        val queries = raw.queries.filter { q ->
            (Language.fromCode(q.language) != null && Validators.isValidQueryPattern(q.pattern)).also { if (!it) skipped++ }
        }.map { it.copy(pattern = it.pattern.trim()) }
        val conditions = Condition.entries.map { it.name }.toSet()
        val entries = raw.collection.filter { e ->
            (Ids.isValidVariantId(e.variantId) &&
                e.quantity in 0..Validators.MAX_QUANTITY &&
                e.condition in conditions &&
                (e.notes == null || e.notes.length <= 500)).also { if (!it) skipped++ }
        }.distinctBy { it.variantId }
        val prices = raw.manualPrices.filter { p ->
            (Ids.isValidVariantId(p.variantId) && p.eur.isFinite() && p.eur > 0 && p.eur < 100_000 && p.fetchedAt > 0)
                .also { if (!it) skipped++ }
        }
        return BackupDecodeResult.Ok(raw.copy(queries = queries, collection = entries, manualPrices = prices), skipped)
    }
}
