package com.robertobiggi.cardcollection.core.cardmarket

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderMatcher
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.price.CardmarketIdSource
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.tcgdex.SetOrdinal
import kotlinx.coroutines.CancellationException

/**
 * Trova il prodotto Cardmarket di una carta dal catalogo pubblico, senza CardTrader: abbina il set tramite
 * l'elenco incluso nell'app, poi la carta per nome (o per Pokédex). Se nello stesso set ci sono più carte con
 * quel nome, vale quella che sta nella posizione indicata dal numero della carta; se resta il dubbio non risponde.
 *
 * @param catalog catalogo Cardmarket.
 * @param expansions set con il loro id Cardmarket.
 */
class CardmarketCatalogIdSource(
    private val catalog: CardmarketCatalog,
    private val expansions: CardmarketExpansions = CardmarketExpansions.bundled,
) : CardmarketIdSource {

    /**
     * Cerca l'id prodotto Cardmarket della carta.
     * @param request PriceRequest - carta da cercare (set, numero, nome e Pokédex).
     * @return Int? - l'id, oppure null se il set o la carta non si trovano o l'abbinamento non è sicuro.
     */
    override suspend fun cardmarketProductId(request: PriceRequest): Int? {
        val expansion = CardTraderMatcher.expansionFor(
            expansions.expansions, request.setName, request.setId, request.setCode,
            preferCode = request.language == Language.JA,
        ) ?: return null
        val products = catalog.productsOf(expansions.cardmarketId(expansion.id) ?: return null)
        // I nomi del Pokémon nelle varie lingue prendono i marcatori della carta (☆, δ, ◇): "Rayquaza ☆" non deve
        // abbinarsi al Rayquaza normale dello stesso set, né il Rayquaza normale a "Rayquaza Gold Star".
        val marks = CardmarketCatalog.markers(request.name)
        val keys = buildSet {
            request.dexId?.let { dex -> PokemonNames.namesOf(dex).forEach { add(CardmarketCatalog.nameKey(it, marks)) } }
            if (request.name.isNotBlank()) add(CardmarketCatalog.nameKey(request.name))
        }
        // Il nome completo della carta vale più dei nomi del Pokémon: "Rayquaza ex" non è il Rayquaza normale dello stesso set.
        val fullName = request.name.takeIf { it.isNotBlank() }?.let { CardmarketCatalog.nameKey(it) }
        val exact = products.filter { it.nameKey == fullName }.ifEmpty { products.filter { it.nameKey in keys } }
        // Carte il cui nome Cardmarket aggiunge qualcosa ("Brock's Omastar"): vale se nel set ce n'è una sola, con gli stessi marcatori.
        val candidates = exact.ifEmpty {
            products.filter { p -> keys.any { CardmarketCatalog.splitKey(it).first.length >= MIN_CONTAINED_KEY && CardmarketCatalog.containsKey(p.nameKey, it) } }
        }
        // Allenatori ed Energie giapponesi (es. "なにかの化石"): il nome non coincide con quello inglese di Cardmarket, si cerca la posizione tra due Pokémon.
        if (candidates.isEmpty() && request.dexId == null) {
            val ordinal = request.ordinal?.invoke() ?: return null
            val number = CardTraderMatcher.normalizeNumber(request.localId).toIntOrNull()
            return (number?.let { productByPosition(products, ordinal, it) } ?: productByOrdinal(products, ordinal))?.idProduct
        }
        if (candidates.size <= 1) return candidates.singleOrNull()?.idProduct
        // I prodotti di un set seguono l'ordine delle carte: il numero stampato è la posizione nel set.
        val number = CardTraderMatcher.normalizeNumber(request.localId).toIntOrNull() ?: return null
        val atPosition = products.getOrNull(number - 1) ?: return null
        return atPosition.idProduct.takeIf { p -> candidates.any { it.idProduct == p } }
    }
}

private const val MIN_CONTAINED_KEY = 4

