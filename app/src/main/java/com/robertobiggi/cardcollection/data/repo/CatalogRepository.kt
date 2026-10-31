package com.robertobiggi.cardcollection.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.Ids
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.core.model.SetCollectionPolicy
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.tcgdex.CardCodeSearch
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.catalog.CardBrief
import com.robertobiggi.cardcollection.data.catalog.CatalogStore
import com.robertobiggi.cardcollection.data.image.DownloadIssue
import com.robertobiggi.cardcollection.data.image.ImagePaths
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionCardEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Fase del sync del catalogo. */
enum class SyncPhase { SEARCH, CARD, IMAGES, IMAGES_CARDTRADER }

/**
 * Avanzamento del sync del catalogo.
 * @property label testo della ricerca o nome della carta in lavorazione (dati, non frasi).
 */
data class SyncProgress(
    val phase: SyncPhase,
    val label: String,
    val done: Int,
    val total: Int,
    /** Solo per le immagini: tentativi ripetuti, immagini non scaricate e ultimo motivo di errore (codice HTTP o tipo di errore di rete). */
    val retries: Int = 0,
    val failed: Int = 0,
    val note: String? = null,
)

/**
 * Esito del sync del catalogo. [emptySets] conta i set (collezioni per set) di cui il database non ha le carte.
 * [noCatalog] è true se il database delle carte non è installato: non è stato fatto nulla.
 */
data class SyncResult(val cardsSaved: Int, val imagesSaved: Int, val errors: Int, val emptySets: Int = 0, val noCatalog: Boolean = false)

/**
 * Set proponibile per una collezione per set.
 * @property id id del set (es. "base1", "SM10a").
 * @property name nome del set.
 * @property cards carte del set, dichiarate o nel database.
 * @property releaseDate data di uscita (ISO, es. "2003-07-18"), se nota.
 */
data class SetChoice(val id: String, val name: String, val cards: Int?, val releaseDate: String? = null, val code: String? = null)

/** Esito della lettura dell'elenco dei set. */
sealed interface SetListResult {
    /** Set disponibili, i più recenti per primi. */
    data class Found(val sets: List<SetChoice>) : SetListResult

    /** Il database delle carte non è installato. */
    data object NoCatalog : SetListResult
}

/** Esito delle azioni sull'immagine di una carta. */
enum class ImageResult { DOWNLOADED, SAVED, NOT_AVAILABLE, NETWORK_ERROR, INVALID_IMAGE }

/** Esito della ricerca di carte da aggiungere a mano. */
sealed interface SearchResult {
    /** Carte trovate (lista eventualmente vuota). */
    data class Found(val cards: List<CardBrief>) : SearchResult

    /** Testo di ricerca non valido. */
    data object InvalidQuery : SearchResult

    /** Il database delle carte non è installato. */
    data object NoCatalog : SearchResult
}

/** Esito dell'aggiunta di una carta singola. */
sealed interface AddCardResult {
    /** Carta [name] aggiunta. */
    data class Added(val name: String) : AddCardResult

    /** La carta non si può aggiungere (id non valido o assente dal database). */
    data object NotAllowed : AddCardResult

    /** Il database delle carte non è installato. */
    data object NoCatalog : AddCardResult
}

