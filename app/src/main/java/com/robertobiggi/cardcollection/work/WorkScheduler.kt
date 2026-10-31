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
 * State of a background job. [phase] and [label] describe the item being processed,
 * [output] contains the final counts (keys of [WorkKeys]); the UI composes the texts.
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
 * Starts and observes the manual jobs (catalog and prices) of each collection and schedules the daily check of the
 * price alerts. Each job is unique per collection: a second tap while the first is running does not start a duplicate.
 *
 * @param context application context.
 */
class WorkScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * State of the catalog sync of a collection.
     * @param collectionId Long - collection.
     * @return Flow<JobState?> - current state, null if never started.
     */
    fun catalogState(collectionId: Long): Flow<JobState?> = observe(catalogName(collectionId))

    /**
     * State of the price update of a collection.
     * @param collectionId Long - collection.
     * @return Flow<JobState?> - current state, null if never started.
     */
    fun priceState(collectionId: Long): Flow<JobState?> = observe(priceName(collectionId))

    /**
     * Starts the catalog sync of a collection, if not already running.
     * @param collectionId Long - collection.
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
     * Starts the price update of a collection, if not already running.
     * @param collectionId Long - collection.
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
     * Schedules the daily price alert check at the next [PriceAlertRules.DAILY_RUN_TIME], if not
     * already scheduled. Safe to call at every app opening and when the user enables alerts.
     * @return Unit
     */
    fun schedulePriceAlerts() {
        workManager.enqueueUniqueWork(PRICE_ALERTS, ExistingWorkPolicy.KEEP, priceAlertRequest())
    }

    /**
     * Schedules the next day's check, queued after the current one (called by the job itself at the end):
     * the time is recomputed every time, so it stays at 4:30 even across daylight saving changes.
     * @return Unit
     */
    fun scheduleNextPriceAlertCheck() {
        workManager.enqueueUniqueWork(PRICE_ALERTS, ExistingWorkPolicy.APPEND_OR_REPLACE, priceAlertRequest())
    }

    /**
     * Removes the daily alert check (the user turned them off).
     * @return Unit
     */
    fun cancelPriceAlerts() {
        workManager.cancelUniqueWork(PRICE_ALERTS)
    }

    /**
     * Request for the alert check, delayed until the next daily time; waits for the network.
     * @return OneTimeWorkRequest - request ready.
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
     * Observes a unique job and converts it into [JobState].
     * @param name String - name of the unique job.
     * @return Flow<JobState?> - current state, null if the job was never started.
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
     * Name of the unique catalog sync job.
     * @param collectionId Long - collection.
     * @return String - name of the job.
     */
    private fun catalogName(collectionId: Long) = "catalog-sync-$collectionId"

    /**
     * Name of the unique price update job.
     * @param collectionId Long - collection.
     * @return String - name of the job.
     */
    private fun priceName(collectionId: Long) = "price-check-$collectionId"

    private companion object {
        /** Name of the unique job for the daily price alert check. */
        const val PRICE_ALERTS = "price-alerts"

        /** Initial wait before retrying the alert check (then it grows). */
        const val ALERT_RETRY_MINUTES = 15L
    }
}
