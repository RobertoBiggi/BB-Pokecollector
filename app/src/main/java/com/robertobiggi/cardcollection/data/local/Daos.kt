package com.robertobiggi.cardcollection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Access to collections, searches, cards and variants. All queries are parameterized. */
@Dao
interface CatalogDao {

    /**
     * Observes all collections, in creation order.
     * @return Flow<List<CollectionEntity>> - collections updated at every change.
     */
    @Query("SELECT * FROM collection ORDER BY createdAt")
    fun observeCollections(): Flow<List<CollectionEntity>>

    /**
     * Reads a collection.
     * @param id Long - row id.
     * @return CollectionEntity? - collection, or null if it does not exist.
     */
    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun getCollection(id: Long): CollectionEntity?

    /**
     * Lists the names of all collections (to make a new one unique).
     * @return List<String> - names.
     */
    @Query("SELECT name FROM collection")
    suspend fun collectionNames(): List<String>

    /**
     * Reads the name of a collection.
     * @param id Long - collection.
     * @return String? - name, or null if it does not exist.
     */
    @Query("SELECT name FROM collection WHERE id = :id")
    suspend fun collectionName(id: Long): String?

    /**
     * Creates a collection.
     * @param collection CollectionEntity - collection to create.
     * @return Long - assigned id.
     */
    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    /**
     * Renames a collection.
     * @param id Long - row id.
     * @param name String - new name.
     * @return Unit
     */
    @Query("UPDATE collection SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    /**
     * Changes the language of a collection.
     * @param id Long - row id.
     * @param language String - value of CollectionLanguage ("en", "ja" or "mixed").
     * @return Unit
     */
    @Query("UPDATE collection SET language = :language WHERE id = :id")
    suspend fun setCollectionLanguage(id: Long, language: String)

    /**
     * Deletes a collection; searches, memberships and quantities are deleted in cascade.
     * @param id Long - row id.
     * @return Unit
     */
    @Query("DELETE FROM collection WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    /**
     * Observes the searches of all collections.
     * @return Flow<List<TrackedQueryEntity>> - searches ordered by language and text.
     */
    @Query("SELECT * FROM tracked_query ORDER BY language, pattern")
    fun observeAllQueries(): Flow<List<TrackedQueryEntity>>

    /**
     * Lists the searches of a collection.
     * @param collectionId Long - collection.
     * @return List<TrackedQueryEntity> - searches.
     */
    @Query("SELECT * FROM tracked_query WHERE collectionId = :collectionId")
    suspend fun queriesOf(collectionId: Long): List<TrackedQueryEntity>

    /**
     * Lists the active searches of a collection.
     * @param collectionId Long - collection.
     * @return List<TrackedQueryEntity> - active searches.
     */
    @Query("SELECT * FROM tracked_query WHERE collectionId = :collectionId AND enabled = 1")
    suspend fun enabledQueriesOf(collectionId: Long): List<TrackedQueryEntity>

    /**
     * Adds a search, ignoring it if it already exists in the collection.
     * @param query TrackedQueryEntity - search to add.
     * @return Long - assigned id, or -1 if duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuery(query: TrackedQueryEntity): Long

    /**
     * Enables or disables a search.
     * @param id Long - row id.
     * @param enabled Boolean - true to enable it.
     * @return Unit
     */
    @Query("UPDATE tracked_query SET enabled = :enabled WHERE id = :id")
    suspend fun setQueryEnabled(id: Long, enabled: Boolean)

    /**
     * Deletes a search.
     * @param id Long - row id.
     * @return Unit
     */
    @Query("DELETE FROM tracked_query WHERE id = :id")
    suspend fun deleteQuery(id: Long)

    /**
     * Lists the cards that no collection contains anymore and for which no collection has quantity or notes, updated before [before]
     * (the margin avoids cards just saved by a sync that has not linked them yet).
     * @param before Long - milliseconds: only cards updated before are considered.
     * @return List<CardEntity> - orphan cards.
     */
    @Query(
        "SELECT * FROM card WHERE catalogUpdatedAt < :before AND id NOT IN (SELECT cardId FROM collection_card) " +
            "AND id NOT IN (SELECT v.cardId FROM card_variant v JOIN collection_entry e ON e.variantId = v.id)",
    )
    suspend fun orphanCards(before: Long): List<CardEntity>

    /**
     * Deletes catalog cards; their variants go in cascade.
     * @param ids List<String> - ids of the cards.
     * @return Unit
     */
    @Query("DELETE FROM card WHERE id IN (:ids)")
    suspend fun deleteCards(ids: List<String>)

    /**
     * Observes all the cards of the shared catalog.
     * @return Flow<List<CardEntity>> - cards.
     */
    @Query("SELECT * FROM card")
    fun observeCards(): Flow<List<CardEntity>>

    /**
     * Reads a catalog card.
     * @param id String - row id.
     * @return CardEntity? - card, or null if it does not exist.
     */
    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun getCard(id: String): CardEntity?

    /**
     * Lists the visible cards of a collection: not hidden and added by hand or by an active search.
     * @param collectionId Long - collection.
     * @return List<CardEntity> - visible cards.
     */
    @Query(
        "SELECT c.* FROM card c INNER JOIN collection_card cc ON cc.cardId = c.id " +
            "WHERE cc.collectionId = :collectionId AND cc.hidden = 0 AND " +
            "(cc.queryId IS NULL OR cc.queryId IN (SELECT id FROM tracked_query WHERE enabled = 1))",
    )
    suspend fun visibleCardsOf(collectionId: Long): List<CardEntity>

    /**
     * Lists the cards with an image URL but without all images saved.
     * @return List<CardEntity> - cards to complete.
     */
    @Query("SELECT * FROM card WHERE imageUrl IS NOT NULL AND (imageLowPath IS NULL OR imageHighPath IS NULL)")
    suspend fun cardsMissingImages(): List<CardEntity>

    /**
     * Removes the image URL from a card that TCGdex does not have (404 response), only if it has no other images: this way the card moves
     * to the other sources (CardTrader) instead of staying forever "with an image to download".
     * @param id String - row id.
     * @return Unit
     */
    @Query("UPDATE card SET imageUrl = NULL WHERE id = :id AND imageLowPath IS NULL AND imageHighPath IS NULL")
    suspend fun clearImageUrl(id: String)

    /**
     * Lists the English and Japanese cards without an image URL or saved images (or manually chosen ones).
     * @return List<CardEntity> - cards for which another source can be tried.
     */
    @Query("SELECT * FROM card WHERE imageUrl IS NULL AND imageLowPath IS NULL AND imageHighPath IS NULL AND language IN ('en', 'ja')")
    suspend fun cardsWithoutImage(): List<CardEntity>

    /**
     * Inserts or updates a catalog card.
     * @param card CardEntity - card to save.
     * @return Unit
     */
    @Upsert
    suspend fun upsertCard(card: CardEntity)

    /**
     * Saves the local image paths of a card.
     * @param id String - row id.
     * @param low String? - path of the small image.
     * @param high String? - path of the large image.
     * @return Unit
     */
    @Query("UPDATE card SET imageLowPath = :low, imageHighPath = :high WHERE id = :id")
    suspend fun setImagePaths(id: String, low: String?, high: String?)

    /**
     * Fills in the missing image paths without touching those already saved (even by another job in the meantime).
     * @param id String - row id.
     * @param low String? - path of the small image just downloaded.
     * @param high String? - path of the large image just downloaded.
     * @return Unit
     */
    @Query("UPDATE card SET imageLowPath = COALESCE(imageLowPath, :low), imageHighPath = COALESCE(imageHighPath, :high) WHERE id = :id")
    suspend fun fillImagePaths(id: String, low: String?, high: String?)

    /**
     * Forgets an image path whose file no longer exists, only if it is still the saved one.
     * @param id String - row id.
     * @param path String - path to remove.
     * @return Unit
     */
    @Query(
        "UPDATE card SET imageLowPath = CASE WHEN imageLowPath = :path THEN NULL ELSE imageLowPath END, " +
            "imageHighPath = CASE WHEN imageHighPath = :path THEN NULL ELSE imageHighPath END WHERE id = :id",
    )
    suspend fun forgetImagePath(id: String, path: String)

    /**
     * Lists the cards with at least one saved image path.
     * @return List<CardEntity> - cards to check.
     */
    @Query("SELECT * FROM card WHERE imageLowPath IS NOT NULL OR imageHighPath IS NOT NULL")
    suspend fun cardsWithImagePaths(): List<CardEntity>

    /**
     * Sets the Pokédex number of a card only if it is not known yet.
     * @param id String - row id.
     * @param dexId Int - Pokédex number.
     * @return Unit
     */
    @Query("UPDATE card SET dexId = :dexId WHERE id = :id AND dexId IS NULL")
    suspend fun fillDexId(id: String, dexId: Int)

    /**
     * Adds variants, ignoring those already present.
     * @param variants List<CardVariantEntity> - variants to add.
     * @return Unit
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVariants(variants: List<CardVariantEntity>)

    /**
     * Saves the Cardmarket product of a separate print (also on an already present variant, for example imported from a file).
     * @param variantId String - variant.
     * @param cardmarketId Int - Cardmarket product.
     */
    @Query("UPDATE card_variant SET cardmarketId = :cardmarketId WHERE id = :variantId")
    suspend fun setVariantProduct(variantId: String, cardmarketId: Int)

    /**
     * Sets the CardTrader blueprint of a variant (oversized card without a Cardmarket product), even if it was already in the database.
     * @param variantId String - id of the variant.
     * @param blueprintId Int - CardTrader blueprint.
     */
    @Query("UPDATE card_variant SET cardTraderBlueprintId = :blueprintId WHERE id = :variantId")
    suspend fun setVariantBlueprint(variantId: String, blueprintId: Int)

    /**
     * Removes the variants that the catalog no longer lists (e.g. a wrongly inferred reverse holo), except those of which the user
     * owns copies or has written notes.
     * @param cardId String - card.
     * @param types List<String> - variant types to keep.
     */
    @Query("DELETE FROM card_variant WHERE cardId = :cardId AND id NOT IN (:keepIds) AND id NOT IN (SELECT variantId FROM collection_entry WHERE quantity > 0 OR notes IS NOT NULL)")
    suspend fun deleteStaleVariants(cardId: String, keepIds: List<String>)

    /**
     * Observes all the variants of the catalog.
     * @return Flow<List<CardVariantEntity>> - variants.
     */
    @Query("SELECT * FROM card_variant")
    fun observeVariants(): Flow<List<CardVariantEntity>>

    /**
     * Lists the variants of some cards.
     * @param cardIds List<String> - ids of the cards.
     * @return List<CardVariantEntity> - variants.
     */
    @Query("SELECT * FROM card_variant WHERE cardId IN (:cardIds)")
    suspend fun variantsOf(cardIds: List<String>): List<CardVariantEntity>

    /**
     * Reads some variants from their id.
     * @param ids List<String> - ids of the variants.
     * @return List<CardVariantEntity> - variants found.
     */
    @Query("SELECT * FROM card_variant WHERE id IN (:ids)")
    suspend fun variantsByIds(ids: List<String>): List<CardVariantEntity>

    /**
     * First collection (by id) that contains a card, to open its detail from a notification.
     * @param cardId String - card.
     * @return Long? - id of the collection, or null if none contains it.
     */
    @Query("SELECT collectionId FROM collection_card WHERE cardId = :cardId ORDER BY hidden ASC, collectionId ASC LIMIT 1")
    suspend fun collectionOfCard(cardId: String): Long?

    /**
     * Observes the card ↔ collection memberships of all collections.
     * @return Flow<List<CollectionCardEntity>> - memberships.
     */
    @Query("SELECT * FROM collection_card")
    fun observeMemberships(): Flow<List<CollectionCardEntity>>

    /**
     * Lists the memberships of a collection.
     * @param collectionId Long - collection.
     * @return List<CollectionCardEntity> - memberships.
     */
    @Query("SELECT * FROM collection_card WHERE collectionId = :collectionId")
    suspend fun membershipsOf(collectionId: Long): List<CollectionCardEntity>

    /**
     * Links a card to a collection, ignoring links already present.
     * @param membership CollectionCardEntity - link to add.
     * @return Long - row id, or -1 if it already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(membership: CollectionCardEntity): Long

    /**
     * Hides or restores a card in a collection.
     * @param collectionId Long - collection.
     * @param cardId String - card.
     * @param hidden Boolean - true to hide it.
     * @return Unit
     */
    @Query("UPDATE collection_card SET hidden = :hidden WHERE collectionId = :collectionId AND cardId = :cardId")
    suspend fun setHidden(collectionId: Long, cardId: String, hidden: Boolean)

    /**
     * Removes from a collection the cards found by a search that the catalog no longer lists, if they have no copies, notes and are not wanted.
     * @param collectionId Long - collection.
     * @param cardIds List<String> - cards to remove.
     * @return Unit
     */
    @Query(
        "DELETE FROM collection_card WHERE collectionId = :collectionId AND queryId IS NOT NULL AND cardId IN (:cardIds) AND cardId NOT IN " +
            "(SELECT v.cardId FROM card_variant v JOIN collection_entry e ON e.variantId = v.id " +
            "WHERE e.collectionId = :collectionId AND (e.quantity > 0 OR e.notes IS NOT NULL OR e.wanted = 1))",
    )
    suspend fun dropStaleMemberships(collectionId: Long, cardIds: List<String>)

    /**
     * Makes a card visible as added by hand (no longer depends on a search).
     * @param collectionId Long - collection.
     * @param cardId String - card.
     * @return Unit
     */
    @Query("UPDATE collection_card SET hidden = 0, queryId = NULL WHERE collectionId = :collectionId AND cardId = :cardId")
    suspend fun makeManual(collectionId: Long, cardId: String)
}

/** Access to the user data (quantity, condition, wanted slots), separate per collection. */
@Dao
interface CollectionDao {

    /**
     * Observes the user data of all collections.
     * @return Flow<List<CollectionEntryEntity>> - entries.
     */
    @Query("SELECT * FROM collection_entry")
    fun observeEntries(): Flow<List<CollectionEntryEntity>>

    /**
     * Lists the user data of a collection.
     * @param collectionId Long - collection.
     * @return List<CollectionEntryEntity> - entries.
     */
    @Query("SELECT * FROM collection_entry WHERE collectionId = :collectionId")
    suspend fun entriesOf(collectionId: Long): List<CollectionEntryEntity>

    /**
     * Reads the user data for a variant of a collection.
     * @param collectionId Long - collection.
     * @param variantId String - variant.
     * @return CollectionEntryEntity? - entry, or null if never modified.
     */
    @Query("SELECT * FROM collection_entry WHERE collectionId = :collectionId AND variantId = :variantId")
    suspend fun getEntry(collectionId: Long, variantId: String): CollectionEntryEntity?

    /**
     * Lists the user data for a variant, in all collections.
     * @param variantId String - variant.
     * @return List<CollectionEntryEntity> - data.
     */
    @Query("SELECT * FROM collection_entry WHERE variantId = :variantId")
    suspend fun entriesOfVariant(variantId: String): List<CollectionEntryEntity>

    /**
     * Deletes the user data for a variant, in all collections.
     * @param variantId String - variant.
     * @return Unit
     */
    @Query("DELETE FROM collection_entry WHERE variantId = :variantId")
    suspend fun deleteEntriesOfVariant(variantId: String)

    /**
     * Inserts or updates the user data for a variant.
     * @param entry CollectionEntryEntity - entry to save.
     * @return Unit
     */
    @Upsert
    suspend fun upsertEntry(entry: CollectionEntryEntity)

    /**
     * Inserts or updates several user entries.
     * @param entries List<CollectionEntryEntity> - entries to save.
     * @return Unit
     */
    @Upsert
    suspend fun upsertEntries(entries: List<CollectionEntryEntity>)

    /**
     * Inserts the entries that do not exist yet, leaving unchanged those already saved (user choices included).
     * @param entries List<CollectionEntryEntity> - entries to add.
     * @return Unit
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntriesIfAbsent(entries: List<CollectionEntryEntity>)
}

/** Access to prices and the update log. */
@Dao
interface PriceDao {

    /**
     * Observes the price alerts of the variants still present.
     * @return Flow<List<PriceAlertEntity>> - alerts.
     */
    @Query("SELECT * FROM price_alert WHERE variantId IN (SELECT id FROM card_variant)")
    fun observeAlerts(): Flow<List<PriceAlertEntity>>

    /**
     * Lists the price alerts of the variants still present.
     * @return List<PriceAlertEntity> - alerts.
     */
    @Query("SELECT * FROM price_alert WHERE variantId IN (SELECT id FROM card_variant)")
    suspend fun alerts(): List<PriceAlertEntity>

    /**
     * Reads the price alert of a variant.
     * @param variantId String - variant.
     * @return PriceAlertEntity? - alert, or null if there is none.
     */
    @Query("SELECT * FROM price_alert WHERE variantId = :variantId")
    suspend fun getAlert(variantId: String): PriceAlertEntity?

    /**
     * Creates or replaces the price alert of a variant.
     * @param alert PriceAlertEntity - alert.
     * @return Unit
     */
    @Upsert
    suspend fun upsertAlert(alert: PriceAlertEntity)

    /**
     * Removes the price alert of a variant.
     * @param variantId String - variant.
     * @return Unit
     */
    @Query("DELETE FROM price_alert WHERE variantId = :variantId")
    suspend fun deleteAlert(variantId: String)

    /**
     * Saves the state of an alert after the daily check, without touching the user's choices.
     * @param variantId String - variant.
     * @param baselines String - encoded reference prices.
     * @param targetReached Boolean - true if the target price alert has been sent.
     * @param checkedAt Long - instant of the check.
     * @return Unit
     */
    @Query("UPDATE price_alert SET baselines = :baselines, targetReached = :targetReached, lastCheckedAt = :checkedAt WHERE variantId = :variantId")
    suspend fun updateAlertState(variantId: String, baselines: String, targetReached: Boolean, checkedAt: Long)

    /**
     * Removes the alerts of the variants of some cards (cards removed from the catalog).
     * @param cardIds List<String> - ids of the cards.
     * @return Unit
     */
    @Query("DELETE FROM price_alert WHERE variantId IN (SELECT id FROM card_variant WHERE cardId IN (:cardIds))")
    suspend fun deleteAlertsOfCards(cardIds: List<String>)

    /**
     * Deletes the saved prices for the variants of some cards.
     * @param cardIds List<String> - ids of the cards.
     * @return Unit
     */
    @Query("DELETE FROM price_snapshot WHERE variantId IN (SELECT id FROM card_variant WHERE cardId IN (:cardIds))")
    suspend fun deleteSnapshotsOfCards(cardIds: List<String>)

    /**
     * Deletes the price update log of a collection.
     * @param collectionId Long - collection.
     * @return Unit
     */
    @Query("DELETE FROM price_run WHERE collectionId = :collectionId")
    suspend fun deleteRunsOf(collectionId: Long)

    /**
     * Observes all saved prices, from oldest to most recent.
     * @return Flow<List<PriceSnapshotEntity>> - prices.
     */
    @Query("SELECT * FROM price_snapshot ORDER BY fetchedAt ASC")
    fun observeSnapshots(): Flow<List<PriceSnapshotEntity>>

    /**
     * Lists the prices of some variants, from oldest to most recent.
     * @param variantIds List<String> - ids of the variants.
     * @return List<PriceSnapshotEntity> - prices.
     */
    @Query("SELECT * FROM price_snapshot WHERE variantId IN (:variantIds) ORDER BY fetchedAt ASC")
    suspend fun snapshotsOf(variantIds: List<String>): List<PriceSnapshotEntity>

    /**
     * Saves a price.
     * @param snapshot PriceSnapshotEntity - price to save.
     * @return Long - assigned id.
     */
    @Insert
    suspend fun insertSnapshot(snapshot: PriceSnapshotEntity): Long

    /**
     * Deletes the prices of a variant that come from a source.
     * @param variantId String - variant.
     * @param source String - source of the prices to delete.
     * @return Unit
     */
    @Query("DELETE FROM price_snapshot WHERE variantId = :variantId AND source = :source")
    suspend fun deleteSnapshotsOf(variantId: String, source: String)

    /**
     * Keeps only the most recent prices of a variant.
     * @param variantId String - variant.
     * @param keep Int - how many to keep.
     * @return Unit
     */
    @Query(
        "DELETE FROM price_snapshot WHERE variantId = :variantId AND id NOT IN " +
            "(SELECT id FROM price_snapshot WHERE variantId = :variantId ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun pruneSnapshots(variantId: String, keep: Int)

    /**
     * Counts identical prices (same variant, source and date), to avoid importing duplicates.
     * @param variantId String - variant.
     * @param source String - source of the price.
     * @param fetchedAt Long - date of the price in milliseconds.
     * @return Int - number of prices found.
     */
    @Query("SELECT COUNT(*) FROM price_snapshot WHERE variantId = :variantId AND source = :source AND fetchedAt = :fetchedAt")
    suspend fun countSnapshot(variantId: String, source: String, fetchedAt: Long): Int

    /**
     * Records the start of a price update.
     * @param run PriceRunEntity - update to save.
     * @return Long - assigned id.
     */
    @Insert
    suspend fun insertRun(run: PriceRunEntity): Long

    /**
     * Updates the log of a price update.
     * @param run PriceRunEntity - update to save.
     * @return Unit
     */
    @Update
    suspend fun updateRun(run: PriceRunEntity)

    /**
     * Observes the log of price updates, most recent first.
     * @return Flow<List<PriceRunEntity>> - updates.
     */
    @Query("SELECT * FROM price_run ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<PriceRunEntity>>

    /**
     * Keeps only the most recent price updates of a collection.
     * @param collectionId Long - collection.
     * @param keep Int - how many to keep.
     * @return Unit
     */
    @Query(
        "DELETE FROM price_run WHERE collectionId = :collectionId AND id NOT IN " +
            "(SELECT id FROM price_run WHERE collectionId = :collectionId ORDER BY startedAt DESC LIMIT :keep)",
    )
    suspend fun pruneRuns(collectionId: Long, keep: Int)
}
