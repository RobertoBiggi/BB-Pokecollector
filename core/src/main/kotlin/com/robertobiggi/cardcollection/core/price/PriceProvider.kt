package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType

/**
 * Richiesta di prezzo per una singola variante.
 *
 * @property language lingua del catalogo della carta.
 * @property tcgdexId id TCGdex della carta.
 * @property variant variante richiesta.
 * @property cardHasNormalAndHolo true se la carta esiste sia normal sia holo.
 * @property cardHasFirstEdition true se TCGdex elenca anche la variante 1st Edition (allora CardTrader non la usa come ripiego per normal/holo).
 * @property name nome della carta nel catalogo (inglese per le carte inglesi e per quelle importate).
 * @property setId id TCGdex del set.
 * @property setName nome del set.
 * @property setCode sigla stampata del set, se c'è.
 * @property localId numero della carta nel set (es. "075").
 * @property dexId numero del Pokédex nazionale, null per Allenatori ed Energie.
 * @property cardTraderCondition condizione peggiore accettata per CardTrader (quella della variante), null = Near Mint.
 * @property cardmarketId id prodotto Cardmarket, se la carta viene dal catalogo Cardmarket: CardTrader la trova da questo, senza il numero.
 * @property cardmarketHint id prodotto Cardmarket indicato da TCGdex: serve solo ad abbinare la carta su CardTrader quando il numero non basta.
 * @property printProductId prodotto Cardmarket della stampa richiesta, se [variant] è una stampa a parte ([VariantType.isPrint]).
 * @property printFinish finitura (normale o holo) della stampa richiesta: serve a leggere il prezzo giusto del suo prodotto.
 */
data class PriceRequest(
    val language: Language,
    val tcgdexId: String,
    val variant: VariantType,
    val cardHasNormalAndHolo: Boolean,
    val cardHasFirstEdition: Boolean = false,
    val setId: String = "",
    val setName: String = "",
    val setCode: String? = null,
    val localId: String = "",
    val name: String = "",
    val dexId: Int? = null,
    val cardTraderCondition: CtCondition? = null,
    val cardmarketId: Int? = null,
    val cardmarketHint: Int? = null,
    val printProductId: Int? = null,
    val printFinish: VariantType = VariantType.NORMAL,
    val cardTraderBlueprintId: Int? = null,
    /** Blueprint CardTrader della sola variante (oversize senza prodotto Cardmarket): se c'è vale al posto di quello della carta. */
    val variantBlueprintId: Int? = null,
)

/** Fonte di prezzi. Le implementazioni lanciano le eccezioni di [com.robertobiggi.cardcollection.core.net]. */
interface PriceProvider {
    /** Identificativo stabile, usato per salvare lo stato del circuit breaker. */
    val id: String

    /** Nome mostrato all'utente. */
    val displayName: String

    /**
     * Cerca il prezzo di una variante.
     * @param request PriceRequest - carta e variante da cercare.
     * @return PriceQuote? - prezzo trovato, oppure null se la fonte non ha dati per la variante.
     */
    suspend fun fetch(request: PriceRequest): PriceQuote?
}
