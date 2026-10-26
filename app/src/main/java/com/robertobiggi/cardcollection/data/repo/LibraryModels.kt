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
 * @property usdHistory prezzi in dollari dal più vecchio al più recente.
 * @property cardTraderHistory prezzi CardTrader dal più vecchio al più recente.
 */
data class VariantUi(
    val id: String,
    val type: VariantType,
    val print: String = "",
    val wanted: Boolean,
    val quantity: Int,
    val condition: Condition,
    val latestEur: PriceSnapshotEntity?,
    val latestUsd: PriceSnapshotEntity?,
    val latestCardTrader: PriceSnapshotEntity? = null,
    val lastSnapshot: PriceSnapshotEntity?,
    val eurHistory: List<Double>,
    val usdHistory: List<Double> = emptyList(),
    val cardTraderHistory: List<Double> = emptyList(),
) {
    /**
     * Converte la variante nell'input del calcolo statistiche.
     * @return SlotInput - dati minimi per [com.robertobiggi.cardcollection.core.stats.CollectionStats].
     */
    fun toSlotInput(): SlotInput = SlotInput(wanted, quantity, latestEur?.eur, latestUsd?.usd, latestCardTrader?.eur)
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

    /**
     * Rarità da mostrare al posto di "Holo" nelle carte solo holo (Double Rare, Illustration Rare…), dove "Holo" non dice nulla.
     * Resta "Holo" se la carta ha anche la versione normale, se la rarità manca, se è già una holo rare o se è una rarità
     * semplice (Common, Uncommon, Rare), che non dice niente sulla stampa holo.
     * @param variant VariantUi - variante della carta.
     * @return String? - rarità, oppure null se si tiene il nome della variante.
     */
    fun rarityLabelFor(variant: VariantUi): String? {
        if (variant.type != VariantType.HOLO || variants.any { it.type == VariantType.NORMAL }) return null
        // "Fixed" è la "rarità" che CardTrader dà alle carte dei mazzi precostruiti: non dice niente sulla carta.
        return card.rarity?.trim()?.takeIf { it.isNotEmpty() && !it.contains("holo", ignoreCase = true) && !it.equals("Fixed", ignoreCase = true) && it.lowercase() !in PLAIN_RARITIES }
    }

    /** Percorso dell'immagine locale migliore per la griglia. */
    val thumbnailPath: String? get() = card.imageLowPath ?: card.imageHighPath

    companion object {
        private val PLAIN_RARITIES = setOf("common", "uncommon", "rare")

        /** Ordine di uscita: data del set, poi set e numero (le carte senza data in fondo). */
        val RELEASE_ORDER: Comparator<CardUi> =
            compareBy<CardUi>({ it.card.releaseDate ?: "9999" }, { it.card.setId }, { it.card.localId.padStart(4, '0') })
    }
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
    /** False se le versioni 1st Edition non contano: senza una scelta dell'utente non sono volute. */
    val countFirstEdition: Boolean = true,
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
    fun libraryOf(state: DbState, collectionId: Long): List<CardUi> =
        libraryOf(state, collectionId, state.variants.groupBy { it.cardId }, state.snapshots.groupBy { it.variantId })

    /**
     * Come [libraryOf], con varianti e prezzi già raggruppati (per non rifarlo a ogni collezione).
     * @param state DbState - tabelle osservate.
     * @param collectionId Long - collezione.
     * @param variantsByCard Map<String, List<CardVariantEntity>> - varianti per carta.
     * @param snapsByVariant Map<String, List<PriceSnapshotEntity>> - prezzi per variante, dal più vecchio.
     * @return List<CardUi> - carte visibili della collezione.
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
        )
    }

    /**
     * Riepiloghi di tutte le collezioni per l'elenco iniziale.
     * @param state DbState - tabelle osservate.
     * @return List<CollectionSummary> - una voce per collezione, in ordine di creazione.
     */
    fun summaries(state: DbState): List<CollectionSummary> {
        val variantsByCard = state.variants.groupBy { it.cardId }
        val snapsByVariant = state.snapshots.groupBy { it.variantId }
        return state.collections.map { collection -> summaryOf(state, collection, variantsByCard, snapsByVariant) }
    }

    /**
     * Riepilogo di una collezione.
     * @param state DbState - tabelle osservate.
     * @param collection CollectionEntity - collezione.
     * @param variantsByCard Map<String, List<CardVariantEntity>> - varianti per carta.
     * @param snapsByVariant Map<String, List<PriceSnapshotEntity>> - prezzi per variante.
     * @return CollectionSummary - numero di carte, statistiche e copertina.
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
     * Statistiche di un insieme di carte.
     * @param cards List<CardUi> - carte da considerare.
     * @return StatsSummary - completamento, valore e mancanti.
     */
    fun statsOf(cards: List<CardUi>): StatsSummary = CollectionStats.compute(cards.flatMap { c -> c.variants.map { it.toSlotInput() } })

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
    ): List<CardUi> = assembleGrouped(cards, variants.groupBy { it.cardId }, entries.associateBy { it.variantId }, snapshots.groupBy { it.variantId })

    /**
     * Unisce carte, varianti, dati utente e prezzi già raggruppati.
     * @param cards List<CardEntity> - carte tracciate.
     * @param variantsByCard Map<String, List<CardVariantEntity>> - varianti per carta.
     * @param entryById Map<String, CollectionEntryEntity> - dati dell'utente per variante.
     * @param snapsByVariant Map<String, List<PriceSnapshotEntity>> - prezzi per variante, dal più vecchio.
     * @param countFirstEdition Boolean - se false, una 1st Edition senza scelta dell'utente non è voluta (non conta tra le mancanti).
     * @return List<CardUi> - carte con varianti ordinate secondo [VariantType].
     */
    private fun assembleGrouped(
        cards: List<CardEntity>,
        variantsByCard: Map<String, List<CardVariantEntity>>,
        entryById: Map<String, CollectionEntryEntity>,
        snapsByVariant: Map<String, List<PriceSnapshotEntity>>,
        countFirstEdition: Boolean = true,
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
                        // I timbri (tornei, prerelease…) sono tanti: si contano solo se l'utente li segna come voluti.
                        VariantType.STAMPED -> false
                        else -> true
                    },
                    quantity = entry?.quantity ?: 0,
                    condition = Condition.entries.firstOrNull { it.name == entry?.condition } ?: Condition.NM,
                    latestEur = snaps.lastOrNull { it.eur != null && !it.isCardTrader() },
                    latestUsd = snaps.lastOrNull { it.usd != null },
                    latestCardTrader = snaps.lastOrNull { it.eur != null && it.isCardTrader() },
                    lastSnapshot = snaps.lastOrNull { !it.isCardTrader() } ?: snaps.lastOrNull(),
                    eurHistory = snaps.filter { !it.isCardTrader() }.mapNotNull { it.eur },
                    usdHistory = snaps.mapNotNull { it.usd },
                    cardTraderHistory = snaps.filter { it.isCardTrader() }.mapNotNull { it.eur },
                )
            }.sortedWith(compareBy({ it.type.ordinal }, { it.print }))
            CardUi(card, list)
        }
    }
}

/** CardTrader ha un prezzo proprio: non deve prendere il posto di quello Cardmarket. */
private fun PriceSnapshotEntity.isCardTrader(): Boolean = source == CardTraderPriceProvider.DISPLAY_NAME
