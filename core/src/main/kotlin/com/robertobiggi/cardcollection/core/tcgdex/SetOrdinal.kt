package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.model.Language

/**
 * Posizione di una carta senza numero del Pokédex (Allenatore, Energia) tra le carte Pokémon del suo set.
 *
 * @property leftDex Pokédex della carta Pokémon più vicina prima della carta, null se la carta è all'inizio del set.
 * @property rightDex Pokédex della carta Pokémon più vicina dopo la carta, null se è in fondo al set.
 * @property rank posizione della carta (da 0) tra le carte senza Pokédex comprese tra le due.
 * @property count quante carte senza Pokédex ci sono tra le due.
 */
data class SetOrdinal(val leftDex: Int?, val rightDex: Int?, val rank: Int, val count: Int)

/**
 * Calcola la [SetOrdinal] di una carta dal catalogo TCGdex. Ricorda set e Pokédex già scaricati:
 * crea una nuova istanza per ogni sessione, non per ogni carta.
 *
 * @param client client TCGdex.
 */
class SetOrdinalFinder(private val client: TcgdexClient) {
    private val sets = HashMap<Pair<Language, String>, List<CardBriefDto>>()
    private val dexIds = HashMap<Pair<Language, String>, Int?>()

    /**
     * Trova le carte Pokémon più vicine alla carta, prima e dopo, e la sua posizione tra le due.
     * @param language Language - lingua del catalogo.
     * @param setId String - set della carta.
     * @param localId String - numero della carta nel set.
     * @return SetOrdinal? - posizione, oppure null se la carta non è nel set o non ci sono carte Pokémon vicine.
     */
    suspend fun of(language: Language, setId: String, localId: String): SetOrdinal? {
        val cards = sets[language to setId] ?: client.getSet(language, setId).cards.also { sets[language to setId] = it }
        val pos = cards.indexOfFirst { it.localId == localId }.takeIf { it >= 0 } ?: return null

        var left = -1
        var leftDex: Int? = null
        for (i in pos - 1 downTo maxOf(0, pos - MAX_STEPS)) {
            val dex = dexAt(language, cards[i].id) ?: continue
            left = i
            leftDex = dex
            break
        }
        var right = cards.size
        var rightDex: Int? = null
        for (i in pos + 1 until minOf(cards.size, pos + 1 + MAX_STEPS)) {
            val dex = dexAt(language, cards[i].id) ?: continue
            right = i
            rightDex = dex
            break
        }
        if (leftDex == null && rightDex == null) return null
        return SetOrdinal(leftDex, rightDex, rank = pos - left - 1, count = right - left - 1)
    }

    private suspend fun dexAt(language: Language, cardId: String): Int? {
        val key = language to cardId
        if (key in dexIds) return dexIds[key]
        return client.getCard(language, cardId).dexId?.firstOrNull().also { dexIds[key] = it }
    }

    private companion object {
        /** Quante carte vicine si scaricano al massimo per cercare ogni carta Pokémon di riferimento (nei set vecchi ci sono file di 30 Allenatori di seguito). */
        const val MAX_STEPS = 40
    }
}
