package com.robertobiggi.cardcollection.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    val output: Map<String, Any?>,
)

/**
 * Avvia e osserva i lavori manuali (catalogo e prezzi) di ogni collezione. Ogni lavoro è unico per
 * collezione: un secondo tocco mentre il primo è in corso non avvia un duplicato.
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
     * Avvia in background la ricerca dei link CardTrader mancanti, se non è già in corso. Sicuro da chiamare a ogni apertura.
     * @return Unit
     */
    fun startCardTraderLinks() {
        val request = OneTimeWorkRequestBuilder<CardTraderLinksWorker>().setConstraints(network).build()
        workManager.enqueueUniqueWork(CARDTRADER_LINKS, ExistingWorkPolicy.KEEP, request)
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
        const val CARDTRADER_LINKS = "cardtrader-links"
    }
}
