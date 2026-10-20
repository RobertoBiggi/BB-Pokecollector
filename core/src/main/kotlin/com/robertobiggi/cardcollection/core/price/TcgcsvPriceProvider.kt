package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvCardRef
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvCatalog
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvPriceDto
import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * Prezzi TCGplayer (USD) letti da TCGCSV, per le carte inglesi e giapponesi. Serve da seconda fonte:
 * completa i prezzi in dollari che TCGdex non ha (soprattutto per le carte giapponesi) o li fornisce
 * quando TCGdex non risponde.
 *
 * @param catalog catalogo TCGCSV, con la cache dei set già scaricati.
 */
class TcgcsvPriceProvider(private val catalog: TcgcsvCatalog) : PriceProvider {
    override val id: String = ID
    override val displayName: String = "TCGplayer (TCGCSV)"

    /**
     * Cerca il prodotto della carta e sceglie la riga di prezzo della variante.
     * @param request PriceRequest - carta e variante; servono sigla o nome del set e numero.
     * @return PriceQuote? - prezzo in dollari, oppure null se la carta o la variante non si trovano.
     */
    override suspend fun fetch(request: PriceRequest): PriceQuote? {
        val localId = request.localId ?: return null
        val match = catalog.find(
            TcgcsvCardRef(request.language, request.setCode, request.setName, localId, request.name.orEmpty()),
        ) ?: return null
        val rows = catalog.prices(match).filter { usd(it) != null }
        val exact = subTypesFor(request.variant).firstNotNullOfOrNull { name -> rows.firstOrNull { it.subTypeName.equals(name, ignoreCase = true) } }
        // Se TCGplayer vende la carta in una sola versione, vale per la normal o la holo di TCGdex
        // (le carte giapponesi sono spesso catalogate in modo diverso), con affidabilità media.
        val row = exact ?: rows.singleOrNull()?.takeIf { request.variant == VariantType.NORMAL || request.variant == VariantType.HOLO }
            ?: return null
        return PriceQuote(
            source = displayName,
            eur = null,
            eurLow = null,
            eurAvg30 = null,
            usd = usd(row),
            usdLow = positive(row.lowPrice),
            sourceUpdatedAt = null,
            confidence = if (exact != null && request.variant != VariantType.W_PROMO) Confidence.HIGH else Confidence.MEDIUM,
        )
    }

    /**
     * Prezzo di riferimento di una riga: il prezzo di mercato, altrimenti il prezzo medio.
     * @param row TcgcsvPriceDto - riga di prezzo.
     * @return Double? - prezzo valido in dollari, oppure null.
     */
    private fun usd(row: TcgcsvPriceDto): Double? = positive(row.marketPrice) ?: positive(row.midPrice)

    companion object {
        const val ID = "tcgcsv"

        /**
         * Nomi delle varianti TCGplayer corrispondenti a una variante, in ordine di preferenza.
         * @param type VariantType - variante richiesta.
         * @return List<String> - valori di subTypeName.
         */
        fun subTypesFor(type: VariantType): List<String> = when (type) {
            VariantType.NORMAL -> listOf("Normal")
            VariantType.HOLO -> listOf("Holofoil", "Unlimited Holofoil")
            VariantType.REVERSE -> listOf("Reverse Holofoil")
            VariantType.FIRST_EDITION -> listOf("1st Edition Holofoil", "1st Edition Normal", "1st Edition")
            VariantType.W_PROMO -> listOf("Holofoil", "Normal")
        }

        /**
         * Scarta i valori nulli, non finiti, non positivi o fuori scala.
         * @param value Double? - valore da controllare.
         * @return Double? - il valore se è un prezzo valido, altrimenti null.
         */
        private fun positive(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 && it < Validators.MAX_PRICE }
    }
}
