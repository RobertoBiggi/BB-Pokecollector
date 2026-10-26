package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardmarket.BallVariants
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
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
 * @param ballVariants prodotti delle varianti Poké Ball e Master Ball.
 */
class CardmarketGuidePriceProvider(
    private val ids: CardmarketIdSource,
    private val guide: CardmarketPriceGuide,
    private val ballVariants: BallVariants = BallVariants.bundled,
) : PriceProvider {
    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    /**
     * Restituisce il prezzo della variante dal listino.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo trovato, oppure null se la carta non è abbinata o non è nel listino.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? {
        // Una carta del catalogo Cardmarket porta già il suo prodotto: il numero della carta non serve (e spesso non c'è).
        // Poké Ball e Master Ball sono prodotti Cardmarket a parte, ma sono reverse holo: il listino ha il loro prezzo nei campi "-holo".
        // Le stampe a parte (shadowless, jumbo…) hanno il loro prodotto, indicato da TCGdex.
        val productId = when {
            request.variant.isBall -> ballVariants.productId(request.setId, request.localId, request.variant)
            request.variant.isPrint -> request.printProductId
            else -> request.cardmarketId ?: ids.cardmarketProductId(request)
        }
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
