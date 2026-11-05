package com.robertobiggi.cardcollection.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.image.CatalogImageSources
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogUpdate
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

/** Phase of the catalog sync. */
enum class SyncPhase { SEARCH, CARD, IMAGES, IMAGES_OTHER }

/**
 * Progress of the catalog sync.
 * @property label text of the search or name of the card being processed (data, not sentences).
 */
data class SyncProgress(
    val phase: SyncPhase,
    val label: String,
    val done: Int,
    val total: Int,
    /** Images only: repeated attempts, images not downloaded and last error reason (HTTP code or network error type). */
    val retries: Int = 0,
    val failed: Int = 0,
    val note: String? = null,
)

/**
 * Outcome of the catalog sync. [emptySets] counts the sets (set collections) whose cards the database does not have.
 * [noCatalog] is true if the card database is not installed: nothing was done.
 */
data class SyncResult(val cardsSaved: Int, val imagesSaved: Int, val errors: Int, val emptySets: Int = 0, val noCatalog: Boolean = false)

/**
 * Set that can be offered for a set collection.
 * @property id id of the set (e.g. "base1", "SM10a").
 * @property name name of the set.
 * @property cards cards of the set, declared or in the database.
 * @property releaseDate release date (ISO, e.g. "2003-07-18"), if known.
 */
data class SetChoice(val id: String, val name: String, val cards: Int?, val releaseDate: String? = null, val code: String? = null)

/** Outcome of reading the list of sets. */
sealed interface SetListResult {
    /** Available sets, most recent first. */
    data class Found(val sets: List<SetChoice>) : SetListResult

    /** The card database is not installed. */
    data object NoCatalog : SetListResult
}

/** Outcome of the actions on a card's image. */
enum class ImageResult { DOWNLOADED, SAVED, NOT_AVAILABLE, NETWORK_ERROR, INVALID_IMAGE }

/** Outcome of the search for cards to add by hand. */
sealed interface SearchResult {
    /** Cards found (possibly an empty list). */
    data class Found(val cards: List<CardBrief>) : SearchResult

    /** Invalid search text. */
    data object InvalidQuery : SearchResult

    /** The card database is not installed. */
    data object NoCatalog : SearchResult
}

/** Outcome of adding a single card. */
sealed interface AddCardResult {
    /** Card [name] added. */
    data class Added(val name: String) : AddCardResult

    /** The card cannot be added (invalid id or absent from the database). */
    data object NotAllowed : AddCardResult

    /** The card database is not installed. */
    data object NoCatalog : AddCardResult
}

