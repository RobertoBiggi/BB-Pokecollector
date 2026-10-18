package com.robertobiggi.cardcollection.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.robertobiggi.cardcollection.AppContainer
import com.robertobiggi.cardcollection.CardCollectionApp
import com.robertobiggi.cardcollection.core.backup.ImageExport
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.tcgdex.CardBriefDto
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.CollectionSummary
import com.robertobiggi.cardcollection.work.JobState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Filtro per possesso nella griglia. */
enum class OwnedFilter { ALL, OWNED, MISSING }

/** Ordinamento della griglia. */
enum class SortMode(val label: String) {
    RELEASE("Uscita"),
    PRICE("Prezzo"),
    CODE("Codice"),
    NAME("Nome"),
}

/** Stato dei filtri della collezione. */
data class CollectionFilter(
    val query: String = "",
    val owned: OwnedFilter = OwnedFilter.ALL,
    val language: Language? = null,
    val sort: SortMode = SortMode.RELEASE,
)

/** Stato della ricerca di carte da aggiungere a mano. */
data class CardSearchState(
    val loading: Boolean = false,
    val results: List<CardBriefDto> = emptyList(),
    val language: Language = Language.EN,
    val error: String? = null,
)

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

    private val _search = MutableStateFlow(CardSearchState())
    val search: StateFlow<CardSearchState> = _search.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Messaggi brevi da mostrare in uno snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
     * @param onResult (String?, Long?) -> Unit - riceve l'errore oppure l'id creato.
     * @return Unit
     */
    fun createCollection(name: String, language: Language, pattern: String, onResult: (String?, Long?) -> Unit) {
        viewModelScope.launch {
            container.collection.createCollection(name, language, pattern).fold(
                onSuccess = { id ->
                    if (pattern.isNotBlank()) container.work.startCatalogSync(id)
                    onResult(null, id)
                },
                onFailure = { onResult(it.message, null) },
            )
        }
    }

    /**
     * Rinomina una collezione.
     * @param collectionId Long - collezione.
     * @param name String - nuovo nome.
     * @param onResult (String?) -> Unit - riceve l'errore, oppure null se rinominata.
     * @return Unit
     */
    fun renameCollection(collectionId: Long, name: String, onResult: (String?) -> Unit) {
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
            _messages.tryEmit("Collezione eliminata.")
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
            _messages.tryEmit("Esportazione in corso…")
            _messages.tryEmit(container.transfer.export(collectionId, uri, images))
        }
    }

    /**
     * Importa un file come nuova collezione.
     * @param uri Uri - file sorgente.
     * @return Unit
     */
    fun importCollection(uri: Uri) {
        viewModelScope.launch {
            _messages.tryEmit("Importazione in corso…")
            _messages.tryEmit(container.transfer.import(uri).message)
        }
    }

    /**
     * Imposta le copie possedute di una variante nella collezione aperta.
     * @param variantId String - variante.
     * @param quantity Int - nuova quantità.
     * @return Unit
     */
    fun setQuantity(variantId: String, quantity: Int) = withSelected { container.collection.setQuantity(it, variantId, quantity) }

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
            _messages.tryEmit("Prezzo manuale salvato.")
        }
        return true
    }

    /**
     * Aggiunge una ricerca alla collezione aperta.
     * @param language Language - lingua.
     * @param pattern String - testo da cercare.
     * @param onResult (String?) -> Unit - riceve l'errore, oppure null se aggiunta.
     * @return Unit
     */
    fun addQuery(language: Language, pattern: String, onResult: (String?) -> Unit) = withSelected { id ->
        val error = container.collection.addQuery(id, language, pattern)
        onResult(error)
        if (error == null) _messages.tryEmit("Ricerca aggiunta: premi \"Aggiorna catalogo\" per scaricare le carte.")
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
            _search.value = container.catalog.search(language, text.trim()).fold(
                onSuccess = { CardSearchState(results = it, language = language, error = if (it.isEmpty()) "Nessuna carta trovata." else null) },
                onFailure = { CardSearchState(language = language, error = it.message) },
            )
        }
    }

    /**
     * Aggiunge alla collezione aperta una carta trovata con la ricerca.
     * @param card CardBriefDto - carta scelta.
     * @return Unit
     */
    fun addSingleCard(card: CardBriefDto) = withSelected { id ->
        _messages.tryEmit("Aggiungo ${card.name}…")
        _messages.tryEmit(container.catalog.addSingleCard(id, _search.value.language, card.id))
    }

    /**
     * Toglie una carta dalla collezione aperta.
     * @param cardId String - carta.
     * @return Unit
     */
    fun hideCard(cardId: String) = withSelected { id ->
        container.collection.hideCard(id, cardId)
        _messages.tryEmit("Carta tolta dalla collezione. Puoi ripristinarla da Gestione.")
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
