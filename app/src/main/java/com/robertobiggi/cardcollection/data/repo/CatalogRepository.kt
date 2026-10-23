package com.robertobiggi.cardcollection.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCardSearch
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.cardmarket.CardNumberResolver
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.NotFoundException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.tcgdex.CardBriefDto
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeSearch
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.SetDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionCardEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** Fase del sync del catalogo. */
enum class SyncPhase { SEARCH, CARD, IMAGES, CARDMARKET, CARDMARKET_DOWNLOAD, CARDMARKET_READ }

/**
 * Avanzamento del sync del catalogo.
 * @property label testo della ricerca o nome della carta in lavorazione (dati, non frasi).
 */
data class SyncProgress(val phase: SyncPhase, val label: String, val done: Int, val total: Int)

/** Esito del sync del catalogo: carte e immagini salvate, numero di problemi incontrati. */
data class SyncResult(val cardsSaved: Int, val imagesSaved: Int, val errors: Int)

/** Esito delle azioni sull'immagine di una carta. */
enum class ImageResult { DOWNLOADED, SAVED, NOT_AVAILABLE, NETWORK_ERROR, INVALID_IMAGE }

/** Esito della ricerca di carte da aggiungere a mano. */
sealed interface SearchResult {
    /** Carte trovate (lista eventualmente vuota). */
    data class Found(val cards: List<CardBriefDto>) : SearchResult

    /** Testo di ricerca non valido. */
    data object InvalidQuery : SearchResult

    /** Rete non disponibile o risposta non valida. */
    data object NetworkError : SearchResult
}

/** Esito dell'aggiunta di una carta singola. */
sealed interface AddCardResult {
    /** Carta [name] aggiunta. */
    data class Added(val name: String) : AddCardResult

    /** La carta non si può aggiungere (id non valido o dati mancanti). */
    data object NotAllowed : AddCardResult

    /** Rete non disponibile o risposta non valida. */
    data object NetworkError : AddCardResult
}

