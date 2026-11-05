package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.stats.CollectionStats
import com.robertobiggi.cardcollection.core.stats.SlotInput
import com.robertobiggi.cardcollection.core.stats.StatsSummary
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionCardEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity

/**
 * Variant ready for the UI, with the user's data and the latest prices.
 *
 * @property latestEur latest snapshot with a price in euros.
 * @property latestCardTrader latest CardTrader snapshot (kept separate from Cardmarket).
 * @property eurHistory prices in euros from oldest to most recent (for the sparkline).
 * @property cardTraderHistory CardTrader prices from oldest to most recent.
 */
data class VariantUi(
    val id: String,
    val type: VariantType,
    val print: String = "",
    val wanted: Boolean,
    val quantity: Int,
    val condition: Condition,
    val latestEur: PriceSnapshotEntity?,
    val latestCardTrader: PriceSnapshotEntity? = null,
    val lastSnapshot: PriceSnapshotEntity?,
    val eurHistory: List<Double>,
    val cardTraderHistory: List<Double> = emptyList(),
) {
    /**
     * Converts the variant into the input of the statistics computation.
     * @return SlotInput - minimal data for [com.robertobiggi.cardcollection.core.stats.CollectionStats].
     */
    fun toSlotInput(): SlotInput = SlotInput(wanted, quantity, latestEur?.eur, latestCardTrader?.eur)
}

/** Card ready for the UI with its variants. */
data class CardUi(
    val card: CardEntity,
    val variants: List<VariantUi>,
    /** True if a card database update no longer lists the card but the collection keeps it (it has data of the user): shown in a section at the end. */
    val removedByUpdate: Boolean = false,
) {
    val language: Language get() = Language.fromCode(card.language) ?: Language.EN

    /** Code printed at the bottom left, e.g. "M1L 046/063". */
    val printedCode: String get() = card.printedCode()

    /** Slots of the card: wanted or owned variants (same rule as [CollectionStats]). */
    val slots: Int get() = variants.count { it.wanted || it.quantity > 0 }

    /** Owned variants, with at least one copy. */
    val ownedSlots: Int get() = variants.count { it.quantity > 0 }
    val copies: Int get() = variants.sumOf { it.quantity }

    /** Lowest price in euros among the variants, used in the grid ("from 0,30 €"). */
    val minEur: Double? get() = variants.mapNotNull { it.latestEur?.eur }.minOrNull()

    /**
     * Rarity to show in place of "Holo" on holo-only cards (Double Rare, Illustration Rare…), where "Holo" says nothing.
     * It stays "Holo" if the card also has the normal version, if the rarity is missing, if it is already a holo rare or if it is a
     * plain rarity (Common, Uncommon, Rare), which says nothing about the holo print.
     * @param variant VariantUi - variant of the card.
     * @return String? - rarity, or null if the variant name is kept.
     */
    fun rarityLabelFor(variant: VariantUi): String? {
        if (variant.type != VariantType.HOLO || variants.any { it.type == VariantType.NORMAL }) return null
        // "Fixed" is the "rarity" CardTrader gives to cards of preconstructed decks: it says nothing about the card.
        return card.rarity?.trim()?.takeIf { it.isNotEmpty() && !it.contains("holo", ignoreCase = true) && !it.equals("Fixed", ignoreCase = true) && it.lowercase() !in PLAIN_RARITIES }
    }

    /** Path of the best local image for the grid. */
    val thumbnailPath: String? get() = card.imageLowPath ?: card.imageHighPath

    companion object {
        private val PLAIN_RARITIES = setOf("common", "uncommon", "rare")

        /** Release order: set date, then set and number (cards without a date at the end). */
        val RELEASE_ORDER: Comparator<CardUi> =
            compareBy<CardUi>({ it.card.releaseDate ?: "9999" }, { it.card.setId }, { it.card.localId.padStart(4, '0') })
    }
}

/** Summary of a collection for the initial list. */
data class CollectionSummary(
    val collection: CollectionEntity,
    val cardCount: Int,
    val stats: StatsSummary,
    val coverPath: String?,
)

/** Snapshot of the observed tables, from which the library and summaries of all collections are derived. */
data class DbState(
    val collections: List<CollectionEntity>,
    val memberships: List<CollectionCardEntity>,
    val queries: List<TrackedQueryEntity>,
    val cards: List<CardEntity>,
    val variants: List<CardVariantEntity>,
    val entries: List<CollectionEntryEntity>,
    val snapshots: List<PriceSnapshotEntity>,
    val runs: List<PriceRunEntity>,
    /** False if the 1st Edition versions do not count: without a user choice they are not wanted. */
    val countFirstEdition: Boolean = true,
)

