package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderPicker
import com.robertobiggi.cardcollection.core.cardtrader.CtBlueprintDto
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto
import com.robertobiggi.cardcollection.core.cardtrader.CtProductDto
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prezzo più basso tra gli annunci CardTrader (EUR) della variante richiesta.
 * Espansioni, blueprint e annunci vengono scaricati una volta sola per istanza:
 * crea una nuova istanza per ogni aggiornamento prezzi.
 *
 * @param client client CardTrader con il token dell'utente.
 * @param worstCondition condizione peggiore accettata predefinita, se la carta non ne ha una sua.
 * @param onLoadingExpansions chiamata subito prima di scaricare l'elenco dei set (solo la prima volta), per mostrarlo all'utente.
 */
class CardTraderPriceProvider(
    private val client: CardTraderClient,
    private val worstCondition: CtCondition = CtCondition.NEAR_MINT,
    private val onLoadingExpansions: suspend () -> Unit = {},
) : PriceProvider, CardmarketIdSource {
    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    private val mutex = Mutex()
    private var expansions: List<CtExpansionDto>? = null
    private val blueprints = HashMap<Int, List<CtBlueprintDto>>()
    private val products = HashMap<Pair<Int, String?>, List<CtProductDto>>()

    /**
     * Restituisce il prezzo più basso degli annunci della variante.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo in euro, oppure null se CardTrader non ha la carta o annunci per la variante.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? = mutex.withLock {
        val languageCode = languageParam(request.language)
        val matching = match(request) ?: return null
        val listings = matching.flatMap { productsOf(it.id, languageCode) }
        val pick = CardTraderPicker.lowest(
            listings, request.variant, languageCode, request.cardTraderCondition ?: worstCondition,
            firstEditionFallback = request.language == Language.JA && !request.cardHasFirstEdition,
            lowerCondition = true,
        ) ?: return null
        if (pick.currency != "EUR") return null
        val eur = pick.cents / 100.0
        PriceQuote(DISPLAY_NAME, eur, eur, null, null, null, null, Confidence.HIGH)
    }

    /**
     * Restituisce l'id prodotto Cardmarket della carta, anche se non ha annunci su CardTrader.
     * @param request PriceRequest - carta da cercare.
     * @return Int? - l'id, oppure null se la carta non si abbina o i blueprint indicano prodotti diversi.
     */
    override suspend fun cardmarketProductId(request: PriceRequest): Int? = mutex.withLock {
        match(request)?.flatMap { it.cardMarketIds.orEmpty() }?.distinct()?.singleOrNull()
    }

    /** Blueprint della carta, o null se l'espansione o la carta non si trovano. Va chiamata con [mutex] preso. */
    private suspend fun match(request: PriceRequest): List<CtBlueprintDto>? {
        val expansion = CardTraderMatcher.expansionFor(
            expansions(), request.setName, request.setId, request.setCode,
            preferCode = request.language == Language.JA,
        ) ?: return null
        val all = blueprintsOf(expansion.id)
        var matching = CardTraderMatcher.blueprintsFor(all, request.localId, request.dexId)
        if (matching.isEmpty() && request.dexId == null) {
            matching = request.ordinal?.invoke()?.let { CardTraderMatcher.blueprintByOrdinal(all, it) }?.let { listOf(it) }.orEmpty()
        }
        return matching.takeIf { it.isNotEmpty() }
    }

    private suspend fun expansions(): List<CtExpansionDto> {
        expansions?.let { return it }
        onLoadingExpansions()
        val gameId = client.games().firstOrNull { g ->
            g.name.contains("pok", ignoreCase = true) || g.displayName?.contains("pok", ignoreCase = true) == true
        }?.id
        val all = client.expansions().filter { gameId == null || it.gameId == gameId }
        expansions = all
        return all
    }

    private suspend fun blueprintsOf(expansionId: Int): List<CtBlueprintDto> =
        blueprints.getOrPut(expansionId) { client.blueprints(expansionId) }

    private suspend fun productsOf(blueprintId: Int, languageCode: String?): List<CtProductDto> =
        products.getOrPut(blueprintId to languageCode) { client.products(blueprintId, languageCode) }

    /** Lingue per cui CardTrader accetta il filtro; le altre non vengono filtrate. */
    private fun languageParam(language: Language): String? = when (language) {
        Language.EN, Language.IT, Language.FR, Language.DE, Language.ES, Language.PT -> language.code
        Language.JA -> "jp"
        else -> null
    }

    companion object {
        const val ID = "cardtrader"
        const val DISPLAY_NAME = "CardTrader"
    }
}