/**
 * Scarica il catalogo delle ricerche di una collezione da TCGdex e lo salva in locale, immagini comprese.
 * Le carte che TCGdex non ha (es. i set giapponesi senza carte) si cercano anche nel catalogo Cardmarket.
 * Non modifica mai quantità, condizioni o slot desiderati dell'utente.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val client: TcgdexClient,
    private val images: ImageStore,
    private val settings: SettingsStore,
    private val cardmarket: CardmarketCardSearch,
    private val http: OkHttpClient,
) {
    private val dao = db.catalogDao()

    /**
     * Esegue il sync delle ricerche attive di una collezione: ricerca, dettaglio carte, set e immagini.
     * @param collectionId Long - collezione da aggiornare.
     * @param progress suspend (SyncProgress) -> Unit - callback di avanzamento.
     * @return SyncResult - carte e immagini salvate, errori incontrati.
     */
    suspend fun sync(collectionId: Long, progress: suspend (SyncProgress) -> Unit): SyncResult {
        var errors = 0
        val limiter = RateLimiter(REQUEST_INTERVAL_MS)
        val setCache = HashMap<String, SetDto?>()
        val tcgdexProducts = HashSet<Int>()
        var saved = 0
        val queries = dao.enabledQueriesOf(collectionId)
        val resolver = cardTraderResolver()
        for ((qIndex, query) in queries.withIndex()) {
            val language = Language.fromCode(query.language) ?: continue
            progress(SyncProgress(SyncPhase.SEARCH, query.pattern, qIndex, queries.size))
            val briefs = ArrayList<CardBriefDto>()
            for (term in PokemonNames.searchTerms(query.pattern, language)) {
                try {
                    limiter.acquire()
                    briefs += withRetry { client.searchCards(language, term) }
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors++
                }
            }
            val candidates = briefs.filterNot { CatalogMapper.isDigitalOnly(it) }.filter { Validators.isValidRemoteId(it.id) }.distinctBy { it.id }
            for ((index, brief) in candidates.withIndex()) {
                progress(SyncProgress(SyncPhase.CARD, brief.name, index, candidates.size))
                try {
                    val card = fetchCatalogCard(language, brief.id, limiter, setCache, cardmarketProducts = tcgdexProducts) ?: continue
                    saveCard(card, legacyQueryId = query.id)
                    dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                    saved++
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors++
                }
            }
            progress(SyncProgress(SyncPhase.CARDMARKET, query.pattern, qIndex, queries.size))
            val known = LinkedHashSet<String>()
            val fromCardmarket = cardmarketCards(language, query.pattern, progress, qIndex, queries.size, resolver, known)
            // Carte che TCGdex ha ma la sua ricerca per nome non ha trovato (nome scritto in modo diverso): Cardmarket le indica per posizione.
            for (id in known) {
                if (candidates.any { it.id == id } || !Validators.isValidRemoteId(id)) continue
                try {
                    limiter.acquire()
                    val card = fetchCatalogCard(language, id, limiter, setCache, cardmarketProducts = tcgdexProducts) ?: continue
                    saveCard(card, legacyQueryId = query.id)
                    dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                    saved++
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors++
                }
            }
            // Una carta che TCGdex ha già (stesso prodotto Cardmarket) non si aggiunge una seconda volta dal catalogo Cardmarket.
            for (card in fromCardmarket) {
                if (card.cardmarketId != null && card.cardmarketId in tcgdexProducts) continue
                saveCard(card, legacyQueryId = query.id)
                dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                saved++
            }
        }
        val imagesSaved = downloadMissingImages(progress)
        if (errors == 0 || saved > 0) settings.setLastCatalogSync(System.currentTimeMillis())
        return SyncResult(saved, imagesSaved, errors)
    }

    /**
     * Cerca carte su TCGdex per aggiungerle a mano a una collezione.
     * Se il testo contiene un numero di carta (es. "M3 117/080") cerca per codice stampato,
     * altrimenti per nome.
     * @param language Language - lingua del catalogo.
     * @param text String - nome o codice da cercare.
     * @return SearchResult - risultati senza carte digitali, oppure il motivo del fallimento.
     */
    suspend fun search(language: Language, text: String): SearchResult {
        val code = CardCodeSearch.parse(text)
        if (code == null && !Validators.isValidQueryPattern(text)) return SearchResult.InvalidQuery
        return try {
            val cards = if (code != null) CardCodeSearch.find(client, language, code) else searchByName(language, text)
            val known = LinkedHashSet<String>()
            val missing = try {
                if (code != null) cardmarket.findByCode(language, code) else cardmarketTerms(text).flatMap { cardmarket.find(language, it, known = known) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // Carte che TCGdex ha ma la sua ricerca per nome non trova (nome in giapponese, cercato in inglese): Cardmarket le indica.
            val indicated = known.filter { id -> cards.none { it.id == id } }.take(MAX_SEARCH_RESULTS).mapNotNull { id ->
                try {
                    client.getCard(language, id).let { CardBriefDto(it.id, it.localId, it.name, it.image) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            SearchResult.Found(
                (cards.filterNot { CatalogMapper.isDigitalOnly(it) } + indicated + missing.map { CardBriefDto(it.tcgdexId, it.localId, it.name, null) })
                    .filter { Validators.isValidRemoteId(it.id) }
                    .distinctBy { it.id }
                    .take(MAX_SEARCH_RESULTS),
            )
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            SearchResult.NetworkError
        }
    }

    /**
     * Aggiunge a mano una carta TCGdex a una collezione (anche se nessuna ricerca la trova).
     * @param collectionId Long - collezione.
     * @param language Language - lingua del catalogo.
     * @param tcgdexId String - id TCGdex della carta.
     * @return AddCardResult - esito dell'aggiunta.
     */
    suspend fun addSingleCard(collectionId: Long, language: Language, tcgdexId: String): AddCardResult {
        if (!Validators.isValidRemoteId(tcgdexId)) return AddCardResult.NotAllowed
        return try {
            val card = try {
                fetchCatalogCard(language, tcgdexId, RateLimiter(0), HashMap())
            } catch (e: NotFoundException) {
                // TCGdex non ha la carta: potrebbe essere una carta trovata nel catalogo Cardmarket.
                cardmarket.cardFor(language, tcgdexId.substringBeforeLast('-'), tcgdexId.substringAfterLast('-'))
            } ?: return AddCardResult.NotAllowed
            saveCard(card, legacyQueryId = 0)
            if (dao.insertMembership(CollectionCardEntity(collectionId, card.id, null)) == -1L) dao.makeManual(collectionId, card.id)
            downloadMissingImages { }
            AddCardResult.Added(card.name)
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            AddCardResult.NetworkError
        }
    }

    /**
     * Riscarica i dati della carta da TCGdex (l'immagine può essere stata aggiunta dopo) e ne scarica
     * l'immagine ufficiale, che sostituisce quella scelta a mano.
     * @param cardId String - id locale della carta.
     * @return ImageResult - DOWNLOADED, NOT_AVAILABLE se TCGdex non ha l'immagine, NETWORK_ERROR.
     */
    suspend fun downloadImage(cardId: String): ImageResult {
        val stored = dao.getCard(cardId) ?: return ImageResult.NOT_AVAILABLE
        val language = Language.fromCode(stored.language) ?: return ImageResult.NOT_AVAILABLE
        val refreshedUrl = try {
            fetchCatalogCard(language, stored.tcgdexId, RateLimiter(0), HashMap())?.let { saveCard(it, stored.queryId) }
            dao.getCard(cardId)?.imageUrl
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            if (e is NotFoundException) stored.imageUrl else return ImageResult.NETWORK_ERROR
        }
        val url = refreshedUrl ?: return ImageResult.NOT_AVAILABLE
        val low = images.ensure(cardId, url, ImageStore.Quality.LOW)
        val high = images.ensure(cardId, url, ImageStore.Quality.HIGH)
        if (low == null && high == null) return ImageResult.NETWORK_ERROR
        val current = dao.getCard(cardId)
        if (images.isManual(current?.imageHighPath)) images.deleteManual(current?.imageHighPath)
        if (images.isManual(current?.imageLowPath)) images.deleteManual(current?.imageLowPath)
        dao.setImagePaths(cardId, low ?: high, high ?: low)
        return ImageResult.DOWNLOADED
    }

    /**
     * Usa come immagine della carta un'immagine scelta dall'utente. Resta finché non si scarica
     * quella ufficiale; il sync del catalogo non la sostituisce.
     * @param cardId String - id locale della carta.
     * @param uri Uri - immagine scelta con il selettore di sistema.
     * @return ImageResult - SAVED oppure INVALID_IMAGE.
     */
    suspend fun setManualImage(cardId: String, uri: Uri): ImageResult {
        val card = dao.getCard(cardId) ?: return ImageResult.INVALID_IMAGE
        val bytes = images.prepareManual(uri) ?: return ImageResult.INVALID_IMAGE
        val path = images.writeManual(cardId, bytes, previous = card.imageHighPath) ?: return ImageResult.INVALID_IMAGE
        if (card.imageLowPath != card.imageHighPath) images.deleteManual(card.imageLowPath)
        dao.setImagePaths(cardId, path, path)
        return ImageResult.SAVED
    }

    /**
     * Scarica dettaglio e set di una carta e la normalizza.
     * @param language Language - lingua.
     * @param tcgdexId String - id TCGdex già validato.
     * @param limiter RateLimiter - limitatore delle richieste.
     * @param setCache HashMap<String, SetDto?> - cache dei set già scaricati.
     * @param cardmarketProducts MutableSet<Int>? - riceve il prodotto Cardmarket della carta, per non aggiungere la stessa carta anche dal catalogo Cardmarket.
     * @return CatalogCard? - carta normalizzata, oppure null se va scartata.
     */
    private suspend fun fetchCatalogCard(
        language: Language,
        tcgdexId: String,
        limiter: RateLimiter,
        setCache: HashMap<String, SetDto?>,
        cardmarketProducts: MutableSet<Int>? = null,
    ): CatalogCard? {
        limiter.acquire()
        val card = withRetry { client.getCard(language, tcgdexId) }
        card.pricing?.cardmarket?.idProduct?.let { cardmarketProducts?.add(it) }
        val setId = card.set.id
        val set = if (Validators.isValidRemoteId(setId)) {
            setCache.getOrPut("${language.code}/$setId") {
                try {
                    limiter.acquire()
                    withRetry { client.getSet(language, setId) }
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    null
                }
            }
        } else {
            null
        }
        if (!CatalogMapper.isTrackable(card, set)) return null
        return CatalogMapper.toCatalogCard(language, card, set)
    }

    /**
     * Salva una carta del catalogo e le sue varianti, mantenendo i percorsi delle immagini già presenti.
     * @param card CatalogCard - carta normalizzata.
     * @param legacyQueryId Long - valore del campo storico queryId.
     * @return Unit
     */
    suspend fun saveCard(card: CatalogCard, legacyQueryId: Long) {
        db.withTransaction {
            val existing = dao.getCard(card.id)
            dao.upsertCard(
                CardEntity(
                    id = card.id,
                    language = card.language.code,
                    tcgdexId = card.tcgdexId,
                    name = card.name,
                    setId = card.setId,
                    setName = card.setName,
                    setCode = card.setCode,
                    serieId = card.serieId,
                    serieName = card.serieName,
                    localId = card.localId,
                    // Rarità e numero che le carte del catalogo Cardmarket prendono da CardTrader: il sync non li cancella.
                    printedNumber = card.printedNumber.ifEmpty { existing?.printedNumber.orEmpty() },
                    rarity = card.rarity ?: existing?.rarity,
                    releaseDate = card.releaseDate,
                    illustrator = card.illustrator,
                    imageUrl = card.imageBaseUrl ?: existing?.imageUrl,
                    imageLowPath = existing?.imageLowPath,
                    imageHighPath = existing?.imageHighPath,
                    queryId = existing?.queryId ?: legacyQueryId,
                    catalogUpdatedAt = System.currentTimeMillis(),
                    dexId = card.dexId ?: existing?.dexId,
                    cardTraderCondition = existing?.cardTraderCondition,
                    cardmarketId = card.cardmarketId ?: existing?.cardmarketId,
                    cardTraderBlueprintId = existing?.cardTraderBlueprintId,
                ),
            )
            dao.insertVariants(card.variants.map { CardVariantEntity(Ids.variantId(card.id, it.type), card.id, it.type.name) })
            moveStaleReverseToHolo(card.id, card.variants.map { it.type })
            dao.deleteStaleVariants(card.id, card.variants.map { it.type.name })
        }
    }

    /**
     * Sposta sulla holo le copie di una reverse holo che il catalogo non elenca più: quella reverse era dedotta dai prezzi
     * "-holo", cioè dalla holo. Senza una holo tra le varianti, o se la reverse esiste ancora, non cambia nulla.
     * @param cardId String - carta.
     * @param types List<VariantType> - varianti attuali della carta.
     * @return Unit
     */
    private suspend fun moveStaleReverseToHolo(cardId: String, types: List<VariantType>) {
        if (VariantType.REVERSE in types || VariantType.HOLO !in types) return
        val user = db.collectionDao()
        val from = Ids.variantId(cardId, VariantType.REVERSE)
        val to = Ids.variantId(cardId, VariantType.HOLO)
        val entries = user.entriesOfVariant(from)
        if (entries.isEmpty()) return
        entries.forEach { user.upsertEntry(EntryMerge.move(it, to, user.getEntry(it.collectionId, to))) }
        user.deleteEntriesOfVariant(from)
    }

    /**
     * Scarica le immagini mancanti (bassa e alta qualità) per tutte le carte salvate.
     * @param progress suspend (SyncProgress) -> Unit - callback di avanzamento.
     * @return Int - numero di carte per cui è stata salvata almeno un'immagine nuova.
     */
    private suspend fun downloadMissingImages(progress: suspend (SyncProgress) -> Unit): Int {
        val missing = dao.cardsMissingImages()
        var count = 0
        for ((index, card) in missing.withIndex()) {
            val url = card.imageUrl ?: continue
            progress(SyncProgress(SyncPhase.IMAGES, card.name, index, missing.size))
            val low = card.imageLowPath ?: images.ensure(card.id, url, ImageStore.Quality.LOW)
            val high = card.imageHighPath ?: images.ensure(card.id, url, ImageStore.Quality.HIGH)
            if (low != card.imageLowPath || high != card.imageHighPath) {
                dao.setImagePaths(card.id, low, high)
                count++
            }
        }
        return count
    }

    /**
     * Ripete una chiamata una volta se la fonte risponde 429, attendendo quanto richiesto.
     * @param block suspend () -> T - chiamata da eseguire.
     * @return T - risultato della chiamata.
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T = try {
        block()
    } catch (e: RateLimitedException) {
        delay(e.retryAfterMs ?: RETRY_DELAY_MS)
        block()
    }

    /**
     * Indica se un errore riguarda la rete o i dati remoti (e quindi si può segnalare e proseguire).
     * Le cancellazioni delle coroutine e gli errori di programmazione vengono rilanciati.
     * @param e Exception - errore intercettato.
     * @return Boolean - true se recuperabile.
     */
    /**
     * Cerca nel catalogo Cardmarket le carte che TCGdex non ha. Un errore (rete assente, catalogo non scaricabile)
     * non fa fallire il sync: le carte di TCGdex sono già salvate.
     * @param language Language - lingua del catalogo.
     * @param pattern String - testo cercato.
     * @param progress suspend (SyncProgress) -> Unit - avanzamento, per mostrare lo scaricamento e la lettura del catalogo.
     * @param index Int - posizione della ricerca.
     * @param total Int - numero di ricerche.
     * @param known MutableCollection<String> - riceve gli id TCGdex delle carte già note a TCGdex.
     * @return List<CatalogCard> - carte trovate, vuoto in caso di errore.
     */
    private suspend fun cardmarketCards(language: Language, pattern: String, progress: suspend (SyncProgress) -> Unit, index: Int, total: Int, resolver: CardNumberResolver?, known: MutableCollection<String>): List<CatalogCard> =
        try {
            cardmarketTerms(pattern).flatMap { term ->
                cardmarket.find(
                    language, term,
                    onDownload = { progress(SyncProgress(SyncPhase.CARDMARKET_DOWNLOAD, CARDMARKET_LABEL, index, total)) },
                    onRead = { progress(SyncProgress(SyncPhase.CARDMARKET_READ, CARDMARKET_LABEL, index, total)) },
                    resolver = resolver,
                    known = known,
                )
            }.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Cerca per nome su TCGdex anche con il nome del Pokémon nella lingua del catalogo.
     * @param language Language - lingua del catalogo.
     * @param text String - nome cercato, in una lingua qualsiasi.
     * @return List<CardBriefDto> - carte trovate, senza doppioni.
     */
    private suspend fun searchByName(language: Language, text: String): List<CardBriefDto> =
        PokemonNames.searchTerms(text, language).flatMap { client.searchCards(language, it) }.distinctBy { it.id }

    /**
     * Numero vero delle carte Cardmarket la cui posizione nel set non coincide con il numero (Hidden Fates, promo),
     * letto da CardTrader. Serve il token; senza, quelle carte si saltano.
     * @return CardNumberResolver? - il resolver, oppure null senza token.
     */
    private suspend fun cardTraderResolver(): CardNumberResolver? {
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return null
        val provider = CardTraderPriceProvider(CardTraderClient(http, token))
        return CardNumberResolver { language, set, detail, product ->
            try {
                provider.collectorNumber(
                    PriceRequest(
                        language = language,
                        tcgdexId = "",
                        variant = VariantType.NORMAL,
                        cardHasNormalAndHolo = false,
                        setId = set.id,
                        setName = detail.name,
                        setCode = CatalogMapper.printedSetCode(language, detail, set.id),
                        cardmarketId = product.idProduct,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Termini per il catalogo Cardmarket, dove i nomi delle carte sono in inglese anche per i set giapponesi. */
    private fun cardmarketTerms(text: String): List<String> = PokemonNames.searchTerms(text, Language.EN)

    private fun isRecoverable(e: Exception): Boolean =
        e is ApiException || e is IOException || e is InvalidResponseException || e is IllegalArgumentException

    private companion object {
        const val REQUEST_INTERVAL_MS = 250L
        const val RETRY_DELAY_MS = 5_000L
        const val MAX_SEARCH_RESULTS = 60
        const val CARDMARKET_LABEL = "Cardmarket"
    }
}
