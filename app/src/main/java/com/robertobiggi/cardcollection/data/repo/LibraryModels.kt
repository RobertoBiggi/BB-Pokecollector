package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.stats.SlotInput
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity

/**
 * Variante pronta per la UI, con i dati dell'utente e gli ultimi prezzi.
 *
 * @property latestEur ultimo snapshot con un prezzo in euro.
 * @property latestUsd ultimo snapshot con un prezzo in dollari.
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

    val slots: Int get() = variants.count { it.wanted }
    val ownedSlots: Int get() = variants.count { it.wanted && it.quantity > 0 }
    val copies: Int get() = variants.sumOf { it.quantity }

    /** Prezzo in euro più basso tra le varianti, usato in griglia ("da 0,30 €"). */
    val minEur: Double? get() = variants.mapNotNull { it.latestEur?.eur }.minOrNull()

    /** Percorso dell'immagine locale migliore per la griglia. */
    val thumbnailPath: String? get() = card.imageLowPath ?: card.imageHighPath
}

/** Costruzione dei modelli UI a partire dalle tabelle del database. */
object LibraryAssembler {

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
                    latestEur = snaps.lastOrNull { it.eur != null },
                    latestUsd = snaps.lastOrNull { it.usd != null },
                    lastSnapshot = snaps.lastOrNull(),
                    eurHistory = snaps.mapNotNull { it.eur },
                )
            }.sortedBy { it.type.ordinal }
            CardUi(card, list)
        }
    }

    /**
     * Calcola l'id della variante (scorciatoia per la UI).
     * @param cardId String - id locale della carta.
     * @param type VariantType - variante.
     * @return String - id della variante.
     */
    fun variantId(cardId: String, type: VariantType): String = Ids.variantId(cardId, type)
}
