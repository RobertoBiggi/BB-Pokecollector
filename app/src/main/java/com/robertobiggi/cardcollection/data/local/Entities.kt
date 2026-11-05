package com.robertobiggi.cardcollection.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.QueryKind

/**
 * Collection created by the user (e.g. "Mawile"). Each collection has its own searches, cards and quantities.
 * [language] is the value of [CollectionLanguage]: fixed language of the searches ("en", "ja") or "mixed".
 */
@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = CollectionLanguage.MIXED) val language: String = CollectionLanguage.MIXED,
    /**
     * `generatedAt` of the card database this collection was last updated from, or null if never ([CatalogUpdate.needsUpdate] compares it with the
     * installed one: when they differ, the collection is updated from scratch).
     */
    val catalogVersion: String? = null,
)

/**
 * Search of a collection: by name (e.g. "Mawile" in English) or entire set (e.g. "base1"), according to [kind]
 * (value of [QueryKind]: "name", "set_base", "set_master").
 */
@Entity(
    tableName = "tracked_query",
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["collectionId", "language", "pattern"], unique = true)],
)
data class TrackedQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val language: String,
    val pattern: String,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "name") val kind: String = QueryKind.NAME.code,
)

/**
 * Card of the card database, shared among all collections. The catalog sync only updates
 * this table and card_variant: user data lives in collection_entry.
 * [queryId] is a historical field of version 1 (first search that found the card): membership
 * in collections is in collection_card.
 */
@Entity(tableName = "card", indices = [Index("queryId")])
data class CardEntity(
    @PrimaryKey val id: String,
    val language: String,
    val tcgdexId: String,
    val name: String,
    val setId: String,
    val setName: String,
    val setCode: String?,
    val serieId: String?,
    val serieName: String?,
    val localId: String,
    val printedNumber: String,
    val rarity: String?,
    val releaseDate: String?,
    val illustrator: String?,
    val imageUrl: String?,
    val imageLowPath: String?,
    val imageHighPath: String?,
    val queryId: Long,
    val catalogUpdatedAt: Long,
    val dexId: Int? = null,
    /** No longer used: the CardTrader condition follows that of the variant. The column stays because databases already at version 4 exist. */
    val cardTraderCondition: String? = null,
    /** Cardmarket product id, only for cards found in the Cardmarket catalog. */
    val cardmarketId: Int? = null,
    /** CardTrader blueprint id, known after a price update that matches the card with certainty: used by the direct link. */
    val cardTraderBlueprintId: Int? = null,
    /** Cardmarket set of the card (`idExpansion`): used by the link, which searches the card in its set. */
    val cardmarketExpansionId: Int? = null,
    /** English name of the card on Cardmarket: it is searched in its set with this, not with the name in the card's language. */
    val cardmarketName: String? = null,
    /** Cardmarket product id given by TCGdex, to match the card on CardTrader when the number is not enough. */
    val cardmarketProductHint: Int? = null,
    /** Cardmarket product of the card, however found by the card database generator: the price is read from there. */
    val cardmarketProduct: Int? = null,
    /** CardTrader expansion of the card's set: used to find the image of its blueprint. */
    val cardTraderExpansionId: Int? = null,
    /**
     * Image sources of the card in the card database at the last update (TCGdex address and the paths of the other sites, as text): if the new
     * database changes them, the downloaded images are dropped and downloaded again. Null if never recorded (images already downloaded stay).
     */
    val imageKey: String? = null,
)

/** Print variant of a card: each row is a slot. */
@Entity(
    tableName = "card_variant",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class CardVariantEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val type: String,
    /** Cardmarket product of the variant, only for separate prints (shadowless, jumbo…); the others use that of the card. */
    val cardmarketId: Int? = null,
    /** Stamp or product that tells apart several variants of the same type (see [com.robertobiggi.cardcollection.core.model.CatalogVariant.print]). */
    @ColumnInfo(defaultValue = "") val print: String = "",
    /** CardTrader blueprint of the variant alone (oversized card without a Cardmarket product): the price comes from there. */
    val cardTraderBlueprintId: Int? = null,
)

