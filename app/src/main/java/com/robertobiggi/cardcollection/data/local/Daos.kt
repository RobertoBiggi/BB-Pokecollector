package com.robertobiggi.cardcollection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Accesso a collezioni, ricerche, carte e varianti. Tutte le query sono parametrizzate. */
@Dao
interface CatalogDao {

    @Query("SELECT * FROM collection ORDER BY createdAt")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun getCollection(id: Long): CollectionEntity?

    @Query("SELECT name FROM collection")
    suspend fun collectionNames(): List<String>

    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("UPDATE collection SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    @Query("DELETE FROM collection WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("SELECT * FROM tracked_query ORDER BY language, pattern")
    fun observeAllQueries(): Flow<List<TrackedQueryEntity>>

    @Query("SELECT * FROM tracked_query WHERE collectionId = :collectionId")
    suspend fun queriesOf(collectionId: Long): List<TrackedQueryEntity>

    @Query("SELECT * FROM tracked_query WHERE collectionId = :collectionId AND enabled = 1")
    suspend fun enabledQueriesOf(collectionId: Long): List<TrackedQueryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuery(query: TrackedQueryEntity): Long

    @Query("UPDATE tracked_query SET enabled = :enabled WHERE id = :id")
    suspend fun setQueryEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM tracked_query WHERE id = :id")
    suspend fun deleteQuery(id: Long)

    @Query("SELECT * FROM card")
    fun observeCards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun getCard(id: String): CardEntity?

    @Query(
        "SELECT c.* FROM card c INNER JOIN collection_card cc ON cc.cardId = c.id " +
            "WHERE cc.collectionId = :collectionId AND cc.hidden = 0 AND " +
            "(cc.queryId IS NULL OR cc.queryId IN (SELECT id FROM tracked_query WHERE enabled = 1))",
    )
    suspend fun visibleCardsOf(collectionId: Long): List<CardEntity>

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

    @Query("SELECT * FROM card_variant WHERE cardId IN (:cardIds)")
    suspend fun variantsOf(cardIds: List<String>): List<CardVariantEntity>

    @Query("SELECT * FROM collection_card")
    fun observeMemberships(): Flow<List<CollectionCardEntity>>

    @Query("SELECT * FROM collection_card WHERE collectionId = :collectionId")
    suspend fun membershipsOf(collectionId: Long): List<CollectionCardEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(membership: CollectionCardEntity): Long

    @Query("UPDATE collection_card SET hidden = :hidden WHERE collectionId = :collectionId AND cardId = :cardId")
    suspend fun setHidden(collectionId: Long, cardId: String, hidden: Boolean)

    @Query("UPDATE collection_card SET hidden = 0, queryId = NULL WHERE collectionId = :collectionId AND cardId = :cardId")
    suspend fun makeManual(collectionId: Long, cardId: String)
}

/** Accesso ai dati dell'utente (quantità, condizione, slot desiderati), separati per collezione. */
@Dao
interface CollectionDao {

    @Query("SELECT * FROM collection_entry")
    fun observeEntries(): Flow<List<CollectionEntryEntity>>

    @Query("SELECT * FROM collection_entry WHERE collectionId = :collectionId")
    suspend fun entriesOf(collectionId: Long): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entry WHERE collectionId = :collectionId AND variantId = :variantId")
    suspend fun getEntry(collectionId: Long, variantId: String): CollectionEntryEntity?

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

    @Query("SELECT * FROM price_snapshot WHERE variantId IN (:variantIds) ORDER BY fetchedAt ASC")
    suspend fun snapshotsOf(variantIds: List<String>): List<PriceSnapshotEntity>

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

    @Query("SELECT * FROM price_run ORDER BY startedAt DESC LIMIT 50")
    fun observeRuns(): Flow<List<PriceRunEntity>>
}
