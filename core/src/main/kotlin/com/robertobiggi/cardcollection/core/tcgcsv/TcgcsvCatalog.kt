package com.robertobiggi.cardcollection.core.tcgcsv

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.net.NotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carta da cercare su TCGplayer, con i dati salvati dal catalogo TCGdex.
 *
 * @param language lingua della carta: solo inglese e giapponese hanno una categoria TCGplayer.
 * @param setCode sigla del set (inglese: abbreviazione ufficiale, es. "MEG"; giapponese: id del set, es. "M3").
 * @param setName nome del set secondo TCGdex (usato per i set inglesi con sigla diversa su TCGplayer).
 * @param localId numero della carta nel set (es. "046", "TG17").
 * @param name nome della carta (controllato solo per le carte inglesi, che su TCGplayer hanno lo stesso nome).
 */
data class TcgcsvCardRef(
    val language: Language,
    val setCode: String?,
    val setName: String?,
    val localId: String,
    val name: String,
)

/**
 * Prodotto TCGplayer trovato per una carta.
 *
 * @param categoryId categoria TCGplayer.
 * @param groupId set TCGplayer.
 * @param product prodotto.
 */
data class TcgcsvMatch(val categoryId: Int, val groupId: Long, val product: TcgcsvProductDto)

/**
 * Trova su TCGCSV il prodotto TCGplayer che corrisponde a una carta del catalogo TCGdex, confrontando
 * set e numero stampato. Tiene in memoria set, prodotti e prezzi già scaricati: va creato uno per ogni
 * aggiornamento (prezzi o catalogo), così ogni set viene scaricato una sola volta.
 *
 * @param client client TCGCSV.
 */
class TcgcsvCatalog(private val client: TcgcsvClient) {
    private val mutex = Mutex()
    private val groupsByCategory = HashMap<Int, List<TcgcsvGroupDto>>()
    private val productsByGroup = HashMap<Long, List<TcgcsvProductDto>>()
    private val pricesByGroup = HashMap<Long, List<TcgcsvPriceDto>>()

    /**
     * Cerca il prodotto TCGplayer di una carta.
     * @param card TcgcsvCardRef - carta da cercare.
     * @return TcgcsvMatch? - prodotto trovato, oppure null se la lingua non è coperta, il set non
     *         esiste su TCGplayer o il numero è ambiguo.
     * @throws com.robertobiggi.cardcollection.core.net.ApiException in caso di errore HTTP.
     */
    suspend fun find(card: TcgcsvCardRef): TcgcsvMatch? = mutex.withLock {
        val category = categoryOf(card.language) ?: return@withLock null
        val groups = groupsByCategory.getOrPut(category) { client.groups(category) }
        for (group in matchingGroups(groups, card)) {
            val products = productsByGroup.getOrPut(group.groupId) { fetchOrEmpty { client.products(category, group.groupId) } }
            pickProduct(products, card)?.let { return@withLock TcgcsvMatch(category, group.groupId, it) }
        }
        null
    }

    /**
     * Prezzi del prodotto trovato, una riga per variante TCGplayer.
     * @param match TcgcsvMatch - prodotto restituito da [find].
     * @return List<TcgcsvPriceDto> - righe di prezzo del prodotto (vuota se non ce ne sono).
     * @throws com.robertobiggi.cardcollection.core.net.ApiException in caso di errore HTTP.
     */
    suspend fun prices(match: TcgcsvMatch): List<TcgcsvPriceDto> = mutex.withLock {
        pricesByGroup.getOrPut(match.groupId) { fetchOrEmpty { client.prices(match.categoryId, match.groupId) } }
            .filter { it.productId == match.product.productId }
    }

    /**
     * Esegue una richiesta trattando il 404 (set senza prodotti o prezzi) come elenco vuoto.
     * @param block suspend () -> List<T> - richiesta.
     * @return List<T> - risultato, oppure lista vuota se la risorsa non esiste.
     */
    private suspend fun <T> fetchOrEmpty(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: NotFoundException) {
        emptyList()
    }

