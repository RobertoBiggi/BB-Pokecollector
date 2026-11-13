package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType

/**
 * Price request for a single variant.
 *
 * @property language language of the card's catalog.
 * @property tcgdexId TCGdex id of the card.
 * @property variant requested variant.
 * @property cardHasNormalAndHolo true if the card exists in both normal and holo.
 * @property cardHasFirstEdition true if TCGdex also lists the 1st Edition variant (then CardTrader does not use it as a fallback for normal/holo).
 * @property setId TCGdex id of the set.
 * @property localId number of the card in the set (e.g. "075").
 * @property cardTraderCondition worst condition accepted for CardTrader (that of the variant), null = Near Mint.
 * @property cardmarketId Cardmarket product id, if the card comes from the Cardmarket catalog: CardTrader finds it from this, without the number.
 * @property printProductId Cardmarket product of the requested print, if [variant] is a separate print ([VariantType.isPrint]).
 * @property tcgplayerRef TCGplayer product of the card as "<group>/<product>" (card database), null if the generator found none.
 * @property printFinish finish (normal or holo) of the requested print: needed to read the right price of its product.
 */
data class PriceRequest(
    val language: Language,
    val tcgdexId: String,
    val variant: VariantType,
    val cardHasNormalAndHolo: Boolean,
    val cardHasFirstEdition: Boolean = false,
    val setId: String = "",
    val localId: String = "",
    val cardTraderCondition: CtCondition? = null,
    val cardmarketId: Int? = null,
    val printProductId: Int? = null,
    val printFinish: VariantType = VariantType.NORMAL,
    val tcgplayerRef: String? = null,
    val cardTraderBlueprintId: Int? = null,
    /** CardTrader blueprint of the variant alone (oversized card without a Cardmarket product): if present it is used instead of the card's. */
    val variantBlueprintId: Int? = null,
)

/** Price source. Implementations throw the exceptions of [com.robertobiggi.cardcollection.core.net]. */
interface PriceProvider {
    /** Stable identifier, used to save the circuit breaker state. */
    val id: String

    /** Name shown to the user. */
    val displayName: String

    /**
     * Looks up the price of a variant.
     * @param request PriceRequest - card and variant to look up.
     * @return PriceQuote? - price found, or null if the source has no data for the variant.
     */
    suspend fun fetch(request: PriceRequest): PriceQuote?
}
