package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardmarket.BallVariants
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
 * @param ballVariants blueprint delle varianti Poké Ball e Master Ball.
 */
class CardTraderPriceProvider(
    private val client: CardTraderClient,
    private val worstCondition: CtCondition = CtCondition.NEAR_MINT,
    private val onLoadingExpansions: suspend () -> Unit = {},
    private val ballVariants: BallVariants = BallVariants.bundled,
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
        val listings = if (request.variant.isBall) {
            // Poké Ball e Master Ball hanno un blueprint a parte.
            val blueprint = ballVariants.blueprintId(request.setId, request.localId, request.variant) ?: return null
            productsOf(blueprint, languageCode)
        } else {
            // Una carta del catalogo Cardmarket si trova da quell'id: il suo numero può mancare o essere dedotto dalla posizione.
            val matching = if (request.cardmarketId != null) listOfNotNull(matchByCardmarketId(request)) else match(request) ?: return null
            matching.flatMap { productsOf(it.id, languageCode) }
        }
        val pick = CardTraderPicker.lowest(
            listings, request.variant, pickerLanguage(request.language), request.cardTraderCondition ?: worstCondition,
            firstEditionFallback = request.language == Language.JA && !request.cardHasFirstEdition,
            lowerCondition = true,
            splitHolo = request.cardHasNormalAndHolo,
        ) ?: return null
        if (pick.currency != "EUR") return null
        val eur = pick.cents / 100.0
        PriceQuote(DISPLAY_NAME, eur, eur, null, null, null, null, Confidence.HIGH)
    }

    /**
     * Restituisce l'id del blueprint della carta, per il link diretto alla sua pagina.
     * @param request PriceRequest - carta da cercare.
     * @return Int? - l'id, oppure null se la carta non si abbina con certezza.
     */
    suspend fun blueprintId(request: PriceRequest): Int? = mutex.withLock { blueprintFor(request)?.id }

    /**
     * Restituisce l'id prodotto Cardmarket della carta, anche se non ha annunci su CardTrader.
     * @param request PriceRequest - carta da cercare.
     * @return Int? - l'id, oppure null se la carta non si abbina o i blueprint indicano prodotti diversi.
     */
    override suspend fun cardmarketProductId(request: PriceRequest): Int? = mutex.withLock {
        match(request)?.flatMap { it.cardMarketIds.orEmpty() }?.distinct()?.singleOrNull()
    }

    /**
     * Restituisce il numero stampato della carta con l'id Cardmarket indicato.
     * @param request PriceRequest - set e [PriceRequest.cardmarketId] della carta.
     * @return String? - numero di collezione (es. "066", "SM210"), oppure null se la carta non si abbina o il blueprint non ha numero.
     */
    suspend fun collectorNumber(request: PriceRequest): String? = mutex.withLock {
        val number = (matchByCardmarketId(request)?.fixedProperties?.get("collector_number") as? kotlinx.serialization.json.JsonPrimitive)?.content
        number?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Restituisce l'indirizzo dell'immagine della carta, nella versione media.
     * @param request PriceRequest - carta da cercare.
     * @return String? - URL completo, oppure null se la carta non si abbina, i blueprint sono più d'uno (non si sa quale sia) o non hanno immagine.
     */
    suspend fun imageUrl(request: PriceRequest): String? = mutex.withLock {
        blueprintFor(request)?.takeIf { request.cardmarketId != null || CardTraderMatcher.nameAgrees(it, request.name, request.dexId) }?.let { imageOrSibling(it) }
    }

    /**
     * Rarità e numero stampato della carta, come li indica CardTrader. Serve alle carte del catalogo Cardmarket, che non li hanno.
     * @param request PriceRequest - carta da cercare.
     * @return CardTraderDetails? - dati trovati, oppure null se la carta non si abbina con certezza.
     */
    suspend fun details(request: PriceRequest): CardTraderDetails? = mutex.withLock {
        val blueprint = blueprintFor(request) ?: return null
        val rarity = (blueprint.fixedProperties?.get("pokemon_rarity") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        CardTraderDetails(rarity, CardTraderMatcher.printedNumber(blueprint), imageOrSibling(blueprint))
    }

    /** Per una carta Cardmarket il numero è dedotto dalla posizione: conta solo l'id, altrimenti si rischia la carta sbagliata. */
    private suspend fun blueprintFor(request: PriceRequest): CtBlueprintDto? =
        if (request.cardmarketId != null) matchByCardmarketId(request) else match(request)?.let { CardTraderMatcher.representative(it) }

    /** Immagine del blueprint o, se ne è privo, quella del blueprint indicato da [CardTraderMatcher.imageSibling]. Va chiamata con [mutex] preso. */
    private suspend fun imageOrSibling(blueprint: CtBlueprintDto): String? {
        imageOf(blueprint)?.let { return it }
        val (code, id) = blueprint.cardMarketIds.orEmpty().firstNotNullOfOrNull { CardTraderMatcher.imageSibling(it) } ?: return null
        val expansion = expansions().firstOrNull { it.code?.lowercase() == code } ?: return null
        return blueprintsOf(expansion.id).firstOrNull { it.id == id }?.let { imageOf(it) }
    }

    private fun imageOf(blueprint: CtBlueprintDto): String? {
        val image = blueprint.image ?: return null
        val path = (image.show?.url ?: image.url)?.takeIf { it.isNotBlank() } ?: return null
        return if (path.startsWith("/")) "$SITE$path" else path
    }

    /**
     * Blueprint con lo stesso id Cardmarket della carta, nell'espansione abbinata al set. Non usa il numero della carta.
     * @return CtBlueprintDto? - il blueprint, oppure null se la carta non ha l'id o l'espansione non lo contiene.
     */
    private suspend fun matchByCardmarketId(request: PriceRequest): CtBlueprintDto? {
        val id = request.cardmarketId ?: return null
        val expansion = CardTraderMatcher.expansionForCardmarketId(expansions(), id) ?: CardTraderMatcher.expansionFor(
            expansions(), request.setName, request.setId, request.setCode,
            preferCode = request.language == Language.JA,
        ) ?: return null
        val all = blueprintsOf(expansion.id)
        // Le promo di CardTrader spesso non hanno l'id Cardmarket: allora vale il nome, se indica una sola carta.
        return all.firstOrNull { id in it.cardMarketIds.orEmpty() } ?: CardTraderMatcher.blueprintByName(all, request.name)
    }

    /** Blueprint della carta, o null se l'espansione o la carta non si trovano. Va chiamata con [mutex] preso. */
    private suspend fun match(request: PriceRequest): List<CtBlueprintDto>? {
        val expansion = CardTraderMatcher.expansionFor(
            expansions(), request.setName, request.setId, request.setCode,
            preferCode = request.language == Language.JA,
        ) ?: return null
        val all = blueprintsOf(expansion.id)
        // L'id Cardmarket di TCGdex vale più del numero se questo porta a un'altra carta, e più del nome del Pokémon (che ne trova anche altre stampe).
        val byHint = CardTraderMatcher.byCardmarketHint(all, request.cardmarketHint)
        var matching = CardTraderMatcher.preferHint(CardTraderMatcher.blueprintsFor(all, request.localId, null), byHint)
            .ifEmpty { CardTraderMatcher.blueprintsFor(all, request.localId, request.dexId) }
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

    /**
     * Lingua con cui scartare gli annunci di altre lingue (es. coreano per una carta giapponese): sempre quella della carta.
     * CardTrader scrive `jp` per il giapponese e `zh-TW` per il cinese tradizionale; il confronto è senza maiuscole.
     */
    private fun pickerLanguage(language: Language): String = when (language) {
        Language.JA -> "jp"
        else -> language.code
    }

    /** Lingue per cui CardTrader accetta il filtro; le altre non vengono filtrate. */
    private fun languageParam(language: Language): String? = when (language) {
        Language.EN, Language.IT, Language.FR, Language.DE, Language.ES, Language.PT -> language.code
        Language.JA -> "jp"
        else -> null
    }

    companion object {
        const val ID = "cardtrader"
        const val DISPLAY_NAME = "CardTrader"
        private const val SITE = "https://www.cardtrader.com"
    }
}

/**
 * Dati di una carta letti da CardTrader.
 * @property rarity rarità ("Ultra Rare", "Promo"), se indicata.
 * @property printedNumber numero con il totale del set ("014/135"), se indicato.
 * @property imageUrl indirizzo dell'immagine, se c'è.
 */
data class CardTraderDetails(val rarity: String?, val printedNumber: String?, val imageUrl: String?)
