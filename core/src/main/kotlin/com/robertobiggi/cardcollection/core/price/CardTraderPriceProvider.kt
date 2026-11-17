package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardmarket.BallVariants
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderPicker
import com.robertobiggi.cardcollection.core.cardtrader.CtBlueprintDto
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.cardtrader.CtProductDto
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lowest price among the CardTrader listings (EUR) of the requested variant.
 * Expansions, blueprints and listings are downloaded only once per instance:
 * create a new instance for each price update.
 *
 * @param client CardTrader client with the user's token.
 * @param worstCondition default worst accepted condition, if the card has none of its own.
 * @param ballVariants blueprints of the Poké Ball and Master Ball variants.
 */
class CardTraderPriceProvider(
    private val client: CardTraderClient,
    private val worstCondition: CtCondition = CtCondition.NEAR_MINT,
    private val ballVariants: BallVariants = BallVariants.bundled,
) : PriceProvider {
    override val id: String = ID
    override val displayName: String = DISPLAY_NAME

    private val mutex = Mutex()
    private val blueprints = HashMap<Int, List<CtBlueprintDto>>()
    private val products = HashMap<Pair<Int, String?>, List<CtProductDto>>()

    /**
     * Returns the lowest price among the listings of the variant.
     * @param request PriceRequest - card and variant to look up.
     * @return PriceQuote? - price in euros, or null if CardTrader has no such card or no listings for the variant.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? = mutex.withLock {
        // Each card of these sets has its own blueprint with listings only in that country's language, while the card is catalogued in English.
        val anyLanguage = request.setId in ANY_LANGUAGE_SETS
        val languageCode = if (anyLanguage) null else languageParam(request.language)
        // The blueprint comes from the card database (already matched by the generator); Poké Ball and Master Ball have a separate one.
        // Separate prints have a blueprint only if the database gives one for the variant: the card's one belongs to the normal card.
        val blueprint = when {
            request.variant.isBall -> ballVariants.blueprintId(request.setId, request.localId, request.variant)
            request.variant.isPrint -> request.variantBlueprintId
            else -> request.variantBlueprintId ?: request.cardTraderBlueprintId
        } ?: return null
        val listings = productsOf(blueprint, languageCode)
        val pick = CardTraderPicker.lowest(
            listings, request.variant, if (anyLanguage) null else pickerLanguage(request.language), request.cardTraderCondition ?: worstCondition,
            firstEditionFallback = request.language == Language.JA && !request.cardHasFirstEdition,
            lowerCondition = true,
            splitHolo = request.cardHasNormalAndHolo,
        ) ?: return null
        if (pick.currency != "EUR") return null
        val eur = pick.cents / 100.0
        PriceQuote(DISPLAY_NAME, eur, eur, null, null, Confidence.HIGH)
    }

    /**
     * Address of the image of a blueprint, in the medium version.
     * @param expansionId Int - CardTrader expansion of the card's set.
     * @param blueprintId Int - blueprint of the card.
     * @return String? - full URL, or null if the blueprint does not exist or has no image.
     */
    suspend fun imageUrl(expansionId: Int, blueprintId: Int): String? = mutex.withLock {
        blueprintsOf(expansionId).firstOrNull { it.id == blueprintId }?.let { imageOf(it) }
    }

    /**
     * Cardmarket product of a blueprint, for cards the catalog could not match to Cardmarket (CardTrader lists it in `card_market_ids`).
     * @param expansionId Int - CardTrader expansion of the card's set.
     * @param blueprintId Int - blueprint of the card.
     * @return Int? - the product, or null if the blueprint has none or more than one (ambiguous), or CardTrader cannot be reached.
     */
    suspend fun cardmarketProductOf(expansionId: Int, blueprintId: Int): Int? = mutex.withLock {
        try {
            blueprintsOf(expansionId).firstOrNull { it.id == blueprintId }?.cardMarketIds?.singleOrNull()
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun imageOf(blueprint: CtBlueprintDto): String? {
        val image = blueprint.image ?: return null
        val path = (image.show?.url ?: image.url)?.takeIf { it.isNotBlank() } ?: return null
        return if (path.startsWith("/")) "$SITE$path" else path
    }

    private suspend fun blueprintsOf(expansionId: Int): List<CtBlueprintDto> =
        blueprints.getOrPut(expansionId) { client.blueprints(expansionId) }

    private suspend fun productsOf(blueprintId: Int, languageCode: String?): List<CtProductDto> =
        products.getOrPut(blueprintId to languageCode) { client.products(blueprintId, languageCode) }

    /**
     * Language used to discard listings in other languages (e.g. Korean for a Japanese card): always the card's.
     * CardTrader writes `jp` for Japanese and `zh-TW` for traditional Chinese; the comparison is case-insensitive.
     */
    private fun pickerLanguage(language: Language): String = when (language) {
        Language.JA -> "jp"
        else -> language.code
    }

    /** Languages for which CardTrader accepts the filter; the others are not filtered. */
    private fun languageParam(language: Language): String? = when (language) {
        Language.EN, Language.IT, Language.FR, Language.DE, Language.ES, Language.PT -> language.code
        Language.JA -> "jp"
        else -> null
    }

    companion object {
        const val ID = "cardtrader"
        const val DISPLAY_NAME = "CardTrader"

        /** Sets whose cards are one per country (Pikachu World Collection 2010: KR, PT, DE…): their listings are not filtered by language. */
        private val ANY_LANGUAGE_SETS = setOf("wc2010")
        private const val SITE = "https://www.cardtrader.com"
    }
}
