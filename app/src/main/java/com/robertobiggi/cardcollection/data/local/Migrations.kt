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

    /** Versione 3 → 4: colonna `cardTraderCondition` sulle carte, rimasta inutilizzata (vedi CardEntity). */
    val SQL_3_4: List<String> = listOf("ALTER TABLE `card` ADD COLUMN `cardTraderCondition` TEXT")

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
