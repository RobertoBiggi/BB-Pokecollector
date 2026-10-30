package com.robertobiggi.cardcollection.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migrazioni del database. Le istruzioni SQL stanno in liste semplici per poterle testare sul JVM. */
object Migrations {

    /** Versione 11 → 12: prodotto Cardmarket delle stampe a parte (shadowless, jumbo…), vuoto per le varianti esistenti. */
    val SQL_11_12: List<String> = listOf("ALTER TABLE `card_variant` ADD COLUMN `cardmarketId` INTEGER")

    /** Versione 12 → 13: timbro o prodotto che distingue più varianti dello stesso tipo, vuoto per le varianti esistenti. */
    val SQL_12_13: List<String> = listOf("ALTER TABLE `card_variant` ADD COLUMN `print` TEXT NOT NULL DEFAULT ''")

    /** Versione 13 → 14: prodotto Cardmarket ed espansione CardTrader delle carte, vuoti finché il sync non li legge dal database delle carte. */
    val SQL_13_14: List<String> = listOf(
        "ALTER TABLE `card` ADD COLUMN `cardmarketProduct` INTEGER",
        "ALTER TABLE `card` ADD COLUMN `cardTraderExpansionId` INTEGER",
    )

    /** Versione 14 → 15: avvisi di prezzo sulle varianti, vuota per chi aggiorna. */
    val SQL_14_15: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `price_alert` (`variantId` TEXT NOT NULL, `notifyChanges` INTEGER NOT NULL, `targetEur` REAL, " +
            "`targetReached` INTEGER NOT NULL, `baselines` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastCheckedAt` INTEGER, " +
            "PRIMARY KEY(`variantId`), FOREIGN KEY(`variantId`) REFERENCES `card_variant`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )

    /** Versione 15 → 16: variazione minima scelta per ogni avviso; vuota per quelli esistenti, che usano quella delle impostazioni. */
    val SQL_15_16: List<String> = listOf("ALTER TABLE `price_alert` ADD COLUMN `changePercent` INTEGER")

    /** Migrazione Room 11 → 12. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        /**
         * Esegue le istruzioni di [SQL_11_12].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_11_12.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 12 → 13. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        /**
         * Esegue le istruzioni di [SQL_12_13].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_12_13.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 13 → 14. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        /**
         * Esegue le istruzioni di [SQL_13_14].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_13_14.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 14 → 15. */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        /**
         * Esegue le istruzioni di [SQL_14_15].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_14_15.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 15 → 16. */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        /**
         * Esegue le istruzioni di [SQL_15_16].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_15_16.forEach { db.execSQL(it) }
        }
    }
}
