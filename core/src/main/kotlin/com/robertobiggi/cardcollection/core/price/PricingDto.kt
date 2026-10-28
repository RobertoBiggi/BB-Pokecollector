package com.robertobiggi.cardcollection.core.price

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Prezzi nel formato del listino Cardmarket e di TCGplayer (campi usati da VariantMapper).

/** Prezzi di una carta o variante. */
@Serializable
data class PricingDto(
    val cardmarket: CardmarketPricingDto? = null,
    val tcgplayer: TcgplayerPricingDto? = null,
)

/**
 * Prezzi Cardmarket in euro. I campi "-holo" si riferiscono alla versione reverse holo del prodotto,
 * quelli senza suffisso alla versione base (normal per le comuni, holo per le rare holo).
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

/** Prezzi TCGplayer in dollari, separati per variante di stampa. */
@Serializable
data class TcgplayerPricingDto(
    val updated: String? = null,
    val unit: String? = null,
    val normal: TcgplayerPriceDto? = null,
    val holofoil: TcgplayerPriceDto? = null,
    @SerialName("reverse-holofoil") val reverseHolofoil: TcgplayerPriceDto? = null,
    @SerialName("1stEditionHolofoil") val firstEditionHolofoil: TcgplayerPriceDto? = null,
    @SerialName("1stEditionNormal") val firstEditionNormal: TcgplayerPriceDto? = null,
    @SerialName("unlimitedHolofoil") val unlimitedHolofoil: TcgplayerPriceDto? = null,
)

/** Singola riga di prezzi TCGplayer. */
@Serializable
data class TcgplayerPriceDto(
    val lowPrice: Double? = null,
    val midPrice: Double? = null,
    val highPrice: Double? = null,
    val marketPrice: Double? = null,
)
