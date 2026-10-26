package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.tcgdex.CardDto
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prezzi Cardmarket (EUR) e TCGplayer (USD) letti da TCGdex, senza mai contattare cardmarket.com.
 * Il dettaglio di ogni carta viene scaricato una sola volta per istanza, anche se ha più varianti:
 * crea una nuova istanza per ogni aggiornamento prezzi.
 *
 * @param client client TCGdex.
 */
class TcgdexPriceProvider(private val client: TcgdexClient) : PriceProvider {
    override val id: String = ID
    override val displayName: String = "TCGdex"

    private val cache = HashMap<Pair<Language, String>, CardDto>()
    private val mutex = Mutex()

    /**
     * Restituisce il prezzo della variante usando i prezzi per variante, oppure quelli della carta.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo trovato, oppure null se TCGdex non ha dati.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? {
        val card = cardFor(request.language, request.tcgdexId)
        if (request.variant.isPrint) {
            // Una stampa a parte ha il suo prodotto: senza i suoi prezzi non si ripiega su quelli della carta, che sono di un'altra stampa.
            val pricing = card.variantsDetailed.orEmpty().firstOrNull { CatalogMapper.cardmarketIdOf(card, it) == request.printProductId }?.pricing ?: return null
            return VariantMapper.quote(request.printFinish, pricing, false, displayName)
        }
        val detailed = card.variantsDetailed.orEmpty().firstOrNull {
            it.type == request.variant.tcgdexKey && (it.size == null || it.size == "standard")
        }
        val pricing = detailed?.pricing ?: card.pricing
        return VariantMapper.quote(request.variant, pricing, request.cardHasNormalAndHolo, displayName)
    }

    /**
     * Restituisce il dettaglio della carta dalla cache o dalla rete.
     * @param language Language - lingua del catalogo.
     * @param tcgdexId String - id TCGdex della carta.
     * @return CardDto - dettaglio della carta.
     */
    private suspend fun cardFor(language: Language, tcgdexId: String): CardDto {
        val key = language to tcgdexId
        mutex.withLock { cache[key] }?.let { return it }
        val card = client.getCard(language, tcgdexId)
        mutex.withLock { cache[key] = card }
        return card
    }

    companion object {
        const val ID = "tcgdex"
    }
}
