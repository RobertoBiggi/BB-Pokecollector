package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.data.local.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifica le migrazioni 1 → 2 → 3 → 4 su SQLite (JVM): il database migrato deve avere lo stesso schema
 * di quello che Room crea da zero (schemi esportati in app/schemas durante la build) e conservare i dati.
 */
class MigrationTest {

    /** Schema della versione 1, copiato dallo schema esportato da Room per la release 0.1.0. */
    private val v1 = listOf(
        "CREATE TABLE IF NOT EXISTS `tracked_query` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `language` TEXT NOT NULL, `pattern` TEXT NOT NULL, `enabled` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tracked_query_language_pattern` ON `tracked_query` (`language`, `pattern`)",
        "CREATE TABLE IF NOT EXISTS `card` (`id` TEXT NOT NULL, `language` TEXT NOT NULL, `tcgdexId` TEXT NOT NULL, `name` TEXT NOT NULL, `setId` TEXT NOT NULL, `setName` TEXT NOT NULL, `setCode` TEXT, `serieId` TEXT, `serieName` TEXT, `localId` TEXT NOT NULL, `printedNumber` TEXT NOT NULL, `rarity` TEXT, `releaseDate` TEXT, `illustrator` TEXT, `imageUrl` TEXT, `imageLowPath` TEXT, `imageHighPath` TEXT, `queryId` INTEGER NOT NULL, `catalogUpdatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_card_queryId` ON `card` (`queryId`)",
        "CREATE TABLE IF NOT EXISTS `card_variant` (`id` TEXT NOT NULL, `cardId` TEXT NOT NULL, `type` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`cardId`) REFERENCES `card`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_card_variant_cardId` ON `card_variant` (`cardId`)",
        "CREATE TABLE IF NOT EXISTS `collection_entry` (`variantId` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `condition` TEXT NOT NULL, `wanted` INTEGER NOT NULL, `notes` TEXT, PRIMARY KEY(`variantId`))",
        "CREATE TABLE IF NOT EXISTS `price_snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `variantId` TEXT NOT NULL, `source` TEXT NOT NULL, `eur` REAL, `eurLow` REAL, `eurAvg30` REAL, `usd` REAL, `usdLow` REAL, `sourceUpdatedAt` TEXT, `fetchedAt` INTEGER NOT NULL, `confidence` TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_price_snapshot_variantId_fetchedAt` ON `price_snapshot` (`variantId`, `fetchedAt`)",
        "CREATE TABLE IF NOT EXISTS `price_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `okCount` INTEGER NOT NULL, `fallbackCount` INTEGER NOT NULL, `failCount` INTEGER NOT NULL, `summary` TEXT)",
    )

    /**
     * Legge le istruzioni di creazione di una versione dallo schema esportato da Room.
     * @param version Int - versione del database.
     * @return List<String> - CREATE TABLE e CREATE INDEX con il nome tabella già sostituito.
     */
    private fun fromRoomSchema(version: Int): List<String> {
        val file = File("schemas/com.robertobiggi.cardcollection.data.local.AppDatabase/$version.json")
        assertTrue("Schema Room v$version non trovato: ${file.absolutePath}", file.isFile)
        val root = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        return root["entities"]!!.jsonArray.flatMap { e ->
            val entity = e.jsonObject
            val table = entity["tableName"]!!.jsonPrimitive.content
            val create = entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            val indices = entity["indices"]?.jsonArray.orEmpty().map {
                (it as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            }
            listOf(create) + indices
        }
    }

    /**
     * Apre un database SQLite in memoria ed esegue le istruzioni indicate.
     * @param statements List<String> - istruzioni SQL.
     * @return Connection - connessione aperta.
     */
    private fun db(statements: List<String>): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        c.createStatement().use { st -> st.execute("PRAGMA foreign_keys = ON") }
        statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
        return c
    }

