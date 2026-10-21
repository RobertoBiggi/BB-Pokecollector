package com.robertobiggi.cardcollection

import android.content.Context
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.validation.Validators
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.repo.CatalogRepository
import com.robertobiggi.cardcollection.data.repo.CollectionRepository
import com.robertobiggi.cardcollection.data.repo.PriceRepository
import com.robertobiggi.cardcollection.data.repo.TransferRepository
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import com.robertobiggi.cardcollection.work.WorkScheduler
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCardSearch
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Contenitore delle dipendenze dell'app (DI manuale): crea una sola istanza di ogni servizio.
 *
 * @param context contesto dell'applicazione.
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
        // Controlla ogni richiesta, redirect compresi: l'app parla solo con TCGdex, Cardmarket e CardTrader.
        .addNetworkInterceptor { chain ->
            val host = chain.request().url.host
            if (host !in ALLOWED_HOSTS) throw IOException("Host non consentito: $host")
            chain.proceed(chain.request())
        }
        .build()

    /** Scope dell'applicazione per il lavoro condiviso tra le schermate. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    val tcgdex = TcgdexClient(http)
    val images = ImageStore(appContext, http)

    val collection = CollectionRepository(database, appScope)
    private val cardmarketCatalog = CardmarketCatalog(http, File(appContext.cacheDir, "cardmarket-products.json"))
    val catalog = CatalogRepository(database, tcgdex, images, settings, CardmarketCardSearch(cardmarketCatalog, tcgdex))
    val prices = PriceRepository(database, tcgdex, http, settings, appContext.cacheDir)
    val transfer = TransferRepository(database, appContext.contentResolver, catalog, collection, images)
    val work = WorkScheduler(appContext)

    init {
        // Legge subito i nomi dei Pokémon, così la prima ricerca non blocca l'interfaccia.
        appScope.launch { PokemonNames.preload() }
    }

    private companion object {
        const val USER_AGENT = "CardCollection-Android (uso personale)"

        /** Host raggiungibili dall'app: API e immagini di TCGdex, file prezzi e prodotti di Cardmarket, API di CardTrader. */
        val ALLOWED_HOSTS = setOf("api.tcgdex.net", "downloads.s3.cardmarket.com", "api.cardtrader.com") + Validators.ALLOWED_IMAGE_HOSTS
    }
}
