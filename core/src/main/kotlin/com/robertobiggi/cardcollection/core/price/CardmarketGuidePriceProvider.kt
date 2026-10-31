package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.PricingDto

/**
 * Cardmarket prices (EUR) read from the public price list. The Cardmarket product of each card comes from the card database
 * ([PriceRequest.cardmarketId]); separate prints and Poké Balls have their own ([PriceRequest.printProductId]).
 *
 * @param guide Cardmarket price list.
 */
class CardmarketGuidePriceProvider(private val guide: CardmarketPriceGuide) : PriceProvider {
    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    /**
     * Returns the price of the variant from the price list.
     * @param request PriceRequest - card and variant to look up.
     * @return PriceQuote? - price found, or null if the card has no product or is not in the price list.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? {
        // Poké Ball and Master Ball are separate products, but they are reverse holo: the price list has their price in the "-holo" fields.
        val productId = if (request.variant.isBall || request.variant.isPrint) request.printProductId else request.cardmarketId
        val cardmarket = guide.lookup(productId ?: return null) ?: return null
        val variant = when {
            request.variant.isBall -> VariantType.REVERSE
            request.variant.isPrint -> request.printFinish
            else -> request.variant
        }
        return VariantMapper.quote(variant, PricingDto(cardmarket = cardmarket), request.cardHasNormalAndHolo && !request.variant.isPrint, DISPLAY_NAME)
    }

    companion object {
        const val ID = "cardmarket-guide"
        const val DISPLAY_NAME = "Cardmarket"
    }
}
