package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.model.VariantType

/**
 * Carte giapponesi con reverse holo Poké Ball e Master Ball (es. Pokémon Card 151). Su Cardmarket ogni variante è un
 * prodotto a parte, in un set "... - Poké Ball Reverse Holo", e TCGdex non le elenca. L'elenco è incluso nell'app
 * (`cardmarket-ball-variants.tsv`) e lo genera `tools/gen_extra_sets.py` dai set CardTrader.
 *
 * @param products prodotto Cardmarket e blueprint CardTrader per set, numero e variante.
 */
class BallVariants(private val products: Map<String, Map<VariantType, Product>>) {
    /**
     * Prodotto di una variante.
     * @property cardmarketId `idProduct` di Cardmarket.
     * @property blueprintId blueprint CardTrader (la carta nel set "... Poké Ball Reverse Holo"), se noto.
     */
    data class Product(val cardmarketId: Int, val blueprintId: Int?)

    /**
     * Varianti Poké Ball e Master Ball di una carta.
     * @param setId String - id TCGdex del set (es. "SV2a").
     * @param localId String - numero della carta nel set.
     * @return Set<VariantType> - varianti, vuoto se la carta non ne ha.
     */
    fun variantsOf(setId: String, localId: String): Set<VariantType> = products[key(setId, localId)]?.keys.orEmpty()

    /**
     * Prodotto Cardmarket di una variante.
     * @param setId String - id TCGdex del set.
     * @param localId String - numero della carta nel set.
     * @param variant VariantType - [VariantType.POKE_BALL] o [VariantType.MASTER_BALL].
     * @return Int? - `idProduct`, oppure null se la carta non ha quella variante.
     */
    fun productId(setId: String, localId: String, variant: VariantType): Int? = products[key(setId, localId)]?.get(variant)?.cardmarketId

    /**
     * Blueprint CardTrader di una variante, per cercarne gli annunci.
     * @param setId String - id TCGdex del set.
     * @param localId String - numero della carta nel set.
     * @param variant VariantType - [VariantType.POKE_BALL] o [VariantType.MASTER_BALL].
     * @return Int? - id del blueprint, oppure null se la carta non ha quella variante.
     */
    fun blueprintId(setId: String, localId: String, variant: VariantType): Int? = products[key(setId, localId)]?.get(variant)?.blueprintId

    companion object {
        /** Elenco incluso nell'app, letto una sola volta. */
        val bundled: BallVariants by lazy {
            val stream = BallVariants::class.java.getResourceAsStream("cardmarket-ball-variants.tsv")
                ?: return@lazy BallVariants(emptyMap())
            parse(stream.bufferedReader(Charsets.UTF_8).readLines())
        }

        /** Le varianti di una carta si cercano per set (senza maiuscole) e numero (senza zeri iniziali). */
        private fun key(setId: String, localId: String) = "${setId.lowercase()}|${CardTraderMatcher.normalizeNumber(localId)}"

        /**
         * Legge le righe "set TAB numero TAB variante TAB idProdotto TAB idBlueprint" (variante: POKE_BALL o MASTER_BALL; l'ultimo campo è facoltativo).
         * @param lines List<String> - righe del file.
         * @return BallVariants - varianti lette; le righe malformate sono ignorate.
         */
        fun parse(lines: List<String>): BallVariants {
            val products = HashMap<String, MutableMap<VariantType, Product>>()
            for (line in lines) {
                val p = line.split('\t')
                val variant = VariantType.fromName(p.getOrNull(2)?.trim())?.takeIf { it.isBall } ?: continue
                val id = p.getOrNull(3)?.trim()?.toIntOrNull() ?: continue
                if (p[0].isBlank() || p[1].isBlank()) continue
                products.getOrPut(key(p[0], p[1])) { java.util.EnumMap(VariantType::class.java) }[variant] = Product(id, p.getOrNull(4)?.trim()?.toIntOrNull())
            }
            return BallVariants(products)
        }
    }
}
