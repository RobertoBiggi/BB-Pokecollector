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
 * Segreti dell'utente, cifrati ([TokenCipher]). File a parte (`datastore/secrets.preferences_pb`) escluso dal backup
 * Android: la chiave resta nel Keystore del telefono, quindi altrove non si decifrerebbe comunque.
 */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Impostazioni persistenti dell'app. Unico dato personale: il token API di CardTrader, salvato solo sul telefono e cifrato
 * con una chiave dell'Android Keystore.
 *
 * @param context contesto dell'applicazione.
 * @param cipher TokenCipher - cifratura del token (sul telefono con la chiave del Keystore).
 */
class SettingsStore(context: Context, private val cipher: TokenCipher = TokenCipher(KeystoreKeys::cardTraderKey)) {

    private val store = context.applicationContext.settingsDataStore
    private val secrets = context.applicationContext.secretsDataStore

    /** Indirizzo da cui scaricare il database delle carte (vedi [DEFAULT_CATALOG_URL]). */
    val catalogUrl: Flow<String> = store.data.map { it[CATALOG_URL] ?: DEFAULT_CATALOG_URL }

    /**
     * Salva l'indirizzo del database delle carte; un testo vuoto ripristina quello predefinito.
     * @param url String - indirizzo HTTPS già controllato.
     * @return Unit
     */
    suspend fun setCatalogUrl(url: String) {
        store.edit { if (url.isEmpty() || url == DEFAULT_CATALOG_URL) it.remove(CATALOG_URL) else it[CATALOG_URL] = url }
    }

    /** Ultimo aggiornamento del catalogo riuscito (0 = mai). */
    val lastCatalogSync: Flow<Long> = store.data.map { it[LAST_CATALOG_SYNC] ?: 0L }

    /**
     * Token API di CardTrader inserito dall'utente (stringa vuota = CardTrader disattivato), decifrato. Finché il token
     * salvato in chiaro dalle versioni precedenti non è stato cifrato ([migrateCardTraderToken]) vale quello. Se il
     * token cifrato non si decifra (chiave del Keystore persa, es. dopo un ripristino) risulta assente e va reinserito.
     */
    val cardTraderToken: Flow<String> = combine(store.data, secrets.data) { plain, secret ->
        secret[CARDTRADER_TOKEN_ENCRYPTED]?.let { cipher.decrypt(it) } ?: plain[LEGACY_CARDTRADER_TOKEN].orEmpty()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /**
     * Salva il token API di CardTrader cifrato e toglie l'eventuale copia in chiaro delle versioni precedenti.
     * @param token String - token già ripulito; vuoto per disattivare la fonte.
     * @return Boolean - false se il Keystore non ha potuto cifrarlo: in quel caso non viene salvato niente (mai in chiaro).
     */
    suspend fun setCardTraderToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isEmpty()) {
            secrets.edit { it.remove(CARDTRADER_TOKEN_ENCRYPTED) }
            store.edit { it.remove(LEGACY_CARDTRADER_TOKEN) }
            return@withContext true
        }
        val sealed = try {
            cipher.encrypt(token)
        } catch (e: GeneralSecurityException) {
            return@withContext false
        }
        secrets.edit { it[CARDTRADER_TOKEN_ENCRYPTED] = sealed }
        store.edit { it.remove(LEGACY_CARDTRADER_TOKEN) }
        true
    }

    /**
     * Cifra il token salvato in chiaro dalle versioni precedenti e cancella la copia in chiaro. Da chiamare all'avvio;
     * se il Keystore non risponde il token resta com'era e si riprova al prossimo avvio.
     * @return Unit
     */
    suspend fun migrateCardTraderToken() {
        val legacy = store.data.first()[LEGACY_CARDTRADER_TOKEN]?.takeIf { it.isNotEmpty() } ?: return
        if (secrets.data.first()[CARDTRADER_TOKEN_ENCRYPTED] != null) {
            store.edit { it.remove(LEGACY_CARDTRADER_TOKEN) }
            return
        }
        setCardTraderToken(legacy)
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

    /** True se il controllo giornaliero dei prezzi con gli avvisi è attivo (spento di default). */
    val priceAlertsEnabled: Flow<Boolean> = store.data.map { it[PRICE_ALERTS_ENABLED] ?: false }

    /**
     * Attiva o spegne il controllo giornaliero dei prezzi con gli avvisi.
     * @param enabled Boolean - true per attivarlo.
     * @return Unit
     */
    suspend fun setPriceAlertsEnabled(enabled: Boolean) {
        store.edit { it[PRICE_ALERTS_ENABLED] = enabled }
    }

    /** Variazione minima, in percentuale, per l'avviso "il prezzo sale o scende". */
    val priceAlertChangePercent: Flow<Int> = store.data.map {
        (it[PRICE_ALERT_CHANGE_PERCENT] ?: PriceAlertRules.DEFAULT_CHANGE_PERCENT).coerceIn(PriceAlertRules.CHANGE_PERCENT_RANGE)
    }

    /**
     * Imposta la variazione minima per l'avviso "il prezzo sale o scende".
     * @param percent Int - percentuale, ricondotta a [PriceAlertRules.CHANGE_PERCENT_RANGE].
     * @return Unit
     */
    suspend fun setPriceAlertChangePercent(percent: Int) {
        store.edit { it[PRICE_ALERT_CHANGE_PERCENT] = percent.coerceIn(PriceAlertRules.CHANGE_PERCENT_RANGE) }
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
     * Registra la data dell'ultimo aggiornamento catalogo riuscito.
     * @param timestamp Long - istante in millisecondi.
     * @return Unit
     */
    suspend fun setLastCatalogSync(timestamp: Long) {
        store.edit { it[LAST_CATALOG_SYNC] = timestamp }
    }

    companion object {
        /** Dove si trova il database delle carte pubblicato (release con tag fisso `catalog` del repository, separata dalle release dell'APK). */
        const val DEFAULT_CATALOG_URL = "https://github.com/RobertoBiggi/Card-collection/releases/download/catalog/cards.sqlite"

        private val CATALOG_URL = stringPreferencesKey("catalog_url")
        /** Token in chiaro delle versioni precedenti: si legge solo per cifrarlo, poi viene cancellato. */
        private val LEGACY_CARDTRADER_TOKEN = stringPreferencesKey("cardtrader_token")
        private val CARDTRADER_TOKEN_ENCRYPTED = stringPreferencesKey("cardtrader_token_v1")
        private val SHOW_CARDMARKET = booleanPreferencesKey("show_price_cardmarket")
        private val SHOW_TCGPLAYER = booleanPreferencesKey("show_price_tcgplayer")
        private val COUNT_FIRST_EDITION = booleanPreferencesKey("count_first_edition")
        private val SHOW_CARDTRADER = booleanPreferencesKey("show_price_cardtrader")
        private val LAST_CATALOG_SYNC = longPreferencesKey("last_catalog_sync")
        private val PRICE_ALERTS_ENABLED = booleanPreferencesKey("price_alerts_enabled")
        private val PRICE_ALERT_CHANGE_PERCENT = intPreferencesKey("price_alert_change_percent")
    }
}
