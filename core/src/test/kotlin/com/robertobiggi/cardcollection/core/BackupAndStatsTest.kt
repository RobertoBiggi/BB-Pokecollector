package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.backup.BackupCodec
import com.robertobiggi.cardcollection.core.backup.BackupDecodeResult
import com.robertobiggi.cardcollection.core.backup.BackupEntry
import com.robertobiggi.cardcollection.core.backup.BackupManualPrice
import com.robertobiggi.cardcollection.core.backup.BackupQuery
import com.robertobiggi.cardcollection.core.backup.BackupV1
import com.robertobiggi.cardcollection.core.stats.CollectionStats
import com.robertobiggi.cardcollection.core.stats.SlotInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupAndStatsTest {

    @Test
    fun `backup andata e ritorno con voci non valide scartate`() {
        val backup = BackupV1(
            exportedAt = "2026-10-17T10:00:00Z",
            queries = listOf(BackupQuery("en", "Mawile", true), BackupQuery("xx", "Mawile", true)),
            collection = listOf(
                BackupEntry("en:me03-032|NORMAL", 2, "NM", true),
                BackupEntry("en:me03-032|REVERSE", 1000, "NM", true),
                BackupEntry("en:../x|HOLO", 1, "NM", true),
            ),
            manualPrices = listOf(BackupManualPrice("ja:MC-754|NORMAL", 12.5, 1L), BackupManualPrice("ja:MC-754|NORMAL", -1.0, 1L)),
        )
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        assertTrue(result is BackupDecodeResult.Ok)
        result as BackupDecodeResult.Ok
        assertEquals(1, result.backup.queries.size)
        assertEquals(listOf("en:me03-032|NORMAL"), result.backup.collection.map { it.variantId })
        assertEquals(1, result.backup.manualPrices.size)
        assertEquals(4, result.skipped)
    }

    @Test
    fun `backup non riconosciuto`() {
        assertTrue(BackupCodec.decode("{}") is BackupDecodeResult.Error)
        assertTrue(BackupCodec.decode("non json") is BackupDecodeResult.Error)
        assertTrue(BackupCodec.decode("""{"schema":2,"app":"card-collection","exportedAt":"x","queries":[],"collection":[]}""") is BackupDecodeResult.Error)
    }

    @Test
    fun `statistiche contano solo gli slot desiderati per completamento`() {
        val s = CollectionStats.compute(
            listOf(
                SlotInput(wanted = true, quantity = 2, latestEur = 1.0, latestUsd = 1.5),
                SlotInput(wanted = true, quantity = 0, latestEur = 3.0, latestUsd = null),
                SlotInput(wanted = true, quantity = 0, latestEur = null, latestUsd = null),
                SlotInput(wanted = false, quantity = 1, latestEur = 10.0, latestUsd = null),
            ),
        )
        assertEquals(3, s.slots)
        assertEquals(1, s.owned)
        assertEquals(3, s.copies)
        assertEquals(12.0, s.valueEur, 1e-9)
        assertEquals(3.0, s.valueUsd, 1e-9)
        assertEquals(2, s.missing)
        assertEquals(3.0, s.missingEur, 1e-9)
        assertEquals(1, s.missingWithoutPrice)
        assertEquals(33, s.completionPercent())
    }
}
