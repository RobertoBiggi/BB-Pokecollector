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
 */
class CardTraderPriceProvider(
    private val client: CardTraderClient,
    private val worstCondition: CtCondition = CtCondition.NEAR_MINT,
) : PriceProvider {
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
        val expansion = CardTraderMatcher.expansionFor(
            expansions(), request.setName, request.setId, request.setCode,
        ) ?: return null
        val matching = CardTraderMatcher.blueprintsFor(blueprintsOf(expansion.id), request.localId)
        if (matching.isEmpty()) return null
        val listings = matching.flatMap { productsOf(it.id, languageCode) }
        val pick = CardTraderPicker.lowest(listings, request.variant, languageCode, request.cardTraderCondition ?: worstCondition) ?: return null
        if (pick.currency != "EUR") return null
        val eur = pick.cents / 100.0
        PriceQuote(DISPLAY_NAME, eur, eur, null, null, null, null, Confidence.HIGH)
    }

    private suspend fun expansions(): List<CtExpansionDto> {
        expansions?.let { return it }
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
