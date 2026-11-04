package com.robertobiggi.cardcollection.core.price

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Prices in the format of the Cardmarket price list (fields used by VariantMapper).

/** Prices of a card or variant. */
@Serializable
data class PricingDto(
    val cardmarket: CardmarketPricingDto? = null,
)

/**
 * Cardmarket prices in euros. The "-holo" fields refer to the reverse holo version of the product,
 * those without suffix to the base version (normal for commons, holo for holo rares).
 */
@Serializable
data class CardmarketPricingDto(
    val idProduct: Int? = null,
    val updated: String? = null,
    val unit: String? = null,
    val avg: Double? = null,
    val low: Double? = null,
    val trend: Double? = null,
    val avg7: Double? = null,
    val avg30: Double? = null,
    @SerialName("avg-holo") val avgHolo: Double? = null,
    @SerialName("low-holo") val lowHolo: Double? = null,
    @SerialName("trend-holo") val trendHolo: Double? = null,
    @SerialName("avg30-holo") val avg30Holo: Double? = null,
)
