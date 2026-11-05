package com.robertobiggi.cardcollection.core.model

/** Rules of the update of a collection from the card database installed on the phone. */
object CatalogUpdate {

    /**
     * True if a collection must be updated from scratch: the card database installed now is not the one it was last updated from.
     * It also happens after an app update that brings a newer database, or after downloading one by hand. A collection never updated
     * (null version) needs it too; without an installed database there is nothing to update from.
     * @param collectionVersion String? - `generatedAt` of the database at the collection's last update, null if never.
     * @param catalogVersion String? - `generatedAt` of the installed database, null if none.
     * @return Boolean - true if the collection must be updated.
     */
    fun needsUpdate(collectionVersion: String?, catalogVersion: String?): Boolean = catalogVersion != null && collectionVersion != catalogVersion

    /**
     * True if the images downloaded for a card are no longer the ones the database points to, so they must be downloaded again.
     * With nothing recorded ([stored] null: updates before this rule) the images already downloaded stay.
     * @param stored String? - image sources recorded at the last update.
     * @param current String - image sources in the installed database.
     * @return Boolean - true if the downloaded images must be dropped.
     */
    fun imagesChanged(stored: String?, current: String): Boolean = stored != null && stored != current

    /**
     * Text that identifies where a card's images come from: the TCGdex address and the path on each site of the database, in a fixed order.
     * @param tcgdexUrl String? - TCGdex address of the image, if any.
     * @param sitePaths List<String?> - paths of the other sites, one per site, in a fixed order.
     * @return String - key to store and compare.
     */
    fun imageKey(tcgdexUrl: String?, sitePaths: List<String?>): String = (listOf(tcgdexUrl) + sitePaths).joinToString("|") { it.orEmpty() }
}
