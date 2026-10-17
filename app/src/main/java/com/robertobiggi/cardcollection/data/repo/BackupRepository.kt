package com.robertobiggi.cardcollection.data.repo

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.backup.BackupCodec
import com.robertobiggi.cardcollection.core.backup.BackupDecodeResult
import com.robertobiggi.cardcollection.core.backup.BackupEntry
import com.robertobiggi.cardcollection.core.backup.BackupManualPrice
import com.robertobiggi.cardcollection.core.backup.BackupQuery
import com.robertobiggi.cardcollection.core.backup.BackupV1
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * Esporta e importa i dati dell'utente in un file JSON scelto dall'utente (Storage Access Framework).
 */
class BackupRepository(private val db: AppDatabase, private val resolver: ContentResolver) {

    /**
     * Scrive il backup nel file indicato.
     * @param uri Uri - file scelto dall'utente con ACTION_CREATE_DOCUMENT.
     * @return String - messaggio di esito per l'utente.
     */
    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        val backup = BackupV1(
            exportedAt = Instant.now().toString(),
            queries = db.catalogDao().allQueries().map { BackupQuery(it.language, it.pattern, it.enabled) },
            collection = db.collectionDao().allEntries().map {
                BackupEntry(it.variantId, it.quantity, it.condition, it.wanted, it.notes)
            },
            manualPrices = db.priceDao().snapshotsBySource(PriceRepository.MANUAL_SOURCE).mapNotNull { s ->
                s.eur?.let { BackupManualPrice(s.variantId, it, s.fetchedAt) }
            },
        )
        try {
            val stream = resolver.openOutputStream(uri, "wt") ?: return@withContext "Impossibile aprire il file."
            stream.use { it.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8)) }
            "Backup salvato: ${backup.collection.size} varianti, ${backup.manualPrices.size} prezzi manuali."
        } catch (e: IOException) {
            "Salvataggio non riuscito: ${e.message}"
        } catch (e: SecurityException) {
            "Permesso negato per il file scelto."
        }
    }

    /**
     * Legge, valida e applica un backup. Le ricerche si aggiungono, le varianti vengono sovrascritte.
     * @param uri Uri - file scelto dall'utente con ACTION_OPEN_DOCUMENT.
     * @return String - messaggio di esito per l'utente.
     */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = try {
            readLimited(uri) ?: return@withContext "Il file è troppo grande (massimo 2 MB)."
        } catch (e: IOException) {
            return@withContext "Lettura non riuscita: ${e.message}"
        } catch (e: SecurityException) {
            return@withContext "Permesso negato per il file scelto."
        }
        when (val result = BackupCodec.decode(text)) {
            is BackupDecodeResult.Error -> result.message
            is BackupDecodeResult.Ok -> {
                apply(result.backup)
                buildString {
                    append("Backup importato: ${result.backup.collection.size} varianti.")
                    if (result.skipped > 0) append(" ${result.skipped} voci non valide ignorate.")
                }
            }
        }
    }

    /**
     * Applica un backup già validato in un'unica transazione.
     * @param backup BackupV1 - dati da applicare.
     * @return Unit
     */
    private suspend fun apply(backup: BackupV1) {
        db.withTransaction {
            backup.queries.forEach {
                db.catalogDao().insertQuery(TrackedQueryEntity(language = it.language, pattern = it.pattern, enabled = it.enabled))
            }
            db.collectionDao().upsertEntries(
                backup.collection.map { CollectionEntryEntity(it.variantId, it.quantity, it.condition, it.wanted, it.notes) },
            )
            val priceDao = db.priceDao()
            backup.manualPrices.forEach { p ->
                if (priceDao.countSnapshot(p.variantId, PriceRepository.MANUAL_SOURCE, p.fetchedAt) == 0) {
                    priceDao.insertSnapshot(
                        PriceSnapshotEntity(
                            variantId = p.variantId,
                            source = PriceRepository.MANUAL_SOURCE,
                            eur = p.eur,
                            eurLow = null,
                            eurAvg30 = null,
                            usd = null,
                            usdLow = null,
                            sourceUpdatedAt = null,
                            fetchedAt = p.fetchedAt,
                            confidence = Confidence.MANUAL.name,
                        ),
                    )
                    priceDao.pruneSnapshots(p.variantId, PriceRepository.KEEP_SNAPSHOTS)
                }
            }
        }
    }

    /**
     * Legge il file rifiutando quelli oltre [BackupCodec.MAX_BYTES].
     * @param uri Uri - file da leggere.
     * @return String? - contenuto UTF-8, oppure null se il file è troppo grande.
     * @throws IOException se il file non si può aprire.
     */
    private fun readLimited(uri: Uri): String? {
        val stream = resolver.openInputStream(uri) ?: throw IOException("file non disponibile")
        stream.use { input ->
            val buffer = ByteArray(BackupCodec.MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            if (total > BackupCodec.MAX_BYTES) return null
            return String(buffer, 0, total, Charsets.UTF_8)
        }
    }
}
