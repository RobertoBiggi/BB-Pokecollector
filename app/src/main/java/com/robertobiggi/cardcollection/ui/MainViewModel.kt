package com.robertobiggi.cardcollection.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.robertobiggi.cardcollection.AppContainer
import com.robertobiggi.cardcollection.CardCollectionApp
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.work.JobState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY

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

/**
 * ViewModel unico dell'app: espone la libreria dal database locale e inoltra le azioni ai repository.
 *
 * @param container dipendenze dell'app.
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    val library: StateFlow<List<CardUi>?> = container.collection.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val queries: StateFlow<List<TrackedQueryEntity>> = container.collection.queries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runs: StateFlow<List<PriceRunEntity>> = container.collection.runs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val catalogJob: StateFlow<JobState?> = container.work.catalogState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val priceJob: StateFlow<JobState?> = container.work.priceState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tcgdexPausedUntil: StateFlow<Long> = container.settings.tcgdexPausedUntil
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastCatalogSync: StateFlow<Long> = container.settings.lastCatalogSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _filter = MutableStateFlow(CollectionFilter())
    val filter: StateFlow<CollectionFilter> = _filter.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Messaggi brevi da mostrare in uno snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * Aggiorna i filtri della collezione.
     * @param change (CollectionFilter) -> CollectionFilter - modifica da applicare.
     * @return Unit
     */
    fun updateFilter(change: (CollectionFilter) -> CollectionFilter) {
        _filter.value = change(_filter.value).let { it.copy(query = it.query.take(40)) }
    }

    /**
     * Avvia il sync del catalogo in background.
     * @return Unit
     */
    fun startCatalogSync() = container.work.startCatalogSync()

    /**
     * Avvia l'aggiornamento prezzi in background.
     * @return Unit
     */
    fun startPriceCheck() = container.work.startPriceCheck()

    /**
     * Imposta le copie possedute di una variante.
     * @param variantId String - variante.
     * @param quantity Int - nuova quantità.
     * @return Unit
     */
    fun setQuantity(variantId: String, quantity: Int) {
        viewModelScope.launch { container.collection.setQuantity(variantId, quantity) }
    }

    /**
     * Imposta la condizione di una variante.
     * @param variantId String - variante.
     * @param condition Condition - condizione.
     * @return Unit
     */
    fun setCondition(variantId: String, condition: Condition) {
        viewModelScope.launch { container.collection.setCondition(variantId, condition) }
    }

    /**
     * Indica se una variante conta come slot.
     * @param variantId String - variante.
     * @param wanted Boolean - true se desiderata.
     * @return Unit
     */
    fun setWanted(variantId: String, wanted: Boolean) {
        viewModelScope.launch { container.collection.setWanted(variantId, wanted) }
    }

    /**
     * Valida e salva un prezzo manuale.
     * @param variantId String - variante.
     * @param input String - testo inserito dall'utente.
     * @return Boolean - true se il prezzo era valido ed è stato salvato.
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
     * Aggiunge una ricerca tracciata.
     * @param language Language - lingua.
     * @param pattern String - testo da cercare.
     * @param onResult (String?) -> Unit - riceve l'errore, oppure null se aggiunta.
     * @return Unit
     */
    fun addQuery(language: Language, pattern: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = container.collection.addQuery(language, pattern)
            onResult(error)
            if (error == null) _messages.tryEmit("Ricerca aggiunta: premi \"Aggiorna catalogo\" per scaricare le carte.")
        }
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
     * Esporta il backup nel file scelto.
     * @param uri Uri - destinazione.
     * @return Unit
     */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch { _messages.tryEmit(container.backup.export(uri)) }
    }

    /**
     * Importa un backup dal file scelto.
     * @param uri Uri - file sorgente.
     * @return Unit
     */
    fun importBackup(uri: Uri) {
        viewModelScope.launch { _messages.tryEmit(container.backup.import(uri)) }
    }

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
