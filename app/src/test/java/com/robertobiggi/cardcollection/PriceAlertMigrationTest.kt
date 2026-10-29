package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Migrazione 14 → 15 (tabella degli avvisi di prezzo) eseguita su SQLite, come la esegue Room sul telefono. */
class PriceAlertMigrationTest {

    /**
     * Database in memoria con la sola tabella delle varianti, come nella versione 14.
     * @return Connection - connessione aperta, con i vincoli di chiave esterna attivi.
     */
    private fun version14(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("CREATE TABLE `card_variant` (`id` TEXT NOT NULL, `cardId` TEXT NOT NULL, `type` TEXT NOT NULL, PRIMARY KEY(`id`))")
            it.execute("INSERT INTO card_variant VALUES ('en:ex8-107|HOLO', 'en:ex8-107', 'HOLO')")
        }
    }

    /**
     * Conta le righe di una tabella.
     * @param db Connection - database.
     * @param table String - tabella (nome fisso del test).
     * @return Int - righe.
     */
    private fun count(db: Connection, table: String): Int =
        db.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }

    @Test
    fun `la migrazione crea la tabella degli avvisi vuota con tutte le colonne`() {
        version14().use { db ->
            db.createStatement().use { st -> Migrations.SQL_14_15.forEach { st.execute(it) } }
            assertEquals(0, count(db, "price_alert"))
            val columns = db.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(price_alert)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
            }
            assertEquals(listOf("variantId", "notifyChanges", "targetEur", "targetReached", "baselines", "createdAt", "lastCheckedAt"), columns)
        }
    }

    @Test
    fun `togliere la variante toglie anche il suo avviso`() {
        version14().use { db ->
            db.createStatement().use { st ->
                Migrations.SQL_14_15.forEach { st.execute(it) }
                st.execute("INSERT INTO price_alert VALUES ('en:ex8-107|HOLO', 1, 50.0, 0, 'Cardmarket|EUR=60.0', 0, NULL)")
            }
            assertEquals(1, count(db, "price_alert"))
            db.createStatement().use { it.execute("DELETE FROM card_variant WHERE id = 'en:ex8-107|HOLO'") }
            assertEquals(0, count(db, "price_alert"))
        }
    }
}
