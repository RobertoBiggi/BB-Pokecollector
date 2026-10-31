package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity

/** Moving of user data from one variant to another of the same card. */
object EntryMerge {
    /**
     * Moves the data of a variant to another, adding the copies if the other already has data in the same collection.
     * @param from CollectionEntryEntity - data to move.
     * @param toVariantId String - target variant.
     * @param existing CollectionEntryEntity? - data already present for the target variant in the same collection, if any.
     * @return CollectionEntryEntity - data to save for the target variant.
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

    /** Finishes of the same print, interchangeable when the catalog corrects a value; the other variants are different products. */
    private val FINISHES = listOf(VariantType.NORMAL, VariantType.HOLO, VariantType.REVERSE)

    /**
     * Variant that replaces one no longer listed by the catalog, whatever the correction. Applies to finishes
     * (normal, holo, reverse): if the catalog lists only one the copies go there, otherwise to the closest
     * (reverse to holo, holo to normal, normal to holo).
     * Other variants (1st edition, promo, Poké Ball…) are not moved: they are different products.
     * @param stale VariantType - variant no longer listed.
     * @param listed List<VariantType> - current variants of the card.
     * @return VariantType? - target variant, or null if the variant still exists or there is no replacement.
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