/** Building of the UI models from the database tables. */
object LibraryAssembler {

    /**
     * Computes which cards are visible in a collection: not hidden and added by hand or
     * by an active search.
     * @param collectionId Long - collection.
     * @param memberships List<CollectionCardEntity> - memberships of all collections.
     * @param queries List<TrackedQueryEntity> - searches of all collections.
     * @return Set<String> - ids of the visible cards.
     */
    fun visibleCardIds(collectionId: Long, memberships: List<CollectionCardEntity>, queries: List<TrackedQueryEntity>): Set<String> {
        val enabled = queries.filter { it.enabled }.map { it.id }.toSet()
        return memberships.filter { it.collectionId == collectionId && !it.hidden && (it.queryId == null || it.queryId in enabled) }
            .map { it.cardId }.toSet()
    }

    /**
     * Builds the library of a collection from the database snapshot.
     * @param state DbState - observed tables.
     * @param collectionId Long - collection.
     * @return List<CardUi> - visible cards with variants, collection quantities and prices.
     */
    fun libraryOf(state: DbState, collectionId: Long): List<CardUi> =
        libraryOf(state, collectionId, state.variants.groupBy { it.cardId }, state.snapshots.groupBy { it.variantId })

    /**
     * Like [libraryOf], with variants and prices already grouped (to avoid redoing it for each collection).
     * @param state DbState - observed tables.
     * @param collectionId Long - collection.
     * @param variantsByCard Map<String, List<CardVariantEntity>> - variants per card.
     * @param snapsByVariant Map<String, List<PriceSnapshotEntity>> - prices per variant, oldest first.
     * @return List<CardUi> - visible cards of the collection.
     */
    private fun libraryOf(
        state: DbState,
        collectionId: Long,
        variantsByCard: Map<String, List<CardVariantEntity>>,
        snapsByVariant: Map<String, List<PriceSnapshotEntity>>,
    ): List<CardUi> {
        val ids = visibleCardIds(collectionId, state.memberships, state.queries)
        return assembleGrouped(
            cards = state.cards.filter { it.id in ids },
            variantsByCard = variantsByCard,
            entryById = state.entries.filter { it.collectionId == collectionId }.associateBy { it.variantId },
            snapsByVariant = snapsByVariant,
            countFirstEdition = state.countFirstEdition,
            removedIds = state.memberships.filter { it.collectionId == collectionId && it.removed }.map { it.cardId }.toSet(),
        )
    }

    /**
     * Summaries of all collections for the initial list.
     * @param state DbState - observed tables.
     * @return List<CollectionSummary> - one entry per collection, in creation order.
     */
    fun summaries(state: DbState): List<CollectionSummary> {
        val variantsByCard = state.variants.groupBy { it.cardId }
        val snapsByVariant = state.snapshots.groupBy { it.variantId }
        return state.collections.map { collection -> summaryOf(state, collection, variantsByCard, snapsByVariant) }
    }

    /**
     * Summary of a collection.
     * @param state DbState - observed tables.
     * @param collection CollectionEntity - collection.
     * @param variantsByCard Map<String, List<CardVariantEntity>> - variants per card.
     * @param snapsByVariant Map<String, List<PriceSnapshotEntity>> - prices per variant.
     * @return CollectionSummary - number of cards, statistics and cover.
     */
    private fun summaryOf(
        state: DbState,
        collection: CollectionEntity,
        variantsByCard: Map<String, List<CardVariantEntity>>,
        snapsByVariant: Map<String, List<PriceSnapshotEntity>>,
    ): CollectionSummary {
        val library = libraryOf(state, collection.id, variantsByCard, snapsByVariant)
        val cover = library.firstOrNull { it.copies > 0 && it.thumbnailPath != null }?.thumbnailPath
            ?: library.firstOrNull { it.thumbnailPath != null }?.thumbnailPath
        return CollectionSummary(
            collection = collection,
            cardCount = library.size,
            stats = statsOf(library),
            coverPath = cover,
        )
    }