    /**
     * Esegue una query e restituisce le righe come liste di stringhe.
     * @param c Connection - database.
     * @param sql String - query.
     * @return List<List<String?>> - righe.
     */
    private fun rows(c: Connection, sql: String): List<List<String?>> = c.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
        }
    }

    /**
     * Descrive lo schema come lo confronta Room: colonne, chiavi esterne e indici di ogni tabella.
     * @param c Connection - database.
     * @return Map<String, Any> - descrizione ordinata per tabella.
     */
    private fun schema(c: Connection): Map<String, Any> {
        val tables = rows(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('sqlite_sequence', 'room_master_table', 'android_metadata') ORDER BY name")
            .map { it[0]!! }
        return tables.associateWith { t ->
            val columns = rows(c, "PRAGMA table_info(`$t`)").map { listOf(it[1], it[2]?.uppercase(), it[3], it[5], it[4]) }.sortedBy { it[0] }
            val fks = rows(c, "PRAGMA foreign_key_list(`$t`)").map { listOf(it[2], it[3], it[4], it[5], it[6]) }.sortedBy { it.toString() }
            val indices = rows(c, "PRAGMA index_list(`$t`)").filter { !it[1]!!.startsWith("sqlite_autoindex") }.map { idx ->
                listOf(idx[1], idx[2], rows(c, "PRAGMA index_info(`${idx[1]}`)").sortedBy { it[0]!!.toInt() }.map { it[2] })
            }.sortedBy { it[0].toString() }
            listOf(columns, fks, indices)
        }
    }

    @Test
    fun `la migrazione produce lo schema di Room e conserva i dati`() {
        val migrated = db(v1)
        listOf(
            "INSERT INTO tracked_query (id, language, pattern, enabled) VALUES (1, 'en', 'Mawile', 1), (2, 'ja', 'クチート', 0)",
            "INSERT INTO card VALUES ('en:me03-032','en','me03-032','Mawile','me03','Perfect Order','POR','me','Mega Evolution','032','032/088','Common',NULL,NULL,NULL,NULL,NULL,1,0)",
            "INSERT INTO card VALUES ('ja:SM10a-030','ja','SM10a-030','クチートGX','SM10a','GG','SM10a',NULL,NULL,'030','030/054',NULL,NULL,NULL,NULL,NULL,NULL,2,0)",
            "INSERT INTO card VALUES ('en:old-1','en','old-1','Mawile','old','Old',NULL,NULL,NULL,'1','1/10',NULL,NULL,NULL,NULL,NULL,NULL,99,0)",
            "INSERT INTO card_variant VALUES ('en:me03-032|NORMAL','en:me03-032','NORMAL')",
            "INSERT INTO collection_entry VALUES ('en:me03-032|NORMAL', 2, 'LP', 1, 'nota')",
            "INSERT INTO price_snapshot (variantId, source, eur, fetchedAt, confidence) VALUES ('en:me03-032|NORMAL', 'TCGdex', 0.03, 5, 'HIGH')",
            "INSERT INTO price_run (startedAt, finishedAt, status, okCount, fallbackCount, failCount, summary) VALUES (1, 2, 'DONE', 1, 0, 0, 'ok')",
        ).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        Migrations.SQL_1_2.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        val expected = db(fromRoomSchema(2))
        assertEquals(schema(expected), schema(migrated))
        assertTrue(rows(migrated, "PRAGMA foreign_key_check").isEmpty())

        assertEquals(listOf(listOf("1", "Mawile")), rows(migrated, "SELECT id, name FROM collection"))
        assertEquals(listOf(listOf("1", "1", "en"), listOf("2", "1", "ja")), rows(migrated, "SELECT id, collectionId, language FROM tracked_query ORDER BY id"))
        assertEquals(listOf(listOf("1", "en:me03-032|NORMAL", "2", "LP", "nota")), rows(migrated, "SELECT collectionId, variantId, quantity, condition, notes FROM collection_entry"))
        assertEquals(
            listOf(listOf("en:me03-032", "1", "0"), listOf("ja:SM10a-030", "2", "0")),
            rows(migrated, "SELECT cardId, queryId, hidden FROM collection_card ORDER BY cardId"),
        )
        assertEquals(listOf(listOf(null)), rows(migrated, "SELECT collectionId FROM price_run"))
        assertEquals(listOf(listOf("1")), rows(migrated, "SELECT COUNT(*) FROM price_snapshot"))
    }

    @Test
    fun `la migrazione 2-3 aggiunge il numero del Pokedex senza toccare i dati`() {
        val migrated = db(fromRoomSchema(2))
        listOf(
            "INSERT INTO collection (id, name, createdAt) VALUES (1, 'Mawile', 0)",
            "INSERT INTO card (id, language, tcgdexId, name, setId, setName, localId, printedNumber, queryId, catalogUpdatedAt) " +
                "VALUES ('en:me03-032','en','me03-032','Mawile','me03','Perfect Order','032','032/088',0,0)",
        ).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        Migrations.SQL_2_3.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        assertEquals(schema(db(fromRoomSchema(3))), schema(migrated))
        assertEquals(listOf(listOf("Mawile", null)), rows(migrated, "SELECT name, dexId FROM card"))
    }

    @Test
    fun `la migrazione 3-4 deduce la lingua delle collezioni`() {
        val migrated = db(fromRoomSchema(3))
        listOf(
            "INSERT INTO collection (id, name, createdAt) VALUES (1, 'Mista', 0), (2, 'Inglese', 0), (3, 'Giapponese', 0), (4, 'Vuota', 0), (5, 'Francese', 0)",
            "INSERT INTO tracked_query (id, collectionId, language, pattern, enabled) VALUES (1, 1, 'en', 'Mawile', 1), (2, 1, 'ja', 'クチート', 1), (3, 2, 'en', 'Eevee', 1)",
            "INSERT INTO card (id, language, tcgdexId, name, setId, setName, localId, printedNumber, queryId, catalogUpdatedAt) VALUES " +
                "('en:me03-032','en','me03-032','Mawile','me03','Perfect Order','032','032/088',0,0), " +
                "('ja:M3-117','ja','M3-117','メガジガルデex','M3','ムニキスゼロ','117','117/080',0,0), " +
                "('fr:me03-032','fr','me03-032','Mysdibule','me03','x','032','032/088',0,0)",
            "INSERT INTO collection_card (collectionId, cardId, queryId, hidden) VALUES (2, 'en:me03-032', 3, 0), (3, 'ja:M3-117', NULL, 0), (5, 'fr:me03-032', NULL, 0)",
        ).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        Migrations.SQL_3_4.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        assertEquals(schema(db(fromRoomSchema(4))), schema(migrated))
        assertEquals(
            listOf(listOf("1", "mixed"), listOf("2", "en"), listOf("3", "ja"), listOf("4", "mixed"), listOf("5", "mixed")),
            rows(migrated, "SELECT id, language FROM collection ORDER BY id"),
        )
    }

    @Test
    fun `la migrazione 5-6 aggiunge l'id Cardmarket delle carte`() {
        val migrated = db(fromRoomSchema(5))
        Migrations.SQL_5_6.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
        assertEquals(schema(db(fromRoomSchema(6))), schema(migrated))
    }

    @Test
    fun `la migrazione 6-7 aggiunge il blueprint CardTrader delle carte`() {
        val migrated = db(fromRoomSchema(6))
        Migrations.SQL_6_7.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
        assertEquals(schema(db(fromRoomSchema(7))), schema(migrated))
    }

    @Test
    fun `la catena 1-2-3-4 produce lo schema attuale`() {
        val migrated = db(v1)
        (Migrations.SQL_1_2 + Migrations.SQL_2_3 + Migrations.SQL_3_4).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
        assertEquals(schema(db(fromRoomSchema(4))), schema(migrated))
    }
}
