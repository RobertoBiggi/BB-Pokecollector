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

/** Errore di un dato inserito dall'utente; la UI lo traduce in un messaggio. */
enum class InputError { NAME_EMPTY, QUERY_INVALID, QUERY_DUPLICATE }

/**
 * Impostazioni modificabili di una collezione.
 * @property name nome.
 * @property languages lingue delle ricerche.
 * @property canAddLanguage true se c'è almeno una ricerca per nome da copiare in una lingua nuova.
 */
data class CollectionSettings(val name: String, val languages: Set<Language>, val canAddLanguage: Boolean)

/** Esito del salvataggio delle impostazioni di una collezione. */
sealed interface SettingsResult {
    /** Salvate; [needsSync] è true se ci sono ricerche nuove da cercare nel catalogo. */
    data class Saved(val needsSync: Boolean) : SettingsResult

    /** Valori non validi. */
    data class Invalid(val error: InputError) : SettingsResult
}

/** Esito della creazione di una collezione. */
sealed interface CreateResult {
    /** Collezione creata con id [id]. */
    data class Created(val id: Long) : CreateResult

    /** Dati non validi. */
    data class Invalid(val error: InputError) : CreateResult
}

/**
 * Collezioni, libreria e modifiche dell'utente (quantità, condizione, slot, ricerche, carte).
 *
 * @param db database dell'app.
 * @param scope scope dell'applicazione, in cui viene condivisa la fotografia del database.
 * @param settings impostazioni: decidono quali prezzi mostrare.
 */
class CollectionRepository(private val db: AppDatabase, scope: CoroutineScope, private val settings: SettingsStore) {

    private val catalogDao = db.catalogDao()
    private val collectionDao = db.collectionDao()
    private val priceDao = db.priceDao()

    /**
     * Fotografia delle tabelle osservate: si aggiorna a ogni modifica del database. È calcolata una
     * sola volta per tutti gli osservatori, fuori dal thread dell'interfaccia; durante gli aggiornamenti
     * prezzi le modifiche ravvicinate vengono accorpate.
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
     * Ricava un dato dalla fotografia del database fuori dal thread dell'interfaccia, emettendo solo
     * quando cambia.
     * @param transform (DbState) -> T - calcolo da eseguire.
     * @return Flow<T> - risultato aggiornato.
     */
    private fun <T> derive(transform: (DbState) -> T): Flow<T> =
        combine(state, settings.priceVisibility, settings.countFirstEdition) { s, visibility, countFirstEdition ->
            transform(s.withVisiblePrices(visibility).copy(countFirstEdition = countFirstEdition))
        }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * Toglie dalla fotografia i prezzi che l'utente ha nascosto: CardTrader sparisce del tutto, euro e dollari
     * vengono azzerati nei prezzi che li contengono. I prezzi inseriti a mano contano come Cardmarket.
     * @param visibility PriceVisibility - prezzi da mostrare.
     * @return DbState - fotografia con i soli prezzi visibili.
     */
    private fun DbState.withVisiblePrices(visibility: PriceVisibility): DbState {
        if (visibility.showsAll) return this
        val visible = snapshots.mapNotNull { snap ->
            if (snap.source == CardTraderPriceProvider.DISPLAY_NAME) return@mapNotNull snap.takeIf { visibility.cardTrader }
            var shown = snap
            if (!visibility.cardmarket) shown = shown.copy(eur = null, eurLow = null, eurAvg30 = null)
            if (!visibility.tcgplayer) shown = shown.copy(usd = null, usdLow = null)
            shown
        }
        return copy(snapshots = visible)
    }

    /** Riepiloghi di tutte le collezioni per l'elenco iniziale. */
    val summaries: Flow<List<CollectionSummary>> = derive { LibraryAssembler.summaries(it) }

    /**
     * Libreria di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<List<CardUi>> - carte visibili con varianti, quantità e prezzi.
     */
    fun library(collectionId: Long): Flow<List<CardUi>> = derive { LibraryAssembler.libraryOf(it, collectionId) }

    /**
     * Carte tolte dall'utente da una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<List<CardEntity>> - carte nascoste.
     */
    fun hiddenCards(collectionId: Long): Flow<List<CardEntity>> = derive { s ->
        val ids = s.memberships.filter { it.collectionId == collectionId && it.hidden }.map { it.cardId }.toSet()
        s.cards.filter { it.id in ids }.sortedBy { it.name }
    }

    /**
     * Ricerche di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<List<TrackedQueryEntity>> - ricerche.
     */
    fun queries(collectionId: Long): Flow<List<TrackedQueryEntity>> = derive { s -> s.queries.filter { it.collectionId == collectionId } }

    /**
     * Ultimi aggiornamenti prezzi di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<List<PriceRunEntity>> - al massimo 10 run, dal più recente.
     */
    fun runs(collectionId: Long): Flow<List<PriceRunEntity>> = derive { s -> s.runs.filter { it.collectionId == collectionId }.take(10) }

