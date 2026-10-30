package com.robertobiggi.cardcollection.ui

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
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.SetListResult
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

/** Filtro per possesso nella griglia. */
enum class OwnedFilter { ALL, OWNED, MISSING }

/** Ordinamento della griglia; [labelRes] è il nome mostrato. */
enum class SortMode(@StringRes val labelRes: Int) {
    RELEASE(R.string.sort_release),
    PRICE(R.string.sort_price),
    CODE(R.string.sort_code),
    NAME(R.string.sort_name),
}

/** Stato dei filtri della collezione. */
data class CollectionFilter(
    val query: String = "",
    val owned: OwnedFilter = OwnedFilter.ALL,
    val language: Language? = null,
    val sort: SortMode = SortMode.RELEASE,
)

/** Stato della ricerca di carte da aggiungere a mano; [messageRes] è un avviso da mostrare, se c'è. */
/**
 * Stato del selettore dei set per le collezioni per set.
 * @property language lingua dei set mostrati.
 * @property loading true mentre l'elenco si scarica.
 * @property sets set disponibili.
 * @property noCatalog true se il database delle carte non è installato.
 */
data class SetPickerState(
    val language: Language = Language.EN,
    val loading: Boolean = false,
    val sets: List<SetChoice> = emptyList(),
    val noCatalog: Boolean = false,
)

/** Scaricamento del database delle carte: byte letti e totali (null se il server non li dichiara). */
data class CatalogDownload(val done: Long, val total: Long?)

data class CardSearchState(
    val loading: Boolean = false,
    val results: List<CardBrief> = emptyList(),
    val language: Language = Language.EN,
    @StringRes val messageRes: Int? = null,
)

/** Azione in corso nel dettaglio di una carta. */
enum class CardAction { PRICE, DOWNLOAD_IMAGE, PICK_IMAGE }

/** Stato del dialog di un'azione sulla carta: `message` è l'esito, null finché l'azione è in corso. */
data class CardActionReport(val action: CardAction, val message: UiText? = null)

