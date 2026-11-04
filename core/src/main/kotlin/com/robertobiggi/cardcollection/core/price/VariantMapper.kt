package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.CardmarketPricingDto
import com.robertobiggi.cardcollection.core.price.PricingDto
import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * Associates Cardmarket prices with the right variant.
 *
 * Cardmarket has a single product per card: the fields without suffix apply to the base version
 * (normal or holo rare), the "-holo" ones to the reverse holo.
 */
object VariantMapper {

    /**
     * Computes the price of a variant from the Cardmarket prices.
     * @param type VariantType - requested variant.
     * @param pricing PricingDto? - prices of the variant (or of the card, if the per-variant ones are missing).
     * @param cardHasNormalAndHolo Boolean - true if the card exists in both normal and holo: in that case
     *        the Cardmarket base price does not tell the two apart and the reliability drops.
     * @param source String - name of the source to report in the result.
     * @return PriceQuote? - price found, or null if no market has a value for the variant.
     */
    fun quote(
        type: VariantType,
        pricing: PricingDto?,
        cardHasNormalAndHolo: Boolean,
        source: String,
    ): PriceQuote? {
        if (pricing == null) return null
        val cm = cardmarketFor(type, pricing.cardmarket, cardHasNormalAndHolo) ?: return null
        return PriceQuote(
            source = source,
            eur = cm.trend,
            eurLow = cm.low,
            eurAvg30 = cm.avg30,
            sourceUpdatedAt = pricing.cardmarket?.updated,
            confidence = cm.confidence,
        )
    }

    /** Cardmarket values chosen for a variant. */
    private data class CmPick(val trend: Double, val low: Double?, val avg30: Double?, val confidence: Confidence)

    /**
     * Picks the right Cardmarket fields for the variant.
     * @param type VariantType - requested variant.
     * @param cm CardmarketPricingDto? - Cardmarket prices.
     * @param cardHasNormalAndHolo Boolean - see [quote].
     * @return CmPick? - values chosen, or null if the trend is missing or not positive.
     */
    private fun cardmarketFor(type: VariantType, cm: CardmarketPricingDto?, cardHasNormalAndHolo: Boolean): CmPick? {
        if (cm == null) return null
        if (cm.unit != null && cm.unit != "EUR") return null
        return when (type) {
            // Separate products on Cardmarket: the price list prices them with their id, not the TCGdex entry of the base card.
            VariantType.POKE_BALL, VariantType.MASTER_BALL, VariantType.ENERGY_REVERSE -> null
            VariantType.REVERSE -> positive(cm.trendHolo ?: cm.avgHolo)?.let {
                CmPick(it, positive(cm.lowHolo), positive(cm.avg30Holo), Confidence.HIGH)
            }
            // With normal and holo together Cardmarket quotes the holo in the "-holo" fields and the normal in the base ones.
            VariantType.HOLO -> if (cardHasNormalAndHolo) holoPick(cm) ?: basePick(type, cm, true) else basePick(type, cm, false)
            else -> basePick(type, cm, cardHasNormalAndHolo)
        }
    }

    private fun holoPick(cm: CardmarketPricingDto): CmPick? = positive(cm.trendHolo ?: cm.avgHolo)?.let {
        CmPick(it, positive(cm.lowHolo), positive(cm.avg30Holo), Confidence.HIGH)
    }

    private fun basePick(type: VariantType, cm: CardmarketPricingDto, cardHasNormalAndHolo: Boolean): CmPick? =
        (positive(cm.trend) ?: positive(cm.avg))?.let {
            val confidence = when (type) {
                VariantType.NORMAL -> Confidence.HIGH
                VariantType.HOLO -> if (cardHasNormalAndHolo) Confidence.MEDIUM else Confidence.HIGH
                VariantType.FIRST_EDITION -> Confidence.LOW
                else -> Confidence.MEDIUM
            }
            CmPick(it, positive(cm.low), positive(cm.avg30), confidence)
        } ?: (positive(cm.trendHolo) ?: positive(cm.avgHolo))?.let {
            // Foil-only product (e.g. Moltres of the Special Battle Set): Cardmarket puts the price in the "-holo" fields and leaves the base ones empty.
            CmPick(it, positive(cm.lowHolo), positive(cm.avg30Holo), Confidence.MEDIUM)
        }

    /**
     * Discards null, non-finite or non-positive values (0 means "no data").
     * @param value Double? - value to check.
     * @return Double? - the value if it is a valid price, otherwise null.
     */
    private fun positive(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 && it < Validators.MAX_PRICE }
}
