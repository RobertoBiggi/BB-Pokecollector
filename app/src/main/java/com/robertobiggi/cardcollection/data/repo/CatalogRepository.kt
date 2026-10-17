package com.robertobiggi.cardcollection.data.repo

import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.tcgdex.CatalogMapper
import com.robertobiggi.cardcollection.core.tcgdex.SetDto
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
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
 * Scarica il catalogo delle ricerche attive da TCGdex e lo salva in locale, immagini comprese.
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
     * Esegue il sync completo: ricerca, dettaglio carte, set e immagini.
     * @param progress suspend (SyncProgress) -> Unit - callback di avanzamento.
     * @return SyncResult - carte e immagini salvate, errori incontrati.
     */
    suspend fun sync(progress: suspend (SyncProgress) -> Unit): SyncResult {
        val errors = mutableListOf<String>()
        val limiter = RateLimiter(REQUEST_INTERVAL_MS)
        val setCache = HashMap<String, SetDto?>()
        var saved = 0
        val queries = dao.enabledQueries()
        for ((qIndex, query) in queries.withIndex()) {
            val language = Language.fromCode(query.language) ?: continue
            progress(SyncProgress("Cerco \"${query.pattern}\" (${language.code.uppercase()})", qIndex, queries.size))
            val briefs = try {
                limiter.acquire()
                withRetry { client.searchCards(language, query.pattern) }
            } catch (e: Exception) {
                if (e !is ApiException && e !is IOException && e !is InvalidResponseException && e !is IllegalArgumentException) throw e
                errors += "ricerca \"${query.pattern}\": ${e.message}"
                continue
            }
            val candidates = briefs.filterNot { CatalogMapper.isDigitalOnly(it) }.filter { Validators.isValidRemoteId(it.id) }
            for ((index, brief) in candidates.withIndex()) {
                progress(SyncProgress("${brief.name} (${index + 1}/${candidates.size})", index, candidates.size))
                try {
                    limiter.acquire()
                    val card = withRetry { client.getCard(language, brief.id) }
                    val setId = card.set.id
                    val set = if (Validators.isValidRemoteId(setId)) {
                        setCache.getOrPut("${language.code}/$setId") {
                            runCatching { limiter.acquire(); withRetry { client.getSet(language, setId) } }.getOrNull()
                        }
                    } else {
                        null
                    }
                    if (!CatalogMapper.isTrackablePokemon(card, set)) continue
                    save(CatalogMapper.toCatalogCard(language, card, set), query.id)
                    saved++
                } catch (e: Exception) {
                    if (e !is ApiException && e !is IOException && e !is InvalidResponseException && e !is IllegalArgumentException) throw e
                    errors += "${brief.id}: ${e.message}"
                }
            }
        }
        val imagesSaved = downloadMissingImages(progress)
        if (errors.isEmpty() || saved > 0) settings.setLastCatalogSync(System.currentTimeMillis())
        return SyncResult(saved, imagesSaved, errors)
    }

    /**
     * Salva una carta e le sue varianti mantenendo i percorsi delle immagini già scaricate.
     * @param card CatalogCard - carta normalizzata.
     * @param queryId Long - ricerca che l'ha trovata.
     * @return Unit
     */
    private suspend fun save(card: CatalogCard, queryId: Long) {
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
                    imageUrl = card.imageBaseUrl,
                    imageLowPath = existing?.imageLowPath,
                    imageHighPath = existing?.imageHighPath,
                    queryId = queryId,
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

    private companion object {
        const val REQUEST_INTERVAL_MS = 250L
        const val RETRY_DELAY_MS = 5_000L
    }
}
