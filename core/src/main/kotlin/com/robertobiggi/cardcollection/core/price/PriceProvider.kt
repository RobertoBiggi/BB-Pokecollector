package com.robertobiggi.cardcollection.core.price

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
 */
data class PriceRequest(
    val language: Language,
    val tcgdexId: String,
    val variant: VariantType,
    val cardHasNormalAndHolo: Boolean,
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
