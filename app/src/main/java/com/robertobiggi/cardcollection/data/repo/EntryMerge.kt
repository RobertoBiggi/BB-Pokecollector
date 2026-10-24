package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity

/** Spostamento dei dati dell'utente da una variante a un'altra della stessa carta. */
object EntryMerge {
    /**
     * Sposta il dato di una variante su un'altra, sommando le copie se l'altra ha già un dato nella stessa collezione.
     * @param from CollectionEntryEntity - dato da spostare.
     * @param toVariantId String - variante di arrivo.
     * @param existing CollectionEntryEntity? - dato già presente per la variante di arrivo nella stessa collezione, se c'è.
     * @return CollectionEntryEntity - dato da salvare per la variante di arrivo.
     */
    fun move(from: CollectionEntryEntity, toVariantId: String, existing: CollectionEntryEntity?): CollectionEntryEntity =
        if (existing == null) {
            from.copy(variantId = toVariantId)
        } else {
            existing.copy(
                quantity = existing.quantity + from.quantity,
                wanted = existing.wanted || from.wanted,
                notes = listOfNotNull(existing.notes, from.notes).distinct().joinToString("\n").ifEmpty { null },
            )
        }

    /** Finiture della stessa stampa, intercambiabili quando il catalogo corregge un dato; le altre varianti sono prodotti diversi. */
    private val FINISHES = listOf(VariantType.NORMAL, VariantType.HOLO, VariantType.REVERSE)

    /**
     * Variante che sostituisce una non più elencata dal catalogo, qualunque sia la correzione. Vale per le finiture
     * (normale, holo, reverse): se il catalogo ne elenca una sola le copie passano lì, altrimenti alla più vicina
     * (la reverse alla holo, la holo alla normale, la normale alla holo).
     * Le altre varianti (1ª edizione, promo, Poké Ball…) non si spostano: sono prodotti diversi.
     * @param stale VariantType - variante non più elencata.
     * @param listed List<VariantType> - varianti attuali della carta.
     * @return VariantType? - variante di arrivo, oppure null se la variante esiste ancora o non c'è una sostituta.
     */
    fun replacement(stale: VariantType, listed: List<VariantType>): VariantType? {
        if (stale in listed || stale !in FINISHES) return null
        val targets = FINISHES.filter { it in listed }
        return targets.singleOrNull() ?: when (stale) {
            VariantType.REVERSE -> VariantType.HOLO
            VariantType.HOLO -> VariantType.NORMAL
            else -> VariantType.HOLO
        }.takeIf { it in targets } ?: targets.firstOrNull()
    }
}
