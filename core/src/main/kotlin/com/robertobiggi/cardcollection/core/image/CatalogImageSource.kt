package com.robertobiggi.cardcollection.core.image

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An image source whose addresses are in the card database: a nullable column of `card` holds, for each card, the path of its image on the
 * source's site (the generator, Card-collector-db-gen, finds it by matching the card's set and number). The address is rebuilt as
 * [baseUrl] + path + [extension], unless the path already ends with an image extension (e.g. "2026/03/SC_242SV-P.png"): then it is used as is, for the
 * sources whose images are not all of one type.
 *
 * To add a source: a new constant in [CatalogImageSources] (and the matching class in the generator's `image_sources.py`).
 * Its host is allowed to the app's HTTP client automatically, through [CatalogImageSources.hosts].
 *
 * @param id String - short name, stable (it identifies the source in logs and messages).
 * @param column String - column of the `card` table with the paths (lowercase letters only: it goes into a query).
 * @param baseUrl String - HTTPS address that precedes the path, ending with "/".
 * @param extension String - what follows the path, with the dot (e.g. ".png").
 * @param displayName String - name shown in the app next to the image (a proper noun, not translated).
 */
data class CatalogImageSource(val id: String, val column: String, val baseUrl: String, val extension: String, val displayName: String) {

    init {
        require(column.matches(COLUMN)) { "Colonna non valida: $column" }
        require(baseUrl.startsWith("https://") && baseUrl.endsWith("/")) { "Indirizzo non valido: $baseUrl" }
    }

    /** Host of the images; the only one [isAllowedUrl] accepts. */
    val host: String = baseUrl.toHttpUrl().host

    /**
     * Address of the image of a card.
     * @param path String - path saved in the card database.
     * @return String? - full address, or null if the path has an unexpected shape (it would let an address leave the source's folder).
     */
    fun urlOf(path: String): String? =
        if (path.length <= MAX_PATH && PATH.matches(path) && ".." !in path) baseUrl + path + (if (OWN_EXTENSION.containsMatchIn(path)) "" else extension) else null

    /**
     * Checks an address before downloading it.
     * @param url String - full address.
     * @return Boolean - true if it is HTTPS and on the source's host.
     */
    fun isAllowedUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host == host
    }

    private companion object {
        val COLUMN = Regex("[a-z]{1,30}")
        val PATH = Regex("[A-Za-z0-9._+%-]+(/[A-Za-z0-9._+%-]+)*")
        val OWN_EXTENSION = Regex("\\.(png|webp|jpe?g)$", RegexOption.IGNORE_CASE)
        const val MAX_PATH = 200
    }
}

/** The image sources whose paths are in the card database. */
object CatalogImageSources {
    /** jp.pokellector.com: most Japanese sets, full-size PNG. */
    val POKELLECTOR = CatalogImageSource("pokellector", "pokellector", "https://den-cards.pokellector.com/", ".png", "Pokellector")

    /** billsarchive.com: older Japanese sets (Neo, PMCG, e-Card, PCG), WebP. */
    val BILLS_ARCHIVE = CatalogImageSource(
        "billsarchive", "billsarchive", "https://bills-archive.nyc3.cdn.digitaloceanspaces.com/tcgdex_cards_jp/", ".webp", "Bill's Archive",
    )

    /** pokoin.com: English and Japanese sets, 480 px wide WebP (the generator saves only cards it matched with no doubt). */
    val POKOIN = CatalogImageSource("pokoin", "pokoin", "https://cdn.pokoin.com/", ".webp", "Pokoin")

    /**
     * pokumon.com: promo cards, including the old unnumbered ones no other source has; JPEG, about 730 px wide (the generator saves only cards it
     * matched with no doubt, from the file built by its `pokumon.py`). The image lives on the site's CDN.
     */
    val POKUMON = CatalogImageSource("pokumon", "pokumon", "https://cdn6966.templcdn.com/wp-content/uploads/", ".jpg", "Pokumon")

    /** Sources tried right after TCGdex and before CardTrader (which needs a token and an API call per card). */
    val BEFORE_CARDTRADER: List<CatalogImageSource> = listOf(POKOIN)

    /** Sources tried after CardTrader. */
    val AFTER_CARDTRADER: List<CatalogImageSource> = listOf(POKELLECTOR, BILLS_ARCHIVE, POKUMON)

    /** Every source, in the order they are tried (the first with an image that downloads wins). */
    val ALL: List<CatalogImageSource> = BEFORE_CARDTRADER + AFTER_CARDTRADER

    /** Hosts the app may reach to download images from these sources. */
    val hosts: Set<String> = ALL.map { it.host }.toSet()
}
