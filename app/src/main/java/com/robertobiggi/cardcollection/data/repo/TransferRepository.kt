package com.robertobiggi.cardcollection.data.repo

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.backup.BackupEntry
import com.robertobiggi.cardcollection.core.backup.BackupQuery
import com.robertobiggi.cardcollection.core.backup.CardRecord
import com.robertobiggi.cardcollection.core.backup.CollectionFileV2
import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.core.backup.CollectionImportResult
import com.robertobiggi.cardcollection.core.backup.CollectionInfo
import com.robertobiggi.cardcollection.core.backup.ImageExport
import com.robertobiggi.cardcollection.core.backup.ImportError
import com.robertobiggi.cardcollection.core.backup.ImportedCollection
import com.robertobiggi.cardcollection.core.backup.PriceRecord
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionCardEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/** Errore di accesso al file scelto dall'utente. */
enum class FileError { NOT_FOUND, OPEN_FAILED, PERMISSION_DENIED }

/** Esito dell'export di una collezione. */
sealed interface ExportResult {
    /** File scritto con [cards] carte, di cui [withImages] con immagine incorporata. */
    data class Done(val cards: Int, val withImages: Int) : ExportResult

    /** Export non riuscito. */
    data class Failed(val error: FileError) : ExportResult
}

/** Esito dell'import di una collezione. */
sealed interface ImportOutcome {
    /** Collezione [name] creata con id [collectionId]; [skipped] voci non valide ignorate. */
    data class Done(val collectionId: Long, val name: String, val cards: Int, val images: Int, val skipped: Int) : ImportOutcome

    /** Il file non è un JSON di collezione valido. */
    data class Invalid(val error: ImportError) : ImportOutcome

    /** Il file non si può leggere. */
    data class Failed(val error: FileError) : ImportOutcome
}

/**
 * Esporta e importa singole collezioni in file JSON (con o senza immagini) scelti dall'utente
 * tramite Storage Access Framework.
 */
