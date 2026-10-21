package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CtExpansionDto

/**
 * Set Pokémon con il loro id Cardmarket (`idExpansion`), per trovare una carta nel catalogo Cardmarket senza
 * passare da CardTrader. L'elenco è incluso nell'app (`cardmarket-expansions.tsv`) e nasce dai blueprint CardTrader,
 * che indicano per ogni carta il prodotto Cardmarket: i set nuovi vi compaiono solo dopo un aggiornamento dell'app.
 *
 * @property expansions set nella forma usata dall'abbinatore CardTrader (id, sigla, nome).
 * @property cardmarketIdOf id Cardmarket del set, per id del set nell'elenco.
 */
class CardmarketExpansions(val expansions: List<CtExpansionDto>, private val cardmarketIdOf: Map<Int, Int>) {
    /**
     * Id Cardmarket di un set dell'elenco.
     * @param expansionId Int - id del set nell'elenco.
     * @return Int? - `idExpansion` di Cardmarket, oppure null se sconosciuto.
     */
    fun cardmarketId(expansionId: Int): Int? = cardmarketIdOf[expansionId]

    companion object {
        /** Elenco incluso nell'app, letto una sola volta. */
        val bundled: CardmarketExpansions by lazy {
            val stream = CardmarketExpansions::class.java.getResourceAsStream("cardmarket-expansions.tsv")
                ?: return@lazy CardmarketExpansions(emptyList(), emptyMap())
            parse(stream.bufferedReader(Charsets.UTF_8).readLines())
        }

        /**
         * Legge le righe "id TAB sigla TAB nome TAB idExpansion".
         * @param lines List<String> - righe del file.
         * @return CardmarketExpansions - set letti; le righe malformate sono ignorate.
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