/**
 * Trova il prodotto di una carta senza Pokédex (Allenatore, Energia, fossile) dal suo numero, se i prodotti del set
 * seguono l'ordine delle carte. Lo si verifica con i due Pokémon di riferimento: dal numero e dalla [SetOrdinal] si
 * ricava dove devono stare, e il prodotto in quelle posizioni deve avere il loro nome (anche con un'aggiunta, come
 * "Registeel Gold Star" o "Altaria ex"). Un set che chiude dopo l'ultima carta, o inizia prima della prima, vale solo
 * se i prodotti finiscono (o iniziano) lì. Se una verifica fallisce non si indovina.
 * @param products List<CatalogProduct> - prodotti del set, nell'ordine di Cardmarket.
 * @param ordinal SetOrdinal - posizione della carta tra i due Pokémon, su TCGdex.
 * @param number Int - numero della carta nel set (1 è il primo prodotto).
 * @return CatalogProduct? - il prodotto, oppure null se l'ordine dei prodotti non coincide con quello delle carte.
 */
internal fun productByPosition(products: List<CatalogProduct>, ordinal: SetOrdinal, number: Int): CatalogProduct? {
    fun isPokemon(index: Int, dex: Int): Boolean {
        val nameKey = products.getOrNull(index)?.nameKey ?: return false
        return PokemonNames.namesOf(dex).map { CardmarketCatalog.nameKey(it) }
            .any { it.length >= MIN_CONTAINED_KEY && (nameKey == it || nameKey.startsWith(it)) }
    }
    // Indici (da 0) dei due Pokémon di riferimento; le carte comprese tra loro non hanno Pokédex.
    val left = number - ordinal.rank - 2
    val right = number - ordinal.rank + ordinal.count - 1
    val leftOk = if (ordinal.leftDex != null) isPokemon(left, ordinal.leftDex) else left == -1
    val rightOk = if (ordinal.rightDex != null) isPokemon(right, ordinal.rightDex) else right == products.size
    if (!leftOk || !rightOk) return null
    return products.getOrNull(number - 1)
}

/**
 * Trova il prodotto di una carta senza Pokédex (Allenatore, Energia) dalla sua posizione tra le carte Pokémon del set:
 * i prodotti Cardmarket seguono l'ordine delle carte. Si cercano i due Pokémon di riferimento (prima e dopo) e si prende
 * il prodotto che occupa la stessa posizione tra quelli che non nominano un Pokémon. Se un riferimento non è univoco
 * o il numero di carte tra i due non coincide con quello di TCGdex, non si indovina.
 * @param products List<CatalogProduct> - prodotti del set, nell'ordine di Cardmarket.
 * @param ordinal SetOrdinal - posizione della carta su TCGdex.
 * @return CatalogProduct? - il prodotto, oppure null se la posizione non è affidabile.
 */
internal fun productByOrdinal(products: List<CatalogProduct>, ordinal: SetOrdinal): CatalogProduct? {
    fun indexOf(dex: Int): Int? {
        val keys = PokemonNames.namesOf(dex).map { CardmarketCatalog.nameKey(it) }.toSet()
        return products.indices.filter { products[it].nameKey in keys }.singleOrNull()
    }
    val left = ordinal.leftDex?.let { indexOf(it) ?: return null } ?: -1
    val right = ordinal.rightDex?.let { indexOf(it) ?: return null } ?: products.size
    if (left >= right) return null
    val between = products.subList(left + 1, right).filterNot { PokemonNames.mentionsPokemon(it.name) }
    return if (between.size == ordinal.count) between.getOrNull(ordinal.rank) else null
}

/**
 * Prova prima questa fonte e, se non trova la carta o non è disponibile (rete, token non valido), l'altra.
 * @param other fonte di ripiego.
 * @return CardmarketIdSource - fonte composta.
 */
fun CardmarketIdSource.orElse(other: CardmarketIdSource): CardmarketIdSource = CardmarketIdSource { request ->
    val first = try {
        cardmarketProductId(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    first ?: other.cardmarketProductId(request)
}
