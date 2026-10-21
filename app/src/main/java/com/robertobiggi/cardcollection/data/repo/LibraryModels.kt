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
 * Variante pronta per la UI, con i dati dell'utente e gli ultimi prezzi.
 *
 * @property latestEur ultimo snapshot con un prezzo in euro.
 * @property latestUsd ultimo snapshot con un prezzo in dollari.
 * @property latestCardTrader ultimo snapshot CardTrader (tenuto separato da Cardmarket).
 * @property eurHistory prezzi in euro dal più vecchio al più recente (per la sparkline).
 */
data class VariantUi(
    val id: String,
    val type: VariantType,
    val wanted: Boolean,
    val quantity: Int,
    val condition: Condition,
    val latestEur: PriceSnapshotEntity?,
    val latestUsd: PriceSnapshotEntity?,
    val latestCardTrader: PriceSnapshotEntity? = null,
    val lastSnapshot: PriceSnapshotEntity?,
    val eurHistory: List<Double>,
) {
    /**
     * Converte la variante nell'input del calcolo statistiche.
     * @return SlotInput - dati minimi per [com.robertobiggi.cardcollection.core.stats.CollectionStats].
     */
    fun toSlotInput(): SlotInput = SlotInput(wanted, quantity, latestEur?.eur, latestUsd?.usd)
}

/** Carta pronta per la UI con le sue varianti. */
data class CardUi(
    val card: CardEntity,
    val variants: List<VariantUi>,
) {
    val language: Language get() = Language.fromCode(card.language) ?: Language.EN

    /** Codice stampato in basso a sinistra, es. "M1L 046/063". */
    val printedCode: String get() = listOfNotNull(card.setCode, card.printedNumber).joinToString(" ")

    /** Slot della carta: varianti desiderate o possedute (stessa regola di [CollectionStats]). */
    val slots: Int get() = variants.count { it.wanted || it.quantity > 0 }

    /** Varianti possedute, con almeno una copia. */
    val ownedSlots: Int get() = variants.count { it.quantity > 0 }
    val copies: Int get() = variants.sumOf { it.quantity }

    /** Prezzo in euro più basso tra le varianti, usato in griglia ("da 0,30 €"). */
    val minEur: Double? get() = variants.mapNotNull { it.latestEur?.eur }.minOrNull()

    /** Percorso dell'immagine locale migliore per la griglia. */
    val thumbnailPath: String? get() = card.imageLowPath ?: card.imageHighPath
}

/** Riepilogo di una collezione per l'elenco iniziale. */
data class CollectionSummary(
    val collection: CollectionEntity,
    val cardCount: Int,
    val stats: StatsSummary,
    val coverPath: String?,
)

/** Fotografia delle tabelle osservate, da cui si ricavano libreria e riepiloghi di tutte le collezioni. */
data class DbState(
    val collections: List<CollectionEntity>,
    val memberships: List<CollectionCardEntity>,
    val queries: List<TrackedQueryEntity>,
    val cards: List<CardEntity>,
    val variants: List<CardVariantEntity>,
    val entries: List<CollectionEntryEntity>,
    val snapshots: List<PriceSnapshotEntity>,
    val runs: List<PriceRunEntity>,
)

/** Costruzione dei modelli UI a partire dalle tabelle del database. */
object LibraryAssembler {

    /**
     * Calcola quali carte sono visibili in una collezione: non nascoste e aggiunte a mano oppure
     * da una ricerca attiva.
     * @param collectionId Long - collezione.
     * @param memberships List<CollectionCardEntity> - appartenenze di tutte le collezioni.
     * @param queries List<TrackedQueryEntity> - ricerche di tutte le collezioni.
     * @return Set<String> - id delle carte visibili.
     */
    fun visibleCardIds(collectionId: Long, memberships: List<CollectionCardEntity>, queries: List<TrackedQueryEntity>): Set<String> {
        val enabled = queries.filter { it.enabled }.map { it.id }.toSet()
        return memberships.filter { it.collectionId == collectionId && !it.hidden && (it.queryId == null || it.queryId in enabled) }
            .map { it.cardId }.toSet()
    }

