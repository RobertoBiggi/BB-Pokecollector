package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.price.CardmarketIdSource
import com.robertobiggi.cardcollection.core.price.PriceRequest
import kotlinx.coroutines.CancellationException

/**
 * Trova il prodotto Cardmarket di una carta dal catalogo pubblico, senza CardTrader: abbina il set tramite
 * l'elenco incluso nell'app, poi la carta per nome (o per Pokédex). Se nello stesso set ci sono più carte con
 * quel nome, vale quella che sta nella posizione indicata dal numero della carta; se resta il dubbio non risponde.
 *
 * @param catalog catalogo Cardmarket.
 * @param expansions set con il loro id Cardmarket.
 */
class CardmarketCatalogIdSource(
    private val catalog: CardmarketCatalog,
    private val expansions: CardmarketExpansions = CardmarketExpansions.bundled,
) : CardmarketIdSource {

    /**
     * Cerca l'id prodotto Cardmarket della carta.
     * @param request PriceRequest - carta da cercare (set, numero, nome e Pokédex).
     * @return Int? - l'id, oppure null se il set o la carta non si trovano o l'abbinamento non è sicuro.
     */
    override suspend fun cardmarketProductId(request: PriceRequest): Int? {
        val expansion = CardTraderMatcher.expansionFor(
            expansions.expansions, request.setName, request.setId, request.setCode,
            preferCode = request.language == Language.JA,
        ) ?: return null
        val products = catalog.productsOf(expansions.cardmarketId(expansion.id) ?: return null)
        val keys = buildSet {
            request.dexId?.let { dex -> PokemonNames.namesOf(dex).forEach { add(CardmarketCatalog.nameKey(it)) } }
            if (request.name.isNotBlank()) add(CardmarketCatalog.nameKey(request.name))
        }
        val exact = products.filter { it.nameKey in keys }
        // Carte il cui nome Cardmarket aggiunge qualcosa ("Brock's Omastar", "Omastar δ Delta Species"): vale se nel set ce n'è una sola.
        val candidates = exact.ifEmpty { products.filter { p -> keys.any { it.length >= MIN_CONTAINED_KEY && it in p.nameKey } } }
        if (candidates.size <= 1) return candidates.singleOrNull()?.idProduct
        // I prodotti di un set seguono l'ordine delle carte: il numero stampato è la posizione nel set.
        val number = CardTraderMatcher.normalizeNumber(request.localId).toIntOrNull() ?: return null
        val atPosition = products.getOrNull(number - 1) ?: return null
        return atPosition.idProduct.takeIf { p -> candidates.any { it.idProduct == p } }
    }
}

private const val MIN_CONTAINED_KEY = 4

/**
 * Prova prima questa fonte e, se non trova la carta o non è disponibile (rete, token non valido), l'altra.
 * @param other fonte di ripiego.
 * @return CardmarketIdSource - fonte composta.
 */
fun CardmarketIdSource.orElse(other: CardmarketIdSource): CardmarketIdSource = CardmarketIdSource { request ->
    val first = try {
        cardmarketProductId(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    first ?: other.cardmarketProductId(request)
}
