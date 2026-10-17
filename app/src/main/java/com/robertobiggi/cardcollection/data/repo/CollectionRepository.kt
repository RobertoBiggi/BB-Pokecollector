package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CollectionEntryEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.TrackedQueryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Lettura della libreria e modifiche dell'utente (quantità, condizione, slot, ricerche). */
class CollectionRepository(private val db: AppDatabase) {

    private val catalogDao = db.catalogDao()
    private val collectionDao = db.collectionDao()
    private val priceDao = db.priceDao()

    /** Carte tracciate con varianti, dati utente e prezzi; si aggiorna a ogni modifica del database. */
    val library: Flow<List<CardUi>> = combine(
        catalogDao.observeTrackedCards(),
        catalogDao.observeVariants(),
        collectionDao.observeEntries(),
        priceDao.observeSnapshots(),
    ) { cards, variants, entries, snapshots -> LibraryAssembler.assemble(cards, variants, entries, snapshots) }

    /** Ricerche tracciate. */
    val queries: Flow<List<TrackedQueryEntity>> = catalogDao.observeQueries()

    /** Ultimi aggiornamenti prezzi. */
    val runs: Flow<List<PriceRunEntity>> = priceDao.observeRuns()

    /**
     * Imposta il numero di copie possedute di una variante.
     * @param variantId String - variante.
     * @param quantity Int - nuova quantità (limitata a 0..999).
     * @return Unit
     */
    suspend fun setQuantity(variantId: String, quantity: Int) = update(variantId) { it.copy(quantity = Validators.clampQuantity(quantity)) }

    /**
     * Imposta la condizione della copia posseduta.
     * @param variantId String - variante.
     * @param condition Condition - nuova condizione.
     * @return Unit
     */
    suspend fun setCondition(variantId: String, condition: Condition) = update(variantId) { it.copy(condition = condition.name) }

    /**
     * Indica se la variante conta come slot della collezione.
     * @param variantId String - variante.
     * @param wanted Boolean - true se la variante è desiderata.
     * @return Unit
     */
    suspend fun setWanted(variantId: String, wanted: Boolean) = update(variantId) { it.copy(wanted = wanted) }

    /**
     * Aggiunge una ricerca dopo averla validata.
     * @param language Language - lingua del catalogo.
     * @param pattern String - testo da cercare nel nome.
     * @return String? - messaggio di errore per l'utente, oppure null se la ricerca è stata aggiunta.
     */
    suspend fun addQuery(language: Language, pattern: String): String? {
        val clean = pattern.trim()
        if (!Validators.isValidQueryPattern(clean)) return "Usa da 2 a 40 caratteri: lettere, numeri, spazi, apostrofo, punto, trattino o &."
        val id = catalogDao.insertQuery(TrackedQueryEntity(language = language.code, pattern = clean))
        return if (id == -1L) "Questa ricerca c'è già." else null
    }

    /**
     * Attiva o disattiva una ricerca: le carte di una ricerca disattivata spariscono dalla collezione,
     * ma le quantità restano salvate.
     * @param id Long - id della ricerca.
     * @param enabled Boolean - nuovo stato.
     * @return Unit
     */
    suspend fun setQueryEnabled(id: Long, enabled: Boolean) = catalogDao.setQueryEnabled(id, enabled)

    /**
     * Elimina una ricerca (le carte già scaricate restano nel database ma non vengono mostrate).
     * @param id Long - id della ricerca.
     * @return Unit
     */
    suspend fun deleteQuery(id: Long) = catalogDao.deleteQuery(id)

    /**
     * Legge, modifica e salva i dati utente di una variante.
     * @param variantId String - variante.
     * @param change (CollectionEntryEntity) -> CollectionEntryEntity - modifica da applicare.
     * @return Unit
     */
    private suspend fun update(variantId: String, change: (CollectionEntryEntity) -> CollectionEntryEntity) {
        val current = collectionDao.getEntry(variantId) ?: CollectionEntryEntity(variantId = variantId)
        collectionDao.upsertEntry(change(current))
    }
}
