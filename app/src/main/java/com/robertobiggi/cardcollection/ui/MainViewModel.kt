package com.robertobiggi.cardcollection.ui

import androidx.annotation.PluralsRes
import kotlinx.coroutines.CancellationException
import com.robertobiggi.cardcollection.work.Notifications
import android.content.Context
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import com.robertobiggi.cardcollection.data.local.PriceAlertEntity
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.robertobiggi.cardcollection.AppContainer
import com.robertobiggi.cardcollection.CardCollectionApp
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.backup.ImageExport
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.CatalogUpdate
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.SetListResult
import com.robertobiggi.cardcollection.data.repo.VariantImage
import com.robertobiggi.cardcollection.data.repo.SetChoice
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.data.catalog.CardBrief
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import com.robertobiggi.cardcollection.data.catalog.CatalogDownloadResult
import com.robertobiggi.cardcollection.data.catalog.CatalogInfo
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import com.robertobiggi.cardcollection.data.settings.PriceKind
import com.robertobiggi.cardcollection.data.settings.PriceVisibility
import com.robertobiggi.cardcollection.data.repo.AbortReason
import com.robertobiggi.cardcollection.data.repo.AddCardResult
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.CollectionSummary
import com.robertobiggi.cardcollection.data.repo.CreateResult
import com.robertobiggi.cardcollection.data.repo.ExportResult
import com.robertobiggi.cardcollection.data.repo.ImageResult
import com.robertobiggi.cardcollection.data.repo.ImportOutcome
import com.robertobiggi.cardcollection.data.repo.CollectionSettings
import com.robertobiggi.cardcollection.data.repo.InputError
import com.robertobiggi.cardcollection.data.repo.SettingsResult
import com.robertobiggi.cardcollection.data.repo.SearchResult
import com.robertobiggi.cardcollection.ui.common.UiText
import com.robertobiggi.cardcollection.ui.common.messageRes
import com.robertobiggi.cardcollection.work.JobState
import com.robertobiggi.cardcollection.ui.collection.applyFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ownership filter in the grid. */
enum class OwnedFilter { ALL, OWNED, MISSING }

/** Grid ordering; [labelRes] is the name shown. */
enum class SortMode(@StringRes val labelRes: Int) {
    RELEASE(R.string.sort_release),
    PRICE(R.string.sort_price),
    CODE(R.string.sort_code),
    NAME(R.string.sort_name),
}

/** State of the collection filters. */
data class CollectionFilter(
    val query: String = "",
    val owned: OwnedFilter = OwnedFilter.ALL,
    val language: Language? = null,
    val sort: SortMode = SortMode.RELEASE,
)

/** State of the search for cards to add by hand; [messageRes] is a notice to show, if any. */
/**
 * State of the set picker for set collections.
 * @property language language of the sets shown.
 * @property loading true while the list is downloading.
 * @property sets available sets.
 * @property noCatalog true if the card database is not installed.
 */
data class SetPickerState(
    val language: Language = Language.EN,
    val loading: Boolean = false,
    val sets: List<SetChoice> = emptyList(),
    val noCatalog: Boolean = false,
)

/** Download of the card database: bytes read and total (null if the server does not declare them). */
data class CatalogDownload(val done: Long, val total: Long?)

data class CardSearchState(
    val loading: Boolean = false,
    val results: List<CardBrief> = emptyList(),
    val language: Language = Language.EN,
    @StringRes val messageRes: Int? = null,
)

/** Action in progress in the detail of a card. */
enum class CardAction { PRICE, DOWNLOAD_IMAGE, PICK_IMAGE }

/** State of the dialog of an action on the card: `message` is the outcome, null while the action is in progress. */
data class CardActionReport(val action: CardAction, val message: UiText? = null)

