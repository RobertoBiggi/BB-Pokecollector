package com.robertobiggi.cardcollection.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.QueryKind

/**
 * Collezione creata dall'utente (es. "Mawile"). Ogni collezione ha ricerche, carte e quantità proprie.
 * [language] è il valore di [CollectionLanguage]: lingua fissa delle ricerche ("en", "ja") oppure "mixed".
 */
@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = CollectionLanguage.MIXED) val language: String = CollectionLanguage.MIXED,
)

/**
 * Ricerca di una collezione: per nome (es. "Mawile" in inglese) oppure set intero (es. "base1"), secondo [kind]
 * (valore di [QueryKind]: "name", "set_base", "set_master").
 */
@Entity(
    tableName = "tracked_query",
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["collectionId", "language", "pattern"], unique = true)],
)
data class TrackedQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val language: String,
    val pattern: String,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "name") val kind: String = QueryKind.NAME.code,
)

/**
 * Carta del catalogo TCGdex, condivisa tra tutte le collezioni. Il sync del catalogo aggiorna solo
 * questa tabella e card_variant: i dati dell'utente stanno in collection_entry.
 * [queryId] è un campo storico della versione 1 (prima ricerca che ha trovato la carta): l'appartenenza
 * alle collezioni è in collection_card.
 */
@Entity(tableName = "card", indices = [Index("queryId")])
data class CardEntity(
    @PrimaryKey val id: String,
    val language: String,
    val tcgdexId: String,
    val name: String,
    val setId: String,
    val setName: String,
    val setCode: String?,
    val serieId: String?,
    val serieName: String?,
    val localId: String,
    val printedNumber: String,
    val rarity: String?,
    val releaseDate: String?,
    val illustrator: String?,
    val imageUrl: String?,
    val imageLowPath: String?,
    val imageHighPath: String?,
    val queryId: Long,
    val catalogUpdatedAt: Long,
    val dexId: Int? = null,
    /** Non più usata: la condizione CardTrader segue quella della variante. La colonna resta perché esistono database già alla versione 4. */
    val cardTraderCondition: String? = null,
    /** Id prodotto Cardmarket, solo per le carte trovate nel catalogo Cardmarket. */
    val cardmarketId: Int? = null,
    /** Id del blueprint CardTrader, noto dopo un aggiornamento prezzi che abbina la carta con certezza: serve al link diretto. */
    val cardTraderBlueprintId: Int? = null,
    /** Set della carta su Cardmarket (`idExpansion`): serve al link, che cerca la carta nel suo set. */
    val cardmarketExpansionId: Int? = null,
    /** Nome inglese della carta su Cardmarket: nel suo set si cerca con questo, non con il nome nella lingua della carta. */
    val cardmarketName: String? = null,
    /** Id prodotto Cardmarket indicato da TCGdex, per abbinare la carta su CardTrader quando il numero non basta. */
    val cardmarketProductHint: Int? = null,
)

/** Variante di stampa di una carta: ogni riga è uno slot. */
@Entity(
    tableName = "card_variant",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class CardVariantEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val type: String,
    /** Prodotto Cardmarket della variante, solo per le stampe a parte (shadowless, jumbo…); le altre usano quello della carta. */
    val cardmarketId: Int? = null,
)

/**
 * Appartenenza di una carta a una collezione.
 * [queryId] è la ricerca che l'ha aggiunta (null se aggiunta a mano o importata): se la ricerca viene
 * disattivata la carta si nasconde, se viene eliminata il collegamento sparisce.
 * [hidden] vale true se l'utente l'ha tolta: il sync non la riaggiunge.
 */
@Entity(
    tableName = "collection_card",
    primaryKeys = ["collectionId", "cardId"],
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackedQueryEntity::class, parentColumns = ["id"], childColumns = ["queryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId"), Index("queryId")],
)
data class CollectionCardEntity(
    val collectionId: Long,
    val cardId: String,
    val queryId: Long?,
    val hidden: Boolean = false,
)

/**
 * Dati dell'utente per una variante in una collezione: le quantità sono separate per collezione.
 * Non ha vincoli verso card_variant, così si può importare prima di scaricare il catalogo.
 */
@Entity(
    tableName = "collection_entry",
    primaryKeys = ["collectionId", "variantId"],
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
)
data class CollectionEntryEntity(
    val collectionId: Long,
    val variantId: String,
    val quantity: Int = 0,
    val condition: String = "NM",
    val wanted: Boolean = true,
    val notes: String? = null,
)

/** Prezzo salvato per una variante (condiviso tra le collezioni); se ne tengono gli ultimi 10. */
@Entity(tableName = "price_snapshot", indices = [Index(value = ["variantId", "fetchedAt"])])
data class PriceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val variantId: String,
    val source: String,
    val eur: Double?,
    val eurLow: Double?,
    val eurAvg30: Double?,
    val usd: Double?,
    val usdLow: Double?,
    val sourceUpdatedAt: String?,
    val fetchedAt: Long,
    val confidence: String,
)

/**
 * Registro di un aggiornamento prezzi. [summary] contiene il motivo dell'interruzione
 * ([com.robertobiggi.cardcollection.data.repo.AbortReason]) oppure null; [collectionId] è null per i run della versione 1.
 */
@Entity(tableName = "price_run")
data class PriceRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
    val okCount: Int,
    val fallbackCount: Int,
    val failCount: Int,
    val summary: String?,
    val collectionId: Long? = null,
)
