package com.robertobiggi.cardcollection.core.cardtrader

/** Addresses of the CardTrader pages (opened in the browser, no calls from the app). */
object CardTraderLinks {
    private const val SITE = "https://www.cardtrader.com/it-IT"

    /**
     * Page of a card. CardTrader recognizes the card by the blueprint number; the rest of the address is the name, only for readability.
     * @param blueprintId Int - id of the blueprint.
     * @param name String - name of the card.
     * @return String - address of the page.
     */
    fun card(blueprintId: Int, name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (slug.isEmpty()) "$SITE/cards/$blueprintId" else "$SITE/cards/$blueprintId-$slug"
    }

    /**
     * Search by name, for cards whose blueprint is unknown.
     * @param encodedName String - name already encoded for an address.
     * @return String - address of the search.
     */
    fun search(encodedName: String): String = "https://www.cardtrader.com/search?query=$encodedName"
}
