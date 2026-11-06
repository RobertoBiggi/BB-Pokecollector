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
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/** Prices the user can show or hide throughout the app. */
enum class PriceKind { CARDMARKET, CARDTRADER }

/**
 * Which prices to show. Hidden ones are downloaded anyway: they do not appear in the cards,
 * grids or totals.
 *
 * @property cardmarket Cardmarket prices in euros (also manually entered ones).
 * @property cardTrader lowest CardTrader price.
 */
data class PriceVisibility(val cardmarket: Boolean = true, val cardTrader: Boolean = true) {
    /** True if no price is hidden. */
    val showsAll: Boolean get() = cardmarket && cardTrader

    /** True if all prices are hidden. */
    val showsNone: Boolean get() = !cardmarket && !cardTrader
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * User secrets, encrypted ([TokenCipher]). Separate file (`datastore/secrets.preferences_pb`) excluded from the Android
 * backup: the key stays in the phone's Keystore, so elsewhere it could not be decrypted anyway.
 */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Persistent settings of the app. Only personal data: the CardTrader API token, saved only on the phone and encrypted
 * with an Android Keystore key.
 *
 * @param context application context.
 * @param cipher TokenCipher - token encryption (on the phone with the Keystore key).
 */
class SettingsStore(context: Context, private val cipher: TokenCipher = TokenCipher(KeystoreKeys::cardTraderKey)) {

    private val store = context.applicationContext.settingsDataStore
    private val secrets = context.applicationContext.secretsDataStore

    /** Address from which to download the card database (see [DEFAULT_CATALOG_URL]). */
    val catalogUrl: Flow<String> = store.data.map { it[CATALOG_URL] ?: DEFAULT_CATALOG_URL }

    /**
     * Saves the address of the card database; an empty text restores the default.
     * @param url String - HTTPS address already checked.
     * @return Unit
     */
    suspend fun setCatalogUrl(url: String) {
        store.edit { if (url.isEmpty() || url == DEFAULT_CATALOG_URL) it.remove(CATALOG_URL) else it[CATALOG_URL] = url }
    }

    /** Last successful catalog update (0 = never). */
    val lastCatalogSync: Flow<Long> = store.data.map { it[LAST_CATALOG_SYNC] ?: 0L }

    /**
     * CardTrader API token entered by the user (empty string = CardTrader disabled), decrypted. If the
     * encrypted token cannot be decrypted (Keystore key lost, e.g. after a restore) it appears absent and must be entered again.
     */
    val cardTraderToken: Flow<String> = secrets.data.map { secret ->
        secret[CARDTRADER_TOKEN_ENCRYPTED]?.let { cipher.decrypt(it) }.orEmpty()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /**
     * Saves the CardTrader API token encrypted.
     * @param token String - already cleaned token; empty to disable the source.
     * @return Boolean - false if the Keystore could not encrypt it: in that case nothing is saved (never in plain text).
     */
    suspend fun setCardTraderToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isEmpty()) {
            secrets.edit { it.remove(CARDTRADER_TOKEN_ENCRYPTED) }
            return@withContext true
        }
        val sealed = try {
            cipher.encrypt(token)
        } catch (e: GeneralSecurityException) {
            return@withContext false
        }
        secrets.edit { it[CARDTRADER_TOKEN_ENCRYPTED] = sealed }
        true
    }

    /** True if 1st Edition versions count among the missing and in the totals (by default yes). */
    val countFirstEdition: Flow<Boolean> = store.data.map { it[COUNT_FIRST_EDITION] ?: true }

    /**
     * Makes the 1st Edition versions count or not.
     * @param counted Boolean - true to count them.
     * @return Unit
     */
    suspend fun setCountFirstEdition(counted: Boolean) {
        store.edit { it[COUNT_FIRST_EDITION] = counted }
    }

    /** True if the daily price check with alerts is on (off by default). */
    val priceAlertsEnabled: Flow<Boolean> = store.data.map { it[PRICE_ALERTS_ENABLED] ?: false }

    /**
     * Turns the daily price check with alerts on or off.
     * @param enabled Boolean - true to turn it on.
     * @return Unit
     */
    suspend fun setPriceAlertsEnabled(enabled: Boolean) {
        store.edit { it[PRICE_ALERTS_ENABLED] = enabled }
    }