    /**
     * Costruisce la libreria di una collezione dalla fotografia del database.
     * @param state DbState - tabelle osservate.
     * @param collectionId Long - collezione.
     * @return List<CardUi> - carte visibili con varianti, quantità della collezione e prezzi.
     */
    fun libraryOf(state: DbState, collectionId: Long): List<CardUi> {
        val ids = visibleCardIds(collectionId, state.memberships, state.queries)
        return assemble(
            cards = state.cards.filter { it.id in ids },
            variants = state.variants,
            entries = state.entries.filter { it.collectionId == collectionId },
            snapshots = state.snapshots,
        )
    }

    /**
     * Riepiloghi di tutte le collezioni per l'elenco iniziale.
     * @param state DbState - tabelle osservate.
     * @return List<CollectionSummary> - una voce per collezione, in ordine di creazione.
     */
    fun summaries(state: DbState): List<CollectionSummary> = state.collections.map { collection ->
        val library = libraryOf(state, collection.id)
        val cover = library.firstOrNull { it.copies > 0 && it.thumbnailPath != null }?.thumbnailPath
            ?: library.firstOrNull { it.thumbnailPath != null }?.thumbnailPath
        CollectionSummary(
            collection = collection,
            cardCount = library.size,
            stats = CollectionStats.compute(library.flatMap { c -> c.variants.map { it.toSlotInput() } }),
            coverPath = cover,
        )
    }

    /**
     * Unisce carte, varianti, dati utente e prezzi.
     * @param cards List<CardEntity> - carte tracciate.
     * @param variants List<CardVariantEntity> - tutte le varianti.
     * @param entries List<CollectionEntryEntity> - dati dell'utente.
     * @param snapshots List<PriceSnapshotEntity> - prezzi ordinati dal più vecchio al più recente.
     * @return List<CardUi> - carte con varianti ordinate secondo [VariantType].
     */
    fun assemble(
        cards: List<CardEntity>,
        variants: List<CardVariantEntity>,
        entries: List<CollectionEntryEntity>,
        snapshots: List<PriceSnapshotEntity>,
    ): List<CardUi> {
        val variantsByCard = variants.groupBy { it.cardId }
        val entryById = entries.associateBy { it.variantId }
        val snapsByVariant = snapshots.groupBy { it.variantId }
        return cards.map { card ->
            val list = variantsByCard[card.id].orEmpty().mapNotNull { v ->
                val type = VariantType.entries.firstOrNull { it.name == v.type } ?: return@mapNotNull null
                val entry = entryById[v.id]
                val snaps = snapsByVariant[v.id].orEmpty()
                VariantUi(
                    id = v.id,
                    type = type,
                    wanted = entry?.wanted ?: true,
                    quantity = entry?.quantity ?: 0,
                    condition = Condition.entries.firstOrNull { it.name == entry?.condition } ?: Condition.NM,
                    latestEur = snaps.lastOrNull { it.eur != null && !it.isCardTrader() },
                    latestUsd = snaps.lastOrNull { it.usd != null },
                    latestCardTrader = snaps.lastOrNull { it.eur != null && it.isCardTrader() },
                    lastSnapshot = snaps.lastOrNull { !it.isCardTrader() } ?: snaps.lastOrNull(),
                    eurHistory = snaps.filter { !it.isCardTrader() }.mapNotNull { it.eur },
                )
            }.sortedBy { it.type.ordinal }
            CardUi(card, list)
        }
    }
}

/** CardTrader ha un prezzo proprio: non deve prendere il posto di quello Cardmarket. */
private fun PriceSnapshotEntity.isCardTrader(): Boolean = source == CardTraderPriceProvider.DISPLAY_NAME
