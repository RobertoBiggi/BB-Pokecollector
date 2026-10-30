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
     * Legge il nome di una collezione.
     * @param id Long - collezione.
     * @return String? - nome, oppure null se non esiste.
     */
    @Query("SELECT name FROM collection WHERE id = :id")
    suspend fun collectionName(id: Long): String?

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
     * Elenca le carte che nessuna collezione contiene più e di cui nessuna collezione ha quantità o note, aggiornate prima di [before]
     * (il margine evita carte appena salvate da un sync che non le ha ancora collegate).
     * @param before Long - millisecondi: si considerano solo le carte aggiornate prima.
     * @return List<CardEntity> - carte orfane.
     */
    @Query(
        "SELECT * FROM card WHERE catalogUpdatedAt < :before AND id NOT IN (SELECT cardId FROM collection_card) " +
            "AND id NOT IN (SELECT v.cardId FROM card_variant v JOIN collection_entry e ON e.variantId = v.id)",
    )
    suspend fun orphanCards(before: Long): List<CardEntity>

    /**
     * Elimina carte del catalogo; le loro varianti vanno a cascata.
     * @param ids List<String> - id delle carte.
     * @return Unit
     */
    @Query("DELETE FROM card WHERE id IN (:ids)")
    suspend fun deleteCards(ids: List<String>)

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
     * Toglie l'URL dell'immagine a una carta che TCGdex non ha (risposta 404), solo se non ha altre immagini: così la carta passa
     * alle altre fonti (CardTrader) invece di restare per sempre "con un'immagine da scaricare".
     * @param id String - id della riga.
     * @return Unit
     */
    @Query("UPDATE card SET imageUrl = NULL WHERE id = :id AND imageLowPath IS NULL AND imageHighPath IS NULL")
    suspend fun clearImageUrl(id: String)

    /**
     * Elenca le carte inglesi e giapponesi senza URL immagine né immagini salvate (né scelte a mano).
     * @return List<CardEntity> - carte per cui si può provare un'altra fonte.
     */
    @Query("SELECT * FROM card WHERE imageUrl IS NULL AND imageLowPath IS NULL AND imageHighPath IS NULL AND language IN ('en', 'ja')")
    suspend fun cardsWithoutImage(): List<CardEntity>

    /**
     * Elenca le carte inglesi e giapponesi del catalogo Cardmarket senza rarità (o senza numero stampato).
     * @return List<CardEntity> - carte per cui CardTrader può dare i dati mancanti.
     */
    @Query("SELECT * FROM card WHERE cardmarketId IS NOT NULL AND (rarity IS NULL OR printedNumber = '') AND language IN ('en', 'ja')")
    suspend fun cardsMissingDetails(): List<CardEntity>

    /**
     * Elenca le carte di cui non si conosce ancora il blueprint CardTrader.
     * @return List<CardEntity> - carte per cui si può cercare il link diretto.
     */
    @Query("SELECT * FROM card WHERE cardTraderBlueprintId IS NULL")
    suspend fun cardsWithoutCardTraderBlueprint(): List<CardEntity>

    /**
     * Elenca le carte con un blueprint CardTrader salvato e l'id Cardmarket di TCGdex, con cui si può controllare che l'abbinamento sia giusto.
     * @return List<CardEntity> - carte da ricontrollare.
     */
    @Query("SELECT * FROM card WHERE cardTraderBlueprintId IS NOT NULL AND cardmarketProductHint IS NOT NULL")
    suspend fun cardsWithCardTraderBlueprintToCheck(): List<CardEntity>

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
     * Completa i percorsi delle immagini mancanti senza toccare quelli già salvati (anche da un altro lavoro nel frattempo).
     * @param id String - id della riga.
     * @param low String? - percorso dell'immagine piccola appena scaricata.
     * @param high String? - percorso dell'immagine grande appena scaricata.
     * @return Unit
     */
    @Query("UPDATE card SET imageLowPath = COALESCE(imageLowPath, :low), imageHighPath = COALESCE(imageHighPath, :high) WHERE id = :id")
    suspend fun fillImagePaths(id: String, low: String?, high: String?)

    /**
     * Dimentica un percorso d'immagine il cui file non esiste più, solo se è ancora quello salvato.
     * @param id String - id della riga.
     * @param path String - percorso da togliere.
     * @return Unit
     */
    @Query(
        "UPDATE card SET imageLowPath = CASE WHEN imageLowPath = :path THEN NULL ELSE imageLowPath END, " +
            "imageHighPath = CASE WHEN imageHighPath = :path THEN NULL ELSE imageHighPath END WHERE id = :id",
    )
    suspend fun forgetImagePath(id: String, path: String)

    /**
     * Elenca le carte con almeno un percorso d'immagine salvato.
     * @return List<CardEntity> - carte da controllare.
     */
    @Query("SELECT * FROM card WHERE imageLowPath IS NOT NULL OR imageHighPath IS NOT NULL")
    suspend fun cardsWithImagePaths(): List<CardEntity>

    /**
     * Toglie l'immagine presa da CardTrader quando il blueprint cambia, solo se la carta non ha un'immagine TCGdex
     * né una scelta a mano (letto al momento, non da una copia della carta che potrebbe essere vecchia).
     * @param id String - id della riga.
     * @return Unit
     */
    @Query("UPDATE card SET imageLowPath = NULL, imageHighPath = NULL WHERE id = :id AND imageUrl IS NULL AND (imageHighPath IS NULL OR imageHighPath NOT LIKE '%-manual-%')")
    suspend fun clearCardTraderImage(id: String)

    /**
     * Imposta l'illustratore di una carta solo se non è ancora noto.
     * @param id String - id della riga.
     * @param illustrator String - illustratore.
     * @return Unit
     */
    @Query("UPDATE card SET illustrator = :illustrator WHERE id = :id AND illustrator IS NULL")
    suspend fun fillIllustrator(id: String, illustrator: String)

    /**
     * Completa rarità e numero stampato di una carta solo dove mancano.
     * @param id String - id della riga.
     * @param rarity String? - rarità trovata.
     * @param printedNumber String - numero stampato trovato (vuoto se non c'è).
     * @return Unit
     */
    @Query("UPDATE card SET rarity = COALESCE(rarity, :rarity), printedNumber = CASE WHEN printedNumber = '' THEN :printedNumber ELSE printedNumber END WHERE id = :id")
    suspend fun fillDetails(id: String, rarity: String?, printedNumber: String)

    /**
     * Imposta il numero del Pokédex di una carta solo se non è ancora noto.
     * @param id String - id della riga.
     * @param dexId Int - numero del Pokédex.
     * @return Unit
     */
    @Query("UPDATE card SET dexId = :dexId WHERE id = :id AND dexId IS NULL")
    suspend fun fillDexId(id: String, dexId: Int)

    /**
     * Salva l'id del blueprint CardTrader di una carta.
     * @param id String - id della riga.
     * @param blueprintId Int - id del blueprint.
     * @return Unit
     */
    @Query("UPDATE card SET cardTraderBlueprintId = :blueprintId WHERE id = :id")
    suspend fun setCardTraderBlueprint(id: String, blueprintId: Int)

    /**
     * Aggiunge varianti, ignorando quelle già presenti.
     * @param variants List<CardVariantEntity> - varianti da aggiungere.
     * @return Unit
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVariants(variants: List<CardVariantEntity>)

    /**
     * Salva il prodotto Cardmarket di una stampa a parte (anche su una variante già presente, per esempio importata da un file).
     * @param variantId String - variante.
     * @param cardmarketId Int - prodotto Cardmarket.
     */
    @Query("UPDATE card_variant SET cardmarketId = :cardmarketId WHERE id = :variantId")
    suspend fun setVariantProduct(variantId: String, cardmarketId: Int)

    /**
     * Toglie le varianti che il catalogo non elenca più (es. una reverse holo dedotta male), tranne quelle di cui l'utente
     * possiede delle copie o ha scritto delle note.
     * @param cardId String - carta.
     * @param types List<String> - tipi di variante da tenere.
     */
    @Query("DELETE FROM card_variant WHERE cardId = :cardId AND id NOT IN (:keepIds) AND id NOT IN (SELECT variantId FROM collection_entry WHERE quantity > 0 OR notes IS NOT NULL)")
    suspend fun deleteStaleVariants(cardId: String, keepIds: List<String>)

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
     * Legge alcune varianti dal loro id.
     * @param ids List<String> - id delle varianti.
     * @return List<CardVariantEntity> - varianti trovate.
     */
    @Query("SELECT * FROM card_variant WHERE id IN (:ids)")
    suspend fun variantsByIds(ids: List<String>): List<CardVariantEntity>

    /**
     * Prima collezione (per id) che contiene una carta, per aprirne il dettaglio da una notifica.
     * @param cardId String - carta.
     * @return Long? - id della collezione, oppure null se nessuna la contiene.
     */
    @Query("SELECT collectionId FROM collection_card WHERE cardId = :cardId ORDER BY hidden ASC, collectionId ASC LIMIT 1")
    suspend fun collectionOfCard(cardId: String): Long?

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
     * Elenca le appartenenze di una carta, in tutte le collezioni.
     * @param cardId String - carta.
     * @return List<CollectionCardEntity> - appartenenze.
     */
    @Query("SELECT * FROM collection_card WHERE cardId = :cardId")
    suspend fun membershipsOfCard(cardId: String): List<CollectionCardEntity>

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
     * Toglie da una collezione le carte trovate da una ricerca che il catalogo non indica più, se non hanno copie, note né sono volute.
     * @param collectionId Long - collezione.
     * @param cardIds List<String> - carte da togliere.
     * @return Unit
     */
    @Query(
        "DELETE FROM collection_card WHERE collectionId = :collectionId AND queryId IS NOT NULL AND cardId IN (:cardIds) AND cardId NOT IN " +
            "(SELECT v.cardId FROM card_variant v JOIN collection_entry e ON e.variantId = v.id " +
            "WHERE e.collectionId = :collectionId AND (e.quantity > 0 OR e.notes IS NOT NULL OR e.wanted = 1))",
    )
    suspend fun dropStaleMemberships(collectionId: Long, cardIds: List<String>)

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
     * Elenca i dati dell'utente per una variante, in tutte le collezioni.
     * @param variantId String - variante.
     * @return List<CollectionEntryEntity> - dati.
     */
    @Query("SELECT * FROM collection_entry WHERE variantId = :variantId")
    suspend fun entriesOfVariant(variantId: String): List<CollectionEntryEntity>

    /**
     * Cancella i dati dell'utente per una variante, in tutte le collezioni.
     * @param variantId String - variante.
     * @return Unit
     */
    @Query("DELETE FROM collection_entry WHERE variantId = :variantId")
    suspend fun deleteEntriesOfVariant(variantId: String)

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

    /**
     * Inserisce le voci che non esistono ancora, lasciando invariate quelle già salvate (scelte dell'utente comprese).
     * @param entries List<CollectionEntryEntity> - voci da aggiungere.
     * @return Unit
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntriesIfAbsent(entries: List<CollectionEntryEntity>)
}

/** Accesso a prezzi e registro degli aggiornamenti. */
@Dao
interface PriceDao {