/**
 * Porta nel database locale dell'app le carte delle ricerche di una collezione, leggendole dal database delle carte installato
 * ([CatalogStore]), e ne scarica le immagini. Non usa più nessun catalogo remoto per le carte.
 * Non modifica mai quantità, condizioni o slot desiderati dell'utente.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val store: CatalogStore,
    private val images: ImageStore,
    private val settings: SettingsStore,
) {
    private val dao = db.catalogDao()

    /**
     * Esegue il sync delle ricerche attive di una collezione: legge le carte dal database delle carte, le salva e scarica le immagini.
     * @param collectionId Long - collezione da aggiornare.
     * @param progress suspend (SyncProgress) -> Unit - callback di avanzamento.
     * @return SyncResult - carte e immagini salvate, errori incontrati.
     */
    suspend fun sync(collectionId: Long, progress: suspend (SyncProgress) -> Unit): SyncResult {
        if (store.info.value == null) return SyncResult(0, 0, 0, noCatalog = true)
        val queries = dao.enabledQueriesOf(collectionId)
        val refreshed = HashSet<String>()
        var saved = 0
        var emptySets = 0
        for ((index, query) in queries.withIndex()) {
            val language = Language.fromCode(query.language) ?: continue
            val kind = QueryKind.fromCode(query.kind) ?: continue
            progress(SyncProgress(SyncPhase.SEARCH, query.pattern, index, queries.size))
            val cards = store.use { cat ->
                if (kind.isSet) cat.cardsOfSet(language, query.pattern.trim()) else cat.cardsByName(language, query.pattern.trim())
            } ?: return SyncResult(saved, 0, 0, noCatalog = true)
            if (kind.isSet && cards.isEmpty()) emptySets++
            for ((i, card) in cards.withIndex()) {
                if (i % PROGRESS_EVERY == 0) progress(SyncProgress(SyncPhase.CARD, card.name, i, cards.size))
                saveCard(card, legacyQueryId = query.id)
                refreshed += card.id
                dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                if (kind.isSet) applySetPolicy(collectionId, card, kind)
                saved++
            }
        }
        // Le carte trovate da una ricerca attiva e non più nel database (set tolti, correzioni) escono dalla collezione;
        // restano quelle nascoste, aggiunte a mano o di ricerche disattivate.
        val active = queries.map { it.id }.toSet()
        val stale = dao.membershipsOf(collectionId).filter { it.queryId in active && !it.hidden && it.cardId !in refreshed }.map { it.cardId }
        if (stale.isNotEmpty()) dao.dropStaleMemberships(collectionId, stale)
        refreshOtherCards(collectionId, refreshed)
        forgetMissingImageFiles()
        val imagesSaved = downloadMissingImages(progress)
        settings.setLastCatalogSync(System.currentTimeMillis())
        return SyncResult(saved, imagesSaved, 0, emptySets)
    }

    /**
     * Cerca nel database delle carte, per aggiungerle a mano a una collezione.
     * Se il testo contiene un numero di carta (es. "M3 117/080") cerca per codice stampato, altrimenti per nome (anche in un'altra lingua).
     * @param language Language - lingua del catalogo.
     * @param text String - nome o codice da cercare.
     * @return SearchResult - risultati, oppure il motivo del fallimento.
     */
    suspend fun search(language: Language, text: String): SearchResult {
        val code = CardCodeSearch.parse(text)
        if (code == null && !Validators.isValidQueryPattern(text)) return SearchResult.InvalidQuery
        val found = store.use { cat ->
            if (code != null) cat.cardsByCode(language, code.number, code.total, code.tokens) else cat.cardsByName(language, text.trim())
        } ?: return SearchResult.NoCatalog
        return SearchResult.Found(
            found.filter { Validators.isValidRemoteId(it.tcgdexId) }.take(MAX_SEARCH_RESULTS)
                .map { CardBrief(it.tcgdexId, it.localId, it.name, it.setName, it.imageBaseUrl) },
        )
    }

    /**
     * Elenco dei set di una lingua da cui creare una collezione per set.
     * @param language Language - lingua del catalogo.
     * @return SetListResult - set trovati, oppure database assente.
     */
    suspend fun setChoices(language: Language): SetListResult {
        val sets = store.use { it.sets(language) } ?: return SetListResult.NoCatalog
        return SetListResult.Found(sets.filter { Validators.isValidRemoteId(it.id) }.map { SetChoice(it.id, it.name, it.declared ?: it.cards, it.releaseDate, it.code) })
    }

    /**
     * Aggiorna dal database le carte della collezione che nessuna ricerca ha aggiornato (aggiunte a mano, importate da un file,
     * ricerche tolte), così ogni sync corregge i dati salvati da versioni precedenti, anche se possiedi copie.
     * @param collectionId Long - collezione.
     * @param refreshed Set<String> - id delle carte già aggiornate in questo sync.
     * @return Unit
     */
    private suspend fun refreshOtherCards(collectionId: Long, refreshed: Set<String>) {
        val others = dao.membershipsOf(collectionId).map { it.cardId }.filter { it !in refreshed }
        if (others.isEmpty()) return
        val cards = store.use { cat -> others.mapNotNull { cat.card(it) } }.orEmpty()
        for (card in cards) saveCard(card, dao.getCard(card.id)?.queryId ?: 0)
    }

    /**
     * Aggiunge a mano una carta del database a una collezione (anche se nessuna ricerca la trova).
     * @param collectionId Long - collezione.
     * @param language Language - lingua del catalogo.
     * @param tcgdexId String - id della carta (senza la lingua).
     * @return AddCardResult - esito dell'aggiunta.
     */
    suspend fun addSingleCard(collectionId: Long, language: Language, tcgdexId: String): AddCardResult {
        if (!Validators.isValidRemoteId(tcgdexId)) return AddCardResult.NotAllowed
        val card = store.use { it.card("${language.code}:$tcgdexId") }
        if (card == null) return if (store.info.value == null) AddCardResult.NoCatalog else AddCardResult.NotAllowed
        saveCard(card, legacyQueryId = 0)
        if (dao.insertMembership(CollectionCardEntity(collectionId, card.id, null)) == -1L) dao.makeManual(collectionId, card.id)
        downloadMissingImages { }
        return AddCardResult.Added(card.name)
    }

    /**
     * Scarica l'immagine ufficiale di una carta (sostituisce quella scelta a mano).
     * @param cardId String - id locale della carta.
     * @return ImageResult - DOWNLOADED, NOT_AVAILABLE se la carta non ha un'immagine ufficiale, NETWORK_ERROR.
     */
    suspend fun downloadImage(cardId: String): ImageResult {
        val stored = dao.getCard(cardId) ?: return ImageResult.NOT_AVAILABLE
        val url = stored.imageUrl ?: return ImageResult.NOT_AVAILABLE
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
     * Salva una carta del catalogo e le sue varianti, mantenendo i percorsi delle immagini già presenti.
     * @param card CatalogCard - carta letta dal database delle carte.
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
                    // Le carte importate da un file possono non avere numero e rarità: restano quelli già salvati.
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
                    cardTraderBlueprintId = card.cardTraderBlueprintId ?: existing?.cardTraderBlueprintId,
                    cardmarketExpansionId = card.cardmarketExpansionId ?: existing?.cardmarketExpansionId,
                    cardmarketName = card.cardmarketName ?: existing?.cardmarketName,
                    cardmarketProductHint = card.cardmarketProductHint ?: existing?.cardmarketProductHint,
                    cardmarketProduct = card.cardmarketProduct ?: existing?.cardmarketProduct,
                    cardTraderExpansionId = card.cardTraderExpansionId ?: existing?.cardTraderExpansionId,
                ),
            )
            val ids = card.variants.map { Ids.variantId(card.id, it.type, it.print) }
            dao.insertVariants(card.variants.zip(ids).map { (v, id) -> CardVariantEntity(id, card.id, v.type.name, v.cardmarketId, v.print, v.cardTraderBlueprintId) })
            card.variants.zip(ids).forEach { (v, id) ->
                v.cardmarketId?.let { dao.setVariantProduct(id, it) }
                v.cardTraderBlueprintId?.let { dao.setVariantBlueprint(id, it) }
            }
            moveStaleVariants(card.id, card.variants.map { it.type })
            dao.deleteStaleVariants(card.id, ids)
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
        val missing = dao.cardsMissingImages().filter { it.imageUrl != null }
        val lock = Mutex()
        val semaphore = Semaphore(IMAGE_PARALLELISM)
        var count = 0
        var started = 0
        val retries = AtomicInteger()
        val note = AtomicReference<String?>(null)
        var failed = 0
        coroutineScope {
            for (card in missing) {
                launch {
                    semaphore.withPermit {
                        val url = card.imageUrl ?: return@withPermit
                        lock.withLock { started++; progress(SyncProgress(SyncPhase.IMAGES, card.name, started - 1, missing.size, retries.get(), failed, note.get())) }
                        var notFound = false
                        val onIssue = { issue: DownloadIssue ->
                            if (issue.reason == HTTP_NOT_FOUND) notFound = true else note.set(issue.reason)
                            if (issue.willRetry) retries.incrementAndGet()
                        }
                        val low = card.imageLowPath ?: images.ensure(card.id, url, ImageStore.Quality.LOW, onIssue)
                        val high = card.imageHighPath ?: images.ensure(card.id, url, ImageStore.Quality.HIGH, onIssue)
                        lock.withLock {
                            if (low != card.imageLowPath || high != card.imageHighPath) {
                                // Solo i percorsi mancanti: un altro lavoro (immagine scelta a mano, CardTrader) può averne salvato uno nel frattempo.
                                dao.fillImagePaths(card.id, low, high)
                                count++
                            }
                            // 404: TCGdex non ha l'immagine di questa carta (succede per molti set giapponesi vecchi), non è un errore di rete.
                            if (low == null && high == null && notFound) dao.clearImageUrl(card.id)
                            else if (low == null || high == null) failed++
                        }
                    }
                }
            }
        }
        return count
    }

    /**
     * Dimentica i percorsi d'immagine il cui file non c'è più, così [downloadMissingImages] (e le immagini CardTrader) le
     * riscaricano. Succede dopo una reinstallazione: il backup Android ripristina il database ma non le immagini.
     * @return Int - numero di carte con almeno un percorso dimenticato.
     */
    private suspend fun forgetMissingImageFiles(): Int = withContext(Dispatchers.IO) {
        var forgotten = 0
        for (card in dao.cardsWithImagePaths()) {
            val missing = ImagePaths.missing(card.imageLowPath, card.imageHighPath, images::isSaved)
            missing.forEach { dao.forgetImagePath(card.id, it) }
            if (missing.isNotEmpty()) forgotten++
        }
        forgotten
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 60
        const val PROGRESS_EVERY = 25
        const val HTTP_NOT_FOUND = "HTTP 404"

        /** Immagini scaricate insieme: con una sola alla volta migliaia di carte richiedono ore. */
        const val IMAGE_PARALLELISM = 4
    }
}
