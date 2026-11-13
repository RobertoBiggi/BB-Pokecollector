package com.robertobiggi.cardcollection.data.repo

import androidx.room.withTransaction
import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.CollectionLanguages
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.LanguageQuery
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.CollectionCardEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntity
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import com.robertobiggi.cardcollection.data.settings.PriceVisibility
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** Error in data entered by the user; the UI translates it into a message. */
enum class InputError { NAME_EMPTY, QUERY_INVALID, QUERY_DUPLICATE }

/**
 * Editable settings of a collection.
 * @property name name.
 * @property languages languages of the searches.
 * @property canAddLanguage true if there is at least one search by name to copy into a new language.
 */
data class CollectionSettings(val name: String, val languages: Set<Language>, val canAddLanguage: Boolean)

/** Outcome of saving a collection's settings. */
sealed interface SettingsResult {
    /** Saved; [needsSync] is true if there are new searches to look up in the catalog. */
    data class Saved(val needsSync: Boolean) : SettingsResult

    /** Invalid values. */
    data class Invalid(val error: InputError) : SettingsResult
}

/** Outcome of creating a collection. */
sealed interface CreateResult {
    /** Collection created with id [id]. */
    data class Created(val id: Long) : CreateResult

    /** Invalid data. */
    data class Invalid(val error: InputError) : CreateResult
}

/**
 * Collections, library and user changes (quantity, condition, slot, searches, cards).
 *
 * @param db the app's database.
 * @param scope application scope, in which the database snapshot is shared.
 * @param settings settings: they decide which prices to show.
 */
class CollectionRepository(private val db: AppDatabase, scope: CoroutineScope, private val settings: SettingsStore, private val images: ImageStore) {

    private val catalogDao = db.catalogDao()
    private val collectionDao = db.collectionDao()
    private val priceDao = db.priceDao()