    /**
     * Nome di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<String?> - nome, oppure null se la collezione non esiste più.
     */
    fun name(collectionId: Long): Flow<String?> = derive { s -> s.collections.firstOrNull { it.id == collectionId }?.name }

    /**
     * Lingua di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<String?> - valore di [CollectionLanguage] ("en", "ja" o "mixed"), oppure null se la collezione non esiste più.
     */
    fun language(collectionId: Long): Flow<String?> = derive { s -> s.collections.firstOrNull { it.id == collectionId }?.language }

    /**
     * Crea una collezione, con una prima ricerca facoltativa.
     * @param rawName String - nome inserito dall'utente.
     * @param languages Collection<Language> - lingue della prima ricerca: una ricerca per lingua. La lingua della
     *        collezione ne è dedotta (una sola tra EN e JP, altrimenti mista).
     * @param pattern String? - nomi della prima ricerca, anche più d'uno separati da virgole (vuoto = nessuna ricerca):
     *        per ogni nome e ogni lingua si crea una ricerca.
     * @return CreateResult - id della collezione creata, oppure l'errore da mostrare.
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
     * Cambia la lingua di una collezione. Le ricerche già salvate restano com'erano.
     * @param collectionId Long - collezione.
     * @param language Language? - lingua fissa, oppure null per una collezione mista.
     * @return Unit
     */
    suspend fun setLanguage(collectionId: Long, language: Language?) =
        catalogDao.setCollectionLanguage(collectionId, CollectionLanguage.codeOf(language))

    /**
     * Legge le impostazioni modificabili di una collezione.
     * @param collectionId Long - collezione.
     * @return CollectionSettings? - nome e lingue, oppure null se la collezione non esiste.
     */
    suspend fun settings(collectionId: Long): CollectionSettings? {
        val name = catalogDao.collectionName(collectionId) ?: return null
        val queries = languageQueries(collectionId)
        return CollectionSettings(name, CollectionLanguages.languagesOf(queries), CollectionLanguages.canAddLanguage(queries))
    }

    /**
     * Salva le impostazioni di una collezione: il nome e le lingue. Le lingue nuove copiano le ricerche per nome,
     * quelle tolte eliminano le loro ricerche (e le carte che solo quelle ricerche avevano trovato); le quantità restano
     * salvate e riappaiono se la lingua viene rimessa.
     * @param collectionId Long - collezione.
     * @param rawName String - nome voluto.
     * @param languages Set<Language> - lingue volute, almeno una.
     * @return SettingsResult - esito: errore sul nome, oppure se le lingue sono cambiate e servono nuove carte.
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
     * Ricerche di una collezione nella forma che serve per cambiarne le lingue.
     * @param collectionId Long - collezione.
     * @return List<LanguageQuery> - ricerche.
     */
    private suspend fun languageQueries(collectionId: Long): List<LanguageQuery> = catalogDao.queriesOf(collectionId).map {
        LanguageQuery(it.id, Language.fromCode(it.language), it.pattern, QueryKind.fromCode(it.kind) ?: QueryKind.NAME)
    }

    /**
     * Rende unico un nome di collezione aggiungendo " (2)", " (3)"… se serve.
     * @param name String - nome desiderato.
     * @return String - nome non ancora usato.
     */
    suspend fun uniqueName(name: String): String {
        val existing = catalogDao.collectionNames().map { it.lowercase() }.toSet()
        if (name.lowercase() !in existing) return name
        var n = 2
        while ("${name.take(54)} ($n)".lowercase() in existing) n++
        return "${name.take(54)} ($n)"
    }

    /**
     * Rinomina una collezione.
     * @param collectionId Long - collezione.
     * @param rawName String - nuovo nome.
     * @return InputError? - errore, oppure null se rinominata.
     */
    suspend fun rename(collectionId: Long, rawName: String): InputError? {
        val name = CollectionFiles.cleanName(rawName) ?: return InputError.NAME_EMPTY
        catalogDao.renameCollection(collectionId, name)
        return null
    }

    /**
     * Elimina una collezione con ricerche e quantità; il catalogo condiviso e i prezzi restano.
     * @param collectionId Long - collezione.
     * @return Unit
     */
    suspend fun delete(collectionId: Long) = catalogDao.deleteCollection(collectionId)

    /**
     * Aggiunge o toglie copie partendo dal valore salvato (non da quello mostrato), così i tocchi
     * ravvicinati su +/- non si perdono.
     * @param collectionId Long - collezione.
     * @param variantId String - variante.
     * @param delta Int - copie da aggiungere (negativo per toglierle).
     * @return Unit
     */
    suspend fun changeQuantity(collectionId: Long, variantId: String, delta: Int) =
        update(collectionId, variantId) { it.copy(quantity = Validators.clampQuantity(it.quantity + delta)) }

