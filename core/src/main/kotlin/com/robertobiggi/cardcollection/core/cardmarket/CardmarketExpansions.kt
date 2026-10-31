package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto

/**
 * Pokémon sets with their Cardmarket id (`idExpansion`), to find a card in the Cardmarket catalog without
 * going through CardTrader. The list is included in the app (`cardmarket-expansions.tsv`) and comes from the CardTrader blueprints,
 * which give the Cardmarket product for each card: new sets only appear there after an app update.
 *
 * @property expansions sets in the form used by the CardTrader matcher (id, code, name).
 * @property cardmarketIdOf Cardmarket id of the set, by set id in the list.
 */
class CardmarketExpansions(val expansions: List<CtExpansionDto>, private val cardmarketIdOf: Map<Int, Int>) {
    /**
     * Cardmarket id of a set in the list.
     * @param expansionId Int - id of the set in the list.
     * @return Int? - Cardmarket `idExpansion`, or null if unknown.
     */
    fun cardmarketId(expansionId: Int): Int? = cardmarketIdOf[expansionId]

    companion object {
        /** List included in the app, read only once. */
        val bundled: CardmarketExpansions by lazy {
            val stream = CardmarketExpansions::class.java.getResourceAsStream("cardmarket-expansions.tsv")
                ?: return@lazy CardmarketExpansions(emptyList(), emptyMap())
            parse(stream.bufferedReader(Charsets.UTF_8).readLines())
        }

        /**
         * Reads the lines "id TAB code TAB name TAB idExpansion".
         * @param lines List<String> - lines of the file.
         * @return CardmarketExpansions - sets read; malformed lines are ignored.
         */
        fun parse(lines: List<String>): CardmarketExpansions {
            val expansions = ArrayList<CtExpansionDto>()
            val ids = HashMap<Int, Int>()
            for (line in lines) {
                val p = line.split('\t')
                val id = p.getOrNull(0)?.toIntOrNull() ?: continue
                val cardmarketId = p.getOrNull(3)?.trim()?.toIntOrNull() ?: continue
                expansions += CtExpansionDto(id, null, p[1], p[2])
                ids[id] = cardmarketId
            }
            return CardmarketExpansions(expansions, ids)
        }
    }
}
