package com.robertobiggi.cardcollection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Accesso a ricerche, carte e varianti del catalogo. Tutte le query sono parametrizzate. */
@Dao
interface CatalogDao {

    @Query("SELECT * FROM tracked_query ORDER BY language, pattern")
    fun observeQueries(): Flow<List<TrackedQueryEntity>>

    @Query("SELECT * FROM tracked_query")
    suspend fun allQueries(): List<TrackedQueryEntity>

    @Query("SELECT * FROM tracked_query WHERE enabled = 1")
    suspend fun enabledQueries(): List<TrackedQueryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuery(query: TrackedQueryEntity): Long

    @Query("UPDATE tracked_query SET enabled = :enabled WHERE id = :id")
    suspend fun setQueryEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM tracked_query WHERE id = :id")
    suspend fun deleteQuery(id: Long)

    @Query("SELECT * FROM card WHERE queryId IN (SELECT id FROM tracked_query WHERE enabled = 1)")
    fun observeTrackedCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM card WHERE queryId IN (SELECT id FROM tracked_query WHERE enabled = 1)")
    suspend fun trackedCards(): List<CardEntity>

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun getCard(id: String): CardEntity?

    @Query("SELECT * FROM card WHERE imageUrl IS NOT NULL AND (imageLowPath IS NULL OR imageHighPath IS NULL)")
    suspend fun cardsMissingImages(): List<CardEntity>

    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("UPDATE card SET imageLowPath = :low, imageHighPath = :high WHERE id = :id")
    suspend fun setImagePaths(id: String, low: String?, high: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVariants(variants: List<CardVariantEntity>)

    @Query("SELECT * FROM card_variant")
    fun observeVariants(): Flow<List<CardVariantEntity>>

    @Query("SELECT * FROM card_variant")
    suspend fun allVariants(): List<CardVariantEntity>
}

/** Accesso ai dati dell'utente (quantità, condizione, slot desiderati). */
@Dao
interface CollectionDao {

    @Query("SELECT * FROM collection_entry")
    fun observeEntries(): Flow<List<CollectionEntryEntity>>

    @Query("SELECT * FROM collection_entry")
    suspend fun allEntries(): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entry WHERE variantId = :variantId")
    suspend fun getEntry(variantId: String): CollectionEntryEntity?

    @Upsert
    suspend fun upsertEntry(entry: CollectionEntryEntity)

    @Upsert
    suspend fun upsertEntries(entries: List<CollectionEntryEntity>)
}

/** Accesso a prezzi e registro degli aggiornamenti. */
@Dao
interface PriceDao {

    @Query("SELECT * FROM price_snapshot ORDER BY fetchedAt ASC")
    fun observeSnapshots(): Flow<List<PriceSnapshotEntity>>

    @Query("SELECT * FROM price_snapshot WHERE source = :source")
    suspend fun snapshotsBySource(source: String): List<PriceSnapshotEntity>

    @Insert
    suspend fun insertSnapshot(snapshot: PriceSnapshotEntity): Long

    @Query(
        "DELETE FROM price_snapshot WHERE variantId = :variantId AND id NOT IN " +
            "(SELECT id FROM price_snapshot WHERE variantId = :variantId ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun pruneSnapshots(variantId: String, keep: Int)

    @Query("SELECT COUNT(*) FROM price_snapshot WHERE variantId = :variantId AND source = :source AND fetchedAt = :fetchedAt")
    suspend fun countSnapshot(variantId: String, source: String, fetchedAt: Long): Int

    @Insert
    suspend fun insertRun(run: PriceRunEntity): Long

    @Update
    suspend fun updateRun(run: PriceRunEntity)

    @Query("SELECT * FROM price_run ORDER BY startedAt DESC LIMIT 10")
    fun observeRuns(): Flow<List<PriceRunEntity>>
}