class TransferRepository(
    private val db: AppDatabase,
    private val resolver: ContentResolver,
    private val catalog: CatalogRepository,
    private val collections: CollectionRepository,
    private val images: ImageStore,
) {
    private val catalogDao = db.catalogDao()

    /**
     * Esporta una collezione: ricerche, carte del catalogo, quantità, ultimi prezzi e prezzi manuali.
     * @param collectionId Long - collezione da esportare.
     * @param uri Uri - file scelto con ACTION_CREATE_DOCUMENT.
     * @param imageExport ImageExport - quali immagini incorporare.
     * @return ExportResult - carte esportate oppure errore sul file.
     */
    suspend fun export(collectionId: Long, uri: Uri, imageExport: ImageExport): ExportResult = withContext(Dispatchers.IO) {
        val collection = catalogDao.getCollection(collectionId) ?: return@withContext ExportResult.Failed(FileError.NOT_FOUND)
        val memberships = catalogDao.membershipsOf(collectionId)
        val cards = memberships.mapNotNull { m -> catalogDao.getCard(m.cardId)?.let { it to m.hidden } }
        val cardIds = cards.map { it.first.id }
        val variants = cardIds.chunked(SQL_CHUNK).flatMap { catalogDao.variantsOf(it) }
        val snapshots = variants.map { it.id }.chunked(SQL_CHUNK).flatMap { db.priceDao().snapshotsOf(it) }
        val typesByCard = variants.groupBy({ it.cardId }, { it.type })

        val records = cards.map { (card, hidden) ->
            val low = if (imageExport != ImageExport.NONE) images.read(card.imageLowPath)?.let(CollectionFiles::encodeImage) else null
            val high = if (imageExport == ImageExport.LOW_AND_HIGH) images.read(card.imageHighPath)?.let(CollectionFiles::encodeImage) else null
            card.toRecord(typesByCard[card.id].orEmpty(), hidden, low, high)
        }
        val prices = snapshots.groupBy { it.variantId }.flatMap { (_, list) ->
            val manual = list.filter { it.source == CollectionFiles.MANUAL_SOURCE }
            val latestMarket = list.lastOrNull { it.source != CollectionFiles.MANUAL_SOURCE }
            (manual + listOfNotNull(latestMarket)).map { it.toRecord() }
        }
        val file = CollectionFileV2(
            exportedAt = Instant.now().toString(),
            info = CollectionInfo(
                name = collection.name,
                queries = catalogDao.queriesOf(collectionId).map { BackupQuery(it.language, it.pattern, it.enabled, it.kind) },
                language = collection.language,
            ),
            cards = records,
            entries = db.collectionDao().entriesOf(collectionId).map { BackupEntry(it.variantId, it.quantity, it.condition, it.wanted, it.notes) },
            prices = prices,
        )
        try {
            val stream = resolver.openOutputStream(uri, "wt") ?: return@withContext ExportResult.Failed(FileError.OPEN_FAILED)
            stream.use { CollectionFiles.write(file, it) }
            ExportResult.Done(records.size, records.count { it.imageLow != null || it.imageHigh != null })
        } catch (e: IOException) {
            ExportResult.Failed(FileError.OPEN_FAILED)
        } catch (e: SecurityException) {
            ExportResult.Failed(FileError.PERMISSION_DENIED)
        }
    }

    /**
     * Importa un file come nuova collezione. Accetta file schema 2 (con o senza immagini) e i backup
     * della prima versione.
     * @param uri Uri - file scelto con ACTION_OPEN_DOCUMENT.
     * @param defaultName String - nome da usare se il file non ne ha uno (backup della prima versione).
     * @return ImportOutcome - collezione creata oppure motivo del fallimento.
     */
    suspend fun import(uri: Uri, defaultName: String): ImportOutcome = withContext(Dispatchers.IO) {
        val result = try {
            val stream = resolver.openInputStream(uri) ?: return@withContext ImportOutcome.Failed(FileError.OPEN_FAILED)
            stream.use { CollectionFiles.read(it) }
        } catch (e: IOException) {
            return@withContext ImportOutcome.Failed(FileError.OPEN_FAILED)
        } catch (e: SecurityException) {
            return@withContext ImportOutcome.Failed(FileError.PERMISSION_DENIED)
        }
        when (result) {
            is CollectionImportResult.Error -> ImportOutcome.Invalid(result.error)
            is CollectionImportResult.Ok -> {
                val imported = result.collection
                val (id, name) = apply(imported, imported.name ?: defaultName)
                ImportOutcome.Done(id, name, imported.cards.size, saveImages(imported), result.skipped)
            }
        }
    }

    /**
     * Scrive la collezione importata nel database in un'unica transazione.
     * Le carte già presenti nel catalogo non vengono sovrascritte.
     * @param imported ImportedCollection - dati validati.
     * @param desiredName String - nome da dare alla collezione (reso unico se serve).
     * @return Pair<Long, String> - id e nome effettivo della nuova collezione.
     */
    private suspend fun apply(imported: ImportedCollection, desiredName: String): Pair<Long, String> = db.withTransaction {
        val name = collections.uniqueName(desiredName)
        val id = catalogDao.insertCollection(CollectionEntity(name = name, createdAt = System.currentTimeMillis(), language = imported.language))
        imported.queries.forEach {
            catalogDao.insertQuery(TrackedQueryEntity(collectionId = id, language = it.language, pattern = it.pattern, enabled = it.enabled, kind = it.kind))
        }
        imported.cards.forEach { c ->
            if (catalogDao.getCard(c.card.id) == null) {
                catalog.saveCard(c.card, legacyQueryId = 0)
            } else {
                // Carta già nel catalogo: non si sovrascrive, ma si aggiungono le varianti mancanti
                // (altrimenti le quantità importate non sarebbero visibili) e il numero del Pokédex.
                catalogDao.insertVariants(c.card.variants.map { CardVariantEntity(Ids.variantId(c.card.id, it.type), c.card.id, it.type.name, it.cardmarketId) })
                c.card.dexId?.let { catalogDao.fillDexId(c.card.id, it) }
            }
            catalogDao.insertMembership(CollectionCardEntity(id, c.card.id, null, c.hidden))
        }
        db.collectionDao().upsertEntries(
            imported.entries.map { CollectionEntryEntity(id, it.variantId, it.quantity, it.condition, it.wanted, it.notes) },
        )
        val priceDao = db.priceDao()
        imported.prices.forEach { p ->
            if (priceDao.countSnapshot(p.variantId, p.source, p.fetchedAt) == 0) {
                priceDao.insertSnapshot(
                    PriceSnapshotEntity(
                        variantId = p.variantId,
                        source = p.source,
                        eur = p.eur,
                        eurLow = p.eurLow,
                        eurAvg30 = p.eurAvg30,
                        usd = p.usd,
                        usdLow = p.usdLow,
                        sourceUpdatedAt = p.sourceUpdatedAt,
                        fetchedAt = p.fetchedAt,
                        confidence = p.confidence,
                    ),
                )
                priceDao.pruneSnapshots(p.variantId, PriceRepository.KEEP_SNAPSHOTS)
            }
        }
        id to name
    }

    /**
     * Salva le immagini incluse nel file per le carte che non ne hanno ancora una.
     * @param imported ImportedCollection - dati validati.
     * @return Int - numero di carte per cui è stata salvata un'immagine.
     */
    private suspend fun saveImages(imported: ImportedCollection): Int {
        var count = 0
        for (c in imported.cards) {
            if (c.imageLow == null && c.imageHigh == null) continue
            val current = catalogDao.getCard(c.card.id) ?: continue
            val low = current.imageLowPath ?: c.imageLow?.let { images.write(c.card.id, ImageStore.Quality.LOW, it) }
            val high = current.imageHighPath ?: c.imageHigh?.let { images.write(c.card.id, ImageStore.Quality.HIGH, it) }
            if (low != current.imageLowPath || high != current.imageHighPath) {
                catalogDao.setImagePaths(c.card.id, low, high)
                count++
            }
        }
        return count
    }

    /**
     * Converte una carta del database nel record del file.
     * @receiver CardEntity - carta.
     * @param variants List<String> - nomi delle varianti.
     * @param hidden Boolean - true se tolta dalla collezione.
     * @param low String? - immagine piccola in base64.
     * @param high String? - immagine grande in base64.
     * @return CardRecord - record esportabile.
     */
    private fun CardEntity.toRecord(variants: List<String>, hidden: Boolean, low: String?, high: String?) = CardRecord(
        id = id, language = language, tcgdexId = tcgdexId, name = name, setId = setId, setName = setName,
        setCode = setCode, serieId = serieId, serieName = serieName, localId = localId, printedNumber = printedNumber,
        rarity = rarity, releaseDate = releaseDate, illustrator = illustrator, imageUrl = imageUrl,
        variants = variants, hidden = hidden, imageLow = low, imageHigh = high, dexId = dexId,
    )

    /**
     * Converte un prezzo salvato nel record del file.
     * @receiver PriceSnapshotEntity - prezzo.
     * @return PriceRecord - record esportabile.
     */
    private fun PriceSnapshotEntity.toRecord() = PriceRecord(
        variantId = variantId, source = source, eur = eur, eurLow = eurLow, eurAvg30 = eurAvg30, usd = usd,
        usdLow = usdLow, sourceUpdatedAt = sourceUpdatedAt, fetchedAt = fetchedAt, confidence = confidence,
    )

    private companion object {
        const val SQL_CHUNK = 500
    }
}
