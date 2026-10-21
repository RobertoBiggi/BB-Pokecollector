package com.robertobiggi.cardcollection.core.cardtrader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Gioco di CardTrader (`GET /games`). */
@Serializable
data class CtGameDto(
    val id: Int,
    val name: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

/** Risposta di `GET /games`: l'elenco è dentro `array`. */
@Serializable
data class CtGamesResponse(val array: List<CtGameDto> = emptyList())

/** Espansione di CardTrader (`GET /expansions`). */
@Serializable
data class CtExpansionDto(
    val id: Int,
    @SerialName("game_id") val gameId: Int? = null,
    val code: String? = null,
    val name: String = "",
)

/**
 * Blueprint: la carta di un'espansione. CardTrader non crea un blueprint per rarità: normal e reverse
 * della stessa carta sono lo stesso blueprint, distinti solo dalle proprietà dei singoli annunci.
 *
 * @property fixedProperties proprietà fisse, tra cui `collector_number`.
 */
@Serializable
data class CtBlueprintDto(
    val id: Int,
    val name: String = "",
    val version: String? = null,
    @SerialName("expansion_id") val expansionId: Int? = null,
    @SerialName("fixed_properties") val fixedProperties: JsonObject? = null,
)

/** Prezzo annidato di un annuncio. */
@Serializable
data class CtPriceDto(
    val cents: Long? = null,
    val currency: String? = null,
)

/**
 * Annuncio di un venditore (`GET /marketplace/products`).
 *
 * @property propertiesHash proprietà dell'annuncio: condizione, lingua, `pokemon_reverse`, prima edizione.
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
