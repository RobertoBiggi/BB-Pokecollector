package com.robertobiggi.cardcollection.data.repo

import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.tcgdex.CardBriefDto
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

/** Avanzamento del sync del catalogo. */
data class SyncProgress(val message: String, val done: Int, val total: Int)

/** Esito del sync del catalogo. */
data class SyncResult(val cardsSaved: Int, val imagesSaved: Int, val errors: List<String>) {
    /**
     * Riassunto per l'utente.
     * @return String - frase breve con carte, immagini ed eventuali errori.
     */
    fun summary(): String = buildString {
        append("$cardsSaved carte aggiornate, $imagesSaved immagini scaricate")
        if (errors.isNotEmpty()) append(" · ${errors.size} problemi: ${errors.first()}")
    }
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
        val errors = mutableListOf<String>()
        val limiter = RateLimiter(REQUEST_INTERVAL_MS)
        val setCache = HashMap<String, SetDto?>()
        var saved = 0
        val queries = dao.enabledQueriesOf(collectionId)
        for ((qIndex, query) in queries.withIndex()) {
            val language = Language.fromCode(query.language) ?: continue
            progress(SyncProgress("Cerco \"${query.pattern}\" (${language.code.uppercase()})", qIndex, queries.size))
            val briefs = try {
                limiter.acquire()
                withRetry { client.searchCards(language, query.pattern) }
            } catch (e: Exception) {
                if (!isRecoverable(e)) throw e
                errors += "ricerca \"${query.pattern}\": ${e.message}"
                continue
            }
            val candidates = briefs.filterNot { CatalogMapper.isDigitalOnly(it) }.filter { Validators.isValidRemoteId(it.id) }
            for ((index, brief) in candidates.withIndex()) {
                progress(SyncProgress("${brief.name} (${index + 1}/${candidates.size})", index, candidates.size))
                try {
                    val card = fetchCatalogCard(language, brief.id, limiter, setCache) ?: continue
                    saveCard(card, legacyQueryId = query.id)
                    dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                    saved++
                } catch (e: Exception) {
                    if (!isRecoverable(e)) throw e
                    errors += "${brief.id}: ${e.message}"
                }
            }
        }
        val imagesSaved = downloadMissingImages(progress)
        if (errors.isEmpty() || saved > 0) settings.setLastCatalogSync(System.currentTimeMillis())
        return SyncResult(saved, imagesSaved, errors)
    }

    /**
     * Cerca carte su TCGdex per aggiungerle a mano a una collezione.
     * @param language Language - lingua del catalogo.
     * @param text String - testo da cercare nel nome.
     * @return Result<List<CardBriefDto>> - risultati senza carte digitali, oppure errore con messaggio.
     */
    suspend fun search(language: Language, text: String): Result<List<CardBriefDto>> {
        if (!Validators.isValidQueryPattern(text)) return Result.failure(IllegalArgumentException("Scrivi da 2 a 40 caratteri validi."))
        return try {
            Result.success(
                client.searchCards(language, text)
                    .filterNot { CatalogMapper.isDigitalOnly(it) }
                    .filter { Validators.isValidRemoteId(it.id) }
                    .take(MAX_SEARCH_RESULTS),
            )
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            Result.failure(IOException("Ricerca non riuscita: controlla la connessione."))
        }
    }

    /**
     * Aggiunge a mano una carta TCGdex a una collezione (anche se nessuna ricerca la trova).
     * @param collectionId Long - collezione.
     * @param language Language - lingua del catalogo.
     * @param tcgdexId String - id TCGdex della carta.
     * @return String - messaggio di esito per l'utente.
     */
    suspend fun addSingleCard(collectionId: Long, language: Language, tcgdexId: String): String {
        if (!Validators.isValidRemoteId(tcgdexId)) return "Carta non valida."
        return try {
            val card = fetchCatalogCard(language, tcgdexId, RateLimiter(0), HashMap(), requirePokemon = false)
                ?: return "Questa carta non si può aggiungere."
            saveCard(card, legacyQueryId = 0)
            if (dao.insertMembership(CollectionCardEntity(collectionId, card.id, null)) == -1L) dao.makeManual(collectionId, card.id)
            downloadMissingImages { }
            "${card.name} aggiunta alla collezione."
        } catch (e: Exception) {
            if (!isRecoverable(e)) throw e
            "Non riesco a scaricare la carta: controlla la connessione."
        }
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
            progress(SyncProgress("Immagini ${index + 1}/${missing.size}", index, missing.size))
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
