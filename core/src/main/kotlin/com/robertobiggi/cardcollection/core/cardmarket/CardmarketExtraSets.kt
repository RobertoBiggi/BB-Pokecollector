package com.robertobiggi.cardcollection.core.cardmarket

/**
 * Set giapponesi che CardTrader e Cardmarket hanno e TCGdex no (serie BW, DP e Platino, promo, mazzi). L'elenco è incluso
 * nell'app (`cardmarket-extra-sets.tsv`) e lo genera `tools/gen_extra_sets.py` incrociando CardTrader con TCGdex: le carte
 * hanno il numero stampato di CardTrader e il prodotto Cardmarket, quindi si cercano per nome come quelle di TCGdex.
 *
 * @property sets set mancanti.
 */
class CardmarketExtraSets(val sets: List<ExtraSet>) {
    /**
     * Un set che TCGdex non elenca.
     * @property setId id con cui la carta è salvata (es. "Pt4").
     * @property setCode sigla stampata, se diversa dall'id.
     * @property name nome del set.
     * @property cardmarketId `idExpansion` di Cardmarket.
     * @property whole true se l'espansione Cardmarket è solo di questo set: allora anche i prodotti senza numero (non collegati a CardTrader) ne fanno parte.
     * @property numbers numero stampato per id prodotto Cardmarket; vuoto se CardTrader non lo ha (promo senza numero).
     */
    class ExtraSet(
        val setId: String,
        val setCode: String?,
        val name: String,
        val cardmarketId: Int,
        val whole: Boolean,
        val numbers: Map<Int, String>,
    )

    companion object {
        /** Elenco incluso nell'app, letto una sola volta. */
        val bundled: CardmarketExtraSets by lazy {
            val stream = CardmarketExtraSets::class.java.getResourceAsStream("cardmarket-extra-sets.tsv")
                ?: return@lazy CardmarketExtraSets(emptyList())
            parse(stream.bufferedReader(Charsets.UTF_8).readLines())
        }

        /**
         * Legge le righe "S TAB lingua TAB id TAB sigla TAB nome TAB idExpansion TAB intero" (set) e
         * "C TAB lingua TAB id TAB numero TAB idProdotto" (carta). Solo giapponese; le righe malformate sono ignorate.
         * @param lines List<String> - righe del file.
         * @return CardmarketExtraSets - set letti.
         */
        fun parse(lines: List<String>): CardmarketExtraSets {
            val numbers = HashMap<String, MutableMap<Int, String>>()
            for (line in lines) {
                val p = line.split('\t')
                if (p.getOrNull(0) != "C" || p.getOrNull(1) != "ja") continue
                val id = p.getOrNull(4)?.trim()?.toIntOrNull() ?: continue
                numbers.getOrPut(p[2]) { HashMap() }[id] = p[3].trim()
            }
            val sets = ArrayList<ExtraSet>()
            for (line in lines) {
                val p = line.split('\t')
                if (p.getOrNull(0) != "S" || p.getOrNull(1) != "ja") continue
                val cardmarketId = p.getOrNull(5)?.trim()?.toIntOrNull() ?: continue
                sets += ExtraSet(p[2], p[3].ifEmpty { null }, p[4], cardmarketId, p.getOrNull(6)?.trim() == "1", numbers[p[2]].orEmpty())
            }
            return CardmarketExtraSets(sets)
        }
    }
}
