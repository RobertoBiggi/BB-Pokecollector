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
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun collectionDao(): CollectionDao
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
                .addMigrations(Migrations.MIGRATION_1_2)
                .build()
    }
}