    /**
     * Snapshot of the observed tables: updated at every change of the database. It is computed only
     * once for all observers, off the UI thread; during price updates
     * close-together changes are merged.
     */
    private val state: Flow<DbState> = combine(
        combine(catalogDao.observeCollections(), catalogDao.observeMemberships(), catalogDao.observeAllQueries(), catalogDao.observeCards()) { c, m, q, cards ->
            listOf<Any>(c, m, q, cards)
        },
        combine(catalogDao.observeVariants(), collectionDao.observeEntries(), priceDao.observeSnapshots(), priceDao.observeRuns()) { v, e, s, r ->
            listOf<Any>(v, e, s, r)
        },
    ) { a, b ->
        @Suppress("UNCHECKED_CAST")
        DbState(
            collections = a[0] as List<CollectionEntity>,
            memberships = a[1] as List<CollectionCardEntity>,
            queries = a[2] as List<TrackedQueryEntity>,
            cards = a[3] as List<CardEntity>,
            variants = b[0] as List<CardVariantEntity>,
            entries = b[1] as List<CollectionEntryEntity>,
            snapshots = b[2] as List<PriceSnapshotEntity>,
            runs = b[3] as List<PriceRunEntity>,
        )
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    /**
     * Snapshot with the user's price visibility and 1st Edition choice applied. It is computed once and shared by all
     * the derived flows (summaries, library, searches...), instead of once per observer at every database change.
     */
    private val visibleState: Flow<DbState> =
        combine(state, settings.priceVisibility, settings.countFirstEdition) { s, visibility, countFirstEdition ->
            s.withVisiblePrices(visibility).copy(countFirstEdition = countFirstEdition)
        }
            .conflate()
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    /**
     * Derives a value from the database snapshot off the UI thread, emitting only
     * when it changes.
     * @param transform (DbState) -> T - computation to run.
     * @return Flow<T> - updated result.
     */
    private fun <T> derive(transform: (DbState) -> T): Flow<T> =
        visibleState.map(transform).distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * Removes from the snapshot the prices the user has hidden: CardTrader disappears entirely, euros
     * are zeroed in the prices that contain them. Manually entered prices count as Cardmarket.
     * @param visibility PriceVisibility - prices to show.
     * @return DbState - snapshot with only the visible prices.
     */
    private fun DbState.withVisiblePrices(visibility: PriceVisibility): DbState {
        if (visibility.showsAll) return this
        val visible = snapshots.mapNotNull { snap ->
            if (snap.source == CardTraderPriceProvider.DISPLAY_NAME) return@mapNotNull snap.takeIf { visibility.cardTrader }
            if (snap.source == PriceRepository.TCGPLAYER_SOURCE) return@mapNotNull snap.takeIf { visibility.tcgplayer }
            var shown = snap
            if (!visibility.cardmarket) shown = shown.copy(eur = null, eurLow = null, eurAvg30 = null)
            shown
        }
        return copy(snapshots = visible)
    }

    /** Summaries of all collections for the initial list. */
    val summaries: Flow<List<CollectionSummary>> = derive { LibraryAssembler.summaries(it) }

    /**
     * Library of a collection.
     * @param collectionId Long - collection.
     * @return Flow<List<CardUi>> - visible cards with variants, quantities and prices.
     */
    fun library(collectionId: Long): Flow<List<CardUi>> = derive { LibraryAssembler.libraryOf(it, collectionId) }

    /**
     * Cards removed by the user from a collection.
     * @param collectionId Long - collection.
     * @return Flow<List<CardEntity>> - hidden cards.
     */
    fun hiddenCards(collectionId: Long): Flow<List<CardEntity>> = derive { s ->
        val ids = s.memberships.filter { it.collectionId == collectionId && it.hidden }.map { it.cardId }.toSet()
        s.cards.filter { it.id in ids }.sortedBy { it.name }
    }

    /**
     * Searches of a collection.
     * @param collectionId Long - collection.
     * @return Flow<List<TrackedQueryEntity>> - searches.
     */
    fun queries(collectionId: Long): Flow<List<TrackedQueryEntity>> = derive { s -> s.queries.filter { it.collectionId == collectionId } }

    /**
     * Latest price updates of a collection.
     * @param collectionId Long - collection.
     * @return Flow<List<PriceRunEntity>> - at most 10 runs, most recent first.
     */
    fun runs(collectionId: Long): Flow<List<PriceRunEntity>> = derive { s -> s.runs.filter { it.collectionId == collectionId }.take(10) }

    /**
     * Name of a collection.
     * @param collectionId Long - collection.
     * @return Flow<String?> - name, or null if the collection no longer exists.
     */
    fun name(collectionId: Long): Flow<String?> = derive { s -> s.collections.firstOrNull { it.id == collectionId }?.name }

    /**
     * Card database a collection was last updated from.
     * @param collectionId Long - collection.
     * @return Flow<String?> - `generatedAt` of that database, or null if never updated (or the collection no longer exists).
     */
    fun catalogVersion(collectionId: Long): Flow<String?> = derive { s -> s.collections.firstOrNull { it.id == collectionId }?.catalogVersion }

    /**
     * Creates a collection, with an optional first search.
     * @param rawName String - name entered by the user.
     * @param languages Collection<Language> - languages of the first search: one search per language. The language of the
     *        collection is inferred from them (a single one among EN and JP, otherwise mixed).
     * @param pattern String? - names of the first search, also more than one separated by commas (empty = no search):
     *        a search is created for each name and each language.
     * @return CreateResult - id of the created collection, or the error to show.
     */
    suspend fun createCollection(rawName: String, languages: Collection<Language>, pattern: String?): CreateResult {
        val name = CollectionFiles.cleanName(rawName) ?: return CreateResult.Invalid(InputError.NAME_EMPTY)
        val names = Validators.parseQueryPatterns(pattern.orEmpty()) ?: return CreateResult.Invalid(InputError.QUERY_INVALID)
        val code = CollectionLanguage.infer(languages.map { it.code })
        val id = db.withTransaction {
            val id = catalogDao.insertCollection(CollectionEntity(name = uniqueName(name), createdAt = System.currentTimeMillis(), language = code))
            names.forEach { clean ->
                languages.distinct().forEach { language ->
                    catalogDao.insertQuery(TrackedQueryEntity(collectionId = id, language = language.code, pattern = clean))
                }
            }
            id
        }
        return CreateResult.Created(id)
    }

    /**
     * Reads the editable settings of a collection.
     * @param collectionId Long - collection.
     * @return CollectionSettings? - name and languages, or null if the collection does not exist.
     */
    suspend fun settings(collectionId: Long): CollectionSettings? {
        val name = catalogDao.collectionName(collectionId) ?: return null
        val queries = languageQueries(collectionId)
        return CollectionSettings(name, CollectionLanguages.languagesOf(queries), CollectionLanguages.canAddLanguage(queries))
    }

    /**
     * Saves the settings of a collection: name and languages. New languages copy the searches by name,
     * removed ones delete their searches (and the cards that only those searches had found); quantities stay
     * saved and reappear if the language is added back.
     * @param collectionId Long - collection.
     * @param rawName String - wanted name.
     * @param languages Set<Language> - wanted languages, at least one.
     * @return SettingsResult - outcome: error on the name, or whether the languages changed and new cards are needed.
     */
    suspend fun saveSettings(collectionId: Long, rawName: String, languages: Set<Language>): SettingsResult {
        val name = CollectionFiles.cleanName(rawName) ?: return SettingsResult.Invalid(InputError.NAME_EMPTY)
        if (languages.isEmpty()) return SettingsResult.Invalid(InputError.QUERY_INVALID)
        return db.withTransaction {
            if (name != catalogDao.collectionName(collectionId)) catalogDao.renameCollection(collectionId, name)
            val plan = CollectionLanguages.plan(languageQueries(collectionId), languages)
            plan.add.forEach { (language, pattern) ->
                catalogDao.insertQuery(TrackedQueryEntity(collectionId = collectionId, language = language.code, pattern = pattern))
            }
            plan.removeIds.forEach { catalogDao.deleteQuery(it) }
            if (plan.add.isNotEmpty() || plan.removeIds.isNotEmpty()) {
                catalogDao.setCollectionLanguage(collectionId, CollectionLanguage.infer(catalogDao.queriesOf(collectionId).map { it.language }))
            }
            SettingsResult.Saved(needsSync = plan.add.isNotEmpty())
        }
    }

    /**
     * Searches of a collection in the form needed to change its languages.
     * @param collectionId Long - collection.
     * @return List<LanguageQuery> - searches.
     */
    private suspend fun languageQueries(collectionId: Long): List<LanguageQuery> = catalogDao.queriesOf(collectionId).map {
        LanguageQuery(it.id, Language.fromCode(it.language), it.pattern, QueryKind.fromCode(it.kind) ?: QueryKind.NAME)
    }

    /**
     * Makes a collection name unique by adding " (2)", " (3)"… if needed.
     * @param name String - desired name.
     * @return String - name not yet used.
     */
    suspend fun uniqueName(name: String): String {
        val existing = catalogDao.collectionNames().map { it.lowercase() }.toSet()
        if (name.lowercase() !in existing) return name
        var n = 2
        while ("${name.take(54)} ($n)".lowercase() in existing) n++
        return "${name.take(54)} ($n)"
    }

    /**
     * Renames a collection.
     * @param collectionId Long - collection.
     * @param rawName String - new name.
     * @return InputError? - error, or null if renamed.
     */
    suspend fun rename(collectionId: Long, rawName: String): InputError? {
        val name = CollectionFiles.cleanName(rawName) ?: return InputError.NAME_EMPTY
        catalogDao.renameCollection(collectionId, name)
        return null
    }

    /**
     * Deletes a collection with its searches, quantities and price log, and removes from the catalog the cards (with variants, prices and images)
     * that no longer belong to any collection, so catalog jobs no longer look for them.
     * @param collectionId Long - collection.
     * @return Unit
     */
    suspend fun delete(collectionId: Long) {
        db.withTransaction {
            priceDao.deleteRunsOf(collectionId)
            catalogDao.deleteCollection(collectionId)
        }
        purgeOrphanCards(System.currentTimeMillis())
    }

    /**
     * Removes the cards that no collection contains anymore (also those left over from collections deleted by earlier versions).
     * @param before Long - milliseconds: only cards updated before are removed.
     * @return Unit
     */
    suspend fun purgeOrphanCards(before: Long) {
        val orphans = catalogDao.orphanCards(before)
        for (chunk in orphans.chunked(PURGE_CHUNK)) {
            val ids = chunk.map { it.id }
            db.withTransaction {
                priceDao.deleteSnapshotsOfCards(ids)
                priceDao.deleteAlertsOfCards(ids)
                catalogDao.deleteCards(ids)
            }
            images.deleteFiles(chunk.flatMap { listOf(it.imageLowPath, it.imageHighPath) })
        }
    }

    /**
     * Adds or removes copies starting from the saved value (not the displayed one), so close-together
     * taps on +/- are not lost.
     * @param collectionId Long - collection.
     * @param variantId String - variant.
     * @param delta Int - copies to add (negative to remove them).
     * @return Unit
     */
    suspend fun changeQuantity(collectionId: Long, variantId: String, delta: Int) =
        update(collectionId, variantId) { it.copy(quantity = Validators.clampQuantity(it.quantity + delta)) }

    /**
     * Sets the condition of the owned copy.
     * @param collectionId Long - collection.
     * @param variantId String - variant.
     * @param condition Condition - new condition.
     * @return Unit
     */
    suspend fun setCondition(collectionId: Long, variantId: String, condition: Condition) =
        update(collectionId, variantId) { it.copy(condition = condition.name) }

    /**
     * Tells whether the variant counts as a slot of the collection.
     * @param collectionId Long - collection.
     * @param variantId String - variant.
     * @param wanted Boolean - true if wanted.
     * @return Unit
     */
    suspend fun setWanted(collectionId: Long, variantId: String, wanted: Boolean) =
        update(collectionId, variantId) { it.copy(wanted = wanted) }

    /**
     * Adds a search to the collection after validating it.
     * @param collectionId Long - collection.
     * @param language Language - catalog language.
     * @param pattern String - text to search in the name.
     * @return InputError? - error, or null if added.
     */
    suspend fun addQuery(collectionId: Long, language: Language, pattern: String): InputError? {
        val clean = pattern.trim()
        if (!Validators.isValidQueryPattern(clean)) return InputError.QUERY_INVALID
        val id = catalogDao.insertQuery(TrackedQueryEntity(collectionId = collectionId, language = language.code, pattern = clean))
        return if (id == -1L) InputError.QUERY_DUPLICATE else null
    }

    /**
     * Adds an entire set to the collection (base set or master set).
     * @param collectionId Long - collection.
     * @param language Language - catalog language.
     * @param setId String - id of the set (e.g. "base1", "M3", "Pt4").
     * @param kind QueryKind - [QueryKind.SET_BASE] or [QueryKind.SET_MASTER].
     * @return InputError? - error, or null if added.
     */
    suspend fun addSetQuery(collectionId: Long, language: Language, setId: String, kind: QueryKind): InputError? {
        val clean = setId.trim()
        if (!kind.isSet || !QueryKind.isValidPattern(kind, clean)) return InputError.QUERY_INVALID
        val id = catalogDao.insertQuery(TrackedQueryEntity(collectionId = collectionId, language = language.code, pattern = clean, kind = kind.code))
        return if (id == -1L) InputError.QUERY_DUPLICATE else null
    }

    /**
     * Creates a set collection: an entire set, as base set or master set.
     * @param rawName String - name entered; if empty the name of the set is used.
     * @param language Language - catalog language.
     * @param setId String - id of the set.
     * @param setName String - name of the set, used if the name is empty.
     * @param kind QueryKind - [QueryKind.SET_BASE] or [QueryKind.SET_MASTER].
     * @return CreateResult - id of the created collection, or the error to show.
     */
    suspend fun createSetCollection(rawName: String, language: Language, setId: String, setName: String, kind: QueryKind): CreateResult {
        val name = CollectionFiles.cleanName(rawName) ?: CollectionFiles.cleanName(setName) ?: return CreateResult.Invalid(InputError.NAME_EMPTY)
        val clean = setId.trim()
        if (!kind.isSet || !QueryKind.isValidPattern(kind, clean)) return CreateResult.Invalid(InputError.QUERY_INVALID)
        val id = db.withTransaction {
            val id = catalogDao.insertCollection(
                CollectionEntity(name = uniqueName(name), createdAt = System.currentTimeMillis(), language = CollectionLanguage.codeOf(language)),
            )
            catalogDao.insertQuery(TrackedQueryEntity(collectionId = id, language = language.code, pattern = clean, kind = kind.code))
            id
        }
        return CreateResult.Created(id)
    }

    /**
     * Enables or disables a search (its cards are hidden but quantities stay).
     * @param id Long - search.
     * @param enabled Boolean - new state.
     * @return Unit
     */
    suspend fun setQueryEnabled(id: Long, enabled: Boolean) = catalogDao.setQueryEnabled(id, enabled)

    /**
     * Deletes a search and the links of the cards it had found.
     * @param id Long - search.
     * @return Unit
     */
    suspend fun deleteQuery(id: Long) = catalogDao.deleteQuery(id)

    /**
     * Removes a card from the collection; the sync does not add it back.
     * @param collectionId Long - collection.
     * @param cardId String - card.
     * @return Unit
     */
    suspend fun hideCard(collectionId: Long, cardId: String) = catalogDao.setHidden(collectionId, cardId, true)

    /**
     * Removes several cards from the collection in a single transaction, so the screens are recomputed once and not once per card.
     * @param collectionId Long - collection.
     * @param cardIds Collection<String> - cards.
     * @return Unit
     */
    suspend fun hideCards(collectionId: Long, cardIds: Collection<String>) {
        db.withTransaction { cardIds.forEach { catalogDao.setHidden(collectionId, it, true) } }
    }

    /**
     * Puts back in the collection a previously removed card.
     * @param collectionId Long - collection.
     * @param cardId String - card.
     * @return Unit
     */
    suspend fun restoreCard(collectionId: Long, cardId: String) = catalogDao.makeManual(collectionId, cardId)

    /**
     * Reads, modifies and saves the user data of a variant in a collection.
     * @param collectionId Long - collection.
     * @param variantId String - variant.
     * @param change (CollectionEntryEntity) -> CollectionEntryEntity - modification.
     * @return Unit
     */
    private suspend fun update(collectionId: Long, variantId: String, change: (CollectionEntryEntity) -> CollectionEntryEntity) {
        db.withTransaction {
            val current = collectionDao.getEntry(collectionId, variantId) ?: CollectionEntryEntity(collectionId = collectionId, variantId = variantId)
            collectionDao.upsertEntry(change(current))
        }
    }

    private companion object {
        /** Time the snapshot stays active without observers (e.g. screen rotation). */
        const val STOP_TIMEOUT_MS = 5_000L

        /** Cards removed at a time (SQLite's limit on parameters is 999). */
        const val PURGE_CHUNK = 500
    }
}
