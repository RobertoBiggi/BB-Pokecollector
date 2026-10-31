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
import com.robertobiggi.cardcollection.core.model.VariantType
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

/** Error accessing the file chosen by the user. */
enum class FileError { NOT_FOUND, OPEN_FAILED, PERMISSION_DENIED }

/** Outcome of exporting a collection. */
sealed interface ExportResult {
    /** File written with [cards] cards, of which [withImages] with an embedded image. */
    data class Done(val cards: Int, val withImages: Int) : ExportResult

    /** Export failed. */
    data class Failed(val error: FileError) : ExportResult
}

/** Outcome of importing a collection. */
sealed interface ImportOutcome {
    /** Collection [name] created with id [collectionId]; [skipped] invalid entries ignored. */
    data class Done(val collectionId: Long, val name: String, val cards: Int, val images: Int, val skipped: Int) : ImportOutcome

    /** The file is not a valid collection JSON. */
    data class Invalid(val error: ImportError) : ImportOutcome

    /** The file cannot be read. */
    data class Failed(val error: FileError) : ImportOutcome
}

/**
 * Exports and imports single collections as JSON files (with or without images) chosen by the user
 * through the Storage Access Framework.
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
     * Exports a collection: searches, catalog cards, quantities, latest prices and manual prices.
     * @param collectionId Long - collection to export.
     * @param uri Uri - file chosen with ACTION_CREATE_DOCUMENT.
     * @param imageExport ImageExport - which images to embed.
     * @return ExportResult - cards exported or file error.
     */
    suspend fun export(collectionId: Long, uri: Uri, imageExport: ImageExport): ExportResult = withContext(Dispatchers.IO) {
        val collection = catalogDao.getCollection(collectionId) ?: return@withContext ExportResult.Failed(FileError.NOT_FOUND)
        val memberships = catalogDao.membershipsOf(collectionId)
        val cards = memberships.mapNotNull { m -> catalogDao.getCard(m.cardId)?.let { it to m.hidden } }
        val cardIds = cards.map { it.first.id }
        val variants = cardIds.chunked(SQL_CHUNK).flatMap { catalogDao.variantsOf(it) }
        val snapshots = variants.map { it.id }.chunked(SQL_CHUNK).flatMap { db.priceDao().snapshotsOf(it) }
        val typesByCard = variants.groupBy({ it.cardId }, { Ids.variantKey(VariantType.fromName(it.type) ?: VariantType.NORMAL, it.print) })

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
     * Imports a file as a new collection. Accepts schema 2 files (with or without images) and first-version
     * backups.
     * @param uri Uri - file chosen with ACTION_OPEN_DOCUMENT.
     * @param defaultName String - name to use if the file has none (first-version backup).
     * @return ImportOutcome - collection created or reason for the failure.
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
     * Writes the imported collection to the database in a single transaction.
     * Cards already in the catalog are not overwritten.
     * @param imported ImportedCollection - validated data.
     * @param desiredName String - name to give the collection (made unique if needed).
     * @return Pair<Long, String> - id and actual name of the new collection.
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
                // Card already in the catalog: it is not overwritten, but the missing variants are added
                // (otherwise the imported quantities would not be visible) and the Pokédex number.
                catalogDao.insertVariants(c.card.variants.map { CardVariantEntity(Ids.variantId(c.card.id, it.type, it.print), c.card.id, it.type.name, it.cardmarketId, it.print, it.cardTraderBlueprintId) })
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
     * Saves the images included in the file for the cards that do not have one yet.
     * @param imported ImportedCollection - validated data.
     * @return Int - number of cards for which an image was saved.
     */
    private suspend fun saveImages(imported: ImportedCollection): Int {
        var count = 0
        for (c in imported.cards) {
            if (c.imageLow == null && c.imageHigh == null) continue
            val current = catalogDao.getCard(c.card.id) ?: continue
            // A path without a file (database restored from Android backup, which does not save images) counts as a missing image.
            val currentLow = current.imageLowPath?.takeIf { withContext(Dispatchers.IO) { images.isSaved(it) } }
            val currentHigh = current.imageHighPath?.takeIf { withContext(Dispatchers.IO) { images.isSaved(it) } }
            val low = currentLow ?: c.imageLow?.let { images.write(c.card.id, ImageStore.Quality.LOW, it) }
            val high = currentHigh ?: c.imageHigh?.let { images.write(c.card.id, ImageStore.Quality.HIGH, it) }
            if (low != current.imageLowPath || high != current.imageHighPath) {
                catalogDao.setImagePaths(c.card.id, low, high)
                count++
            }
        }
        return count
    }

    /**
     * Converts a database card into the file record.
     * @receiver CardEntity - card.
     * @param variants List<String> - names of the variants.
     * @param hidden Boolean - true if removed from the collection.
     * @param low String? - small image in base64.
     * @param high String? - large image in base64.
     * @return CardRecord - exportable record.
     */
    private fun CardEntity.toRecord(variants: List<String>, hidden: Boolean, low: String?, high: String?) = CardRecord(
        id = id, language = language, tcgdexId = tcgdexId, name = name, setId = setId, setName = setName,
        setCode = setCode, serieId = serieId, serieName = serieName, localId = localId, printedNumber = printedNumber,
        rarity = rarity, releaseDate = releaseDate, illustrator = illustrator, imageUrl = imageUrl,
        variants = variants, hidden = hidden, imageLow = low, imageHigh = high, dexId = dexId,
    )

    /**
     * Converts a saved price into the file record.
     * @receiver PriceSnapshotEntity - price.
     * @return PriceRecord - exportable record.
     */
    private fun PriceSnapshotEntity.toRecord() = PriceRecord(
        variantId = variantId, source = source, eur = eur, eurLow = eurLow, eurAvg30 = eurAvg30, usd = usd,
        usdLow = usdLow, sourceUpdatedAt = sourceUpdatedAt, fetchedAt = fetchedAt, confidence = confidence,
    )

    private companion object {
        const val SQL_CHUNK = 500
    }
}
