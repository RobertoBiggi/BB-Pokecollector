package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.model.Language

/**
 * Completa le carte "scheletro": quelle senza dettaglio TCGdex (importate da un file o rimaste nel
 * database quando TCGdex ha tolto il set), che non hanno illustratore, rarità, data né URL immagine TCGdex
 * (possono avere un'immagine importata dal file, che non conta).
 */
object CardRepair {
    /** Massimo di carte inglesi con lo stesso nome di cui si scarica il dettaglio. */
    private const val MAX_CANDIDATES = 6

    /**
     * Dice se una carta è uno scheletro.
     * @param rarity String? - rarità salvata.
     * @param illustrator String? - illustratore salvato.
     * @param releaseDate String? - data di uscita salvata.
     * @param hasTcgdexImage Boolean - true se la carta ha l'URL immagine di TCGdex.
     * @return Boolean - true se manca tutto ciò che viene dal dettaglio TCGdex.
     */
    fun isStub(rarity: String?, illustrator: String?, releaseDate: String?, hasTcgdexImage: Boolean): Boolean =
        rarity == null && illustrator == null && releaseDate == null && !hasTcgdexImage

    /**
     * Cerca l'illustratore di una carta nel catalogo inglese. Le carte giapponesi di set che TCGdex non
     * ha sono quasi sempre ristampe di carte inglesi con la stessa illustrazione (es. "Omastar BREAK").
     * Vale solo se ogni carta inglese con lo stesso nome e lo stesso numero del Pokédex ha lo stesso
     * illustratore: con più illustratori diversi la carta giapponese è ambigua e non si indovina.
     * @param client TcgdexClient - client TCGdex.
     * @param name String - nome della carta scheletro.
     * @param dexId Int? - numero del Pokédex nazionale, obbligatorio (le carte senza non si abbinano).
     * @return String? - illustratore, oppure null se non trovato o ambiguo.
     */
    suspend fun illustratorFromEnglish(client: TcgdexClient, name: String, dexId: Int?): String? {
        if (dexId == null || name.isBlank()) return null
        val wanted = key(name)
        val details = client.searchCards(Language.EN, name.trim())
            .filter { key(it.name) == wanted }
            .take(MAX_CANDIDATES)
            .map { client.getCard(Language.EN, it.id) }
            .filter { dexId in it.dexId.orEmpty() }
        val illustrators = details.map { it.illustrator?.trim().orEmpty() }.toSet()
        return illustrators.singleOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
}
