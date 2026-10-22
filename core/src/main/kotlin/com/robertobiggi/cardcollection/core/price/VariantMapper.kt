package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.tcgdex.CardmarketPricingDto
import com.robertobiggi.cardcollection.core.tcgdex.PricingDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgplayerPriceDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgplayerPricingDto
import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * Associa i prezzi Cardmarket e TCGplayer alla variante giusta.
 *
 * Cardmarket ha un solo prodotto per carta: i campi senza suffisso valgono per la versione base
 * (normal o holo rare), quelli "-holo" per la reverse holo. TCGplayer separa ogni variante.
 */
object VariantMapper {

    /**
     * Calcola il prezzo di una variante a partire dai prezzi TCGdex.
     * @param type VariantType - variante richiesta.
     * @param pricing PricingDto? - prezzi della variante (o della carta, se mancano quelli per variante).
     * @param cardHasNormalAndHolo Boolean - true se la carta esiste sia normal sia holo: in quel caso
     *        il prezzo base Cardmarket non distingue le due e l'affidabilità scende.
     * @param source String - nome della fonte da riportare nel risultato.
     * @return PriceQuote? - prezzo trovato, oppure null se nessun mercato ha un valore per la variante.
     */
    fun quote(
        type: VariantType,
        pricing: PricingDto?,
        cardHasNormalAndHolo: Boolean,
        source: String,
    ): PriceQuote? {
        if (pricing == null) return null
        val cm = cardmarketFor(type, pricing.cardmarket, cardHasNormalAndHolo)
        val tp = tcgplayerFor(type, pricing.tcgplayer)
        if (cm == null && tp == null) return null
        val confidence = when {
            cm != null -> cm.confidence
            type == VariantType.W_PROMO -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        return PriceQuote(
            source = source,
            eur = cm?.trend,
            eurLow = cm?.low,
            eurAvg30 = cm?.avg30,
            usd = tp?.let { positive(it.marketPrice) ?: positive(it.midPrice) },
            usdLow = tp?.let { positive(it.lowPrice) },
            sourceUpdatedAt = pricing.cardmarket?.updated ?: pricing.tcgplayer?.updated,
            confidence = confidence,
        )
    }

    /** Valori Cardmarket scelti per una variante. */
    private data class CmPick(val trend: Double, val low: Double?, val avg30: Double?, val confidence: Confidence)

    /**
     * Sceglie i campi Cardmarket giusti per la variante.
     * @param type VariantType - variante richiesta.
     * @param cm CardmarketPricingDto? - prezzi Cardmarket.
     * @param cardHasNormalAndHolo Boolean - vedi [quote].
     * @return CmPick? - valori scelti, oppure null se il trend manca o non è positivo.
     */
    private fun cardmarketFor(type: VariantType, cm: CardmarketPricingDto?, cardHasNormalAndHolo: Boolean): CmPick? {
        if (cm == null) return null
        if (cm.unit != null && cm.unit != "EUR") return null
        return when (type) {
            // Prodotti a parte su Cardmarket: li prezza il listino con il loro id, non la scheda TCGdex della carta base.
            VariantType.POKE_BALL, VariantType.MASTER_BALL -> null
            VariantType.REVERSE -> positive(cm.trendHolo ?: cm.avgHolo)?.let {
                CmPick(it, positive(cm.lowHolo), positive(cm.avg30Holo), Confidence.HIGH)
            }
            else -> positive(cm.trend ?: cm.avg)?.let {
                val confidence = when (type) {
                    VariantType.NORMAL -> Confidence.HIGH
                    VariantType.HOLO -> if (cardHasNormalAndHolo) Confidence.MEDIUM else Confidence.HIGH
                    VariantType.FIRST_EDITION -> Confidence.LOW
                    else -> Confidence.MEDIUM
                }
                CmPick(it, positive(cm.low), positive(cm.avg30), confidence)
            }
        }
    }

    /**
     * Sceglie la riga TCGplayer corrispondente alla variante.
     * @param type VariantType - variante richiesta.
     * @param tp TcgplayerPricingDto? - prezzi TCGplayer.
     * @return TcgplayerPriceDto? - riga con almeno un prezzo positivo, oppure null.
     */
    private fun tcgplayerFor(type: VariantType, tp: TcgplayerPricingDto?): TcgplayerPriceDto? {
        if (tp == null) return null
        if (tp.unit != null && tp.unit != "USD") return null
        val row = when (type) {
            VariantType.NORMAL -> tp.normal
            VariantType.HOLO -> tp.holofoil ?: tp.unlimitedHolofoil
            VariantType.REVERSE -> tp.reverseHolofoil
            VariantType.FIRST_EDITION -> tp.firstEditionHolofoil ?: tp.firstEditionNormal
            VariantType.W_PROMO -> tp.holofoil ?: tp.normal
            VariantType.POKE_BALL, VariantType.MASTER_BALL -> null
        }
        return row?.takeIf { positive(it.marketPrice) != null || positive(it.midPrice) != null }
    }

    /**
     * Scarta i valori nulli, non finiti o non positivi (TCGdex usa 0 per "nessun dato").
     * @param value Double? - valore da controllare.
     * @return Double? - il valore se è un prezzo valido, altrimenti null.
     */
    private fun positive(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 && it < Validators.MAX_PRICE }
}