    /**
     * Osserva gli avvisi di prezzo delle varianti ancora presenti.
     * @return Flow<List<PriceAlertEntity>> - avvisi.
     */
    @Query("SELECT * FROM price_alert WHERE variantId IN (SELECT id FROM card_variant)")
    fun observeAlerts(): Flow<List<PriceAlertEntity>>

    /**
     * Elenca gli avvisi di prezzo delle varianti ancora presenti.
     * @return List<PriceAlertEntity> - avvisi.
     */
    @Query("SELECT * FROM price_alert WHERE variantId IN (SELECT id FROM card_variant)")
    suspend fun alerts(): List<PriceAlertEntity>

    /**
     * Legge l'avviso di prezzo di una variante.
     * @param variantId String - variante.
     * @return PriceAlertEntity? - avviso, oppure null se non c'è.
     */
    @Query("SELECT * FROM price_alert WHERE variantId = :variantId")
    suspend fun getAlert(variantId: String): PriceAlertEntity?

    /**
     * Crea o sostituisce l'avviso di prezzo di una variante.
     * @param alert PriceAlertEntity - avviso.
     * @return Unit
     */
    @Upsert
    suspend fun upsertAlert(alert: PriceAlertEntity)

    /**
     * Sposta lo storico dei prezzi di una variante su un'altra (carta il cui id è cambiato).
     * @param from String - variante di partenza.
     * @param to String - variante di arrivo.
     * @return Unit
     */
    @Query("UPDATE price_snapshot SET variantId = :to WHERE variantId = :from")
    suspend fun moveSnapshots(from: String, to: String)

