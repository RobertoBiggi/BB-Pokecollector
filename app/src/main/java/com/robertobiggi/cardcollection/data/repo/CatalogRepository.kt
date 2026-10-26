package com.robertobiggi.cardcollection.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCardSearch
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.cardmarket.CardNumberResolver
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.core.model.SetCollectionPolicy
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.NotFoundException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.tcgdex.CardBriefDto
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeSearch
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.CardDto
import com.robertobiggi.cardcollection.core.tcgdex.SetDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionCardEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** Fase del sync del catalogo. */
enum class SyncPhase { SEARCH, CARD, IMAGES, IMAGES_CARDTRADER, CARDMARKET, CARDMARKET_DOWNLOAD, CARDMARKET_READ }

/**
 * Avanzamento del sync del catalogo.
 * @property label testo della ricerca o nome della carta in lavorazione (dati, non frasi).
 */
data class SyncProgress(val phase: SyncPhase, val label: String, val done: Int, val total: Int)

/** Esito del sync del catalogo: carte e immagini salvate, numero di problemi incontrati. */
/**
 * Esito del sync del catalogo. [emptySets] conta i set (collezioni per set) di cui nessuna fonte ha ancora le carte.
 */
data class SyncResult(val cardsSaved: Int, val imagesSaved: Int, val errors: Int, val emptySets: Int = 0)

/**
 * Set proponibile per una collezione per set.
 * @property id id del set (TCGdex oppure dell'elenco dei set mancanti, es. "base1", "Pt4").
 * @property name nome del set.
 * @property cards carte dichiarate, se note.
 * @property fromCardmarket true per i set che TCGdex non elenca, presi dall'elenco Cardmarket/CardTrader.
 */
data class SetChoice(val id: String, val name: String, val cards: Int?, val fromCardmarket: Boolean)

/** Esito della lettura dell'elenco dei set. */
sealed interface SetListResult {
    /** Set disponibili, nell'ordine di TCGdex seguiti da quelli che TCGdex non elenca. */
    data class Found(val sets: List<SetChoice>) : SetListResult