/**
 * Brings the cards of a collection's searches into the app's local database, reading them from the installed card database
 * ([CatalogStore]), and downloads their images. It no longer uses any remote catalog for cards.
 * It never changes the user's quantities, conditions or wanted slots.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val store: CatalogStore,
    private val images: ImageStore,
    private val settings: SettingsStore,
) {
    private val dao = db.catalogDao()

    /**
     * Runs the sync of the active searches of a collection: reads the cards from the card database, saves them and downloads the images.
     * @param collectionId Long - collection to update.
     * @param progress suspend (SyncProgress) -> Unit - progress callback.
     * @return SyncResult - cards and images saved, errors encountered.
     */
    suspend fun sync(collectionId: Long, progress: suspend (SyncProgress) -> Unit): SyncResult {
        val version = store.info.value?.generatedAt ?: return SyncResult(0, 0, 0, noCatalog = true)
        val queries = dao.enabledQueriesOf(collectionId)
        val refreshed = HashSet<String>()
        val byKey = HashMap<String, MutableList<String>>()
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
            val keys = imageKeys(cards)
            for ((i, card) in cards.withIndex()) {
                if (i % PROGRESS_EVERY == 0) progress(SyncProgress(SyncPhase.CARD, card.name, i, cards.size))
                saveCard(card, legacyQueryId = query.id, fresh = true, imageKey = keys[card.id])
                refreshed += card.id
                byKey.getOrPut(CatalogUpdate.replacementKey(card.language.code, card.name, card.localId)) { mutableListOf() } += card.id
                dao.insertMembership(CollectionCardEntity(collectionId, card.id, query.id))
                if (kind.isSet) applySetPolicy(collectionId, card, kind)
                saved++
            }
        }
        // Cards found by an active search and no longer in the database (removed sets, corrections, changed ids) stay in the collection, marked as
        // removed by the update: the screen lists them in a section at the end, with or without user data, so nothing disappears unnoticed. The
        // hidden ones and those from disabled searches stay as they are.
        val active = queries.map { it.id }.toSet()
        val stale = dao.membershipsOf(collectionId).filter { it.queryId in active && !it.hidden && it.cardId !in refreshed }.map { it.cardId }
        val missingOthers = refreshOtherCards(collectionId, refreshed)
        db.withTransaction {
            dao.clearRemoved(collectionId)
            for (id in (stale + missingOthers).distinct()) dao.markRemoved(collectionId, id, removedNote(id, byKey))
        }
        // Images only for this collection's cards: updating one collection must not download (or touch) those of the others.
        forgetMissingImageFiles(dao.visibleCardsOf(collectionId))
        val imagesSaved = downloadMissingImages(dao.visibleCardsOf(collectionId), progress)
        dao.setCatalogVersion(collectionId, version)
        settings.setLastCatalogSync(System.currentTimeMillis())
        return SyncResult(saved, imagesSaved, 0, emptySets)
    }

    /**
     * Why a card is no longer in the database: another card with the same name and number replaces it (its id changed), or nothing does.
     * @param cardId String - id of the card the database no longer lists.
     * @param byKey Map<String, List<String>> - ids of the cards found in this update, by [CatalogUpdate.replacementKey].
     * @return String? - `replaced:<id>` or null.
     */
    private suspend fun removedNote(cardId: String, byKey: Map<String, List<String>>): String? {
        val card = dao.getCard(cardId) ?: return null
        val replacement = CatalogUpdate.replacementOf(cardId, byKey[CatalogUpdate.replacementKey(card.language, card.name, card.localId)].orEmpty()) ?: return null
        return CatalogUpdate.REPLACED_PREFIX + replacement
    }

    /**
     * Text that records where the images of some cards come from in the installed database (see [CatalogUpdate.imageKey]).
     * @param cards List<CatalogCard> - cards read from the database.
     * @return Map<String, String> - key by card id.
     */
    private suspend fun imageKeys(cards: List<CatalogCard>): Map<String, String> {
        val ids = cards.map { it.id }
        val sites = store.use { cat -> CatalogImageSources.ALL.map { cat.imagePaths(it, ids) } }.orEmpty()
        return cards.associate { c -> c.id to CatalogUpdate.imageKey(c.imageBaseUrl, sites.map { it[c.id] }) }
    }

    /**
     * Searches the card database, to add cards by hand to a collection.
     * If the text contains a card number (e.g. "M3 117/080") it searches by printed code, otherwise by name (also in another language).
     * @param language Language - catalog language.
     * @param text String - name or code to search.
     * @return SearchResult - results, or the reason for the failure.
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
     * List of the sets of a language from which to create a set collection.
     * @param language Language - catalog language.
     * @return SetListResult - sets found, or database missing.
     */
    suspend fun setChoices(language: Language): SetListResult {
        val sets = store.use { it.sets(language) } ?: return SetListResult.NoCatalog
        return SetListResult.Found(sets.filter { Validators.isValidRemoteId(it.id) }.map { SetChoice(it.id, it.name, it.declared ?: it.cards, it.releaseDate, it.code) })
    }

    /**
     * Updates from the database the cards of the collection that no search has updated (added by hand, imported from a file,
     * removed searches), so every sync fixes the data saved by earlier versions, even if you own copies.
     * @param collectionId Long - collection.
     * @param refreshed Set<String> - ids of the cards already updated in this sync.
     * @return List<String> - ids of those the database no longer has (they stay in the collection, marked as removed).
     */
    private suspend fun refreshOtherCards(collectionId: Long, refreshed: Set<String>): List<String> {
        val others = dao.membershipsOf(collectionId).filter { !it.hidden }.map { it.cardId }.filter { it !in refreshed }
        if (others.isEmpty()) return emptyList()
        val cards = store.use { cat -> others.mapNotNull { cat.card(it) } }.orEmpty()
        val keys = imageKeys(cards)
        for (card in cards) saveCard(card, dao.getCard(card.id)?.queryId ?: 0, fresh = true, imageKey = keys[card.id])
        val found = cards.map { it.id }.toSet()
        return others.filter { it !in found }
    }

    /**
     * Adds a card of the database to a collection by hand (even if no search finds it).
     * @param collectionId Long - collection.
     * @param language Language - catalog language.
     * @param tcgdexId String - id of the card (without the language).
     * @return AddCardResult - outcome of the addition.
     */
    suspend fun addSingleCard(collectionId: Long, language: Language, tcgdexId: String): AddCardResult {
        if (!Validators.isValidRemoteId(tcgdexId)) return AddCardResult.NotAllowed
        val card = store.use { it.card("${language.code}:$tcgdexId") }
        if (card == null) return if (store.info.value == null) AddCardResult.NoCatalog else AddCardResult.NotAllowed
        saveCard(card, legacyQueryId = 0)
        if (dao.insertMembership(CollectionCardEntity(collectionId, card.id, null)) == -1L) dao.makeManual(collectionId, card.id)
        downloadMissingImages(listOfNotNull(dao.getCard(card.id))) { }
        return AddCardResult.Added(card.name)
    }

    /**
     * Uses an image chosen by the user as the card's image. It stays until the official one is
     * downloaded; the catalog sync does not replace it.
     * @param cardId String - local id of the card.
     * @param uri Uri - image chosen with the system picker.
     * @return ImageResult - SAVED or INVALID_IMAGE.
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
     * Marks as "I don't want it" the variants that the set collection's level excludes. Entries already present
     * (quantity or user choices) stay unchanged.
     * @param collectionId Long - collection.
     * @param card CatalogCard - card just saved.
     * @param kind QueryKind - base set or master set.
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
     * Saves a catalog card and its variants, keeping the image paths already present.
     * @param card CatalogCard - card read from the card database.
     * @param legacyQueryId Long - value of the historical queryId field.
     * @param fresh Boolean - true for the update from the card database: what the database says replaces what was saved (a missing value does not
     *   keep the old one), and the downloaded images are dropped if the database now points to others ([imageKey] changed). False for cards
     *   imported from a file, which may lack data: what is already saved stays.
     * @param imageKey String? - image sources of the card in the database now ([CatalogUpdate.imageKey]), only with [fresh].
     * @return Unit
     */
    suspend fun saveCard(card: CatalogCard, legacyQueryId: Long, fresh: Boolean = false, imageKey: String? = null) {
        db.withTransaction {
            val existing = dao.getCard(card.id)
            val dropImages = fresh && imageKey != null && CatalogUpdate.imagesChanged(existing?.imageKey, imageKey)
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
                    // Cards imported from a file may lack number and rarity: those already saved stay.
                    printedNumber = card.printedNumber.ifEmpty { existing?.printedNumber.orEmpty() },
                    rarity = if (fresh) card.rarity else card.rarity ?: existing?.rarity,
                    releaseDate = card.releaseDate,
                    illustrator = card.illustrator,
                    imageUrl = if (fresh) card.imageBaseUrl else card.imageBaseUrl ?: existing?.imageUrl,
                    imageLowPath = if (dropImages) null else existing?.imageLowPath,
                    imageHighPath = if (dropImages) null else existing?.imageHighPath,
                    queryId = existing?.queryId ?: legacyQueryId,
                    catalogUpdatedAt = System.currentTimeMillis(),
                    dexId = if (fresh) card.dexId else card.dexId ?: existing?.dexId,
                    cardTraderCondition = existing?.cardTraderCondition,
                    cardmarketId = if (fresh) card.cardmarketId else card.cardmarketId ?: existing?.cardmarketId,
                    cardTraderBlueprintId = if (fresh) card.cardTraderBlueprintId else card.cardTraderBlueprintId ?: existing?.cardTraderBlueprintId,
                    cardmarketExpansionId = if (fresh) card.cardmarketExpansionId else card.cardmarketExpansionId ?: existing?.cardmarketExpansionId,
                    cardmarketName = if (fresh) card.cardmarketName else card.cardmarketName ?: existing?.cardmarketName,
                    cardmarketProductHint = if (fresh) card.cardmarketProductHint else card.cardmarketProductHint ?: existing?.cardmarketProductHint,
                    cardmarketProduct = if (fresh) card.cardmarketProduct else card.cardmarketProduct ?: existing?.cardmarketProduct,
                    cardTraderExpansionId = if (fresh) card.cardTraderExpansionId else card.cardTraderExpansionId ?: existing?.cardTraderExpansionId,
                    imageKey = imageKey ?: existing?.imageKey,
                ),
            )
            val ids = card.variants.map { Ids.variantId(card.id, it.type, it.print) }
            dao.insertVariants(card.variants.zip(ids).map { (v, id) -> CardVariantEntity(id, card.id, v.type.name, v.cardmarketId, v.print, v.cardTraderBlueprintId) })
            card.variants.zip(ids).forEach { (v, id) ->
                if (fresh) {
                    dao.setVariantRefs(id, v.cardmarketId, v.cardTraderBlueprintId)
                } else {
                    v.cardmarketId?.let { dao.setVariantProduct(id, it) }
                    v.cardTraderBlueprintId?.let { dao.setVariantBlueprint(id, it) }
                }
            }
            moveStaleVariants(card.id, card.variants.map { it.type })
            dao.deleteStaleVariants(card.id, ids)
        }
    }

    /**
     * Moves the copies of each finish that the catalog no longer lists to the one that replaces it (see [EntryMerge.replacement]),
     * so catalog corrections also apply to data saved by earlier versions.
     * If the variant still exists or the target one is missing, nothing changes.
     * @param cardId String - card.
     * @param types List<VariantType> - current variants of the card.
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
     * Downloads the missing images (low and high quality) for all saved cards.
     * @param progress suspend (SyncProgress) -> Unit - progress callback.
     * @return Int - number of cards for which at least one new image was saved.
     */
    private suspend fun downloadMissingImages(cards: List<CardEntity>, progress: suspend (SyncProgress) -> Unit): Int {
        val missing = cards.filter { it.imageUrl != null && (it.imageLowPath == null || it.imageHighPath == null) }
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
                                // Only the missing paths: another job (manually chosen image, CardTrader) may have saved one in the meantime.
                                dao.fillImagePaths(card.id, low, high)
                                count++
                            }
                            // 404: TCGdex does not have this card's image (happens for many old Japanese sets), it is not a network error.
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
     * Forgets the image paths whose file is no longer there, so [downloadMissingImages] (and the CardTrader images) download
     * them again. Happens after a reinstall: the Android backup restores the database but not the images.
     * @return Int - number of cards with at least one forgotten path.
     */
    private suspend fun forgetMissingImageFiles(cards: List<CardEntity>): Int = withContext(Dispatchers.IO) {
        var forgotten = 0
        for (card in cards.filter { it.imageLowPath != null || it.imageHighPath != null }) {
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

        /** Images downloaded together: with only one at a time, thousands of cards take hours. */
        const val IMAGE_PARALLELISM = 4
    }
}