/**
 * ViewModel unico dell'app: elenco delle collezioni e stato della collezione aperta, letti dal
 * database locale; inoltra le azioni ai repository.
 *
 * @param container dipendenze dell'app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _selected = MutableStateFlow<Long?>(null)

    /** Collezione aperta, null nell'elenco iniziale. */
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    val summaries: StateFlow<List<CollectionSummary>?> = container.collection.summaries.stateInVm(null)

    val collectionName: StateFlow<String?> = perCollection { container.collection.name(it) }.stateInVm(null)

    /** Lingua della collezione aperta: valore di CollectionLanguage, null finché non è caricata. */
    val collectionLanguage: StateFlow<String?> = perCollection { container.collection.language(it) }.stateInVm(null)
    val library: StateFlow<List<CardUi>?> = perCollection { container.collection.library(it) }.stateInVm(null)
    val queries: StateFlow<List<TrackedQueryEntity>> = perCollection(emptyList()) { container.collection.queries(it) }.stateInVm(emptyList())
    val hiddenCards: StateFlow<List<CardEntity>> = perCollection(emptyList()) { container.collection.hiddenCards(it) }.stateInVm(emptyList())
    val runs: StateFlow<List<PriceRunEntity>> = perCollection(emptyList()) { container.collection.runs(it) }.stateInVm(emptyList())
    val catalogJob: StateFlow<JobState?> = perCollection { container.work.catalogState(it) }.stateInVm(null)
    val priceJob: StateFlow<JobState?> = perCollection { container.work.priceState(it) }.stateInVm(null)

    /** Database delle carte installato, null se manca. */
    val catalogInfo: StateFlow<CatalogInfo?> = container.catalogStore.info

    val catalogUrl: StateFlow<String> = container.settings.catalogUrl.stateInVm(SettingsStore.DEFAULT_CATALOG_URL)

    private val _catalogDownload = MutableStateFlow<CatalogDownload?>(null)

    /** Scaricamento del database delle carte in corso, null se non c'è. */
    val catalogDownload: StateFlow<CatalogDownload?> = _catalogDownload.asStateFlow()
    val lastCatalogSync: StateFlow<Long> = container.settings.lastCatalogSync.stateInVm(0L)
    val cardTraderToken: StateFlow<String> = container.settings.cardTraderToken.stateInVm("")
    val countFirstEdition: StateFlow<Boolean> = container.settings.countFirstEdition.stateInVm(true)
    val priceVisibility: StateFlow<PriceVisibility> = container.settings.priceVisibility.stateInVm(PriceVisibility())
    val priceAlertsEnabled: StateFlow<Boolean> = container.settings.priceAlertsEnabled.stateInVm(false)
    val priceAlertChangePercent: StateFlow<Int> = container.settings.priceAlertChangePercent.stateInVm(PriceAlertRules.DEFAULT_CHANGE_PERCENT)

    /** Avvisi di prezzo per id della variante. */
    val priceAlerts: StateFlow<Map<String, PriceAlertEntity>> = container.prices.observeAlerts().stateInVm(emptyMap())

    private val _filter = MutableStateFlow(CollectionFilter())
    val filter: StateFlow<CollectionFilter> = _filter.asStateFlow()

    /** Carte della collezione aperta dopo ricerca, filtri e ordinamento (calcolate fuori dal thread UI). */
    val visibleCards: StateFlow<List<CardUi>> = combine(library, _filter) { cards, f -> applyFilter(cards.orEmpty(), f) }
        .flowOn(Dispatchers.Default)
        .stateInVm(emptyList())

    private val _setPicker = MutableStateFlow(SetPickerState())
    val setPicker: StateFlow<SetPickerState> = _setPicker.asStateFlow()

    private val _search = MutableStateFlow(CardSearchState())
    val search: StateFlow<CardSearchState> = _search.asStateFlow()

    private val _cardAction = MutableStateFlow<CardAction?>(null)

    /** Azione sulla carta aperta in corso (prezzo o immagine), null se nessuna. */
    val cardAction: StateFlow<CardAction?> = _cardAction.asStateFlow()

    private val _cardReport = MutableStateFlow<CardActionReport?>(null)

    /** Dialog dell'azione sulla carta: avanzamento mentre è in corso, poi l'esito fino a quando l'utente lo chiude. */
    val cardReport: StateFlow<CardActionReport?> = _cardReport.asStateFlow()

    /**
     * Chiude il dialog dell'esito.
     * @return Unit
     */
    fun dismissCardReport() {
        if (_cardAction.value == null) _cardReport.value = null
    }

    private fun report(@StringRes id: Int, vararg args: Any) {
        _cardReport.value = _cardReport.value?.copy(message = UiText(id, *args))
    }

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)

    /** Messaggi brevi da mostrare in uno snackbar, tradotti al momento della visualizzazione. */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /**
     * Accoda un messaggio per lo snackbar.
     * @param id Int - risorsa stringa.
     * @param args Any - argomenti del formato.
     * @return Unit
     */
    private fun say(@StringRes id: Int, vararg args: Any) {
        _messages.tryEmit(UiText(id, *args))
    }

    /**
     * Converte un flusso in StateFlow legato al ViewModel.
     * @receiver Flow<T> - flusso di partenza.
     * @param initial T - valore iniziale.
     * @return StateFlow<T> - stato condiviso.
     */
    private fun <T> Flow<T>.stateInVm(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /**
     * Crea un flusso che segue la collezione aperta.
     * @param empty T - valore quando nessuna collezione è aperta.
     * @param source (Long) -> Flow<T> - flusso per una collezione.
     * @return Flow<T> - flusso della collezione corrente.
     */
    private fun <T> perCollection(empty: T, source: (Long) -> Flow<T>): Flow<T> =
        _selected.flatMapLatest { id -> if (id == null) flowOf(empty) else source(id) }

    /**
     * Variante di [perCollection] con valore vuoto null.
     * @param source (Long) -> Flow<T?> - flusso per una collezione.
     * @return Flow<T?> - flusso della collezione corrente.
     */
    private fun <T> perCollection(source: (Long) -> Flow<T?>): Flow<T?> = perCollection(null, source)

    /**
     * Apre una collezione (azzera filtri e ricerca se cambia).
     * @param collectionId Long - collezione.
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
     * Esegue un'azione sulla collezione aperta, se c'è.
     * @param block suspend (Long) -> Unit - azione.
     * @return Unit
     */
    private fun withSelected(block: suspend (Long) -> Unit) {
        val id = _selected.value ?: return
        viewModelScope.launch { block(id) }
    }

    /**
     * Aggiorna i filtri della collezione.
     * @param change (CollectionFilter) -> CollectionFilter - modifica.
     * @return Unit
     */
    fun updateFilter(change: (CollectionFilter) -> CollectionFilter) {
        _filter.value = change(_filter.value).let { it.copy(query = it.query.take(40)) }
    }

    /**
     * Avvia il sync del catalogo della collezione aperta.
     * @return Unit
     */
    fun startCatalogSync() {
        _selected.value?.let { container.work.startCatalogSync(it) }
    }

    /**
     * Scarica (o aggiorna) il database delle carte dall'indirizzo indicato nelle impostazioni.
     * @param url String - indirizzo HTTPS; se è diverso da quello salvato lo ricorda per i prossimi aggiornamenti.
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
     * Avvia l'aggiornamento prezzi della collezione aperta.
     * @return Unit
     */
    fun startPriceCheck() {
        _selected.value?.let { container.work.startPriceCheck(it) }
    }

    /**
     * Crea una collezione e, se ha una ricerca, ne scarica subito il catalogo.
     * @param name String - nome.
     * @param languages List<Language> - lingue della prima ricerca (una ricerca per lingua).
     * @param pattern String - nomi della prima ricerca, separati da virgole (vuota = nessuna).
     * @param onResult (InputError?, Long?) -> Unit - riceve l'errore oppure l'id creato.
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
     * Scarica l'elenco dei set di una lingua per il selettore (se non è già caricato).
     * @param language Language - lingua dei set.
     * @param force Boolean - true per riscaricarlo anche se presente (es. dopo un errore).
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
     * Crea una collezione per set e ne scarica subito le carte.
     * @param name String - nome (vuoto = nome del set).
     * @param language Language - lingua del catalogo.
     * @param set SetChoice - set scelto.
     * @param kind QueryKind - set base o master set.
     * @param onResult (InputError?, Long?) -> Unit - riceve l'errore oppure l'id creato.
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
     * Aggiunge un set intero alla collezione aperta e ne avvia il download.
     * @param language Language - lingua del catalogo.
     * @param set SetChoice - set scelto.
     * @param kind QueryKind - set base o master set.
     * @param onResult (InputError?) -> Unit - riceve l'errore, oppure null se aggiunto.
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
     * Rinomina una collezione.
     * @param collectionId Long - collezione.
     * @param name String - nuovo nome.
     * @param onResult (InputError?) -> Unit - riceve l'errore, oppure null se rinominata.
     * @return Unit
     */
    fun renameCollection(collectionId: Long, name: String, onResult: (InputError?) -> Unit) {
        viewModelScope.launch { onResult(container.collection.rename(collectionId, name)) }
    }

    /**
     * Cambia la lingua della collezione aperta.
     * @param language Language? - lingua fissa, oppure null per una collezione mista.
     * @return Unit
     */
    fun setCollectionLanguage(language: Language?) = withSelected { id -> container.collection.setLanguage(id, language) }

    /**
     * Legge le impostazioni di una collezione per mostrarle nel dialogo.
     * @param collectionId Long - collezione.
     * @param onResult (CollectionSettings?) -> Unit - impostazioni, oppure null se la collezione non esiste più.
     * @return Unit
     */
    fun loadCollectionSettings(collectionId: Long, onResult: (CollectionSettings?) -> Unit) {
        viewModelScope.launch { onResult(container.collection.settings(collectionId)) }
    }

    /**
     * Salva nome e lingue di una collezione; se servono carte nuove avvia il sync del catalogo.
     * @param collectionId Long - collezione.
     * @param name String - nome.
     * @param languages Set<Language> - lingue volute.
     * @param onResult (InputError?) -> Unit - errore da mostrare, oppure null se salvate.
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
     * Elimina una collezione.
     * @param collectionId Long - collezione.
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
     * Esporta una collezione nel file scelto.
     * @param collectionId Long - collezione.
     * @param uri Uri - destinazione.
     * @param images ImageExport - immagini da includere.
     * @return Unit
     */
    fun exportCollection(collectionId: Long, uri: Uri, images: ImageExport) {
        viewModelScope.launch {
            say(R.string.msg_exporting)
            when (val result = container.transfer.export(collectionId, uri, images)) {
                is ExportResult.Done ->
                    if (images == ImageExport.NONE) say(R.string.msg_exported, result.cards) else say(R.string.msg_exported_images, result.cards, result.withImages)
                is ExportResult.Failed -> say(result.error.messageRes())
            }
        }
    }

    /**
     * Importa un file come nuova collezione.
     * @param uri Uri - file sorgente.
     * @param defaultName String - nome da usare se il file non ne ha uno (già tradotto).
     * @return Unit
     */
    fun importCollection(uri: Uri, defaultName: String) {
        viewModelScope.launch {
            say(R.string.msg_importing)
            when (val result = container.transfer.import(uri, defaultName)) {
                is ImportOutcome.Done -> {
                    if (result.cards == 0) say(R.string.msg_imported_no_cards, result.name) else say(R.string.msg_imported, result.name, result.cards, result.images)
                    if (result.skipped > 0) say(R.string.msg_import_skipped, result.skipped)
                }
                is ImportOutcome.Invalid -> say(result.error.messageRes())
                is ImportOutcome.Failed -> say(result.error.messageRes())
            }
        }
    }

    /**
     * Aggiunge o toglie copie di una variante nella collezione aperta, partendo dal valore salvato.
     * @param variantId String - variante.
     * @param delta Int - copie da aggiungere (negativo per toglierle).
     * @return Unit
     */
    fun changeQuantity(variantId: String, delta: Int) = withSelected { container.collection.changeQuantity(it, variantId, delta) }

    /**
     * Imposta la condizione di una variante nella collezione aperta.
     * @param variantId String - variante.
     * @param condition Condition - condizione.
     * @return Unit
     */
    fun setCondition(variantId: String, condition: Condition) = withSelected { container.collection.setCondition(it, variantId, condition) }

    /**
     * Indica se una variante conta come slot nella collezione aperta.
     * @param variantId String - variante.
     * @param wanted Boolean - true se desiderata.
     * @return Unit
     */
    fun setWanted(variantId: String, wanted: Boolean) = withSelected { container.collection.setWanted(it, variantId, wanted) }

    /**
     * Fa contare o no le versioni 1st Edition tra le mancanti e nei totali.
     * @param counted Boolean - true per contarle.
     * @return Unit
     */
    fun setCountFirstEdition(counted: Boolean) {
        viewModelScope.launch { container.settings.setCountFirstEdition(counted) }
    }

    /**
     * Attiva o spegne gli avvisi di prezzo e il controllo giornaliero che li verifica.
     * @param enabled Boolean - true per attivarli.
     * @return Unit
     */
    fun setPriceAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setPriceAlertsEnabled(enabled)
            if (enabled) container.work.schedulePriceAlerts() else container.work.cancelPriceAlerts()
        }
    }

    /**
     * Imposta la variazione minima proposta ai nuovi avvisi "il prezzo sale o scende" (ognuno poi ha la sua).
     * @param percent Int - percentuale (ricondotta ai limiti ammessi).
     * @return Unit
     */
    fun setPriceAlertChangePercent(percent: Int) {
        viewModelScope.launch { container.settings.setPriceAlertChangePercent(percent) }
    }

    /**
     * Crea, modifica o toglie l'avviso di prezzo di una variante.
     * @param variantId String - variante.
     * @param notifyChanges Boolean - true per l'avviso "sale o scende".
     * @param changePercent Int - variazione minima scelta per questa variante, in percentuale.
     * @param targetText String - prezzo obiettivo scritto dall'utente (vuoto = nessuno; virgola o punto).
     * @return Boolean - false se il prezzo obiettivo non è valido (il campo resta da correggere), true altrimenti.
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
     * Toglie l'avviso di prezzo di una variante.
     * @param variantId String - variante.
     * @return Unit
     */
    fun deletePriceAlert(variantId: String) {
        viewModelScope.launch {
            container.prices.deleteAlert(variantId)
            say(R.string.msg_alert_removed)
        }
    }

    /**
     * Mostra o nasconde un tipo di prezzo in tutta l'app.
     * @param kind PriceKind - prezzo da cambiare.
     * @param shown Boolean - true per mostrarlo.
     * @return Unit
     */
    fun setPriceShown(kind: PriceKind, shown: Boolean) {
        viewModelScope.launch { container.settings.setPriceShown(kind, shown) }
    }

    /**
     * Salva il token CardTrader cifrato (spazi e a capo ai bordi vengono tolti).
     * @param input String - testo incollato dall'utente.
     * @return Unit
     */
    fun saveCardTraderToken(input: String) {
        viewModelScope.launch {
            if (!container.settings.setCardTraderToken(input.trim())) say(R.string.msg_token_not_saved)
        }
    }

    /**
     * Valida e salva un prezzo manuale (i prezzi sono condivisi tra le collezioni).
     * @param variantId String - variante.
     * @param input String - testo inserito.
     * @return Boolean - true se valido e salvato.
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
     * Aggiorna subito i prezzi di una sola carta.
     * @param cardId String - carta.
     * @return Unit
     */
    fun checkCardPrice(cardId: String) = runCardAction(CardAction.PRICE) {
        val result = container.prices.checkCard(cardId, _selected.value)
        val found = result.ok + result.fallback
        when {
            result.abort == AbortReason.SOURCE_PAUSED -> report(R.string.abort_source_paused)
            found > 0 -> report(R.string.msg_card_price_updated, found)
            result.abort == AbortReason.OFFLINE -> report(R.string.msg_network_error)
            else -> report(R.string.msg_card_price_none)
        }
    }

    /**
     * Scarica da TCGdex l'immagine ufficiale di una carta; se TCGdex non la ha (o non risponde) e c'è il token CardTrader, la prende da lì.
     * @param cardId String - carta.
     * @return Unit
     */
    fun downloadCardImage(cardId: String) = runCardAction(CardAction.DOWNLOAD_IMAGE) {
        var result = container.catalog.downloadImage(cardId)
        if (result == ImageResult.NOT_AVAILABLE || result == ImageResult.NETWORK_ERROR) {
            val fromCardTrader = container.prices.downloadImageFromCardTrader(cardId)
            // Senza token o senza abbinamento resta l'esito di TCGdex, che spiega meglio perché manca.
            if (fromCardTrader == ImageResult.DOWNLOADED) result = fromCardTrader
        }
        report(result.messageRes())
    }

    /**
     * Usa un'immagine scelta dall'utente per una carta.
     * @param cardId String - carta.
     * @param uri Uri - immagine scelta con il selettore di sistema.
     * @return Unit
     */
    fun setCardImage(cardId: String, uri: Uri) = runCardAction(CardAction.PICK_IMAGE) {
        report(container.catalog.setManualImage(cardId, uri).messageRes())
    }

    /**
     * Esegue un'azione sulla carta aperta, una alla volta, esponendo lo stato in [cardAction].
     * @param action CardAction - azione da segnalare alla UI.
     * @param block suspend () -> Unit - lavoro da eseguire.
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
                // Senza esito (azione interrotta) il dialog si chiude da solo.
                if (_cardReport.value?.message == null) _cardReport.value = null
            }
        }
    }

    /**
     * Aggiunge una ricerca alla collezione aperta.
     * @param language Language - lingua.
     * @param pattern String - testo da cercare.
     * @param onResult (InputError?) -> Unit - riceve l'errore, oppure null se aggiunta.
     * @return Unit
     */
    fun addQuery(language: Language, pattern: String, onResult: (InputError?) -> Unit) = withSelected { id ->
        val error = container.collection.addQuery(id, language, pattern)
        onResult(error)
        if (error == null) say(R.string.msg_search_added)
    }

    /**
     * Attiva o disattiva una ricerca.
     * @param id Long - ricerca.
     * @param enabled Boolean - nuovo stato.
     * @return Unit
     */
    fun setQueryEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { container.collection.setQueryEnabled(id, enabled) }
    }

    /**
     * Elimina una ricerca.
     * @param id Long - ricerca.
     * @return Unit
     */
    fun deleteQuery(id: Long) {
        viewModelScope.launch { container.collection.deleteQuery(id) }
    }

    /**
     * Cerca nel database delle carte quelle da aggiungere a mano.
     * @param language Language - lingua.
     * @param text String - testo da cercare.
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
     * Aggiunge alla collezione aperta una carta trovata con la ricerca.
     * @param card CardBrief - carta scelta.
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
     * Toglie una carta dalla collezione aperta.
     * @param cardId String - carta.
     * @return Unit
     */
    fun hideCard(cardId: String) = withSelected { id ->
        container.collection.hideCard(id, cardId)
        say(R.string.msg_card_removed)
    }

    /**
     * Toglie più carte dalla collezione aperta.
     * @param cardIds Set<String> - carte.
     * @return Unit
     */
    fun hideCards(cardIds: Set<String>) = withSelected { id ->
        cardIds.forEach { container.collection.hideCard(id, it) }
        say(R.string.msg_cards_removed, cardIds.size)
    }

    /**
     * Rimette una carta nella collezione aperta.
     * @param cardId String - carta.
     * @return Unit
     */
    fun restoreCard(cardId: String) = withSelected { container.collection.restoreCard(it, cardId) }

    companion object {
        /** Factory che recupera il contenitore delle dipendenze dall'Application. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as CardCollectionApp
                MainViewModel(app.container)
            }
        }
    }
}
