package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvClient
import com.robertobiggi.cardcollection.core.tcgcsv.TcgcsvPriceDto
import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * TCGplayer price in dollars of a variant.
 * @property market market price (the reference one), or the mid price if the market one is missing.
 * @property low lowest listed price, null if absent.
 */
data class UsdQuote(val market: Double, val low: Double?)

/**
 * TCGplayer prices, read from TCGCSV with the product the card database generator matched to the card (`card.tcgplayer`, "<group>/<product>").
 * Only English and Japanese cards have one; a card without it has no TCGplayer price.
 */
class TcgplayerPriceProvider(private val client: TcgcsvClient) {
    /**
     * Looks up the TCGplayer price of a variant.
     * @param request PriceRequest - card and variant; [PriceRequest.tcgplayerRef] is the product.
     * @return UsdQuote? - price, or null if the card has no product or TCGplayer has no price for the variant.
     */
    suspend fun fetch(request: PriceRequest): UsdQuote? {
        val (group, product) = parseRef(request.tcgplayerRef) ?: return null
        val category = categoryOf(request.language) ?: return null
        val rows = client.prices(category, group).filter { it.productId == product }
        return pick(request.variant, rows)
    }

    companion object {
        /**
         * Splits a reference saved by the generator.
         * @param ref String? - "<groupId>/<productId>".
         * @return Pair<Long, Long>? - group and product, or null if the text is not a valid reference.
         */
        fun parseRef(ref: String?): Pair<Long, Long>? {
            val parts = ref?.split('/') ?: return null
            if (parts.size != 2) return null
            val group = parts[0].toLongOrNull() ?: return null
            val product = parts[1].toLongOrNull() ?: return null
            return if (group > 0 && product > 0) group to product else null
        }

        /** TCGplayer category of the cards of a language; null where TCGplayer has no cards (every language but English and Japanese). */
        fun categoryOf(language: Language): Int? = when (language) {
            Language.EN -> TcgcsvClient.CATEGORY_POKEMON
            Language.JA -> TcgcsvClient.CATEGORY_POKEMON_JAPAN
            else -> null
        }

        /**
         * Picks the row of the variant among those of its product and turns it into a price.
         * @param type VariantType - requested variant.
         * @param rows List<TcgcsvPriceDto> - rows of the product.
         * @return UsdQuote? - price of the matching row, null if there is none or it has no valid value.
         */
        fun pick(type: VariantType, rows: List<TcgcsvPriceDto>): UsdQuote? {
            fun row(vararg names: String): TcgcsvPriceDto? = names.firstNotNullOfOrNull { n -> rows.firstOrNull { it.subTypeName.equals(n, ignoreCase = true) } }
            val chosen = when (type) {
                VariantType.NORMAL -> row("Normal")
                VariantType.HOLO -> row("Holofoil", "Unlimited Holofoil")
                VariantType.REVERSE -> row("Reverse Holofoil")
                VariantType.FIRST_EDITION -> row("1st Edition Holofoil", "1st Edition Normal", "1st Edition")
                VariantType.W_PROMO -> row("Holofoil", "Normal")
                else -> null // separate prints and balls have their own products or none: no TCGplayer price is guessed for them
            } ?: return null
            val market = valid(chosen.marketPrice) ?: valid(chosen.midPrice) ?: return null
            return UsdQuote(market, valid(chosen.lowPrice))
        }

        private fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 && it < Validators.MAX_PRICE }
    }
}