    /** Minimum change, in percent, for the "price rises or falls" alert. */
    val priceAlertChangePercent: Flow<Int> = store.data.map {
        (it[PRICE_ALERT_CHANGE_PERCENT] ?: PriceAlertRules.DEFAULT_CHANGE_PERCENT).coerceIn(PriceAlertRules.CHANGE_PERCENT_RANGE)
    }

    /**
     * Sets the minimum change for the "price rises or falls" alert.
     * @param percent Int - percentage, brought back to [PriceAlertRules.CHANGE_PERCENT_RANGE].
     * @return Unit
     */
    suspend fun setPriceAlertChangePercent(percent: Int) {
        store.edit { it[PRICE_ALERT_CHANGE_PERCENT] = percent.coerceIn(PriceAlertRules.CHANGE_PERCENT_RANGE) }
    }

    /** True if the anonymous usage counter is allowed (on by default; the user can turn it off in Settings). */
    val analyticsEnabled: Flow<Boolean> = store.data.map { it[ANALYTICS_ENABLED] ?: true }

    /**
     * Turns the anonymous usage counter on or off.
     * @param enabled Boolean - true to allow it.
     * @return Unit
     */
    suspend fun setAnalyticsEnabled(enabled: Boolean) {
        store.edit { it[ANALYTICS_ENABLED] = enabled }
    }

    /** Instant of the last usage event sent (0 = never). */
    val lastAnalyticsPing: Flow<Long> = store.data.map { it[LAST_ANALYTICS_PING] ?: 0L }

    /**
     * Records when the last usage event was sent.
     * @param timestamp Long - instant in milliseconds.
     * @return Unit
     */
    suspend fun setLastAnalyticsPing(timestamp: Long) {
        store.edit { it[LAST_ANALYTICS_PING] = timestamp }
    }

    /** Prices shown in the app (all by default). */
    val priceVisibility: Flow<PriceVisibility> = store.data.map {
        PriceVisibility(it[SHOW_CARDMARKET] ?: true, it[SHOW_CARDTRADER] ?: true)
    }

    /**
     * Shows or hides a type of price.
     * @param kind PriceKind - price to change.
     * @param shown Boolean - true to show it.
     * @return Unit
     */
    suspend fun setPriceShown(kind: PriceKind, shown: Boolean) {
        val key = when (kind) {
            PriceKind.CARDMARKET -> SHOW_CARDMARKET
            PriceKind.CARDTRADER -> SHOW_CARDTRADER
        }
        store.edit { it[key] = shown }
    }

    /**
     * Records the date of the last successful catalog update.
     * @param timestamp Long - instant in milliseconds.
     * @return Unit
     */
    suspend fun setLastCatalogSync(timestamp: Long) {
        store.edit { it[LAST_CATALOG_SYNC] = timestamp }
    }

    companion object {
        /** Where the published card database is (release with fixed tag `catalog` of the repository, separate from the APK releases). */
        const val DEFAULT_CATALOG_URL = "https://github.com/RobertoBiggi/Card-collection/releases/download/catalog/cards.sqlite"

        private val CATALOG_URL = stringPreferencesKey("catalog_url")
        private val CARDTRADER_TOKEN_ENCRYPTED = stringPreferencesKey("cardtrader_token_v1")
        private val SHOW_CARDMARKET = booleanPreferencesKey("show_price_cardmarket")
        private val COUNT_FIRST_EDITION = booleanPreferencesKey("count_first_edition")
        private val SHOW_CARDTRADER = booleanPreferencesKey("show_price_cardtrader")
        private val LAST_CATALOG_SYNC = longPreferencesKey("last_catalog_sync")
        private val ANALYTICS_ENABLED = booleanPreferencesKey("analytics_enabled")
        private val LAST_ANALYTICS_PING = longPreferencesKey("last_analytics_ping")
        private val PRICE_ALERTS_ENABLED = booleanPreferencesKey("price_alerts_enabled")
        private val PRICE_ALERT_CHANGE_PERCENT = intPreferencesKey("price_alert_change_percent")
    }
}
