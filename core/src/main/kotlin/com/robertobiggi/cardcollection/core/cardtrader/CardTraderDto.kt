package com.robertobiggi.cardcollection.core.cardtrader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** CardTrader game (`GET /games`). */
@Serializable
data class CtGameDto(
    val id: Int,
    val name: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

/** Response of `GET /games`: the list is inside `array`. */
@Serializable
data class CtGamesResponse(val array: List<CtGameDto> = emptyList())

/** CardTrader expansion (`GET /expansions`). */
@Serializable
data class CtExpansionDto(
    val id: Int,
    @SerialName("game_id") val gameId: Int? = null,
    val code: String? = null,
    val name: String = "",
)

/**
 * Blueprint: the card of an expansion. CardTrader does not create a blueprint per rarity: normal and reverse
 * of the same card are the same blueprint, distinguished only by the properties of the individual listings.
 *
 * @property fixedProperties fixed properties, including `collector_number`.
 */
@Serializable
data class CtBlueprintDto(
    val id: Int,
    val name: String = "",
    val version: String? = null,
    @SerialName("expansion_id") val expansionId: Int? = null,
    @SerialName("fixed_properties") val fixedProperties: JsonObject? = null,
    @SerialName("card_market_ids") val cardMarketIds: List<Int>? = null,
    val image: CtImageDto? = null,
)

/** Image of a blueprint: `show` is the medium version (about 500 px wide), `url` the original. Paths relative to the site. */
@Serializable
data class CtImageDto(
    val url: String? = null,
    val show: CtImageSizeDto? = null,
)

/** A size of the image of a blueprint. */
@Serializable
data class CtImageSizeDto(val url: String? = null)

/** Nested price of a listing. */
@Serializable
data class CtPriceDto(
    val cents: Long? = null,
    val currency: String? = null,
)

/**
 * Listing from a seller (`GET /marketplace/products`).
 *
 * @property propertiesHash properties of the listing: condition, language, `pokemon_reverse`, first edition.
 */
@Serializable
data class CtProductDto(
    val id: Long? = null,
    @SerialName("blueprint_id") val blueprintId: Int? = null,
    val quantity: Int? = null,
    val price: CtPriceDto? = null,
    @SerialName("price_cents") val priceCents: Long? = null,
    @SerialName("price_currency") val priceCurrency: String? = null,
    @SerialName("properties_hash") val propertiesHash: JsonObject? = null,
    val graded: Boolean? = null,
)