    /**
     * Sposta l'avviso di prezzo di una variante su un'altra, se l'arrivo non ne ha già uno (la variante di arrivo deve esistere).
     * @param from String - variante di partenza.
     * @param to String - variante di arrivo.
     * @return Unit
     */
    @Query("UPDATE OR IGNORE price_alert SET variantId = :to WHERE variantId = :from")
    suspend fun moveAlert(from: String, to: String)

    /**
     * Toglie l'avviso di prezzo di una variante.
     * @param variantId String - variante.
     * @return Unit
     */
    @Query("DELETE FROM price_alert WHERE variantId = :variantId")
    suspend fun deleteAlert(variantId: String)

    /**
     * Salva lo stato di un avviso dopo il controllo giornaliero, senza toccare le scelte dell'utente.
     * @param variantId String - variante.
     * @param baselines String - prezzi di riferimento codificati.
     * @param targetReached Boolean - true se l'avviso del prezzo obiettivo è partito.
     * @param checkedAt Long - istante del controllo.
     * @return Unit
     */
    @Query("UPDATE price_alert SET baselines = :baselines, targetReached = :targetReached, lastCheckedAt = :checkedAt WHERE variantId = :variantId")
    suspend fun updateAlertState(variantId: String, baselines: String, targetReached: Boolean, checkedAt: Long)

