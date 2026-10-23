package com.robertobiggi.cardcollection.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migrazioni del database. Le istruzioni SQL stanno in liste semplici per poterle testare sul JVM. */
object Migrations {

    /**
     * Versione 1 → 2: collezioni multiple. I dati esistenti (ricerche, quantità, carte) finiscono
     * nella collezione "Mawile" con id 1; prezzi e catalogo restano invariati.
     */
    val SQL_1_2: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `collection` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "INSERT INTO `collection` (`id`, `name`, `createdAt`) VALUES (1, 'Mawile', CAST(strftime('%s','now') AS INTEGER) * 1000)",

        "CREATE TABLE IF NOT EXISTS `tracked_query_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `collectionId` INTEGER NOT NULL, " +
            "`language` TEXT NOT NULL, `pattern` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
            "FOREIGN KEY(`collectionId`) REFERENCES `collection`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `tracked_query_new` (`id`, `collectionId`, `language`, `pattern`, `enabled`) " +
            "SELECT `id`, 1, `language`, `pattern`, `enabled` FROM `tracked_query`",
        "DROP TABLE `tracked_query`",
        "ALTER TABLE `tracked_query_new` RENAME TO `tracked_query`",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tracked_query_collectionId_language_pattern` ON `tracked_query` (`collectionId`, `language`, `pattern`)",

        "CREATE TABLE IF NOT EXISTS `collection_entry_new` (`collectionId` INTEGER NOT NULL, `variantId` TEXT NOT NULL, " +
            "`quantity` INTEGER NOT NULL, `condition` TEXT NOT NULL, `wanted` INTEGER NOT NULL, `notes` TEXT, " +
            "PRIMARY KEY(`collectionId`, `variantId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `collection`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `collection_entry_new` (`collectionId`, `variantId`, `quantity`, `condition`, `wanted`, `notes`) " +
            "SELECT 1, `variantId`, `quantity`, `condition`, `wanted`, `notes` FROM `collection_entry`",
        "DROP TABLE `collection_entry`",
        "ALTER TABLE `collection_entry_new` RENAME TO `collection_entry`",

        "CREATE TABLE IF NOT EXISTS `collection_card` (`collectionId` INTEGER NOT NULL, `cardId` TEXT NOT NULL, `queryId` INTEGER, " +
            "`hidden` INTEGER NOT NULL, PRIMARY KEY(`collectionId`, `cardId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `collection`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`cardId`) REFERENCES `card`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`queryId`) REFERENCES `tracked_query`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_collection_card_cardId` ON `collection_card` (`cardId`)",
        "CREATE INDEX IF NOT EXISTS `index_collection_card_queryId` ON `collection_card` (`queryId`)",
        "INSERT INTO `collection_card` (`collectionId`, `cardId`, `queryId`, `hidden`) " +
            "SELECT 1, `id`, `queryId`, 0 FROM `card` WHERE `queryId` IN (SELECT `id` FROM `tracked_query`)",

        "ALTER TABLE `price_run` ADD COLUMN `collectionId` INTEGER",
    )

    /** Versione 2 → 3: numero del Pokédex sulle carte, per cercarle per nome in ogni lingua (si compila al prossimo aggiornamento del catalogo). */
    val SQL_2_3: List<String> = listOf("ALTER TABLE `card` ADD COLUMN `dexId` INTEGER")

    /**
     * Istruzione che dà la lingua [code] alle collezioni con almeno una ricerca o carta, tutte in quella lingua.
     * @param code String - codice della lingua ("en" o "ja"), costante.
     * @return String - istruzione UPDATE.
     */
    private fun inferLanguage(code: String): String =
        "UPDATE `collection` SET `language` = '$code' WHERE " +
            "(EXISTS (SELECT 1 FROM `tracked_query` q WHERE q.`collectionId` = `collection`.`id`) " +
            "OR EXISTS (SELECT 1 FROM `collection_card` cc WHERE cc.`collectionId` = `collection`.`id`)) " +
            "AND NOT EXISTS (SELECT 1 FROM `tracked_query` q WHERE q.`collectionId` = `collection`.`id` AND q.`language` <> '$code') " +
            "AND NOT EXISTS (SELECT 1 FROM `collection_card` cc JOIN `card` c ON c.`id` = cc.`cardId` " +
            "WHERE cc.`collectionId` = `collection`.`id` AND c.`language` <> '$code')"

    /**
     * Versione 3 → 4: lingua della collezione. Le collezioni esistenti diventano miste, tranne quelle
     * con ricerche e carte tutte in inglese o tutte in giapponese, che prendono quella lingua.
     */
    val SQL_3_4: List<String> = listOf(
        "ALTER TABLE `collection` ADD COLUMN `language` TEXT NOT NULL DEFAULT 'mixed'",
        inferLanguage("en"),
        inferLanguage("ja"),
    )

    /** Versione 5 → 6: id prodotto Cardmarket delle carte trovate nel catalogo Cardmarket. */
    val SQL_5_6: List<String> = listOf("ALTER TABLE `card` ADD COLUMN `cardmarketId` INTEGER")

    /** Versione 6 → 7: id del blueprint CardTrader delle carte, per il link diretto. */
    val SQL_6_7: List<String> = listOf("ALTER TABLE `card` ADD COLUMN `cardTraderBlueprintId` INTEGER")

    /** Migrazione Room 6 → 7. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        /**
         * Esegue le istruzioni di [SQL_6_7].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_6_7.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 5 → 6. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        /**
         * Esegue le istruzioni di [SQL_5_6].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_5_6.forEach { db.execSQL(it) }
        }
    }

    /**
     * Migrazione Room 4 → 5: unisce le due versioni 4 apparse in parallelo (una con `collection.language`,
     * l'altra con `card.cardTraderCondition`), aggiungendo la colonna che manca.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        /**
         * Aggiunge `card.cardTraderCondition` e `collection.language` solo se non ci sono già.
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!hasColumn(db, "card", "cardTraderCondition")) {
                db.execSQL("ALTER TABLE `card` ADD COLUMN `cardTraderCondition` TEXT")
            }
            if (!hasColumn(db, "collection", "language")) {
                db.execSQL("ALTER TABLE `collection` ADD COLUMN `language` TEXT NOT NULL DEFAULT 'mixed'")
            }
        }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == column }
            }
    }

    /** Migrazione Room 3 → 4. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        /**
         * Esegue le istruzioni di [SQL_3_4].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_3_4.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 2 → 3. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        /**
         * Esegue le istruzioni di [SQL_2_3].
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_2_3.forEach { db.execSQL(it) }
        }
    }

    /** Migrazione Room 1 → 2. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        /**
         * Esegue le istruzioni di [SQL_1_2] (Room le racchiude in una transazione).
         * @param db SupportSQLiteDatabase - database da migrare.
         * @return Unit
         */
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_1_2.forEach { db.execSQL(it) }
        }
    }
}
