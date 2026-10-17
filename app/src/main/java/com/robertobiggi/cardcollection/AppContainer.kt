package com.robertobiggi.cardcollection

import android.content.Context
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.repo.BackupRepository
import com.robertobiggi.cardcollection.data.repo.CatalogRepository
import com.robertobiggi.cardcollection.data.repo.CollectionRepository
import com.robertobiggi.cardcollection.data.repo.PriceRepository
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import com.robertobiggi.cardcollection.work.WorkScheduler
import okhttp3.OkHttpClient
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
        .build()

    val database: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    val tcgdex = TcgdexClient(http)
    val images = ImageStore(appContext, http)

    val collection = CollectionRepository(database)
    val catalog = CatalogRepository(database, tcgdex, images, settings)
    val prices = PriceRepository(database, tcgdex, settings)
    val backup = BackupRepository(database, appContext.contentResolver)
    val work = WorkScheduler(appContext)

    private companion object {
        const val USER_AGENT = "CardCollection-Android/0.1 (uso personale)"
    }
}
