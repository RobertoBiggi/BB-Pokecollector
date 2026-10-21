package com.robertobiggi.cardcollection.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
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
import kotlinx.coroutines.delay
import java.io.IOException

/** Fase del sync del catalogo. */
enum class SyncPhase { SEARCH, CARD, IMAGES }

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
 * Non modifica mai quantità, condizioni o slot desiderati dell'utente.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val client: TcgdexClient,
    private val images: ImageStore,
    private val settings: SettingsStore,
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
        var saved = 0
        val queries = dao.enabledQueriesOf(collectionId)
        for ((qIndex, query) in queries.withIndex()) {
            val language = Language.fromCode(query.language) ?: continue
            progress(SyncProgress(SyncPhase.SEARCH, query.pattern, qIndex, queries.size))
            val briefs = try {
                limiter.acquire()
                withRetry { client.searchCards(language, query.pattern) }
            } catch (e: Exception) {
                if (!isRecoverable(e)) throw e
                errors++
                continue
            }
            val candidates = briefs.filterNot { CatalogMapper.isDigitalOnly(it) }.filter { Validators.isValidRemoteId(it.id) }
            for ((index, brief) in candidates.withIndex()) {
                progress(SyncProgress(SyncPhase.CARD, brief.name, index, candidates.size))
                try {
                    val card = fetchCatalogCard(language, brief.id, limiter, setCache) ?: continue
                    saveCard(card, legacyQueryId = query.id)
                    dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                    saved++
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors++
                }
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
            val cards = if (code != null) CardCodeSearch.find(client, language, code) else client.searchCards(language, text)
            SearchResult.Found(
                cards
                    .filterNot { CatalogMapper.isDigitalOnly(it) }
                    .filter { Validators.isValidRemoteId(it.id) }
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
            val card = fetchCatalogCard(language, tcgdexId, RateLimiter(0), HashMap(), requirePokemon = false)
                ?: return AddCardResult.NotAllowed
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
            fetchCatalogCard(language, stored.tcgdexId, RateLimiter(0), HashMap(), requirePokemon = false)?.let { saveCard(it, stored.queryId) }
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
     * @param requirePokemon Boolean - true per scartare Allenatori, Energie e carte digitali.
     * @return CatalogCard? - carta normalizzata, oppure null se va scartata.
     */
    private suspend fun fetchCatalogCard(
        language: Language,
        tcgdexId: String,
        limiter: RateLimiter,
        setCache: HashMap<String, SetDto?>,
        requirePokemon: Boolean = true,
    ): CatalogCard? {
        limiter.acquire()
        val card = withRetry { client.getCard(language, tcgdexId) }
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
        if (requirePokemon && !CatalogMapper.isTrackablePokemon(card, set)) return null
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
                    printedNumber = card.printedNumber,
                    rarity = card.rarity,
                    releaseDate = card.releaseDate,
                    illustrator = card.illustrator,
                    imageUrl = card.imageBaseUrl ?: existing?.imageUrl,
                    imageLowPath = existing?.imageLowPath,
                    imageHighPath = existing?.imageHighPath,
                    queryId = existing?.queryId ?: legacyQueryId,
                    catalogUpdatedAt = System.currentTimeMillis(),
                    dexId = card.dexId ?: existing?.dexId,
                    cardTraderCondition = existing?.cardTraderCondition,
                ),
            )
            dao.insertVariants(card.variants.map { CardVariantEntity(Ids.variantId(card.id, it.type), card.id, it.type.name) })
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
    private fun isRecoverable(e: Exception): Boolean =
        e is ApiException || e is IOException || e is InvalidResponseException || e is IllegalArgumentException

    private companion object {
        const val REQUEST_INTERVAL_MS = 250L
        const val RETRY_DELAY_MS = 5_000L
        const val MAX_SEARCH_RESULTS = 60
    }
}