    companion object {
        private val GROUP_PREFIX = Regex("^[A-Za-z0-9]{1,8}(?::\\s+|\\s+-\\s+)")
        private val NUMBER_SUFFIX = Regex("\\s+-\\s+[A-Za-z0-9]+/[A-Za-z0-9]+$")
        private val PARENTHESES = Regex("\\(.*?\\)")
        private const val MIN_SUFFIX_NAME = 6

        /**
         * Categoria TCGplayer di una lingua.
         * @param language Language - lingua della carta.
         * @return Int? - categoria, oppure null per le lingue che TCGplayer non vende.
         */
        fun categoryOf(language: Language): Int? = when (language) {
            Language.EN -> TcgcsvClient.CATEGORY_POKEMON
            Language.JA -> TcgcsvClient.CATEGORY_POKEMON_JAPAN
            else -> null
        }

        /**
         * URL dell'immagine di un prodotto sul CDN di TCGplayer.
         * @param productId Long - prodotto.
         * @param high Boolean - true per la versione grande (1000 px), false per quella piccola (400 px).
         * @return String - URL HTTPS dell'immagine JPEG.
         */
        fun imageUrl(productId: Long, high: Boolean): String =
            "https://tcgplayer-cdn.tcgplayer.com/product/$productId" + if (high) "_in_1000x1000.jpg" else "_400w.jpg"

        /**
         * Sceglie i set TCGplayer della carta: prima per sigla, poi (solo per le carte inglesi) per nome,
         * togliendo il prefisso che TCGplayer mette davanti al nome (es. "SWSH11: Lost Origin").
         * @param groups List<TcgcsvGroupDto> - set della categoria.
         * @param card TcgcsvCardRef - carta da cercare.
         * @return List<TcgcsvGroupDto> - set candidati, i più probabili per primi.
         */
        fun matchingGroups(groups: List<TcgcsvGroupDto>, card: TcgcsvCardRef): List<TcgcsvGroupDto> {
            val code = card.setCode?.trim().orEmpty()
            val byCode = if (code.isEmpty()) emptyList() else groups.filter { it.abbreviation?.trim().equals(code, ignoreCase = true) }
            if (byCode.isNotEmpty() || card.language != Language.EN) return byCode
            val setName = PokemonNames.normalize(card.setName.orEmpty())
            if (setName.isEmpty()) return emptyList()
            val exact = groups.filter { PokemonNames.normalize(it.name.replace(GROUP_PREFIX, "")) == setName }
            if (exact.isNotEmpty()) return exact
            if (setName.length < MIN_SUFFIX_NAME) return emptyList()
            return groups.filter { PokemonNames.normalize(it.name).endsWith(setName) }
        }

        /**
         * Sceglie il prodotto con il numero della carta. Se più prodotti hanno lo stesso numero (es. le
         * versioni "Poke Ball Pattern") preferisce quello senza indicazioni tra parentesi.
         * @param products List<TcgcsvProductDto> - prodotti del set.
         * @param card TcgcsvCardRef - carta da cercare.
         * @return TcgcsvProductDto? - prodotto, oppure null se manca o resta ambiguo.
         */
        fun pickProduct(products: List<TcgcsvProductDto>, card: TcgcsvCardRef): TcgcsvProductDto? {
            var candidates = products.filter { p -> p.number()?.let { matchesNumber(it, card.localId) } == true }
            if (card.language == Language.EN) {
                val name = PokemonNames.normalize(card.name)
                candidates = candidates.filter { p ->
                    val productName = PokemonNames.normalize(baseName(p.name))
                    name.isNotEmpty() && productName.isNotEmpty() && (productName.contains(name) || name.contains(productName))
                }
            }
            if (candidates.size > 1) candidates = candidates.filterNot { it.name.contains('(') }
            return candidates.singleOrNull()
        }

        /**
         * Confronta il numero di un prodotto ("046/132", "TG17/TG30") con quello della carta ("46", "TG17"),
         * ignorando maiuscole e zeri iniziali.
         * @param productNumber String - numero TCGplayer.
         * @param localId String - numero TCGdex.
         * @return Boolean - true se indicano la stessa carta.
         */
        fun matchesNumber(productNumber: String, localId: String): Boolean {
            val a = productNumber.substringBefore('/').trim()
            val b = localId.trim()
            if (a.isEmpty() || b.isEmpty()) return false
            if (a.all { it.isDigit() } && b.all { it.isDigit() }) return a.trimStart('0') == b.trimStart('0')
            return a.equals(b, ignoreCase = true)
        }

        /**
         * Nome della carta senza numero finale né indicazioni tra parentesi.
         * @param productName String - nome TCGplayer (es. "Mega Zygarde ex - 117/080").
         * @return String - nome ripulito (es. "Mega Zygarde ex").
         */
        fun baseName(productName: String): String = productName.replace(NUMBER_SUFFIX, "").replace(PARENTHESES, "").trim()
    }
}
