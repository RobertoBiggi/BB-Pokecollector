package com.robertobiggi.cardcollection.data.repo

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
}
