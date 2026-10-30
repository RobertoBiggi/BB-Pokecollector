package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.model.CatalogCard

/** Ritrova nel database delle carte una carta salvata con un id che il database non usa più (le carte Cardmarket/CardTrader avevano altri id). */
object CardIdRemap {
    /**
     * Sceglie la carta del database che corrisponde a quella salvata, solo se la scelta è sicura.
     * @param localId String - numero locale della carta salvata.
     * @param printedNumber String - numero stampato della carta salvato.
     * @param byProduct List<CatalogCard> - carte del database con lo stesso prodotto Cardmarket.
     * @param sameName List<CatalogCard> - carte del database con lo stesso nome.
     * @return CatalogCard? - la carta, oppure null se nessuna o più d'una corrisponde.
     */
    fun pick(localId: String, printedNumber: String, byProduct: List<CatalogCard>, sameName: List<CatalogCard>): CatalogCard? {
        if (byProduct.size == 1) return byProduct.single()
        if (byProduct.size > 1) return byProduct.filter { it.localId == localId }.singleOrNull()
        if (printedNumber.isEmpty()) return null
        return sameName.filter { it.printedNumber == printedNumber }.singleOrNull()
    }
}
