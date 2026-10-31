package com.robertobiggi.cardcollection.core.cardmarket

import java.net.URLEncoder

/** Addresses of the Cardmarket pages (opened in the browser, no calls from the app). */
object CardmarketLinks {
    private const val SEARCH = "https://www.cardmarket.com/it/Pokemon/Products/Search"

    /**
     * Search by name; limited to the set if known, so it finds the right card (or the few with the same name in the set)
     * instead of all prints in all sets. Cardmarket has no fixed address for cards: the page name
     * contains the set and the printed code as it writes them, which cannot be derived from the catalog data.
     * @param name String - name of the card as Cardmarket writes it (English), if known.
     * @param idExpansion Int? - set on Cardmarket (`idExpansion`), if known.
     * @return String - address of the search.
     */
    fun search(name: String, idExpansion: Int?): String {
        val term = URLEncoder.encode(name, Charsets.UTF_8.name())
        return if (idExpansion == null) "$SEARCH?searchString=$term" else "$SEARCH?idExpansion=$idExpansion&searchString=$term"
    }
}
