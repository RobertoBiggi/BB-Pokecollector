package com.robertobiggi.cardcollection

import android.content.Context
import android.os.Build
import com.robertobiggi.cardcollection.core.analytics.AnalyticsContext
import com.robertobiggi.cardcollection.core.analytics.AptabaseClient
import com.robertobiggi.cardcollection.core.image.CatalogImageSources
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.data.catalog.CatalogStore
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.imagesource.CardTraderImageSource
import com.robertobiggi.cardcollection.data.imagesource.CatalogColumnImageSource
import com.robertobiggi.cardcollection.data.imagesource.ImageDownloader
import com.robertobiggi.cardcollection.data.imagesource.TcgdexImageSource
import com.robertobiggi.cardcollection.data.repo.CatalogRepository
import com.robertobiggi.cardcollection.data.repo.CollectionRepository
import com.robertobiggi.cardcollection.data.repo.PriceRepository
import com.robertobiggi.cardcollection.data.repo.TransferRepository
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import com.robertobiggi.cardcollection.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Container of the app's dependencies (manual DI): creates a single instance of each service.
 *
 * @param context application context.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        // Checks every request, redirects included: the app only talks to the images of TCGdex, Cardmarket (price list), CardTrader and the other image sources.
        .addNetworkInterceptor { chain ->
            val host = chain.request().url.host
            if (host !in ALLOWED_HOSTS) throw IOException("Host non consentito: $host")
            chain.proceed(chain.request())
        }
        .build()

    /** Application scope for work shared between screens. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    /** Client to download the card database: the address is chosen by the user (HTTPS), so it has no list of allowed hosts. */
    private val downloadHttp = http.newBuilder().apply { networkInterceptors().clear() }.readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    val catalogStore = CatalogStore(appContext, downloadHttp)
    val images = ImageStore(appContext, http)

    val collection = CollectionRepository(database, appScope, settings, images)
    val catalog = CatalogRepository(database, catalogStore, images, settings)
    val prices = PriceRepository(database, http, settings, appContext.cacheDir)
    /** Image sources in order of preference: the first that has an image for a card, and downloads it, wins. */
    val imageDownloader = ImageDownloader(
        database, images,
        listOf(TcgdexImageSource(images)) + CatalogImageSources.BEFORE_CARDTRADER.map { CatalogColumnImageSource(it, catalogStore, images) } +
            CardTraderImageSource(http, settings, images) + CatalogImageSources.AFTER_CARDTRADER.map { CatalogColumnImageSource(it, catalogStore, images) },
    )
    val transfer = TransferRepository(database, appContext.contentResolver, catalog, collection, images)
    val work = WorkScheduler(appContext)
    /** Anonymous usage counter: does nothing without a key in the build. */
    private val analytics = AptabaseClient(http, BuildConfig.APTABASE_KEY)

    /** True if this build has a counter, so Settings shows its switch. */
    val analyticsAvailable: Boolean get() = analytics.configured

    init {
        // Reads the Pokémon names right away, so the first search does not block the UI.
        appScope.launch { PokemonNames.preload() }
        appScope.launch { catalogStore.load() }
        // Removes cards left over from deleted collections (the one-hour margin avoids those of a sync in progress).
        appScope.launch { collection.purgeOrphanCards(System.currentTimeMillis() - ORPHAN_GRACE_MS) }
        // One anonymous "app_open" per day, only if the user did not turn it off. Failures are ignored and retried at the next opening.
        appScope.launch { sendUsagePing() }
        // Reschedules the daily alert check if it was lost (e.g. app force-stopped).
        appScope.launch { if (settings.priceAlertsEnabled.first()) work.schedulePriceAlerts() }
    }

    /**
     * Sends the daily anonymous usage event if allowed and not already sent in the last 24 hours.
     * @return Unit
     */
    private suspend fun sendUsagePing() {
        if (!analytics.configured || !settings.analyticsEnabled.first()) return
        val now = System.currentTimeMillis()
        if (now - settings.lastAnalyticsPing.first() < PING_INTERVAL_MS) return
        val info = AnalyticsContext(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), Build.VERSION.RELEASE, java.util.Locale.getDefault().toLanguageTag())
        if (analytics.track("app_open", info)) settings.setLastAnalyticsPing(now)
    }

    private companion object {
        const val PING_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val ORPHAN_GRACE_MS = 60 * 60 * 1000L
        const val USER_AGENT = "CardCollection-Android (uso personale)"

        /** Hosts reachable by the app: TCGdex images, Cardmarket price list, CardTrader API and images, the other image sources, Aptabase (anonymous usage counter). */
        val ALLOWED_HOSTS = setOf("downloads.s3.cardmarket.com", "api.cardtrader.com", "tcgcsv.com") + AptabaseClient.HOSTS + Validators.ALLOWED_IMAGE_HOSTS + Validators.ALLOWED_CARDTRADER_IMAGE_HOSTS +
            CatalogImageSources.hosts
    }
}
