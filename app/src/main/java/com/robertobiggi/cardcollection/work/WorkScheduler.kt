package com.robertobiggi.cardcollection.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Stato di un lavoro in background. [phase] e [label] descrivono l'elemento in lavorazione,
 * [output] contiene i conteggi finali (chiavi di [WorkKeys]); i testi li compone la UI.
 */
data class JobState(
    val running: Boolean,
    val waitingForNetwork: Boolean,
    val failed: Boolean,
    val phase: String?,
    val label: String?,
    val done: Int,
    val total: Int,
    val retries: Int = 0,
    val imagesFailed: Int = 0,
    val note: String? = null,
    val output: Map<String, Any?>,
)

/**
 * Avvia e osserva i lavori manuali (catalogo e prezzi) di ogni collezione e programma il controllo giornaliero degli
 * avvisi di prezzo. Ogni lavoro è unico per collezione: un secondo tocco mentre il primo è in corso non avvia un duplicato.
 *
 * @param context contesto dell'applicazione.
 */
class WorkScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Stato del sync del catalogo di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<JobState?> - stato corrente, null se mai avviato.
     */
    fun catalogState(collectionId: Long): Flow<JobState?> = observe(catalogName(collectionId))

    /**
     * Stato dell'aggiornamento prezzi di una collezione.
     * @param collectionId Long - collezione.
     * @return Flow<JobState?> - stato corrente, null se mai avviato.
     */
    fun priceState(collectionId: Long): Flow<JobState?> = observe(priceName(collectionId))

    /**
     * Avvia il sync del catalogo di una collezione, se non è già in corso.
     * @param collectionId Long - collezione.
     * @return Unit
     */
    fun startCatalogSync(collectionId: Long) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(WorkKeys.COLLECTION_ID to collectionId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(catalogName(collectionId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Avvia l'aggiornamento prezzi di una collezione, se non è già in corso.
     * @param collectionId Long - collezione.
     * @return Unit
     */
    fun startPriceCheck(collectionId: Long) {
        val request = OneTimeWorkRequestBuilder<PriceCheckWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(WorkKeys.COLLECTION_ID to collectionId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(priceName(collectionId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Programma il controllo giornaliero degli avvisi di prezzo al prossimo [PriceAlertRules.DAILY_RUN_TIME], se non è
     * già programmato. Sicuro da chiamare a ogni apertura dell'app e quando l'utente attiva gli avvisi.
     * @return Unit
     */
    fun schedulePriceAlerts() {
        workManager.enqueueUniqueWork(PRICE_ALERTS, ExistingWorkPolicy.KEEP, priceAlertRequest())
    }

    /**
     * Programma il controllo del giorno dopo, accodato a quello in corso (lo chiama il lavoro stesso alla fine):
     * l'orario si ricalcola ogni volta, così resta alle 4:30 anche con il cambio dell'ora legale.
     * @return Unit
     */
    fun scheduleNextPriceAlertCheck() {
        workManager.enqueueUniqueWork(PRICE_ALERTS, ExistingWorkPolicy.APPEND_OR_REPLACE, priceAlertRequest())
    }

    /**
     * Toglie il controllo giornaliero degli avvisi (l'utente li ha spenti).
     * @return Unit
     */
    fun cancelPriceAlerts() {
        workManager.cancelUniqueWork(PRICE_ALERTS)
    }

    /**
     * Richiesta del controllo degli avvisi, ritardata fino al prossimo orario giornaliero; aspetta la rete.
     * @return OneTimeWorkRequest - richiesta pronta.
     */
    private fun priceAlertRequest(): OneTimeWorkRequest {
        val now = ZonedDateTime.now()
        val delay = Duration.between(now, PriceAlertRules.nextRun(now)).toMillis().coerceAtLeast(0)
        return OneTimeWorkRequestBuilder<PriceAlertWorker>()
            .setConstraints(network)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ALERT_RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Osserva un lavoro unico e lo converte in [JobState].
     * @param name String - nome del lavoro unico.
     * @return Flow<JobState?> - stato corrente, null se il lavoro non è mai stato avviato.
     */
    private fun observe(name: String): Flow<JobState?> =
        workManager.getWorkInfosForUniqueWorkFlow(name).map { infos ->
            val info = infos.lastOrNull() ?: return@map null
            val progress = info.progress
            JobState(
                running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                waitingForNetwork = info.state == WorkInfo.State.ENQUEUED,
                failed = info.state == WorkInfo.State.FAILED,
                phase = progress.getString(WorkKeys.PHASE),
                label = progress.getString(WorkKeys.LABEL),
                done = progress.getInt(WorkKeys.DONE, 0),
                total = progress.getInt(WorkKeys.TOTAL, 0),
                retries = progress.getInt(WorkKeys.RETRIES, 0),
                imagesFailed = progress.getInt(WorkKeys.IMAGES_FAILED, 0),
                note = progress.getString(WorkKeys.NOTE),
                output = info.outputData.keyValueMap,
            )
        }

    /**
     * Nome del lavoro unico di sync catalogo.
     * @param collectionId Long - collezione.
     * @return String - nome del lavoro.
     */
    private fun catalogName(collectionId: Long) = "catalog-sync-$collectionId"

    /**
     * Nome del lavoro unico di aggiornamento prezzi.
     * @param collectionId Long - collezione.
     * @return String - nome del lavoro.
     */
    private fun priceName(collectionId: Long) = "price-check-$collectionId"

    private companion object {
    }
}
