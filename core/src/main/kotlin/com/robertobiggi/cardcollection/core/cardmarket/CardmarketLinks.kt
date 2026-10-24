package com.robertobiggi.cardcollection.core.cardmarket

import java.net.URLEncoder

/** Indirizzi delle pagine di Cardmarket (aperti nel browser, nessuna chiamata dall'app). */
object CardmarketLinks {
    private const val SEARCH = "https://www.cardmarket.com/it/Pokemon/Products/Search"

    /**
     * Ricerca per nome; limitata al set se si conosce, così trova la carta giusta (o le poche con lo stesso nome nel set)
     * invece di tutte le stampe in tutti i set. Cardmarket non ha un indirizzo fisso per le carte: il nome della pagina
     * contiene il set e il codice stampato come li scrive lui, che non si ricavano dai dati del catalogo.
     * @param name String - nome della carta come lo scrive Cardmarket (inglese), se noto.
     * @param idExpansion Int? - set su Cardmarket (`idExpansion`), se noto.
     * @return String - indirizzo della ricerca.
     */
    fun search(name: String, idExpansion: Int?): String {
        val term = URLEncoder.encode(name, Charsets.UTF_8.name())
        return if (idExpansion == null) "$SEARCH?searchString=$term" else "$SEARCH?idExpansion=$idExpansion&searchString=$term"
    }
}
