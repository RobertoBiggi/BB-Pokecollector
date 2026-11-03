package com.robertobiggi.cardcollection.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.robertobiggi.cardcollection.CardCollectionApp
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.data.imagesource.TcgdexImageSource
import com.robertobiggi.cardcollection.data.repo.AbortReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import com.robertobiggi.cardcollection.data.repo.SyncPhase
import com.robertobiggi.cardcollection.data.repo.SyncProgress

/** Keys of the input, progress and result data of the workers (data only: the UI composes the texts). */
object WorkKeys {
    const val COLLECTION_ID = "collectionId"
    const val PHASE = "phase"
    const val LABEL = "label"
    const val DONE = "done"
    const val TOTAL = "total"
    const val CARDS = "cards"
    const val IMAGES = "images"
    const val ERRORS = "errors"
    const val EMPTY_SETS = "emptySets"
    const val NO_CATALOG = "noCatalog"
    const val OK = "ok"
    const val FALLBACK = "fallback"
    const val FAILED = "failed"
    const val ABORT = "abort"
    const val RETRIES = "retries"
    const val IMAGES_FAILED = "imagesFailed"
    const val NOTE = "note"
}

/**
 * Brings the job to the foreground with its notification, so it is not interrupted after 10 minutes
 * (normal background jobs have that limit). If the system does not allow it, the job
 * continues anyway in the background.
 * @receiver CoroutineWorker - running worker.
 * @return Unit
 */
private suspend fun CoroutineWorker.promoteToForeground() {
    try {
        setForeground(getForegroundInfo())
    } catch (e: IllegalStateException) {
        // Android 12+ may refuse to start in the foreground (e.g. app in the background without exemption).
    }
}

/** Downloads the catalog of a collection's active searches and the images. Started by hand only. */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Runs the catalog sync of the collection given in the input data, publishing the progress.
     * @return Result - success with the counts, failure if no card was saved and there were errors.
     */
    override suspend fun doWork(): Result {
        val collectionId = inputData.getLong(WorkKeys.COLLECTION_ID, -1L)
        if (collectionId < 0) return Result.failure()
        promoteToForeground()
        val container = (applicationContext as CardCollectionApp).container
        val result = container.catalog.sync(collectionId) { p -> publish(p) }
        // The cards still without an image (TCGdex does not have it, or failed) go through the other sources in order: CardTrader (with a token),
        // then the sites whose paths are in the card database. Sources with nothing for a card are skipped.
        val otherImages = container.imageDownloader.fillMissing(collectionId, alreadyTried = setOf(TcgdexImageSource.ID)) { p -> publish(p) }
        val data = workDataOf(WorkKeys.CARDS to result.cardsSaved, WorkKeys.IMAGES to result.imagesSaved + otherImages, WorkKeys.ERRORS to result.errors, WorkKeys.EMPTY_SETS to result.emptySets, WorkKeys.NO_CATALOG to result.noCatalog)
        return if (result.noCatalog || (result.cardsSaved == 0 && result.errors > 0)) Result.failure(data) else Result.success(data)
    }

    /**
     * Publishes the progress for the screen and updates the notification (with the text of what is being downloaded and of attempts and errors).
     * Images arrive many times per second: the notification is updated at most once per second.
     * @param p SyncProgress - progress.
     * @return Unit
     */
    private suspend fun publish(p: SyncProgress) {
        setProgress(
            workDataOf(
                WorkKeys.PHASE to p.phase.name, WorkKeys.LABEL to p.label, WorkKeys.DONE to p.done, WorkKeys.TOTAL to p.total,
                WorkKeys.RETRIES to p.retries, WorkKeys.IMAGES_FAILED to p.failed, WorkKeys.NOTE to p.note,
            ),
        )
        val now = System.currentTimeMillis()
        if (p.phase !in IMAGE_PHASES || now - lastNotified < NOTIFY_EVERY_MS) return
        lastNotified = now
        val context = applicationContext
        val text = context.getString(
            if (p.phase == SyncPhase.IMAGES) R.string.job_images else R.string.job_images_other,
            p.done + 1, p.total, p.label,
        ) + issuesSuffix(context, p.retries, p.failed, p.note)
        context.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            Notifications.progress(context, context.getString(R.string.notification_images_title), text, p.done, p.total),
        )
    }

    /**
     * Notification shown when the job runs as a foreground service (Android 11 and earlier).
     * @return ForegroundInfo - progress notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        Notifications.progress(applicationContext, applicationContext.getString(R.string.notification_catalog_title)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private var lastNotified = 0L

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFY_EVERY_MS = 1_000L
        val IMAGE_PHASES = setOf(SyncPhase.IMAGES, SyncPhase.IMAGES_OTHER)
    }
}

/** Updates the prices of the wanted or owned variants of a collection. Started by hand only. */
class PriceCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Runs the price update of the collection given in the input data, publishing the progress.
     * @return Result - success with the counts (even if interrupted: the saved prices stay valid).
     */
    override suspend fun doWork(): Result {
        val collectionId = inputData.getLong(WorkKeys.COLLECTION_ID, -1L)
        if (collectionId < 0) return Result.failure()
        promoteToForeground()
        val container = (applicationContext as CardCollectionApp).container
        val result = container.prices.runPriceCheck(collectionId) { p ->
            setProgress(workDataOf(WorkKeys.LABEL to p.cardName, WorkKeys.PHASE to (p.step?.name ?: p.variant?.name), WorkKeys.DONE to p.done, WorkKeys.TOTAL to p.total))
        }
        return Result.success(
            workDataOf(WorkKeys.OK to result.ok, WorkKeys.FALLBACK to result.fallback, WorkKeys.FAILED to result.failed, WorkKeys.ABORT to result.abort?.name),
        )
    }

    /**
     * Notification shown when the job runs as a foreground service (Android 11 and earlier).
     * @return ForegroundInfo - progress notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        Notifications.progress(applicationContext, applicationContext.getString(R.string.notification_prices_title)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val NOTIFICATION_ID = 1002
    }
}

/**
 * Tail of the image progress text: repeated attempts, images not downloaded and last error. Empty if there is nothing to say.
 * @param context Context - context for the translated texts.
 * @param retries Int - repeated attempts so far.
 * @param failed Int - images not downloaded.
 * @param note String? - last error reason (e.g. "HTTP 429").
 * @return String - text to append, already with the separator.
 */
fun issuesSuffix(context: Context, retries: Int, failed: Int, note: String?): String =
    if (retries == 0 && failed == 0) "" else "\n" + context.getString(R.string.job_images_issues, retries, failed, note ?: "-")

/**
 * Daily check of price alerts, shortly after the Cardmarket price list update. Updates only the
 * variants with an alert, shows the notifications and schedules the next day's check. If the user turned
 * alerts off it does nothing and does not reschedule.
 */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Runs the alert check.
     * @return Result - success; a new attempt (at most [MAX_ATTEMPTS]) if the network or the price list do not respond.
     */
    override suspend fun doWork(): Result {
        val container = (applicationContext as CardCollectionApp).container
        if (!container.settings.priceAlertsEnabled.first()) return Result.success()
        val result = try {
            container.prices.runAlertCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Price list not downloadable or invalid response: retry later, then move on to the next day.
            null
        }
        result?.notices?.forEach { Notifications.showPriceAlert(applicationContext, it) }
        val failed = result == null || result.abort == AbortReason.OFFLINE
        if (failed && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        container.work.scheduleNextPriceAlertCheck()
        return Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