    /**
     * Statistics of a set of cards.
     * @param cards List<CardUi> - cards to consider.
     * @return StatsSummary - completion, value and missing ones.
     */
    fun statsOf(cards: List<CardUi>): StatsSummary = CollectionStats.compute(cards.flatMap { c -> c.variants.map { it.toSlotInput() } })

    /**
     * Joins cards, variants, user data and prices.
     * @param cards List<CardEntity> - tracked cards.
     * @param variants List<CardVariantEntity> - all variants.
     * @param entries List<CollectionEntryEntity> - user data.
     * @param snapshots List<PriceSnapshotEntity> - prices sorted from oldest to most recent.
     * @return List<CardUi> - cards with variants sorted according to [VariantType].
     */
    fun assemble(
        cards: List<CardEntity>,
        variants: List<CardVariantEntity>,
        entries: List<CollectionEntryEntity>,
        snapshots: List<PriceSnapshotEntity>,
    ): List<CardUi> = assembleGrouped(cards, variants.groupBy { it.cardId }, entries.associateBy { it.variantId }, snapshots.groupBy { it.variantId })

    /**
     * Joins cards, variants, user data and already grouped prices.
     * @param cards List<CardEntity> - tracked cards.
     * @param variantsByCard Map<String, List<CardVariantEntity>> - variants per card.
     * @param entryById Map<String, CollectionEntryEntity> - user data per variant.
     * @param snapsByVariant Map<String, List<PriceSnapshotEntity>> - prices per variant, oldest first.
     * @param countFirstEdition Boolean - if false, a 1st Edition without a user choice is not wanted (does not count among the missing).
     * @param removedIds Set<String> - cards that a database update no longer lists but the collection keeps.
     * @return List<CardUi> - cards with variants sorted according to [VariantType].
     */
    private fun assembleGrouped(
        cards: List<CardEntity>,
        variantsByCard: Map<String, List<CardVariantEntity>>,
        entryById: Map<String, CollectionEntryEntity>,
        snapsByVariant: Map<String, List<PriceSnapshotEntity>>,
        countFirstEdition: Boolean = true,
        removedIds: Set<String> = emptySet(),
    ): List<CardUi> {
        return cards.map { card ->
            val list = variantsByCard[card.id].orEmpty().mapNotNull { v ->
                val type = VariantType.fromName(v.type) ?: return@mapNotNull null
                val entry = entryById[v.id]
                val snaps = snapsByVariant[v.id].orEmpty()
                VariantUi(
                    id = v.id,
                    type = type,
                    print = v.print,
                    wanted = entry?.wanted ?: when (type) {
                        VariantType.FIRST_EDITION -> countFirstEdition
                        // Stamps (tournaments, prereleases…) are many: they only count if the user marks them as wanted.
                        VariantType.STAMPED -> false
                        else -> true
                    },
                    quantity = entry?.quantity ?: 0,
                    condition = Condition.entries.firstOrNull { it.name == entry?.condition } ?: Condition.NM,
                    latestEur = snaps.lastOrNull { it.eur != null && !it.isCardTrader() },
                    latestCardTrader = snaps.lastOrNull { it.eur != null && it.isCardTrader() },
                    lastSnapshot = snaps.lastOrNull { !it.isCardTrader() } ?: snaps.lastOrNull(),
                    eurHistory = snaps.filter { !it.isCardTrader() }.mapNotNull { it.eur },
                    cardTraderHistory = snaps.filter { it.isCardTrader() }.mapNotNull { it.eur },
                )
            }.sortedWith(compareBy({ it.type.ordinal }, { it.print }))
            CardUi(card, list, removedByUpdate = card.id in removedIds)
        }
    }
}

/** CardTrader has its own price: it must not take the place of the Cardmarket one. */
private fun PriceSnapshotEntity.isCardTrader(): Boolean = source == CardTraderPriceProvider.DISPLAY_NAME

private val CARDMARKET_PLACEHOLDER = Regex("^Cardmarket\\d+$")

/** Printed number, or "N/A" for cards that Cardmarket lists without a number (even if an old database had put "Cardmarket<id>" there). */
fun CardEntity.printedNumberOrNa(): String = printedNumber.takeIf { it.isNotBlank() && !CARDMARKET_PLACEHOLDER.matches(it) } ?: "N/A"

/** Code printed at the bottom left, e.g. "M1L 046/063"; without a number "N/A". */
fun CardEntity.printedCode(): String = listOfNotNull(setCode, printedNumberOrNa()).joinToString(" ")
