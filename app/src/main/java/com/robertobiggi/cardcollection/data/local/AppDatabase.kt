package com.robertobiggi.cardcollection.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Database locale: è l'unica fonte di dati della UI, quindi l'app funziona offline. */
@Database(
    entities = [
        TrackedQueryEntity::class,
        CardEntity::class,
        CardVariantEntity::class,
        CollectionEntryEntity::class,
        PriceSnapshotEntity::class,
        PriceRunEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun collectionDao(): CollectionDao
    abstract fun priceDao(): PriceDao

    companion object {
        private const val NAME = "card-collection.db"

        /**
         * Crea il database e, alla prima apertura, inserisce le ricerche iniziali (Mawile EN e JP).
         * @param context Context - contesto dell'applicazione.
         * @return AppDatabase - istanza del database.
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT INTO tracked_query (language, pattern, enabled) VALUES (?, ?, 1), (?, ?, 1)",
                            arrayOf<Any>("en", "Mawile", "ja", "クチート"),
                        )
                    }
                })
                .build()
    }
}
