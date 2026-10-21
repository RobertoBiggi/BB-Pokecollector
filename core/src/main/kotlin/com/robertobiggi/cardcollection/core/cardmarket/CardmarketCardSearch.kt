package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeQuery
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.SetBriefDto
import com.robertobiggi.cardcollection.core.tcgdex.SetDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * Cerca nel catalogo Cardmarket le carte che TCGdex non ha. Vale solo per i set che TCGdex elenca ma di cui non
 * ha quella carta (es. i set giapponesi XY10 o WCP, presenti senza carte): per ognuno si abbina il set Cardmarket,
 * e la posizione di una carta nel set, che coincide con il numero stampato, dà il numero. Le carte escono con la
 * forma di quelle TCGdex (id "XY10-017"), così se TCGdex le aggiunge in seguito i dati si sovrappongono invece di duplicarsi.
 * Mancano immagine, rarità e illustratore. Solo inglese e giapponese: negli altri cataloghi un set con la stessa sigla
 * avrebbe i prezzi della versione sbagliata.
 *
 * @param catalog catalogo Cardmarket.
 * @param tcgdex client TCGdex, per l'elenco e il dettaglio dei set.
 * @param expansions set con il loro id Cardmarket.
 */
class CardmarketCardSearch(
    private val catalog: CardmarketCatalog,
    private val tcgdex: TcgdexClient,
    private val expansions: CardmarketExpansions = CardmarketExpansions.bundled,
) {
    private val setLists = HashMap<Language, List<SetBriefDto>>()
    private val setDetails = HashMap<Pair<Language, String>, SetDto>()
    private val cardmarketIds = HashMap<Pair<Language, String>, Int?>()

    /**
     * Cerca per nome le carte che TCGdex non ha.
     * @param language Language - lingua del catalogo.
     * @param pattern String - testo cercato nel nome (come per TCGdex, contenuto nel nome).
     * @param onDownload suspend () -> Unit - chiamata prima di scaricare il catalogo.
     * @param onRead suspend () -> Unit - chiamata prima di leggere il catalogo.
     * @return List<CatalogCard> - carte trovate, senza immagine.
     */
    suspend fun find(language: Language, pattern: String, onDownload: suspend () -> Unit = {}, onRead: suspend () -> Unit = {}): List<CatalogCard> {
        if (!supports(language)) return emptyList()
        val key = CardmarketCatalog.nameKey(pattern)
        if (key.length < MIN_KEY_LENGTH) return emptyList()
        val found = ArrayList<CatalogCard>()
        for (set in sets(language)) {
            val products = productsOf(language, set, onDownload, onRead) ?: continue
            val hits = products.withIndex().filter { key in it.value.nameKey }
            if (hits.isEmpty()) continue
            val detail = detail(language, set.id)
            val have = numbersIn(detail)
            for ((index, product) in hits) {
                if (index + 1 !in have) card(language, set, detail, product, index + 1)?.let { found += it }
            }
        }
        return found
    }

    /**
     * Cerca per codice stampato (es. "XY10 017") nei set di cui una parola è l'id.
     * @param language Language - lingua del catalogo.
     * @param query CardCodeQuery - codice già scomposto.
     * @return List<CatalogCard> - carte trovate, senza immagine.
     */
    suspend fun findByCode(language: Language, query: CardCodeQuery): List<CatalogCard> {
        if (!supports(language)) return emptyList()
        return sets(language).filter { set -> query.tokens.any { it.equals(set.id, ignoreCase = true) } }
            .take(MAX_CODE_SETS).mapNotNull { cardFor(language, it.id, query.number.toString()) }
    }

    /**
     * Ricostruisce una carta dal suo id, per aggiungerla a mano.
     * @param language Language - lingua del catalogo.
     * @param setId String - id TCGdex del set.
     * @param localId String - numero della carta nel set.
     * @return CatalogCard? - la carta, oppure null se il set non è abbinabile, il numero non esiste o TCGdex ha già quella carta.
     */
    suspend fun cardFor(language: Language, setId: String, localId: String): CatalogCard? {
        if (!supports(language)) return null
        val number = localId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val set = sets(language).firstOrNull { it.id == setId } ?: return null
        val product = productsOf(language, set)?.getOrNull(number - 1) ?: return null
        val detail = detail(language, set.id)
        if (number in numbersIn(detail)) return null
        return card(language, set, detail, product, number)
    }

    private fun supports(language: Language) = language == Language.EN || language == Language.JA

    private suspend fun sets(language: Language): List<SetBriefDto> =
        setLists[language] ?: tcgdex.listSets(language).also { setLists[language] = it }

    private suspend fun detail(language: Language, setId: String): SetDto =
        setDetails[language to setId] ?: tcgdex.getSet(language, setId).also { setDetails[language to setId] = it }

    /**
     * Carte del set Cardmarket corrispondente, nell'ordine del set. Un set non abbinabile, senza numero ufficiale di
     * carte o con meno carte del numero ufficiale non viene usato: la posizione non sarebbe il numero.
     */
    private suspend fun productsOf(language: Language, set: SetBriefDto, onDownload: suspend () -> Unit = {}, onRead: suspend () -> Unit = {}): List<CatalogProduct>? {
        val official = set.cardCount?.official?.takeIf { it > 0 } ?: return null
        val id = cardmarketIds.getOrPut(language to set.id) {
            CardTraderMatcher.expansionFor(expansions.expansions, set.name, set.id, null, preferCode = language.asianSets)
                ?.let { expansions.cardmarketId(it.id) }
        } ?: return null
        return catalog.productsOf(id, onDownload, onRead).takeIf { it.size >= official }
    }

    private fun numbersIn(detail: SetDto): Set<Int> = detail.cards.mapNotNull { it.localId.toIntOrNull() }.toSet()

    private fun card(language: Language, set: SetBriefDto, detail: SetDto, product: CatalogProduct, number: Int): CatalogCard? {
        val width = detail.cards.map { it.localId }.filter { it.all(Char::isDigit) }.maxOfOrNull { it.length } ?: if (language.asianSets) 3 else 1
        val localId = number.toString().padStart(width, '0')
        val tcgdexId = "${set.id}-$localId"
        if (!Validators.isValidRemoteId(tcgdexId)) return null
        return CatalogCard(
            id = CatalogMapper.localCardId(language, tcgdexId),
            language = language,
            tcgdexId = tcgdexId,
            name = product.name.take(MAX_NAME),
            setId = set.id,
            setName = detail.name.take(MAX_NAME),
            setCode = CatalogMapper.printedSetCode(language, detail, set.id),
            serieId = detail.serie?.id,
            serieName = detail.serie?.name,
            localId = localId,
            printedNumber = CatalogMapper.printedNumber(localId, detail.cardCount?.official),
            rarity = null,
            releaseDate = detail.releaseDate,
            illustrator = null,
            imageBaseUrl = null,
            variants = listOf(CatalogVariant(VariantType.NORMAL)),
            dexId = PokemonNames.dexIdOfName(product.name),
        )
    }

    private companion object {
        const val MIN_KEY_LENGTH = 3
        const val MAX_CODE_SETS = 3
        const val MAX_NAME = 120
    }
}
