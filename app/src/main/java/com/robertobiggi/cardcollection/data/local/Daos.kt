package com.robertobiggi.cardcollection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Accesso a collezioni, ricerche, carte e varianti. Tutte le query sono parametrizzate. */
@Dao
interface CatalogDao {

    /**
     * Osserva tutte le collezioni, in ordine di creazione.
     * @return Flow<List<CollectionEntity>> - collezioni aggiornate a ogni modifica.
     */
    @Query("SELECT * FROM collection ORDER BY createdAt")
    fun observeCollections(): Flow<List<CollectionEntity>>

    /**
     * Legge una collezione.
     * @param id Long - id della riga.
     * @return CollectionEntity? - collezione, oppure null se non esiste.
     */
    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun getCollection(id: Long): CollectionEntity?

    /**
     * Elenca i nomi di tutte le collezioni (per renderne unico uno nuovo).
     * @return List<String> - nomi.
     */
    @Query("SELECT name FROM collection")
    suspend fun collectionNames(): List<String>

    /**
     * Crea una collezione.
     * @param collection CollectionEntity - collezione da creare.
     * @return Long - id assegnato.
     */
    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    /**
     * Rinomina una collezione.
     * @param id Long - id della riga.
     * @param name String - nuovo nome.
     * @return Unit
     */
    @Query("UPDATE collection SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    /**
     * Cambia la lingua di una collezione.
     * @param id Long - id della riga.
     * @param language String - valore di CollectionLanguage ("en", "ja" o "mixed").
     * @return Unit
     */
    @Query("UPDATE collection SET language = :language WHERE id = :id")
    suspend fun setCollectionLanguage(id: Long, language: String)

    /**
     * Elimina una collezione; ricerche, appartenenze e quantità vengono eliminate a cascata.
     * @param id Long - id della riga.
     * @return Unit
     */
    @Query("DELETE FROM collection WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    /**
     * Osserva le ricerche di tutte le collezioni.
     * @return Flow<List<TrackedQueryEntity>> - ricerche ordinate per lingua e testo.
     */
    @Query("SELECT * FROM tracked_query ORDER BY language, pattern")
    fun observeAllQueries(): Flow<List<TrackedQueryEntity>>

    /**
     * Elenca le ricerche di una collezione.
     * @param collectionId Long - collezione.
     * @return List<TrackedQueryEntity> - ricerche.
     */
    @Query("SELECT * FROM tracked_query WHERE collectionId = :collectionId")
    suspend fun queriesOf(collectionId: Long): List<TrackedQueryEntity>

    /**
     * Elenca le ricerche attive di una collezione.
     * @param collectionId Long - collezione.
     * @return List<TrackedQueryEntity> - ricerche attive.
     */
    @Query("SELECT * FROM tracked_query WHERE collectionId = :collectionId AND enabled = 1")
    suspend fun enabledQueriesOf(collectionId: Long): List<TrackedQueryEntity>

    /**
     * Aggiunge una ricerca, ignorandola se esiste già nella collezione.
     * @param query TrackedQueryEntity - ricerca da aggiungere.
     * @return Long - id assegnato, oppure -1 se duplicata.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuery(query: TrackedQueryEntity): Long

    /**
     * Attiva o disattiva una ricerca.
     * @param id Long - id della riga.
     * @param enabled Boolean - true per attivarla.
     * @return Unit
     */
    @Query("UPDATE tracked_query SET enabled = :enabled WHERE id = :id")
    suspend fun setQueryEnabled(id: Long, enabled: Boolean)

    /**
     * Elimina una ricerca.
     * @param id Long - id della riga.
     * @return Unit
     */
    @Query("DELETE FROM tracked_query WHERE id = :id")
    suspend fun deleteQuery(id: Long)

    /**
     * Osserva tutte le carte del catalogo condiviso.
     * @return Flow<List<CardEntity>> - carte.
     */
    @Query("SELECT * FROM card")
    fun observeCards(): Flow<List<CardEntity>>

    /**
     * Legge una carta del catalogo.
     * @param id String - id della riga.
     * @return CardEntity? - carta, oppure null se non esiste.
     */
    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun getCard(id: String): CardEntity?

    /**
     * Elenca le carte visibili di una collezione: non nascoste e aggiunte a mano o da una ricerca attiva.
     * @param collectionId Long - collezione.
     * @return List<CardEntity> - carte visibili.
     */
    @Query(
        "SELECT c.* FROM card c INNER JOIN collection_card cc ON cc.cardId = c.id " +
            "WHERE cc.collectionId = :collectionId AND cc.hidden = 0 AND " +
            "(cc.queryId IS NULL OR cc.queryId IN (SELECT id FROM tracked_query WHERE enabled = 1))",
    )
    suspend fun visibleCardsOf(collectionId: Long): List<CardEntity>

    /**
     * Elenca le carte con un URL immagine ma senza tutte le immagini salvate.
     * @return List<CardEntity> - carte da completare.
     */
    @Query("SELECT * FROM card WHERE imageUrl IS NOT NULL AND (imageLowPath IS NULL OR imageHighPath IS NULL)")
    suspend fun cardsMissingImages(): List<CardEntity>

    /**
     * Elenca le carte inglesi e giapponesi senza URL immagine né immagini salvate (né scelte a mano).
     * @return List<CardEntity> - carte per cui si può provare un'altra fonte.
     */
    @Query("SELECT * FROM card WHERE imageUrl IS NULL AND imageLowPath IS NULL AND imageHighPath IS NULL AND language IN ('en', 'ja')")
    suspend fun cardsWithoutImage(): List<CardEntity>

    /**
     * Inserisce o aggiorna una carta del catalogo.
     * @param card CardEntity - carta da salvare.
     * @return Unit
     */
    @Upsert
    suspend fun upsertCard(card: CardEntity)

    /**
     * Salva i percorsi locali delle immagini di una carta.
     * @param id String - id della riga.
     * @param low String? - percorso dell'immagine piccola.
     * @param high String? - percorso dell'immagine grande.
     * @return Unit
     */
    @Query("UPDATE card SET imageLowPath = :low, imageHighPath = :high WHERE id = :id")
    suspend fun setImagePaths(id: String, low: String?, high: String?)

    /**
     * Imposta il numero del Pokédex di una carta solo se non è ancora noto.
     * @param id String - id della riga.
     * @param dexId Int - numero del Pokédex.
     * @return Unit
     */
    @Query("UPDATE card SET dexId = :dexId WHERE id = :id AND dexId IS NULL")
    suspend fun fillDexId(id: String, dexId: Int)

    /**
     * Aggiunge varianti, ignorando quelle già presenti.
     * @param variants List<CardVariantEntity> - varianti da aggiungere.
     * @return Unit
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVariants(variants: List<CardVariantEntity>)

    /**
     * Osserva tutte le varianti del catalogo.
     * @return Flow<List<CardVariantEntity>> - varianti.
     */
    @Query("SELECT * FROM card_variant")
    fun observeVariants(): Flow<List<CardVariantEntity>>

    /**
     * Elenca le varianti di alcune carte.
     * @param cardIds List<String> - id delle carte.
     * @return List<CardVariantEntity> - varianti.
     */
    @Query("SELECT * FROM card_variant WHERE cardId IN (:cardIds)")
    suspend fun variantsOf(cardIds: List<String>): List<CardVariantEntity>

    /**
     * Osserva le appartenenze carta ↔ collezione di tutte le collezioni.
     * @return Flow<List<CollectionCardEntity>> - appartenenze.
     */
    @Query("SELECT * FROM collection_card")
    fun observeMemberships(): Flow<List<CollectionCardEntity>>

    /**
     * Elenca le appartenenze di una collezione.
     * @param collectionId Long - collezione.
     * @return List<CollectionCardEntity> - appartenenze.
     */
    @Query("SELECT * FROM collection_card WHERE collectionId = :collectionId")
    suspend fun membershipsOf(collectionId: Long): List<CollectionCardEntity>

    /**
     * Collega una carta a una collezione, ignorando i collegamenti già presenti.
     * @param membership CollectionCardEntity - collegamento da aggiungere.
     * @return Long - id della riga, oppure -1 se esisteva già.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(membership: CollectionCardEntity): Long

    /**
     * Nasconde o rimette una carta in una collezione.
     * @param collectionId Long - collezione.
     * @param cardId String - carta.
     * @param hidden Boolean - true per nasconderla.
     * @return Unit
     */
    @Query("UPDATE collection_card SET hidden = :hidden WHERE collectionId = :collectionId AND cardId = :cardId")
    suspend fun setHidden(collectionId: Long, cardId: String, hidden: Boolean)

    /**
     * Rende visibile una carta come aggiunta a mano (non dipende più da una ricerca).
     * @param collectionId Long - collezione.
     * @param cardId String - carta.
     * @return Unit
     */
    @Query("UPDATE collection_card SET hidden = 0, queryId = NULL WHERE collectionId = :collectionId AND cardId = :cardId")
    suspend fun makeManual(collectionId: Long, cardId: String)
}

/** Accesso ai dati dell'utente (quantità, condizione, slot desiderati), separati per collezione. */
@Dao
interface CollectionDao {

    /**
     * Osserva i dati dell'utente di tutte le collezioni.
     * @return Flow<List<CollectionEntryEntity>> - voci.
     */
    @Query("SELECT * FROM collection_entry")
    fun observeEntries(): Flow<List<CollectionEntryEntity>>

    /**
     * Elenca i dati dell'utente di una collezione.
     * @param collectionId Long - collezione.
     * @return List<CollectionEntryEntity> - voci.
     */
    @Query("SELECT * FROM collection_entry WHERE collectionId = :collectionId")
    suspend fun entriesOf(collectionId: Long): List<CollectionEntryEntity>

    /**
     * Legge i dati dell'utente per una variante di una collezione.
     * @param collectionId Long - collezione.
     * @param variantId String - variante.
     * @return CollectionEntryEntity? - voce, oppure null se mai modificata.
     */
    @Query("SELECT * FROM collection_entry WHERE collectionId = :collectionId AND variantId = :variantId")
    suspend fun getEntry(collectionId: Long, variantId: String): CollectionEntryEntity?

    /**
     * Inserisce o aggiorna i dati dell'utente per una variante.
     * @param entry CollectionEntryEntity - voce da salvare.
     * @return Unit
     */
    @Upsert
    suspend fun upsertEntry(entry: CollectionEntryEntity)

    /**
     * Inserisce o aggiorna più voci dell'utente.
     * @param entries List<CollectionEntryEntity> - voci da salvare.
     * @return Unit
     */
    @Upsert
    suspend fun upsertEntries(entries: List<CollectionEntryEntity>)
}

/** Accesso a prezzi e registro degli aggiornamenti. */
@Dao
interface PriceDao {

    /**
     * Osserva tutti i prezzi salvati, dal più vecchio al più recente.
     * @return Flow<List<PriceSnapshotEntity>> - prezzi.
     */
    @Query("SELECT * FROM price_snapshot ORDER BY fetchedAt ASC")
    fun observeSnapshots(): Flow<List<PriceSnapshotEntity>>

    /**
     * Elenca i prezzi di alcune varianti, dal più vecchio al più recente.
     * @param variantIds List<String> - id delle varianti.
     * @return List<PriceSnapshotEntity> - prezzi.
     */
    @Query("SELECT * FROM price_snapshot WHERE variantId IN (:variantIds) ORDER BY fetchedAt ASC")
    suspend fun snapshotsOf(variantIds: List<String>): List<PriceSnapshotEntity>

    /**
     * Salva un prezzo.
     * @param snapshot PriceSnapshotEntity - prezzo da salvare.
     * @return Long - id assegnato.
     */
    @Insert
    suspend fun insertSnapshot(snapshot: PriceSnapshotEntity): Long

    /**
     * Tiene solo i prezzi più recenti di una variante.
     * @param variantId String - variante.
     * @param keep Int - quanti tenerne.
     * @return Unit
     */
    @Query(
        "DELETE FROM price_snapshot WHERE variantId = :variantId AND id NOT IN " +
            "(SELECT id FROM price_snapshot WHERE variantId = :variantId ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun pruneSnapshots(variantId: String, keep: Int)

    /**
     * Conta i prezzi identici (stessa variante, fonte e data), per non importare doppioni.
     * @param variantId String - variante.
     * @param source String - fonte del prezzo.
     * @param fetchedAt Long - data del prezzo in millisecondi.
     * @return Int - numero di prezzi trovati.
     */
    @Query("SELECT COUNT(*) FROM price_snapshot WHERE variantId = :variantId AND source = :source AND fetchedAt = :fetchedAt")
    suspend fun countSnapshot(variantId: String, source: String, fetchedAt: Long): Int

    /**
     * Registra l'inizio di un aggiornamento prezzi.
     * @param run PriceRunEntity - aggiornamento da salvare.
     * @return Long - id assegnato.
     */
    @Insert
    suspend fun insertRun(run: PriceRunEntity): Long

    /**
     * Aggiorna il registro di un aggiornamento prezzi.
     * @param run PriceRunEntity - aggiornamento da salvare.
     * @return Unit
     */
    @Update
    suspend fun updateRun(run: PriceRunEntity)

    /**
     * Osserva il registro degli aggiornamenti prezzi, dal più recente.
     * @return Flow<List<PriceRunEntity>> - aggiornamenti.
     */
    @Query("SELECT * FROM price_run ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<PriceRunEntity>>

    /**
     * Tiene solo gli aggiornamenti prezzi più recenti di una collezione.
     * @param collectionId Long - collezione.
     * @param keep Int - quanti tenerne.
     * @return Unit
     */
    @Query(
        "DELETE FROM price_run WHERE collectionId = :collectionId AND id NOT IN " +
            "(SELECT id FROM price_run WHERE collectionId = :collectionId ORDER BY startedAt DESC LIMIT :keep)",
    )
    suspend fun pruneRuns(collectionId: Long, keep: Int)
}
