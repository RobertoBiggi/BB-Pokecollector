package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.PricingDto

/**
 * Prezzi Cardmarket (EUR) letti dal listino pubblico. Il prodotto Cardmarket di ogni carta arriva dal database delle carte
 * ([PriceRequest.cardmarketId]); le stampe a parte e le Poké Ball hanno il loro ([PriceRequest.printProductId]).
 *
 * @param guide listino Cardmarket.
 */
class CardmarketGuidePriceProvider(private val guide: CardmarketPriceGuide) : PriceProvider {
    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    /**
     * Restituisce il prezzo della variante dal listino.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo trovato, oppure null se la carta non ha un prodotto o non è nel listino.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? {
        // Poké Ball e Master Ball sono prodotti a parte, ma sono reverse holo: il listino ha il loro prezzo nei campi "-holo".
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