    /**
     * Imposta la condizione della copia posseduta.
     * @param collectionId Long - collezione.
     * @param variantId String - variante.
     * @param condition Condition - nuova condizione.
     * @return Unit
     */
    suspend fun setCondition(collectionId: Long, variantId: String, condition: Condition) =
        update(collectionId, variantId) { it.copy(condition = condition.name) }

    /**
     * Indica se la variante conta come slot della collezione.
     * @param collectionId Long - collezione.
     * @param variantId String - variante.
     * @param wanted Boolean - true se desiderata.
     * @return Unit
     */
    suspend fun setWanted(collectionId: Long, variantId: String, wanted: Boolean) =
        update(collectionId, variantId) { it.copy(wanted = wanted) }

    /**
     * Aggiunge una ricerca alla collezione dopo averla validata.
     * @param collectionId Long - collezione.
     * @param language Language - lingua del catalogo.
     * @param pattern String - testo da cercare nel nome.
     * @return InputError? - errore, oppure null se aggiunta.
     */
    suspend fun addQuery(collectionId: Long, language: Language, pattern: String): InputError? {
        val clean = pattern.trim()
        if (!Validators.isValidQueryPattern(clean)) return InputError.QUERY_INVALID
        val id = catalogDao.insertQuery(TrackedQueryEntity(collectionId = collectionId, language = language.code, pattern = clean))
        return if (id == -1L) InputError.QUERY_DUPLICATE else null
    }

    /**
     * Aggiunge alla collezione un set intero (set base o master set).
     * @param collectionId Long - collezione.
     * @param language Language - lingua del catalogo.
     * @param setId String - id del set (es. "base1", "M3", "Pt4").
     * @param kind QueryKind - [QueryKind.SET_BASE] o [QueryKind.SET_MASTER].
     * @return InputError? - errore, oppure null se aggiunto.
     */
    suspend fun addSetQuery(collectionId: Long, language: Language, setId: String, kind: QueryKind): InputError? {
        val clean = setId.trim()
        if (!kind.isSet || !QueryKind.isValidPattern(kind, clean)) return InputError.QUERY_INVALID
        val id = catalogDao.insertQuery(TrackedQueryEntity(collectionId = collectionId, language = language.code, pattern = clean, kind = kind.code))
        return if (id == -1L) InputError.QUERY_DUPLICATE else null
    }

    /**
     * Crea una collezione per set: un set intero, come set base o master set.
     * @param rawName String - nome inserito; se vuoto si usa il nome del set.
     * @param language Language - lingua del catalogo.
     * @param setId String - id del set.
     * @param setName String - nome del set, usato se il nome è vuoto.
     * @param kind QueryKind - [QueryKind.SET_BASE] o [QueryKind.SET_MASTER].
     * @return CreateResult - id della collezione creata, oppure l'errore da mostrare.
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
     * Attiva o disattiva una ricerca (le sue carte si nascondono ma le quantità restano).
     * @param id Long - ricerca.
     * @param enabled Boolean - nuovo stato.
     * @return Unit
     */
    suspend fun setQueryEnabled(id: Long, enabled: Boolean) = catalogDao.setQueryEnabled(id, enabled)

    /**
     * Elimina una ricerca e i collegamenti delle carte che aveva trovato.
     * @param id Long - ricerca.
     * @return Unit
     */
    suspend fun deleteQuery(id: Long) = catalogDao.deleteQuery(id)

    /**
     * Toglie una carta dalla collezione; il sync non la riaggiunge.
     * @param collectionId Long - collezione.
     * @param cardId String - carta.
     * @return Unit
     */
    suspend fun hideCard(collectionId: Long, cardId: String) = catalogDao.setHidden(collectionId, cardId, true)

    /**
     * Rimette nella collezione una carta tolta in precedenza.
     * @param collectionId Long - collezione.
     * @param cardId String - carta.
     * @return Unit
     */
    suspend fun restoreCard(collectionId: Long, cardId: String) = catalogDao.makeManual(collectionId, cardId)

    /**
     * Legge, modifica e salva i dati utente di una variante in una collezione.
     * @param collectionId Long - collezione.
     * @param variantId String - variante.
     * @param change (CollectionEntryEntity) -> CollectionEntryEntity - modifica.
     * @return Unit
     */
    private suspend fun update(collectionId: Long, variantId: String, change: (CollectionEntryEntity) -> CollectionEntryEntity) {
        db.withTransaction {
            val current = collectionDao.getEntry(collectionId, variantId) ?: CollectionEntryEntity(collectionId = collectionId, variantId = variantId)
            collectionDao.upsertEntry(change(current))
        }
    }

    private companion object {
        /** Tempo per cui la fotografia resta attiva senza osservatori (es. rotazione dello schermo). */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
