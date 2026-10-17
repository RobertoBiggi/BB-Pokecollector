package com.robertobiggi.cardcollection.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Ricerca per nome che definisce quali carte tracciare (es. "Mawile" in inglese). */
@Entity(tableName = "tracked_query", indices = [Index(value = ["language", "pattern"], unique = true)])
data class TrackedQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val language: String,
    val pattern: String,
    val enabled: Boolean = true,
)

/**
 * Carta del catalogo, scaricata da TCGdex. Il sync del catalogo aggiorna solo questa tabella e
 * card_variant: i dati dell'utente stanno in collection_entry e non vengono mai toccati.
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
)

/** Variante di stampa di una carta: ogni riga è uno slot della collezione. */
@Entity(
    tableName = "card_variant",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class CardVariantEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val type: String,
)

/**
 * Dati dell'utente per una variante. Non ha vincoli verso card_variant, così un backup si può
 * importare anche prima di aver scaricato il catalogo.
 */
@Entity(tableName = "collection_entry")
data class CollectionEntryEntity(
    @PrimaryKey val variantId: String,
    val quantity: Int = 0,
    val condition: String = "NM",
    val wanted: Boolean = true,
    val notes: String? = null,
)

/** Prezzo salvato per una variante; se ne tengono gli ultimi [com.robertobiggi.cardcollection.data.repo.PriceRepository.KEEP_SNAPSHOTS]. */
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

/** Registro di un aggiornamento prezzi. */
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
)
