package com.robertobiggi.cardcollection.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.robertobiggi.cardcollection.CardCollectionApp

/** Chiavi dei dati di avanzamento e risultato dei worker. */
object WorkKeys {
    const val MESSAGE = "message"
    const val DONE = "done"
    const val TOTAL = "total"
    const val COLLECTION_ID = "collectionId"
}

/** Scarica il catalogo delle ricerche attive e le immagini. Avviato solo a mano. */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Esegue il sync del catalogo della collezione indicata nei dati di input, pubblicando l'avanzamento.
     * @return Result - successo con il riepilogo, oppure fallimento con il messaggio d'errore.
     */
    override suspend fun doWork(): Result {
        val container = (applicationContext as CardCollectionApp).container
        val collectionId = inputData.getLong(WorkKeys.COLLECTION_ID, -1L)
        if (collectionId < 0) return Result.failure()
        val result = container.catalog.sync(collectionId) { p ->
            setProgress(workDataOf(WorkKeys.MESSAGE to p.message, WorkKeys.DONE to p.done, WorkKeys.TOTAL to p.total))
        }
        val data = workDataOf(WorkKeys.MESSAGE to result.summary())
        return if (result.cardsSaved == 0 && result.errors.isNotEmpty()) Result.failure(data) else Result.success(data)
    }

    /**
     * Notifica mostrata quando il lavoro gira come servizio in primo piano (Android 11 e precedenti).
     * @return ForegroundInfo - notifica di avanzamento.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        Notifications.progress(applicationContext, "Aggiornamento catalogo", "Scarico carte e immagini", 0, 0),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}

/** Aggiorna i prezzi di tutte le varianti desiderate o possedute. Avviato solo a mano. */
class PriceCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Esegue l'aggiornamento prezzi della collezione indicata nei dati di input, pubblicando l'avanzamento.
     * @return Result - successo con il riepilogo (anche se interrotto: i prezzi salvati restano validi).
     */
    override suspend fun doWork(): Result {
        val container = (applicationContext as CardCollectionApp).container
        val collectionId = inputData.getLong(WorkKeys.COLLECTION_ID, -1L)
        if (collectionId < 0) return Result.failure()
        val result = container.prices.runPriceCheck(collectionId) { p ->
            setProgress(workDataOf(WorkKeys.MESSAGE to p.message, WorkKeys.DONE to p.done, WorkKeys.TOTAL to p.total))
        }
        return Result.success(workDataOf(WorkKeys.MESSAGE to result.message))
    }

    /**
     * Notifica mostrata quando il lavoro gira come servizio in primo piano (Android 11 e precedenti).
     * @return ForegroundInfo - notifica di avanzamento.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        Notifications.progress(applicationContext, "Aggiornamento prezzi", "Controllo i prezzi delle carte", 0, 0),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val NOTIFICATION_ID = 1002
    }
}
