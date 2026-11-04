package com.robertobiggi.cardcollection

import com.robertobiggi.cardcollection.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the schema of the local database. Room writes the schema of the current version in `app/schemas` while compiling,
 * so the highest version found there is the current one: every older schema still in the repository must reach it through
 * [Migrations.ALL], otherwise the app would close at startup on the phones that update from that version.
 * (CI also fails if the schema changed without its file being committed: see `.github/workflows/android.yml`.)
 */
class SchemaMigrationTest {

    /** Schemas exported by Room (the working directory of the unit tests is the `app` module). */
    private val schemaDir = File("schemas/com.robertobiggi.cardcollection.data.local.AppDatabase")

    /**
     * Versions with a saved schema.
     * @return List<Int> - versions in increasing order.
     */
    private fun savedVersions(): List<Int> =
        schemaDir.listFiles().orEmpty().mapNotNull { SCHEMA_FILE.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }.sorted()

    @Test
    fun `ogni schema salvato arriva alla versione attuale con le migrazioni`() {
        val versions = savedVersions()
        assertTrue("Nessuno schema in ${schemaDir.absolutePath}", versions.isNotEmpty())
        val current = versions.last()
        val steps = Migrations.ALL.associate { it.startVersion to it.endVersion }
        for (start in versions.dropLast(1)) {
            var at = start
            while (at < current) {
                at = steps[at] ?: throw AssertionError(
                    "Manca la migrazione da $at verso la versione $current (schema $start.json): aggiungila in Migrations.ALL",
                )
            }
            assertEquals("Le migrazioni da $start superano la versione attuale $current", current, at)
        }
    }

    @Test
    fun `le migrazioni collegano versioni con uno schema salvato`() {
        val versions = savedVersions().toSet()
        for (migration in Migrations.ALL) {
            assertTrue("Migrazione ${migration.startVersion}→${migration.endVersion} all'indietro", migration.endVersion > migration.startVersion)
            assertTrue("Schema ${migration.startVersion}.json assente: committalo", migration.startVersion in versions)
            assertTrue("Schema ${migration.endVersion}.json assente: committalo", migration.endVersion in versions)
        }
    }

    private companion object {
        val SCHEMA_FILE = Regex("^(\\d+)\\.json$")
    }
}