    /**
     * Toglie gli avvisi delle varianti di alcune carte (carte tolte dal catalogo).
     * @param cardIds List<String> - id delle carte.
     * @return Unit
     */
    @Query("DELETE FROM price_alert WHERE variantId IN (SELECT id FROM card_variant WHERE cardId IN (:cardIds))")
    suspend fun deleteAlertsOfCards(cardIds: List<String>)

    /**
     * Elimina i prezzi salvati per le varianti di alcune carte.
     * @param cardIds List<String> - id delle carte.
     * @return Unit
     */
    @Query("DELETE FROM price_snapshot WHERE variantId IN (SELECT id FROM card_variant WHERE cardId IN (:cardIds))")
    suspend fun deleteSnapshotsOfCards(cardIds: List<String>)

    /**
     * Elimina il registro degli aggiornamenti prezzi di una collezione.
     * @param collectionId Long - collezione.
     * @return Unit
     */
    @Query("DELETE FROM price_run WHERE collectionId = :collectionId")
    suspend fun deleteRunsOf(collectionId: Long)

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
     * Cancella i prezzi di una variante che vengono da una fonte.
     * @param variantId String - variante.
     * @param source String - fonte dei prezzi da cancellare.
     * @return Unit
     */
    @Query("DELETE FROM price_snapshot WHERE variantId = :variantId AND source = :source")
    suspend fun deleteSnapshotsOf(variantId: String, source: String)

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