    /** TCGdex non raggiungibile. */
    data object NetworkError : SetListResult
}

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
        val refreshed = HashSet<String>()
        val unnumbered = ArrayList<Pair<CatalogCard, Long>>()
        val numberedProducts = HashSet<Int>()
        val queries = dao.enabledQueriesOf(collectionId)
        val resolver = cardTraderResolver()
        var emptySets = 0
        for ((qIndex, query) in queries.withIndex()) {
            val language = Language.fromCode(query.language) ?: continue
            val kind = QueryKind.fromCode(query.kind) ?: continue
            progress(SyncProgress(SyncPhase.SEARCH, query.pattern, qIndex, queries.size))
            if (kind.isSet) {
                val outcome = syncSet(collectionId, query, kind, language, limiter, setCache, tcgdexProducts, refreshed, progress)
                saved += outcome.saved
                errors += outcome.errors
                if (outcome.saved == 0 && outcome.errors == 0) emptySets++
                continue
            }
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
                    val card = fetchCatalogCard(language, brief.id, limiter, setCache, cardmarketProducts = tcgdexProducts, lookupExpansion = true) ?: continue
                    saveCard(card, legacyQueryId = query.id)
                    refreshed += card.id
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
                    val card = fetchCatalogCard(language, id, limiter, setCache, cardmarketProducts = tcgdexProducts, lookupExpansion = true) ?: continue
                    saveCard(card, legacyQueryId = query.id)
                    refreshed += card.id
                    dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                    saved++
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors++
                }
            }
            // Una carta che TCGdex ha già (stesso prodotto Cardmarket) non si aggiunge una seconda volta dal catalogo Cardmarket.
            // Le carte senza numero aspettano la fine del sync: si scartano se sono un'altra scheda di una carta con numero
            // trovata da qualunque ricerca (vedi sotto).
            for (card in fromCardmarket) {
                if (card.cardmarketId != null && card.cardmarketId in tcgdexProducts) continue
                if (card.printedNumber.isEmpty() && card.cardmarketId != null) {
                    unnumbered += card to query.id
                    continue
                }
                card.cardmarketId?.let { numberedProducts += it }
                saveCard(card, legacyQueryId = query.id)
                refreshed += card.id
                dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                saved++
            }
        }
        saved += saveUnnumbered(collectionId, unnumbered, tcgdexProducts + numberedProducts, refreshed)
        // Le carte senza numero trovate da una ricerca e non più indicate dal catalogo (doppioni, set tolti) escono dalla collezione.
        // Solo se la ricerca non ha avuto errori, altrimenti una rete assente le farebbe sembrare sparite.
        if (errors == 0) {
            val stale = dao.membershipsOf(collectionId).map { it.cardId }.filter { Ids.isUnnumberedCardmarket(it) && it !in refreshed }
            if (stale.isNotEmpty()) dao.dropStaleMemberships(collectionId, stale)
        }
        errors += refreshOtherCards(collectionId, refreshed, limiter, setCache, progress)
        val imagesSaved = downloadMissingImages(progress)
        if (errors == 0 || saved > 0) settings.setLastCatalogSync(System.currentTimeMillis())
        return SyncResult(saved, imagesSaved, errors, emptySets)
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
     * Salva le carte senza numero del catalogo Cardmarket, tranne quelle che sono un'altra scheda (stessa metacard) di una carta
     * con numero che la collezione ha o ha appena trovato nello stesso set Cardmarket, in qualunque lingua: Cardmarket tiene nello stesso set
     * stampe giapponesi (es. "Retail Exclusive Promos" ha MCharizard EX dello Starter Pack 20th). La stessa carta in un altro set è una ristampa e si tiene.
     * @param collectionId Long - collezione.
     * @param cards List<Pair<CatalogCard, Long>> - carte senza numero e id della ricerca che le ha trovate.
     * @param foundProducts Set<Int> - prodotti Cardmarket delle carte con numero trovate in questo sync.
     * @param refreshed MutableSet<String> - riceve gli id delle carte salvate.
     * @return Int - carte salvate.
     */
    private suspend fun saveUnnumbered(collectionId: Long, cards: List<Pair<CatalogCard, Long>>, foundProducts: Set<Int>, refreshed: MutableSet<String>): Int {
        if (cards.isEmpty()) return 0
        val owned = dao.membershipsOf(collectionId).map { it.cardId }.filterNot { Ids.isUnnumberedCardmarket(it) }
            .mapNotNull { dao.getCard(it) }.flatMap { listOfNotNull(it.cardmarketId, it.cardmarketProductHint) }
        val numbered = HashSet<Pair<Int, Int>>().apply { for (product in foundProducts + owned) printKeyOrNull(product)?.let(::add) }
        var saved = 0
        for ((card, queryId) in cards) {
            if (printKeyOrNull(card.cardmarketId ?: continue) in numbered) continue
            saveCard(card, legacyQueryId = queryId)
            refreshed += card.id
            dao.insertMembership(CollectionCardEntity(collectionId, card.id, queryId))
            saved++
        }
        return saved
    }

    private suspend fun printKeyOrNull(product: Int): Pair<Int, Int>? = try {
        cardmarket.printKeyOf(product)
    } catch (e: IOException) {
        null
    }

    /** Carte salvate ed errori del sync di un set. */
    private data class SetSyncOutcome(val saved: Int, val errors: Int)

    /**
     * Scarica tutte le carte di un set per una collezione per set: da TCGdex se il set ha le carte, altrimenti
     * dall'elenco dei set mancanti (Cardmarket/CardTrader). Per ogni carta segna come "non la voglio" le varianti
     * escluse dal livello scelto (vedi [SetCollectionPolicy]), senza toccare le voci già presenti.
     * @param collectionId Long - collezione.
     * @param query TrackedQueryEntity - ricerca di tipo set (pattern = id del set).
     * @param kind QueryKind - set base o master set.
     * @param language Language - lingua del catalogo.
     * @param limiter RateLimiter - limitatore delle richieste TCGdex.
     * @param setCache HashMap<String, SetDto?> - cache dei dettagli dei set.
     * @param tcgdexProducts MutableSet<Int> - riceve i prodotti Cardmarket delle carte TCGdex.
     * @param refreshed MutableSet<String> - riceve gli id delle carte aggiornate.
     * @param progress suspend (SyncProgress) -> Unit - callback di avanzamento.
     * @return SetSyncOutcome - carte salvate ed errori.
     */
    private suspend fun syncSet(
        collectionId: Long,
        query: TrackedQueryEntity,
        kind: QueryKind,
        language: Language,
        limiter: RateLimiter,
        setCache: HashMap<String, SetDto?>,
        tcgdexProducts: MutableSet<Int>,
        refreshed: MutableSet<String>,
        progress: suspend (SyncProgress) -> Unit,
    ): SetSyncOutcome {
        val setId = query.pattern.trim()
        if (!Validators.isValidRemoteId(setId)) return SetSyncOutcome(0, 1)
        var saved = 0
        var errors = 0
        val keep: suspend (CatalogCard) -> Unit = { card ->
            saveCard(card, legacyQueryId = query.id)
            refreshed += card.id
            dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
            applySetPolicy(collectionId, card, kind)
            saved++
        }
        val briefs = try {
            limiter.acquire()
            withRetry { client.getSet(language, setId) }.cards
        } catch (e: NotFoundException) {
            emptyList()
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            return SetSyncOutcome(0, 1)
        }
        val valid = briefs.filterNot { CatalogMapper.isDigitalOnly(it) }.filter { Validators.isValidRemoteId(it.id) }
        if (valid.isNotEmpty()) {
            for ((index, brief) in valid.withIndex()) {
                progress(SyncProgress(SyncPhase.CARD, brief.name, index, valid.size))
                try {
                    val card = fetchCatalogCard(language, brief.id, limiter, setCache, cardmarketProducts = tcgdexProducts, lookupExpansion = true) ?: continue
                    keep(card)
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors++
                }
            }
            return SetSyncOutcome(saved, errors)
        }
        // Set che TCGdex non elenca (o elenca senza carte): elenco dei set mancanti, se lo contiene.
        val extra = try {
            cardmarket.extraSetAll(
                language, setId,
                onDownload = { progress(SyncProgress(SyncPhase.CARDMARKET_DOWNLOAD, CARDMARKET_LABEL, 0, 1)) },
                onRead = { progress(SyncProgress(SyncPhase.CARDMARKET_READ, CARDMARKET_LABEL, 0, 1)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            return SetSyncOutcome(0, 1)
        }
        extra.forEach { keep(it) }
        return SetSyncOutcome(saved, errors)
    }

    /**
     * Segna come "non la voglio" le varianti che il livello della collezione per set esclude. Le voci già presenti
     * (quantità o scelte dell'utente) restano invariate.
     * @param collectionId Long - collezione.
     * @param card CatalogCard - carta appena salvata.
     * @param kind QueryKind - set base o master set.
     * @return Unit
     */
    private suspend fun applySetPolicy(collectionId: Long, card: CatalogCard, kind: QueryKind) {
        val unwanted = SetCollectionPolicy.unwantedVariants(card.variants.map { it.type }, kind)
        if (unwanted.isEmpty()) return
        db.collectionDao().insertEntriesIfAbsent(
            card.variants.filter { it.type in unwanted }.map { CollectionEntryEntity(collectionId, Ids.variantId(card.id, it.type, it.print), wanted = false) },
        )
    }

    /**
     * Elenco dei set di una lingua da cui creare una collezione per set: quelli di TCGdex più quelli che TCGdex non elenca.
     * @param language Language - lingua del catalogo.
     * @return SetListResult - set trovati, oppure errore di rete.
     */
    suspend fun setChoices(language: Language): SetListResult {
        val sets = try {
            withRetry { client.listSets(language) }
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            return SetListResult.NetworkError
        }
        val known = sets.map { it.id }.toSet()
        val fromTcgdex = sets.filter { Validators.isValidRemoteId(it.id) && !CatalogMapper.isDigitalOnlySet(it) }
            .map { SetChoice(it.id, it.name, it.cardCount?.total ?: it.cardCount?.official, fromCardmarket = false) }
        val missing = cardmarket.extraSetsFor(language).filter { it.setId !in known && Validators.isValidRemoteId(it.setId) }
            .map { SetChoice(it.setId, it.name, it.numbers.size.takeIf { n -> n > 0 }, fromCardmarket = true) }
        return SetListResult.Found((fromTcgdex + missing).distinctBy { it.id })
    }

    /**
     * Riscarica dal catalogo le carte della collezione che nessuna ricerca ha aggiornato (aggiunte a mano, importate da un file,
     * ricerche tolte), così ogni sync corregge i dati sbagliati salvati da versioni precedenti, anche se possiedi copie.
     * @param collectionId Long - collezione.
     * @param refreshed Set<String> - id delle carte già aggiornate in questo sync.
     * @param limiter RateLimiter - limitatore delle richieste.
     * @param setCache HashMap<String, SetDto?> - cache dei set già scaricati.
     * @param progress suspend (SyncProgress) -> Unit - callback di avanzamento.
     * @return Int - errori incontrati.
     */
    private suspend fun refreshOtherCards(
        collectionId: Long,
        refreshed: Set<String>,
        limiter: RateLimiter,
        setCache: HashMap<String, SetDto?>,
        progress: suspend (SyncProgress) -> Unit,
    ): Int {
        var errors = 0
        val others = dao.membershipsOf(collectionId).map { it.cardId }.filter { it !in refreshed }.mapNotNull { dao.getCard(it) }
        for ((index, stored) in others.withIndex()) {
            val language = Language.fromCode(stored.language) ?: continue
            if (!Validators.isValidRemoteId(stored.tcgdexId)) continue
            progress(SyncProgress(SyncPhase.CARD, stored.name, index, others.size))
            try {
                val card = fetchOrCardmarketCard(language, stored.tcgdexId, limiter, setCache, lookupExpansion = true) ?: continue
                saveCard(card, stored.queryId)
            } catch (e: Exception) {
                if (!isRecoverable(e)) throw e
                errors++
            }
        }
        return errors
    }

    /**
     * Scarica una carta da TCGdex; se TCGdex non la ha, la cerca nel catalogo Cardmarket.
     * @param language Language - lingua.
     * @param tcgdexId String - id TCGdex già validato.
     * @param limiter RateLimiter - limitatore delle richieste.
     * @param setCache HashMap<String, SetDto?> - cache dei set già scaricati.
     * @param lookupExpansion Boolean - true per ricavare anche il set Cardmarket.
     * @return CatalogCard? - carta normalizzata, oppure null se va scartata.
     */
    private suspend fun fetchOrCardmarketCard(language: Language, tcgdexId: String, limiter: RateLimiter, setCache: HashMap<String, SetDto?>, lookupExpansion: Boolean = false): CatalogCard? =
        try {
            fetchCatalogCard(language, tcgdexId, limiter, setCache, lookupExpansion = lookupExpansion)
        } catch (e: NotFoundException) {
            cardmarket.cardFor(language, tcgdexId.substringBeforeLast('-'), tcgdexId.substringAfterLast('-'))
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
            // Se TCGdex non ha la carta, potrebbe essere una carta trovata nel catalogo Cardmarket.
            val card = fetchOrCardmarketCard(language, tcgdexId, RateLimiter(0), HashMap()) ?: return AddCardResult.NotAllowed
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
     * @param lookupExpansion Boolean - true per ricavare anche il set Cardmarket (legge il catalogo Cardmarket, quindi solo nel sync).
     * @return CatalogCard? - carta normalizzata, oppure null se va scartata.
     */
    private suspend fun fetchCatalogCard(
        language: Language,
        tcgdexId: String,
        limiter: RateLimiter,
        setCache: HashMap<String, SetDto?>,
        cardmarketProducts: MutableSet<Int>? = null,
        lookupExpansion: Boolean = false,
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
        val catalogCard = CatalogMapper.toCatalogCard(language, card, set).copy(cardmarketProductHint = card.pricing?.cardmarket?.idProduct)
        if (!lookupExpansion) return catalogCard
        // Set e nome inglese su Cardmarket servono al link; se il catalogo non c'è si riprova al prossimo sync, senza perdere quello già salvato.
        val located = try {
            card.pricing?.cardmarket?.idProduct?.let { cardmarket.locate(it) }
        } catch (e: IOException) {
            null
        }
        val located2 = catalogCard.copy(cardmarketExpansionId = located?.first, cardmarketName = located?.second)
        if (located == null) return located2
        val others = otherCardmarketPrints(card, set, located2.variants, cardmarketProducts)
        cardmarketProducts?.addAll(others)
        return located2.copy(variants = located2.variants + others.map { CatalogVariant(VariantType.CARDMARKET_PRINT, it, it.toString()) })
    }

    /**
     * Altre stampe della carta che Cardmarket elenca e TCGdex no (stesso set e stessa metacard). Si cercano solo se la carta
     * è la sola con quel nome nel set: altrimenti un prodotto potrebbe essere di una carta sorella (le Energie base, le promo).
     * @param card CardDto - carta TCGdex.
     * @param set SetDto? - set della carta, con l'elenco delle sue carte.
     * @param variants List<CatalogVariant> - varianti già note, coi loro prodotti.
     * @param claimed Set<Int>? - prodotti già assegnati ad altre carte durante questo aggiornamento.
     * @return List<Int> - prodotti Cardmarket da aggiungere come stampe a parte.
     */
    private suspend fun otherCardmarketPrints(card: CardDto, set: SetDto?, variants: List<CatalogVariant>, claimed: Set<Int>?): List<Int> {
        val main = card.pricing?.cardmarket?.idProduct ?: return emptyList()
        if (set == null || set.cards.count { it.name.equals(card.name, ignoreCase = true) } != 1) return emptyList()
        val known = variants.mapNotNull { it.cardmarketId }.toSet() + card.variantsDetailed.orEmpty().mapNotNull { it.cardmarketId } + main + claimed.orEmpty()
        return try {
            cardmarket.otherPrintsOf(main).filter { it !in known }
        } catch (e: IOException) {
            emptyList()
        }
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
                    cardmarketExpansionId = card.cardmarketExpansionId ?: existing?.cardmarketExpansionId,
                    cardmarketName = card.cardmarketName ?: existing?.cardmarketName,
                    cardmarketProductHint = card.cardmarketProductHint ?: existing?.cardmarketProductHint,
                ),
            )
            // Senza la ricerca su Cardmarket (offline, aggiunta singola) le altre stampe Cardmarket già salvate restano.
            val keptExtras = if (card.cardmarketExpansionId != null) emptyList() else dao.variantsOf(listOf(card.id)).filter { it.type == VariantType.CARDMARKET_PRINT.name }.map { it.id }
            val ids = card.variants.map { Ids.variantId(card.id, it.type, it.print) }
            dao.insertVariants(card.variants.zip(ids).map { (v, id) -> CardVariantEntity(id, card.id, v.type.name, v.cardmarketId, v.print) })
            card.variants.zip(ids).forEach { (v, id) -> v.cardmarketId?.let { dao.setVariantProduct(id, it) } }
            moveStaleVariants(card.id, card.variants.map { it.type })
            dao.deleteStaleVariants(card.id, ids + keptExtras)
        }
    }

    /**
     * Sposta le copie di ogni finitura che il catalogo non elenca più su quella che la sostituisce (vedi [EntryMerge.replacement]),
     * così le correzioni del catalogo valgono anche per i dati salvati da versioni precedenti.
     * Se la variante esiste ancora o manca quella di arrivo, non cambia nulla.
     * @param cardId String - carta.
     * @param types List<VariantType> - varianti attuali della carta.
     * @return Unit
     */
    private suspend fun moveStaleVariants(cardId: String, types: List<VariantType>) {
        val user = db.collectionDao()
        for (stale in VariantType.entries) {
            val target = EntryMerge.replacement(stale, types) ?: continue
            val from = Ids.variantId(cardId, stale)
            val to = Ids.variantId(cardId, target)
            val entries = user.entriesOfVariant(from)
            if (entries.isEmpty()) continue
            entries.forEach { user.upsertEntry(EntryMerge.move(it, to, user.getEntry(it.collectionId, to))) }
            user.deleteEntriesOfVariant(from)
        }
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
