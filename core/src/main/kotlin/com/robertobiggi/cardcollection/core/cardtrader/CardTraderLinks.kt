package com.robertobiggi.cardcollection.core.cardtrader

/** Indirizzi delle pagine di CardTrader (aperti nel browser, nessuna chiamata dall'app). */
object CardTraderLinks {
    private const val SITE = "https://www.cardtrader.com/it-IT"

    /**
     * Pagina di una carta. CardTrader riconosce la carta dal numero del blueprint; il resto dell'indirizzo è il nome, solo per leggibilità.
     * @param blueprintId Int - id del blueprint.
     * @param name String - nome della carta.
     * @return String - indirizzo della pagina.
     */
    fun card(blueprintId: Int, name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (slug.isEmpty()) "$SITE/cards/$blueprintId" else "$SITE/cards/$blueprintId-$slug"
    }

    /**
     * Ricerca per nome, per le carte di cui non si conosce il blueprint.
     * @param encodedName String - nome già codificato per un indirizzo.
     * @return String - indirizzo della ricerca.
     */
    fun search(encodedName: String): String = "https://www.cardtrader.com/search?query=$encodedName"
}