/**
 * Membership of a card in a collection.
 * [queryId] is the search that added it (null if added by hand or imported): if the search is
 * disabled the card is hidden, if it is deleted the link disappears.
 * [hidden] is true if the user removed it: the sync does not add it back.
 * [removed] is true if a card database update no longer lists the card but the collection keeps it because it has user data (copies, notes,
 * wanted slots): the screen shows these cards in a separate section at the end.
 */
@Entity(
    tableName = "collection_card",
    primaryKeys = ["collectionId", "cardId"],
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackedQueryEntity::class, parentColumns = ["id"], childColumns = ["queryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId"), Index("queryId")],
)
data class CollectionCardEntity(
    val collectionId: Long,
    val cardId: String,
    val queryId: Long?,
    val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false,
)

/**
 * User data for a variant in a collection: quantities are separate per collection.
 * It has no constraints towards card_variant, so it can be imported before downloading the catalog.
 */
@Entity(
    tableName = "collection_entry",
    primaryKeys = ["collectionId", "variantId"],
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
)
data class CollectionEntryEntity(
    val collectionId: Long,
    val variantId: String,
    val quantity: Int = 0,
    val condition: String = "NM",
    val wanted: Boolean = true,
    val notes: String? = null,
)

/**
 * Price saved for a variant (shared among collections); the last 10 are kept.
 * [usd] and [usdLow] (old TCGplayer prices) are no longer written or shown: they stay for old data and backups.
 */
@Entity(tableName = "price_snapshot", indices = [Index(value = ["variantId", "fetchedAt"])])
data class PriceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val variantId: String,
    val source: String,
    val eur: Double?,
    val eurLow: Double?,
    val eurAvg30: Double?,
    val usd: Double?,
    val usdLow: Double?,
    val sourceUpdatedAt: String?,
    val fetchedAt: Long,
    val confidence: String,
)

/**
 * Price alert on a variant, set from the card detail. It is checked by the daily job
 * ([com.robertobiggi.cardcollection.work.PriceAlertWorker]) if alerts are enabled in the settings.
 * @property variantId variant being watched.
 * @property notifyChanges true for an alert when the price rises or falls beyond the threshold ([changePercent]).
 * @property targetEur price in euros near or below which to alert; null if not set.
 * @property targetReached true if the target price alert has already been sent and the price has not risen again yet.
 * @property baselines reference prices per source (see [com.robertobiggi.cardcollection.core.price.PriceAlertRules.encodeBaselines]).
 * @property createdAt when it was created or last modified.
 * @property lastCheckedAt last daily check, null if never checked.
 * @property changePercent minimum change chosen for this variant, in percent; null = the default from the settings
 *   (alerts created before the choice was possible).
 */
@Entity(
    tableName = "price_alert",
    foreignKeys = [ForeignKey(entity = CardVariantEntity::class, parentColumns = ["id"], childColumns = ["variantId"], onDelete = ForeignKey.CASCADE)],
)
data class PriceAlertEntity(
    @PrimaryKey val variantId: String,
    val notifyChanges: Boolean,
    val targetEur: Double?,
    val targetReached: Boolean,
    val baselines: String,
    val createdAt: Long,
    val lastCheckedAt: Long?,
    val changePercent: Int? = null,
)

/**
 * Log of a price update. [summary] contains the reason for the interruption
 * ([com.robertobiggi.cardcollection.data.repo.AbortReason]) or null; [collectionId] is null for version 1 runs.
 */
@Entity(tableName = "price_run")
data class PriceRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
    val okCount: Int,
    val fallbackCount: Int,
    val failCount: Int,
    val summary: String?,
    val collectionId: Long? = null,
)
