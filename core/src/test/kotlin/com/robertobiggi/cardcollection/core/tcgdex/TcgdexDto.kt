package com.robertobiggi.cardcollection.core.tcgdex

import kotlinx.serialization.SerialName
import com.robertobiggi.cardcollection.core.price.PricingDto
import kotlinx.serialization.Serializable

// DTOs of the TCGdex v2 API, used only by tests to read the responses saved in resources/fixtures
// (the app no longer queries TCGdex for cards).

/** Element of the list returned by /cards?name=... */
@Serializable
data class CardBriefDto(
    val id: String,
    val localId: String,
    val name: String,
    val image: String? = null,
)

/** Detail of a card from /cards/{id}. */
@Serializable
data class CardDto(
    val id: String,
    val localId: String,
    val name: String,
    val category: String? = null,
    val illustrator: String? = null,
    val image: String? = null,
    val rarity: String? = null,
    val set: CardSetRefDto,
    val variants: VariantFlagsDto? = null,
    @SerialName("variants_detailed") val variantsDetailed: List<VariantDetailDto>? = null,
    val dexId: List<Int>? = null,
    val pricing: PricingDto? = null,
)

/** Reference to the set contained in the card detail. */
@Serializable
data class CardSetRefDto(
    val id: String,
    val name: String,
    val cardCount: CardCountDto? = null,
)

/** Card count of a set: [official] is the number printed after the slash. */
@Serializable
data class CardCountDto(
    val official: Int? = null,
    val total: Int? = null,
)

/** Available variants expressed as boolean flags (historical TCGdex field). */
@Serializable
data class VariantFlagsDto(
    val normal: Boolean = false,
    val reverse: Boolean = false,
    val holo: Boolean = false,
    val firstEdition: Boolean = false,
    val wPromo: Boolean = false,
)

/**
 * Detailed variant with specific prices. [size] distinguishes standard cards from jumbo, [subtype] the print
 * (unlimited, shadowless…), [stamp] the stamps (1st-edition, poketour-99…).
 */
@Serializable
data class VariantDetailDto(
    val type: String,
    val size: String? = null,
    val subtype: String? = null,
    val stamp: List<String>? = null,
    val thirdParty: ThirdPartyDto? = null,
    val pricing: PricingDto? = null,
) {
    /** Cardmarket product id of the variant, if TCGdex gives it. */
    val cardmarketId: Int? get() = thirdParty?.cardmarket ?: pricing?.cardmarket?.idProduct
}

/** Ids of the variant's products at other sites. */
@Serializable
data class ThirdPartyDto(val cardmarket: Int? = null)

/** Detail of a set from /sets/{id}. */
@Serializable
data class SetDto(
    val id: String,
    val name: String,
    val releaseDate: String? = null,
    val serie: SerieRefDto? = null,
    val abbreviation: AbbreviationDto? = null,
    val cardCount: CardCountDto? = null,
    val cards: List<CardBriefDto> = emptyList(),
)

/** Synthetic element of the /sets list (without code or cards). [logo] contains the series in the path (e.g. ".../tcgp/A1/logo"). */
@Serializable
data class SetBriefDto(
    val id: String,
    val name: String,
    val cardCount: CardCountDto? = null,
    val logo: String? = null,
)

/** Reference to the series (era) of a set. */
@Serializable
data class SerieRefDto(
    val id: String,
    val name: String,
)

/**
 * Set code: [official] is the one printed on recent English cards (e.g. "POR"),
 * [localized] that of translated editions that have their own (e.g. "PXY" in French).
 */
@Serializable
data class AbbreviationDto(
    val official: String? = null,
    val localized: String? = null,
)
