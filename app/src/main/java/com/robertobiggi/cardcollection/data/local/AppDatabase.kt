package com.robertobiggi.cardcollection.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
         * Crea il database. Alla prima installazione crea la collezione "Mawile" con le ricerche
         * Mawile (EN) e クチート (JP); chi aveva la versione 1 viene migrato con [Migrations.MIGRATION_1_2].
         * @param context Context - contesto dell'applicazione.
         * @return AppDatabase - istanza del database.
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(Migrations.MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT INTO collection (id, name, createdAt) VALUES (1, ?, ?)",
                            arrayOf<Any>("Mawile", System.currentTimeMillis()),
                        )
                        db.execSQL(
                            "INSERT INTO tracked_query (collectionId, language, pattern, enabled) VALUES (1, ?, ?, 1), (1, ?, ?, 1)",
                            arrayOf<Any>("en", "Mawile", "ja", "クチート"),
                        )
                    }
                })
                .build()
    }
}
