package com.robertobiggi.cardcollection.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Prezzi che l'utente può mostrare o nascondere in tutta l'app. */
enum class PriceKind { CARDMARKET, TCGPLAYER, CARDTRADER }

/**
 * Quali prezzi mostrare. Quelli nascosti vengono comunque scaricati: non compaiono nelle schede,
 * nelle griglie né nei totali.
 *
 * @property cardmarket prezzi in euro di Cardmarket (anche quelli inseriti a mano).
 * @property tcgplayer prezzi in dollari di TCGplayer.
 * @property cardTrader prezzo più basso di CardTrader.
 */
data class PriceVisibility(val cardmarket: Boolean = true, val tcgplayer: Boolean = true, val cardTrader: Boolean = true) {
    /** True se nessun prezzo è nascosto. */
    val showsAll: Boolean get() = cardmarket && tcgplayer && cardTrader

    /** True se tutti i prezzi sono nascosti. */
    val showsNone: Boolean get() = !cardmarket && !tcgplayer && !cardTrader
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Impostazioni persistenti dell'app. Unico dato personale: il token API di CardTrader, salvato solo sul telefono.
 *
 * @param context contesto dell'applicazione.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    /** Fine della pausa di TCGdex decisa dal circuit breaker (0 = nessuna pausa). */
    val tcgdexPausedUntil: Flow<Long> = store.data.map { it[TCGDEX_PAUSED_UNTIL] ?: 0L }

    /** Ultimo aggiornamento del catalogo riuscito (0 = mai). */
    val lastCatalogSync: Flow<Long> = store.data.map { it[LAST_CATALOG_SYNC] ?: 0L }

    /** Versione delle regole di abbinamento CardTrader con cui sono stati controllati i blueprint salvati (0 = mai). */
    val cardTraderChecked: Flow<Int> = store.data.map { it[CARDTRADER_CHECKED] ?: 0 }

    /**
     * Registra la versione delle regole di abbinamento con cui i blueprint salvati sono stati controllati.
     * @param version Int - versione.
     * @return Unit
     */
    suspend fun setCardTraderChecked(version: Int) {
        store.edit { it[CARDTRADER_CHECKED] = version }
    }

    /** Token API di CardTrader inserito dall'utente (stringa vuota = CardTrader disattivato). */
    val cardTraderToken: Flow<String> = store.data.map { it[CARDTRADER_TOKEN].orEmpty() }

    /**
     * Salva il token API di CardTrader.
     * @param token String - token già ripulito; vuoto per disattivare la fonte.
     * @return Unit
     */
    suspend fun setCardTraderToken(token: String) {
        store.edit { if (token.isEmpty()) it.remove(CARDTRADER_TOKEN) else it[CARDTRADER_TOKEN] = token }
    }

    /** True se le versioni 1st Edition contano tra le mancanti e nei totali (di default sì). */
    val countFirstEdition: Flow<Boolean> = store.data.map { it[COUNT_FIRST_EDITION] ?: true }

    /**
     * Fa contare o no le versioni 1st Edition.
     * @param counted Boolean - true per contarle.
     * @return Unit
     */
    suspend fun setCountFirstEdition(counted: Boolean) {
        store.edit { it[COUNT_FIRST_EDITION] = counted }
    }

    /** Prezzi mostrati nell'app (tutti di default). */
    val priceVisibility: Flow<PriceVisibility> = store.data.map {
        PriceVisibility(it[SHOW_CARDMARKET] ?: true, it[SHOW_TCGPLAYER] ?: true, it[SHOW_CARDTRADER] ?: true)
    }

    /**
     * Mostra o nasconde un tipo di prezzo.
     * @param kind PriceKind - prezzo da cambiare.
     * @param shown Boolean - true per mostrarlo.
     * @return Unit
     */
    suspend fun setPriceShown(kind: PriceKind, shown: Boolean) {
        val key = when (kind) {
            PriceKind.CARDMARKET -> SHOW_CARDMARKET
            PriceKind.TCGPLAYER -> SHOW_TCGPLAYER
            PriceKind.CARDTRADER -> SHOW_CARDTRADER
        }
        store.edit { it[key] = shown }
    }

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
        val CARDTRADER_TOKEN = stringPreferencesKey("cardtrader_token")
        val SHOW_CARDMARKET = booleanPreferencesKey("show_price_cardmarket")
        val SHOW_TCGPLAYER = booleanPreferencesKey("show_price_tcgplayer")
        val COUNT_FIRST_EDITION = booleanPreferencesKey("count_first_edition")
        val SHOW_CARDTRADER = booleanPreferencesKey("show_price_cardtrader")
        val LAST_CATALOG_SYNC = longPreferencesKey("last_catalog_sync")
        val CARDTRADER_CHECKED = intPreferencesKey("cardtrader_checked")
    }
}
