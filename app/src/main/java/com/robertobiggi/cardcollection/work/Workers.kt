package com.robertobiggi.cardcollection.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.robertobiggi.cardcollection.CardCollectionApp
import com.robertobiggi.cardcollection.R

/** Chiavi dei dati di input, avanzamento e risultato dei worker (solo dati: i testi li compone la UI). */
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
}

/**
 * Porta il lavoro in primo piano con la sua notifica, così non viene interrotto dopo 10 minuti
 * (i lavori in background normali hanno quel limite). Se il sistema non lo consente, il lavoro
 * continua lo stesso in background.
 * @receiver CoroutineWorker - worker in esecuzione.
 * @return Unit
 */
private suspend fun CoroutineWorker.promoteToForeground() {
    try {
        setForeground(getForegroundInfo())
    } catch (e: IllegalStateException) {
        // Android 12+ può rifiutare l'avvio in primo piano (es. app in background senza esenzione).
    }
}

/** Scarica il catalogo delle ricerche attive di una collezione e le immagini. Avviato solo a mano. */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Esegue il sync del catalogo della collezione indicata nei dati di input, pubblicando l'avanzamento.
     * @return Result - successo con i conteggi, fallimento se nessuna carta è stata salvata e ci sono stati errori.
     */
    override suspend fun doWork(): Result {
        val collectionId = inputData.getLong(WorkKeys.COLLECTION_ID, -1L)
        if (collectionId < 0) return Result.failure()
        promoteToForeground()
        val container = (applicationContext as CardCollectionApp).container
        val result = container.catalog.sync(collectionId) { p ->
            setProgress(workDataOf(WorkKeys.PHASE to p.phase.name, WorkKeys.LABEL to p.label, WorkKeys.DONE to p.done, WorkKeys.TOTAL to p.total))
        }
        // Le immagini che TCGdex non ha si prendono da CardTrader (se c'è il token).
        val cardTraderImages = container.prices.fillImagesFromCardTrader { p ->
            setProgress(workDataOf(WorkKeys.PHASE to p.phase.name, WorkKeys.LABEL to p.label, WorkKeys.DONE to p.done, WorkKeys.TOTAL to p.total))
        }
        val data = workDataOf(WorkKeys.CARDS to result.cardsSaved, WorkKeys.IMAGES to result.imagesSaved + cardTraderImages, WorkKeys.ERRORS to result.errors, WorkKeys.EMPTY_SETS to result.emptySets, WorkKeys.NO_CATALOG to result.noCatalog)
        return if (result.noCatalog || (result.cardsSaved == 0 && result.errors > 0)) Result.failure(data) else Result.success(data)
    }

    /**
     * Notifica mostrata quando il lavoro gira come servizio in primo piano (Android 11 e precedenti).
     * @return ForegroundInfo - notifica di avanzamento.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        Notifications.progress(applicationContext, applicationContext.getString(R.string.notification_catalog_title)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}

/** Aggiorna i prezzi delle varianti desiderate o possedute di una collezione. Avviato solo a mano. */
class PriceCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Esegue l'aggiornamento prezzi della collezione indicata nei dati di input, pubblicando l'avanzamento.
     * @return Result - successo con i conteggi (anche se interrotto: i prezzi salvati restano validi).
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
     * Notifica mostrata quando il lavoro gira come servizio in primo piano (Android 11 e precedenti).
     * @return ForegroundInfo - notifica di avanzamento.
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
