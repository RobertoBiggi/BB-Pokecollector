package com.robertobiggi.cardcollection.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migrazioni del database. Le istruzioni SQL stanno in liste semplici per poterle testare sul JVM. */
object Migrations {

    /** Versione 11 → 12: prodotto Cardmarket delle stampe a parte (shadowless, jumbo…), vuoto per le varianti esistenti. */
    val SQL_11_12: List<String> = listOf("ALTER TABLE `card_variant` ADD COLUMN `cardmarketId` INTEGER")

    /** Versione 12 → 13: timbro o prodotto che distingue più varianti dello stesso tipo, vuoto per le varianti esistenti. */
    val SQL_12_13: List<String> = listOf("ALTER TABLE `card_variant` ADD COLUMN `print` TEXT NOT NULL DEFAULT ''")

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
}