/**
 * The app's single ViewModel: list of collections and state of the open collection, read from the
 * local database; forwards the actions to the repositories.
 *
 * @param container dependencies of the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _selected = MutableStateFlow<Long?>(null)

    /** Open collection, null in the initial list. */
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    val summaries: StateFlow<List<CollectionSummary>?> = container.collection.summaries.stateInVm(null)

    val collectionName: StateFlow<String?> = perCollection { container.collection.name(it) }.stateInVm(null)

    /** Language of the open collection: value of CollectionLanguage, null until loaded. */
    val collectionLanguage: StateFlow<String?> = perCollection { container.collection.language(it) }.stateInVm(null)
    val library: StateFlow<List<CardUi>?> = perCollection { container.collection.library(it) }.stateInVm(null)

    /** Card database the open collection was last updated from, null if never. */
    private val collectionCatalogVersion: StateFlow<String?> = perCollection { container.collection.catalogVersion(it) }.stateInVm(null)
    val queries: StateFlow<List<TrackedQueryEntity>> = perCollection(emptyList()) { container.collection.queries(it) }.stateInVm(emptyList())
    val hiddenCards: StateFlow<List<CardEntity>> = perCollection(emptyList()) { container.collection.hiddenCards(it) }.stateInVm(emptyList())
    val runs: StateFlow<List<PriceRunEntity>> = perCollection(emptyList()) { container.collection.runs(it) }.stateInVm(emptyList())
    val catalogJob: StateFlow<JobState?> = perCollection { container.work.catalogState(it) }.stateInVm(null)
    val priceJob: StateFlow<JobState?> = perCollection { container.work.priceState(it) }.stateInVm(null)

    /** Installed card database, null if missing. */
    val catalogInfo: StateFlow<CatalogInfo?> = container.catalogStore.info

    /** True if the open collection must be updated from the installed card database (after an app update that brings a newer one, for example). */
    val needsCatalogUpdate: StateFlow<Boolean> = combine(selected, collectionCatalogVersion, catalogInfo) { id, version, info ->
        id != null && CatalogUpdate.needsUpdate(version, info?.generatedAt)
    }.stateInVm(false)

    val catalogUrl: StateFlow<String> = container.settings.catalogUrl.stateInVm(SettingsStore.DEFAULT_CATALOG_URL)

    private val _catalogDownload = MutableStateFlow<CatalogDownload?>(null)

    /** Card database download in progress, null if none. */
    val catalogDownload: StateFlow<CatalogDownload?> = _catalogDownload.asStateFlow()
    val lastCatalogSync: StateFlow<Long> = container.settings.lastCatalogSync.stateInVm(0L)
    val cardTraderToken: StateFlow<String> = container.settings.cardTraderToken.stateInVm("")
    val countFirstEdition: StateFlow<Boolean> = container.settings.countFirstEdition.stateInVm(true)
    val priceVisibility: StateFlow<PriceVisibility> = container.settings.priceVisibility.stateInVm(PriceVisibility())
    val priceAlertsEnabled: StateFlow<Boolean> = container.settings.priceAlertsEnabled.stateInVm(false)
    val priceAlertChangePercent: StateFlow<Int> = container.settings.priceAlertChangePercent.stateInVm(PriceAlertRules.DEFAULT_CHANGE_PERCENT)

    /** Price alerts by variant id. */
    val priceAlerts: StateFlow<Map<String, PriceAlertEntity>> = container.prices.observeAlerts().stateInVm(emptyMap())

    private val _filter = MutableStateFlow(CollectionFilter())
    val filter: StateFlow<CollectionFilter> = _filter.asStateFlow()

    /** Cards of the open collection after search, filters and ordering (computed off the UI thread). */
    val visibleCards: StateFlow<List<CardUi>> = combine(library, _filter) { cards, f -> applyFilter(cards.orEmpty(), f) }
        .flowOn(Dispatchers.Default)
        .stateInVm(emptyList())

    private val _setPicker = MutableStateFlow(SetPickerState())
    val setPicker: StateFlow<SetPickerState> = _setPicker.asStateFlow()

    private val _search = MutableStateFlow(CardSearchState())
    val search: StateFlow<CardSearchState> = _search.asStateFlow()

    private val _cardAction = MutableStateFlow<CardAction?>(null)

    /** Action on the open card in progress (price or image), null if none. */
    val cardAction: StateFlow<CardAction?> = _cardAction.asStateFlow()

    private val _cardReport = MutableStateFlow<CardActionReport?>(null)

    /** Dialog of the action on the card: progress while it is in progress, then the outcome until the user closes it. */
    val cardReport: StateFlow<CardActionReport?> = _cardReport.asStateFlow()

    /**
     * Closes the outcome dialog.
     * @return Unit
     */
    fun dismissCardReport() {
        if (_cardAction.value == null) _cardReport.value = null
    }

    private fun report(@StringRes id: Int, vararg args: Any) {
        _cardReport.value = _cardReport.value?.copy(message = UiText(id, *args))
    }

    /**
     * Like [report], with a plural text.
     * @param id Int - plural resource.
     * @param quantity Int - number that decides the form.
     * @param args Any - format arguments.
     * @return Unit
     */
    private fun reportPlural(@PluralsRes id: Int, quantity: Int, vararg args: Any) {
        _cardReport.value = _cardReport.value?.copy(message = UiText.plural(id, quantity, *args))
    }

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)

    /** Short messages to show in a snackbar, translated at display time. */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /**
     * Queues a message for the snackbar.
     * @param id Int - string resource.
     * @param args Any - format arguments.
     * @return Unit
     */
    private fun say(@StringRes id: Int, vararg args: Any) {
        _messages.tryEmit(UiText(id, *args))
    }

    /**
     * Queues a plural message for the snackbar ("1 card", "2 cards").
     * @param id Int - plural resource.
     * @param quantity Int - number that decides the form.
     * @param args Any - format arguments.
     * @return Unit
     */
    private fun sayPlural(@PluralsRes id: Int, quantity: Int, vararg args: Any) {
        _messages.tryEmit(UiText.plural(id, quantity, *args))
    }

    /**
     * Converts a flow into a StateFlow tied to the ViewModel.
     * @receiver Flow<T> - source flow.
     * @param initial T - initial value.
     * @return StateFlow<T> - shared state.
     */
    private fun <T> Flow<T>.stateInVm(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /**
     * Creates a flow that follows the open collection.
     * @param empty T - value when no collection is open.
     * @param source (Long) -> Flow<T> - flow for a collection.
     * @return Flow<T> - flow of the current collection.
     */
    private fun <T> perCollection(empty: T, source: (Long) -> Flow<T>): Flow<T> =
        _selected.flatMapLatest { id -> if (id == null) flowOf(empty) else source(id) }

    /**
     * Variant of [perCollection] with null as the empty value.
     * @param source (Long) -> Flow<T?> - flow for a collection.
     * @return Flow<T?> - flow of the current collection.
     */
    private fun <T> perCollection(source: (Long) -> Flow<T?>): Flow<T?> = perCollection(null, source)

    /**
     * Opens a collection (resets filters and search if it changes).
     * @param collectionId Long - collection.
     * @return Unit
     */
    fun open(collectionId: Long) {
        if (_selected.value != collectionId) {
            _selected.value = collectionId
            _filter.value = CollectionFilter()
            _search.value = CardSearchState()
        }
    }

    /**
     * Runs an action on the open collection, if any.
     * @param block suspend (Long) -> Unit - action.
     * @return Unit
     */
    private fun withSelected(block: suspend (Long) -> Unit) {
        val id = _selected.value ?: return
        viewModelScope.launch { block(id) }
    }

    /**
     * Updates the collection filters.
     * @param change (CollectionFilter) -> CollectionFilter - modification.
     * @return Unit
     */
    fun updateFilter(change: (CollectionFilter) -> CollectionFilter) {
        _filter.value = change(_filter.value).let { it.copy(query = it.query.take(40)) }
    }

    /**
     * Starts the update of the open collection by itself if it needs it (called when the collection opens or the installed database changes).
     * The work is unique per collection: if it is already running nothing starts twice.
     * @return Unit
     */
    fun updateIfNeeded() {
        if (needsCatalogUpdate.value) startCatalogSync()
    }

    /**
     * Starts the catalog sync of the open collection.
     * @return Unit
     */
    fun startCatalogSync() {
        _selected.value?.let { container.work.startCatalogSync(it) }
    }

    /**
     * Downloads (or updates) the card database from the address given in the settings.
     * @param url String - HTTPS address; if different from the saved one it is remembered for the next updates.
     * @return Unit
     */
    fun downloadCatalog(url: String) {
        if (_catalogDownload.value != null) return
        viewModelScope.launch {
            val clean = url.trim()
            _catalogDownload.value = CatalogDownload(0, null)
            val result = container.catalogStore.download(clean) { done, total -> _catalogDownload.value = CatalogDownload(done, total) }
            _catalogDownload.value = null
            when (result) {
                is CatalogDownloadResult.Installed -> {
                    container.settings.setCatalogUrl(clean)
                    say(R.string.msg_catalog_installed)
                }
                CatalogDownloadResult.InvalidUrl -> say(R.string.msg_catalog_invalid_url)
                CatalogDownloadResult.NetworkError -> say(R.string.msg_network_error)
                CatalogDownloadResult.InvalidFile -> say(R.string.msg_catalog_invalid_file)
            }
        }
    }

    /**
     * Starts the price update of the open collection.
     * @return Unit
     */
    fun startPriceCheck() {
        _selected.value?.let { container.work.startPriceCheck(it) }
    }

    /**
     * Creates a collection and, if it has a search, downloads its catalog right away.
     * @param name String - name.
     * @param languages List<Language> - languages of the first search (one search per language).
     * @param pattern String - names of the first search, separated by commas (empty = none).
     * @param onResult (InputError?, Long?) -> Unit - receives the error or the created id.
     * @return Unit
     */
    fun createCollection(name: String, languages: List<Language>, pattern: String, onResult: (InputError?, Long?) -> Unit) {
        viewModelScope.launch {
            when (val result = container.collection.createCollection(name, languages, pattern)) {
                is CreateResult.Created -> {
                    if (Validators.parseQueryPatterns(pattern).orEmpty().isNotEmpty()) container.work.startCatalogSync(result.id)
                    onResult(null, result.id)
                }
                is CreateResult.Invalid -> onResult(result.error, null)
            }
        }
    }

    /**
     * Downloads the list of sets of a language for the picker (if not already loaded).
     * @param language Language - language of the sets.
     * @param force Boolean - true to download it again even if present (e.g. after an error).
     * @return Unit
     */
    fun loadSets(language: Language, force: Boolean = false) {
        val current = _setPicker.value
        if (!force && current.language == language && (current.loading || current.sets.isNotEmpty())) return
        _setPicker.value = SetPickerState(language = language, loading = true)
        viewModelScope.launch {
            _setPicker.value = when (val result = container.catalog.setChoices(language)) {
                is SetListResult.Found -> SetPickerState(language = language, sets = result.sets)
                SetListResult.NoCatalog -> SetPickerState(language = language, noCatalog = true)
            }
        }
    }

    /**
     * Creates a set collection and downloads its cards right away.
     * @param name String - name (empty = name of the set).
     * @param language Language - catalog language.
     * @param set SetChoice - chosen set.
     * @param kind QueryKind - base set or master set.
     * @param onResult (InputError?, Long?) -> Unit - receives the error or the created id.
     * @return Unit
     */
    fun createSetCollection(name: String, language: Language, set: SetChoice, kind: QueryKind, onResult: (InputError?, Long?) -> Unit) {
        viewModelScope.launch {
            when (val result = container.collection.createSetCollection(name, language, set.id, set.name, kind)) {
                is CreateResult.Created -> {
                    container.work.startCatalogSync(result.id)
                    onResult(null, result.id)
                }
                is CreateResult.Invalid -> onResult(result.error, null)
            }
        }
    }

    /**
     * Adds an entire set to the open collection and starts its download.
     * @param language Language - catalog language.
     * @param set SetChoice - chosen set.
     * @param kind QueryKind - base set or master set.
     * @param onResult (InputError?) -> Unit - receives the error, or null if added.
     * @return Unit
     */
    fun addSetQuery(language: Language, set: SetChoice, kind: QueryKind, onResult: (InputError?) -> Unit) = withSelected { id ->
        val error = container.collection.addSetQuery(id, language, set.id, kind)
        onResult(error)
        if (error == null) {
            say(R.string.msg_set_added, set.name)
            container.work.startCatalogSync(id)
        }
    }

    /**
     * Renames a collection.
     * @param collectionId Long - collection.
     * @param name String - new name.
     * @param onResult (InputError?) -> Unit - receives the error, or null if renamed.
     * @return Unit
     */
    fun renameCollection(collectionId: Long, name: String, onResult: (InputError?) -> Unit) {
        viewModelScope.launch { onResult(container.collection.rename(collectionId, name)) }
    }

    /**
     * Changes the language of the open collection.
     * @param language Language? - fixed language, or null for a mixed collection.
     * @return Unit
     */
    fun setCollectionLanguage(language: Language?) = withSelected { id -> container.collection.setLanguage(id, language) }

    /**
     * Reads the settings of a collection to show them in the dialog.
     * @param collectionId Long - collection.
     * @param onResult (CollectionSettings?) -> Unit - settings, or null if the collection no longer exists.
     * @return Unit
     */
    fun loadCollectionSettings(collectionId: Long, onResult: (CollectionSettings?) -> Unit) {
        viewModelScope.launch { onResult(container.collection.settings(collectionId)) }
    }

    /**
     * Saves name and languages of a collection; if new cards are needed it starts the catalog sync.
     * @param collectionId Long - collection.
     * @param name String - name.
     * @param languages Set<Language> - wanted languages.
     * @param onResult (InputError?) -> Unit - error to show, or null if saved.
     * @return Unit
     */
    fun saveCollectionSettings(collectionId: Long, name: String, languages: Set<Language>, onResult: (InputError?) -> Unit) {
        viewModelScope.launch {
            when (val result = container.collection.saveSettings(collectionId, name, languages)) {
                is SettingsResult.Invalid -> onResult(result.error)
                is SettingsResult.Saved -> {
                    if (result.needsSync) container.work.startCatalogSync(collectionId)
                    onResult(null)
                }
            }
        }
    }

    /**
     * Deletes a collection.
     * @param collectionId Long - collection.
     * @return Unit
     */
    fun deleteCollection(collectionId: Long) {
        viewModelScope.launch {
            container.collection.delete(collectionId)
            if (_selected.value == collectionId) _selected.value = null
            say(R.string.msg_collection_deleted)
        }
    }

    /**
     * Exports a collection to the chosen file.
     * @param collectionId Long - collection.
     * @param uri Uri - destination.
     * @param images ImageExport - images to include.
     * @return Unit
     */
    fun exportCollection(collectionId: Long, uri: Uri, images: ImageExport) {
        viewModelScope.launch {
            say(R.string.msg_exporting)
            when (val result = container.transfer.export(collectionId, uri, images)) {
                is ExportResult.Done ->
                    if (images == ImageExport.NONE) {
                        sayPlural(R.plurals.msg_exported, result.cards, result.cards)
                    } else {
                        sayPlural(R.plurals.msg_exported_images, result.cards, result.cards, result.withImages)
                    }
                is ExportResult.Failed -> say(result.error.messageRes())
            }
        }
    }

    /**
     * Imports a file as a new collection.
     * @param uri Uri - source file.
     * @param defaultName String - name to use if the file has none (already translated).
     * @return Unit
     */
    fun importCollection(uri: Uri, defaultName: String) {
        viewModelScope.launch {
            say(R.string.msg_importing)
            when (val result = container.transfer.import(uri, defaultName)) {
                is ImportOutcome.Done -> {
                    if (result.cards == 0) say(R.string.msg_imported_no_cards, result.name) else say(R.string.msg_imported, result.name, result.cards, result.images)
                    if (result.skipped > 0) sayPlural(R.plurals.msg_import_skipped, result.skipped, result.skipped)
                }
                is ImportOutcome.Invalid -> say(result.error.messageRes())
                is ImportOutcome.Failed -> say(result.error.messageRes())
            }
        }
    }

    /**
     * Adds or removes copies of a variant in the open collection, starting from the saved value.
     * @param variantId String - variant.
     * @param delta Int - copies to add (negative to remove them).
     * @return Unit
     */
    fun changeQuantity(variantId: String, delta: Int) = withSelected { container.collection.changeQuantity(it, variantId, delta) }

    /**
     * Sets the condition of a variant in the open collection.
     * @param variantId String - variant.
     * @param condition Condition - condition.
     * @return Unit
     */
    fun setCondition(variantId: String, condition: Condition) = withSelected { container.collection.setCondition(it, variantId, condition) }

    /**
     * Tells whether a variant counts as a slot in the open collection.
     * @param variantId String - variant.
     * @param wanted Boolean - true if wanted.
     * @return Unit
     */
    fun setWanted(variantId: String, wanted: Boolean) = withSelected { container.collection.setWanted(it, variantId, wanted) }

    /**
     * Makes the 1st Edition versions count or not among the missing and in the totals.
     * @param counted Boolean - true to count them.
     * @return Unit
     */
    fun setCountFirstEdition(counted: Boolean) {
        viewModelScope.launch { container.settings.setCountFirstEdition(counted) }
    }

    /**
     * Turns the price alerts and the daily check that verifies them on or off.
     * @param enabled Boolean - true to turn them on.
     * @return Unit
     */
    fun setPriceAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setPriceAlertsEnabled(enabled)
            if (enabled) container.work.schedulePriceAlerts() else container.work.cancelPriceAlerts()
        }
    }

    private val _alertCheckRunning = MutableStateFlow(false)

    /** True while the alert check started with "Check now" is running. */
    val alertCheckRunning: StateFlow<Boolean> = _alertCheckRunning.asStateFlow()

    /**
     * Runs the price alert check right away, as the daily one does: updates the prices of the cards with an alert, sends
     * the notifications and says how it went (handy to try the alerts without waiting for the night).
     * @param appContext Context - application context, to show the notifications.
     * @return Unit
     */
    fun checkPriceAlertsNow(appContext: Context) {
        if (_alertCheckRunning.value) return
        _alertCheckRunning.value = true
        viewModelScope.launch {
            try {
                val result = try {
                    container.prices.runAlertCheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Price list not downloadable or invalid response.
                    null
                }
                result?.notices?.forEach { Notifications.showPriceAlert(appContext, it) }
                when {
                    result == null -> say(R.string.msg_alert_check_failed)
                    result.checked == 0 && result.abort == null -> say(R.string.msg_alert_check_no_alerts)
                    result.abort == AbortReason.OFFLINE -> say(R.string.abort_offline)
                    result.abort == AbortReason.SOURCE_PAUSED -> say(R.string.abort_source_paused)
                    result.notices.isNotEmpty() && !Notifications.canNotify(appContext) ->
                        say(R.string.msg_alert_check_blocked, result.checked, result.notices.size)
                    else -> say(R.string.msg_alert_check_done, result.checked, result.notices.size)
                }
            } finally {
                _alertCheckRunning.value = false
            }
        }
    }

    /**
     * Sets the minimum change proposed for new "price rises or falls" alerts (each then has its own).
     * @param percent Int - percentage (brought back to the allowed limits).
     * @return Unit
     */
    fun setPriceAlertChangePercent(percent: Int) {
        viewModelScope.launch { container.settings.setPriceAlertChangePercent(percent) }
    }

    /**
     * Creates, modifies or removes the price alert of a variant.
     * @param variantId String - variant.
     * @param notifyChanges Boolean - true for the "rises or falls" alert.
     * @param changePercent Int - minimum change chosen for this variant, in percent.
     * @param targetText String - target price written by the user (empty = none; comma or period).
     * @return Boolean - false if the target price is not valid (the field stays to be corrected), true otherwise.
     */
    fun savePriceAlert(variantId: String, notifyChanges: Boolean, changePercent: Int, targetText: String): Boolean {
        val target = if (targetText.isBlank()) null else Validators.parseManualPrice(targetText)?.takeIf { it <= PriceAlertRules.MAX_TARGET_EUR } ?: return false
        viewModelScope.launch {
            val active = container.prices.saveAlert(variantId, notifyChanges, changePercent, target)
            say(if (active) R.string.msg_alert_saved else R.string.msg_alert_removed)
        }
        return true
    }

    /**
     * Removes the price alert of a variant.
     * @param variantId String - variant.
     * @return Unit
     */
    fun deletePriceAlert(variantId: String) {
        viewModelScope.launch {
            container.prices.deleteAlert(variantId)
            say(R.string.msg_alert_removed)
        }
    }

    /**
     * Shows or hides a type of price throughout the app.
     * @param kind PriceKind - price to change.
     * @param shown Boolean - true to show it.
     * @return Unit
     */
    fun setPriceShown(kind: PriceKind, shown: Boolean) {
        viewModelScope.launch { container.settings.setPriceShown(kind, shown) }
    }

    /**
     * Saves the CardTrader token encrypted (spaces and newlines at the edges are removed).
     * @param input String - text pasted by the user.
     * @return Unit
     */
    fun saveCardTraderToken(input: String) {
        viewModelScope.launch {
            if (!container.settings.setCardTraderToken(input.trim())) say(R.string.msg_token_not_saved)
        }
    }

    /**
     * Validates and saves a manual price (prices are shared among collections).
     * @param variantId String - variant.
     * @param input String - text entered.
     * @return Boolean - true if valid and saved.
     */
    fun addManualPrice(variantId: String, input: String): Boolean {
        val value = Validators.parseManualPrice(input) ?: return false
        viewModelScope.launch {
            container.prices.addManualPrice(variantId, value)
            say(R.string.msg_manual_price_saved)
        }
        return true
    }

    /**
     * Immediately updates the prices of a single card.
     * @param cardId String - card.
     * @return Unit
     */
    fun checkCardPrice(cardId: String) = runCardAction(CardAction.PRICE) {
        val result = container.prices.checkCard(cardId, _selected.value)
        val found = result.ok + result.fallback
        when {
            result.abort == AbortReason.SOURCE_PAUSED -> report(R.string.abort_source_paused)
            found > 0 -> reportPlural(R.plurals.msg_card_price_updated, found, found)
            result.abort == AbortReason.OFFLINE -> report(R.string.msg_network_error)
            else -> report(R.string.msg_card_price_none)
        }
    }

    /**
     * Downloads the image of a card from the first source that has one: TCGdex, CardTrader (with a token), then the other sites whose paths are
     * in the card database. Sources with nothing for the card are skipped; if one fails, the next is tried.
     * @param cardId String - card.
     * @return Unit
     */
    fun downloadCardImage(cardId: String) = runCardAction(CardAction.DOWNLOAD_IMAGE) {
        report(container.imageDownloader.download(cardId).messageRes())
    }

    /**
     * Images of the other prints of a card.
     * @param cardId String - card.
     * @return List<VariantImage> - empty if there are none.
     */
    suspend fun variantImages(cardId: String): List<VariantImage> = container.catalog.variantImages(cardId)

    /**
     * Downloads the image of a print just to show it.
     * @param url String - address of the image.
     * @return ByteArray? - the image, or null if it cannot be downloaded.
     */
    suspend fun previewImage(url: String): ByteArray? = container.catalog.previewImage(url)

    /**
     * Uses an image chosen by the user for a card.
     * @param cardId String - card.
     * @param uri Uri - image chosen with the system picker.
     * @return Unit
     */
    fun setCardImage(cardId: String, uri: Uri) = runCardAction(CardAction.PICK_IMAGE) {
        report(container.catalog.setManualImage(cardId, uri).messageRes())
    }

    /**
     * Runs an action on the open card, one at a time, exposing the state in [cardAction].
     * @param action CardAction - action to signal to the UI.
     * @param block suspend () -> Unit - work to run.
     * @return Unit
     */
    private fun runCardAction(action: CardAction, block: suspend () -> Unit) {
        if (_cardAction.value != null) return
        _cardAction.value = action
        _cardReport.value = CardActionReport(action)
        viewModelScope.launch {
            try {
                block()
            } finally {
                _cardAction.value = null
                // Without an outcome (action interrupted) the dialog closes by itself.
                if (_cardReport.value?.message == null) _cardReport.value = null
            }
        }
    }

    /**
     * Adds a search to the open collection.
     * @param language Language - language.
     * @param pattern String - text to search.
     * @param onResult (InputError?) -> Unit - receives the error, or null if added.
     * @return Unit
     */
    fun addQuery(language: Language, pattern: String, onResult: (InputError?) -> Unit) = withSelected { id ->
        val error = container.collection.addQuery(id, language, pattern)
        onResult(error)
        if (error == null) say(R.string.msg_search_added)
    }

    /**
     * Enables or disables a search.
     * @param id Long - search.
     * @param enabled Boolean - new state.
     * @return Unit
     */
    fun setQueryEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { container.collection.setQueryEnabled(id, enabled) }
    }

    /**
     * Deletes a search.
     * @param id Long - search.
     * @return Unit
     */
    fun deleteQuery(id: Long) {
        viewModelScope.launch { container.collection.deleteQuery(id) }
    }

    /**
     * Searches the card database for cards to add by hand.
     * @param language Language - language.
     * @param text String - text to search.
     * @return Unit
     */
    fun searchCards(language: Language, text: String) {
        _search.value = CardSearchState(loading = true, language = language)
        viewModelScope.launch {
            _search.value = when (val result = container.catalog.search(language, text.trim())) {
                is SearchResult.Found -> CardSearchState(
                    results = result.cards,
                    language = language,
                    messageRes = if (result.cards.isEmpty()) R.string.search_no_results else null,
                )
                SearchResult.InvalidQuery -> CardSearchState(language = language, messageRes = R.string.err_query_invalid)
                SearchResult.NoCatalog -> CardSearchState(language = language, messageRes = R.string.msg_no_catalog)
            }
        }
    }

    /**
     * Adds to the open collection a card found with the search.
     * @param card CardBrief - chosen card.
     * @return Unit
     */
    fun addSingleCard(card: CardBrief, onAdded: () -> Unit = {}) = withSelected { id ->
        say(R.string.msg_adding_card, card.name)
        when (val result = container.catalog.addSingleCard(id, _search.value.language, card.id)) {
            is AddCardResult.Added -> {
                onAdded()
                say(R.string.msg_card_added, result.name)
            }
            AddCardResult.NotAllowed -> say(R.string.msg_card_not_allowed)
            AddCardResult.NoCatalog -> say(R.string.msg_no_catalog)
        }
    }

    /**
     * Removes a card from the open collection.
     * @param cardId String - card.
     * @return Unit
     */
    fun hideCard(cardId: String) = withSelected { id ->
        container.collection.hideCard(id, cardId)
        say(R.string.msg_card_removed)
    }

    /**
     * Removes several cards from the open collection.
     * @param cardIds Set<String> - cards.
     * @return Unit
     */
    fun hideCards(cardIds: Set<String>) = withSelected { id ->
        cardIds.forEach { container.collection.hideCard(id, it) }
        sayPlural(R.plurals.msg_cards_removed, cardIds.size, cardIds.size)
    }

    /**
     * Puts a card back in the open collection.
     * @param cardId String - card.
     * @return Unit
     */
    fun restoreCard(cardId: String) = withSelected { container.collection.restoreCard(it, cardId) }

    companion object {
        /** Factory that gets the dependency container from the Application. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as CardCollectionApp
                MainViewModel(app.container)
            }
        }
    }
}
