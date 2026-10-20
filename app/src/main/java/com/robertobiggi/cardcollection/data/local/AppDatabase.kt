package com.robertobiggi.cardcollection.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Database locale: è l'unica fonte di dati della UI, quindi l'app funziona offline. */
@Database(
    entities = [
        CollectionEntity::class,
        TrackedQueryEntity::class,
        CardEntity::class,
        CardVariantEntity::class,
        CollectionCardEntity::class,
        CollectionEntryEntity::class,
        PriceSnapshotEntity::class,
        PriceRunEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    /**
     * Accesso a collezioni, ricerche, carte e varianti.
     * @return CatalogDao - DAO generato da Room.
     */
    abstract fun catalogDao(): CatalogDao

    /**
     * Accesso ai dati dell'utente di ogni collezione.
     * @return CollectionDao - DAO generato da Room.
     */
    abstract fun collectionDao(): CollectionDao

    /**
     * Accesso a prezzi e registro degli aggiornamenti.
     * @return PriceDao - DAO generato da Room.
     */
    abstract fun priceDao(): PriceDao

    companion object {
        private const val NAME = "card-collection.db"

        /**
         * Crea il database. Alla prima installazione è vuoto, senza collezioni né ricerche;
         * chi aveva la versione 1 viene migrato con [Migrations.MIGRATION_1_2].
         * @param context Context - contesto dell'applicazione.
         * @return AppDatabase - istanza del database.
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)
                .build()
    }
}
