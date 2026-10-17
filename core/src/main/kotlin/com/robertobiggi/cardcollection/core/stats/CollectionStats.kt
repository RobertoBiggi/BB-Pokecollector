package com.robertobiggi.cardcollection.core.stats

/**
 * Dati minimi di uno slot (variante) per calcolare le statistiche.
 *
 * @property wanted true se la variante conta come slot della collezione.
 * @property quantity copie possedute.
 * @property latestEur ultimo prezzo noto in euro, se presente.
 * @property latestUsd ultimo prezzo noto in dollari, se presente.
 */
data class SlotInput(
    val wanted: Boolean,
    val quantity: Int,
    val latestEur: Double?,
    val latestUsd: Double?,
)

/**
 * Riepilogo di un gruppo di slot. EUR e USD restano separati: nessuna conversione.
 */
data class StatsSummary(
    val slots: Int,
    val owned: Int,
    val copies: Int,
    val valueEur: Double,
    val valueUsd: Double,
    val missing: Int,
    val missingEur: Double,
    val missingWithoutPrice: Int,
) {
    /**
     * Percentuale di completamento.
     * @return Int - slot posseduti su slot desiderati, da 0 a 100 (0 se non ci sono slot).
     */
    fun completionPercent(): Int = if (slots == 0) 0 else (owned * 100 / slots)
}

/** Calcolo delle statistiche della collezione. */
object CollectionStats {

    /**
     * Calcola completamento, valore e costo dei mancanti.
     * Il valore conta tutte le copie possedute (anche delle varianti non desiderate);
     * completamento e mancanti considerano solo gli slot desiderati.
     * @param slots List<SlotInput> - varianti da considerare.
     * @return StatsSummary - riepilogo.
     */
    fun compute(slots: List<SlotInput>): StatsSummary {
        var wanted = 0
        var owned = 0
        var copies = 0
        var valueEur = 0.0
        var valueUsd = 0.0
        var missing = 0
        var missingEur = 0.0
        var missingWithoutPrice = 0
        for (slot in slots) {
            val quantity = slot.quantity.coerceAtLeast(0)
            copies += quantity
            slot.latestEur?.let { valueEur += it * quantity }
            slot.latestUsd?.let { valueUsd += it * quantity }
            if (!slot.wanted) continue
            wanted++
            if (quantity > 0) {
                owned++
            } else {
                missing++
                if (slot.latestEur != null) missingEur += slot.latestEur else missingWithoutPrice++
            }
        }
        return StatsSummary(wanted, owned, copies, valueEur, valueUsd, missing, missingEur, missingWithoutPrice)
    }
}
