package com.robertobiggi.cardcollection.core.stats

/**
 * Minimal data of a slot (variant) to compute the statistics.
 *
 * @property wanted true if the variant is wanted: it counts as a slot even when you do not own it.
 * @property quantity copies owned.
 * @property latestEur latest known price in euros, if present.
 * @property latestCardTrader latest CardTrader price in euros, if present.
 */
data class SlotInput(
    val wanted: Boolean,
    val quantity: Int,
    val latestEur: Double?,
    val latestCardTrader: Double? = null,
)

/**
 * Summary of a group of slots.
 * The `priced*` fields count the owned slots that have a price from that source, i.e. those included in the value (out of [owned]).
 */
data class StatsSummary(
    val slots: Int,
    val owned: Int,
    val copies: Int,
    val valueEur: Double,
    val missing: Int,
    val missingEur: Double,
    val missingWithoutPrice: Int,
    val valueCardTrader: Double = 0.0,
    val pricedEur: Int = 0,
    val pricedCardTrader: Int = 0,
) {
    /**
     * Completion percentage.
     * @return Int - owned slots over wanted slots, from 0 to 100 (0 if there are no slots).
     */
    fun completionPercent(): Int = if (slots == 0) 0 else (owned * 100 / slots)
}

/** Computation of the collection statistics. */
object CollectionStats {

    /**
     * Computes completion, value and cost of the missing ones.
     * A slot is a variant that is wanted or owned: a variant you have always counts as
     * completed, even if you removed "I want it". The missing ones are only the wanted ones with 0 copies.
     * @param slots List<SlotInput> - variants to consider.
     * @return StatsSummary - summary.
     */
    fun compute(slots: List<SlotInput>): StatsSummary {
        var wanted = 0
        var owned = 0
        var copies = 0
        var valueEur = 0.0
        var missing = 0
        var missingEur = 0.0
        var missingWithoutPrice = 0
        var valueCardTrader = 0.0
        var pricedEur = 0
        var pricedCardTrader = 0
        for (slot in slots) {
            val quantity = slot.quantity.coerceAtLeast(0)
            copies += quantity
            slot.latestEur?.let { valueEur += it * quantity }
            slot.latestCardTrader?.let { valueCardTrader += it * quantity }
            if (quantity > 0) {
                if (slot.latestEur != null) pricedEur++
                if (slot.latestCardTrader != null) pricedCardTrader++
            }
            if (!slot.wanted && quantity == 0) continue
            wanted++
            if (quantity > 0) {
                owned++
            } else {
                missing++
                if (slot.latestEur != null) missingEur += slot.latestEur else missingWithoutPrice++
            }
        }
        return StatsSummary(wanted, owned, copies, valueEur, missing, missingEur, missingWithoutPrice, valueCardTrader, pricedEur, pricedCardTrader)
    }
}
