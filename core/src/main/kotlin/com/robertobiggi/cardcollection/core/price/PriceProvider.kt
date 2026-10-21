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
 * @property setId id TCGdex del set.
 * @property setName nome del set.
 * @property setCode sigla stampata del set, se c'è.
 * @property localId numero della carta nel set (es. "075").
 * @property cardTraderCondition condizione peggiore accettata per CardTrader scelta per questa carta, null = predefinita.
 */
data class PriceRequest(
    val language: Language,
    val tcgdexId: String,
    val variant: VariantType,
    val cardHasNormalAndHolo: Boolean,
    val setId: String = "",
    val setName: String = "",
    val setCode: String? = null,
    val localId: String = "",
    val cardTraderCondition: CtCondition? = null,
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
