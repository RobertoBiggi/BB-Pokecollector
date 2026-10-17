package com.robertobiggi.cardcollection.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Impostazioni persistenti dell'app (nessun segreto: le fonti usate non richiedono chiavi).
 *
 * @param context contesto dell'applicazione.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    /** Fine della pausa di TCGdex decisa dal circuit breaker (0 = nessuna pausa). */
    val tcgdexPausedUntil: Flow<Long> = store.data.map { it[TCGDEX_PAUSED_UNTIL] ?: 0L }

    /** Ultimo aggiornamento del catalogo riuscito (0 = mai). */
    val lastCatalogSync: Flow<Long> = store.data.map { it[LAST_CATALOG_SYNC] ?: 0L }

    /**
     * Legge la fine della pausa di TCGdex.
     * @return Long - istante in millisecondi, 0 se nessuna pausa.
     */
    suspend fun tcgdexPausedUntilNow(): Long = tcgdexPausedUntil.first()

    /**
     * Salva la fine della pausa di TCGdex.
     * @param until Long - istante in millisecondi (0 per azzerare).
     * @return Unit
     */
    suspend fun setTcgdexPausedUntil(until: Long) {
        store.edit { it[TCGDEX_PAUSED_UNTIL] = until }
    }

    /**
     * Registra la data dell'ultimo aggiornamento catalogo riuscito.
     * @param timestamp Long - istante in millisecondi.
     * @return Unit
     */
    suspend fun setLastCatalogSync(timestamp: Long) {
        store.edit { it[LAST_CATALOG_SYNC] = timestamp }
    }

    private companion object {
        val TCGDEX_PAUSED_UNTIL = longPreferencesKey("tcgdex_paused_until")
        val LAST_CATALOG_SYNC = longPreferencesKey("last_catalog_sync")
    }
}
