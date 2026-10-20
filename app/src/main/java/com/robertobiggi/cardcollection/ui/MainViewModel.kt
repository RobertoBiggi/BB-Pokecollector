package com.robertobiggi.cardcollection.ui

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
import com.robertobiggi.cardcollection.core.tcgdex.CardBriefDto
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import com.robertobiggi.cardcollection.data.repo.AbortReason
import com.robertobiggi.cardcollection.data.repo.AddCardResult
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.CollectionSummary
import com.robertobiggi.cardcollection.data.repo.CreateResult
import com.robertobiggi.cardcollection.data.repo.ExportResult
import com.robertobiggi.cardcollection.data.repo.ImportOutcome
import com.robertobiggi.cardcollection.data.repo.InputError
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
data class CardSearchState(
    val loading: Boolean = false,
    val results: List<CardBriefDto> = emptyList(),
    val language: Language = Language.EN,
    @StringRes val messageRes: Int? = null,
)

/** Azione in corso nel dettaglio di una carta. */
enum class CardAction { PRICE, DOWNLOAD_IMAGE, PICK_IMAGE }

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
    val library: StateFlow<List<CardUi>?> = perCollection { container.collection.library(it) }.stateInVm(null)
    val queries: StateFlow<List<TrackedQueryEntity>> = perCollection(emptyList()) { container.collection.queries(it) }.stateInVm(emptyList())
    val hiddenCards: StateFlow<List<CardEntity>> = perCollection(emptyList()) { container.collection.hiddenCards(it) }.stateInVm(emptyList())
    val runs: StateFlow<List<PriceRunEntity>> = perCollection(emptyList()) { container.collection.runs(it) }.stateInVm(emptyList())
    val catalogJob: StateFlow<JobState?> = perCollection { container.work.catalogState(it) }.stateInVm(null)
    val priceJob: StateFlow<JobState?> = perCollection { container.work.priceState(it) }.stateInVm(null)

    val tcgdexPausedUntil: StateFlow<Long> = container.settings.tcgdexPausedUntil.stateInVm(0L)
    val lastCatalogSync: StateFlow<Long> = container.settings.lastCatalogSync.stateInVm(0L)

    private val _filter = MutableStateFlow(CollectionFilter())
    val filter: StateFlow<CollectionFilter> = _filter.asStateFlow()

    /** Carte della collezione aperta dopo ricerca, filtri e ordinamento (calcolate fuori dal thread UI). */
    val visibleCards: StateFlow<List<CardUi>> = combine(library, _filter) { cards, f -> applyFilter(cards.orEmpty(), f) }
        .flowOn(Dispatchers.Default)
        .stateInVm(emptyList())

    private val _search = MutableStateFlow(CardSearchState())
    val search: StateFlow<CardSearchState> = _search.asStateFlow()

    private val _cardAction = MutableStateFlow<CardAction?>(null)

    /** Azione sulla carta aperta in corso (prezzo o immagine), null se nessuna. */
    val cardAction: StateFlow<CardAction?> = _cardAction.asStateFlow()

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
     * Avvia l'aggiornamento prezzi della collezione aperta.
     * @return Unit
     */
    fun startPriceCheck() {
        _selected.value?.let { container.work.startPriceCheck(it) }
    }

    /**
     * Crea una collezione e, se ha una ricerca, ne scarica subito il catalogo.
     * @param name String - nome.
     * @param language Language - lingua della prima ricerca.
     * @param pattern String - prima ricerca (vuota = nessuna).
     * @param onResult (InputError?, Long?) -> Unit - riceve l'errore oppure l'id creato.
     * @return Unit
     */
    fun createCollection(name: String, language: Language, pattern: String, onResult: (InputError?, Long?) -> Unit) {
        viewModelScope.launch {
            when (val result = container.collection.createCollection(name, language, pattern)) {
                is CreateResult.Created -> {
                    if (pattern.isNotBlank()) container.work.startCatalogSync(result.id)
                    onResult(null, result.id)
                }
                is CreateResult.Invalid -> onResult(result.error, null)
            }
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
        val result = container.prices.checkCard(cardId)
        val found = result.ok + result.fallback
        when {
            result.abort == AbortReason.SOURCE_PAUSED -> say(R.string.abort_source_paused)
            found > 0 -> say(R.string.msg_card_price_updated, found)
            result.abort == AbortReason.OFFLINE -> say(R.string.msg_network_error)
            else -> say(R.string.msg_card_price_none)
        }
    }

    /**
     * Scarica da TCGdex l'immagine ufficiale di una carta.
     * @param cardId String - carta.
     * @return Unit
     */
    fun downloadCardImage(cardId: String) = runCardAction(CardAction.DOWNLOAD_IMAGE) {
        say(container.catalog.downloadImage(cardId).messageRes())
    }

    /**
     * Usa un'immagine scelta dall'utente per una carta.
     * @param cardId String - carta.
     * @param uri Uri - immagine scelta con il selettore di sistema.
     * @return Unit
     */
    fun setCardImage(cardId: String, uri: Uri) = runCardAction(CardAction.PICK_IMAGE) {
        say(container.catalog.setManualImage(cardId, uri).messageRes())
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
        viewModelScope.launch {
            try {
                block()
            } finally {
                _cardAction.value = null
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
     * Cerca su TCGdex le carte da aggiungere a mano.
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
                SearchResult.NetworkError -> CardSearchState(language = language, messageRes = R.string.msg_network_error)
            }
        }
    }

    /**
     * Aggiunge alla collezione aperta una carta trovata con la ricerca.
     * @param card CardBriefDto - carta scelta.
     * @return Unit
     */
    fun addSingleCard(card: CardBriefDto) = withSelected { id ->
        say(R.string.msg_adding_card, card.name)
        when (val result = container.catalog.addSingleCard(id, _search.value.language, card.id)) {
            is AddCardResult.Added -> say(R.string.msg_card_added, result.name)
            AddCardResult.NotAllowed -> say(R.string.msg_card_not_allowed)
            AddCardResult.NetworkError -> say(R.string.msg_network_error)
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
