package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.tcgdex.PricingDto

/** Fonte che sa dire a quale prodotto Cardmarket corrisponde una carta. */
fun interface CardmarketIdSource {
    /**
     * Trova l'id prodotto Cardmarket (`idProduct`) della carta.
     * @param request PriceRequest - carta da cercare.
     * @return Int? - l'id, oppure null se la carta non si abbina o l'abbinamento non è univoco.
     */
    suspend fun cardmarketProductId(request: PriceRequest): Int?
}

/**
 * Prezzi Cardmarket (EUR) letti dal listino pubblico. Serve per le carte che TCGdex non ha:
 * l'id prodotto arriva dall'abbinamento CardTrader, anche quando la carta non ha annunci.
 *
 * @param ids fonte degli id prodotto.
 * @param guide listino Cardmarket.
 */
class CardmarketGuidePriceProvider(
    private val ids: CardmarketIdSource,
    private val guide: CardmarketPriceGuide,
) : PriceProvider {
    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    /**
     * Restituisce il prezzo della variante dal listino.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo trovato, oppure null se la carta non è abbinata o non è nel listino.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? {
        val productId = ids.cardmarketProductId(request) ?: return null
        val cardmarket = guide.lookup(productId) ?: return null
        return VariantMapper.quote(request.variant, PricingDto(cardmarket = cardmarket), request.cardHasNormalAndHolo, DISPLAY_NAME)
    }

    companion object {
        const val ID = "cardmarket-guide"
        const val DISPLAY_NAME = "Cardmarket"
    }
}
