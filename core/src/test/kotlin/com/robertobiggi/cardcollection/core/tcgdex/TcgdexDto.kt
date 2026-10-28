package com.robertobiggi.cardcollection.core.tcgdex

import kotlinx.serialization.SerialName
import com.robertobiggi.cardcollection.core.price.PricingDto
import kotlinx.serialization.Serializable

// DTO dell'API TCGdex v2, usati solo dai test per leggere le risposte salvate in resources/fixtures
// (l'app non interroga più TCGdex per le carte).

/** Elemento della lista restituita da /cards?name=... */
@Serializable
data class CardBriefDto(
    val id: String,
    val localId: String,
    val name: String,
    val image: String? = null,
)

/** Dettaglio di una carta da /cards/{id}. */
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

/** Riferimento al set contenuto nel dettaglio carta. */
@Serializable
data class CardSetRefDto(
    val id: String,
    val name: String,
    val cardCount: CardCountDto? = null,
)

/** Conteggio delle carte di un set: [official] è il numero stampato dopo la barra. */
@Serializable
data class CardCountDto(
    val official: Int? = null,
    val total: Int? = null,
)

/** Varianti disponibili espresse come flag booleani (campo storico di TCGdex). */
@Serializable
data class VariantFlagsDto(
    val normal: Boolean = false,
    val reverse: Boolean = false,
    val holo: Boolean = false,
    val firstEdition: Boolean = false,
    val wPromo: Boolean = false,
)

/**
 * Variante dettagliata con i prezzi specifici. [size] distingue le carte standard dalle jumbo, [subtype] la stampa
 * (unlimited, shadowless…), [stamp] i timbri (1st-edition, poketour-99…).
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
    /** Id prodotto Cardmarket della variante, se TCGdex lo indica. */
    val cardmarketId: Int? get() = thirdParty?.cardmarket ?: pricing?.cardmarket?.idProduct
}

/** Id dei prodotti della variante presso altri siti. */
@Serializable
data class ThirdPartyDto(val cardmarket: Int? = null)

/** Dettaglio di un set da /sets/{id}. */
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

/** Elemento sintetico della lista /sets (senza sigla né carte). [logo] contiene la serie nel percorso (es. ".../tcgp/A1/logo"). */
@Serializable
data class SetBriefDto(
    val id: String,
    val name: String,
    val cardCount: CardCountDto? = null,
    val logo: String? = null,
)

/** Riferimento alla serie (era) di un set. */
@Serializable
data class SerieRefDto(
    val id: String,
    val name: String,
)

/**
 * Sigla del set: [official] è quella stampata sulle carte inglesi recenti (es. "POR"),
 * [localized] quella delle edizioni tradotte che ne hanno una propria (es. "PXY" in francese).
 */
@Serializable
data class AbbreviationDto(
    val official: String? = null,
    val localized: String? = null,
)
