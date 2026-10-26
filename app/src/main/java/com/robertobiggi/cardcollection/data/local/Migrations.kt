package com.robertobiggi.cardcollection.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migrazioni del database. Le istruzioni SQL stanno in liste semplici per poterle testare sul JVM. */
object Migrations {

    /** Versione 11 → 12: prodotto Cardmarket delle stampe a parte (shadowless, jumbo…), vuoto per le varianti esistenti. */
    val SQL_11_12: List<String> = listOf("ALTER TABLE `card_variant` ADD COLUMN `cardmarketId` INTEGER")

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
}
