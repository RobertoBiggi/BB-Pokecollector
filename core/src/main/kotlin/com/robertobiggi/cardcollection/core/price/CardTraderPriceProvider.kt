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
 * Prezzo più basso tra gli annunci CardTrader (EUR) della variante richiesta.
 * Espansioni, blueprint e annunci vengono scaricati una volta sola per istanza:
 * crea una nuova istanza per ogni aggiornamento prezzi.
 *
 * @param client client CardTrader con il token dell'utente.
 * @param worstCondition condizione peggiore accettata predefinita, se la carta non ne ha una sua.
 * @param ballVariants blueprint delle varianti Poké Ball e Master Ball.
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
     * Restituisce il prezzo più basso degli annunci della variante.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo in euro, oppure null se CardTrader non ha la carta o annunci per la variante.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? = mutex.withLock {
        val languageCode = languageParam(request.language)
        // Il blueprint arriva dal database delle carte (già abbinato dal generatore); Poké Ball e Master Ball ne hanno uno a parte.
        // Le stampe a parte hanno un blueprint solo se il database lo indica per la variante: quello della carta è della carta normale.
        val blueprint = when {
            request.variant.isBall -> ballVariants.blueprintId(request.setId, request.localId, request.variant)
            request.variant.isPrint -> request.variantBlueprintId
            else -> request.variantBlueprintId ?: request.cardTraderBlueprintId
        } ?: return null
        val listings = productsOf(blueprint, languageCode)
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
     * Indirizzo dell'immagine di un blueprint, nella versione media.
     * @param expansionId Int - espansione CardTrader del set della carta.
     * @param blueprintId Int - blueprint della carta.
     * @return String? - URL completo, oppure null se il blueprint non c'è o non ha immagine.
     */
    suspend fun imageUrl(expansionId: Int, blueprintId: Int): String? = mutex.withLock {
        blueprintsOf(expansionId).firstOrNull { it.id == blueprintId }?.let { imageOf(it) }
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
