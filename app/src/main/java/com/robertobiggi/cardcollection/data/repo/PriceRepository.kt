package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.price.AlertConfig
import com.robertobiggi.cardcollection.core.price.AlertEvent
import com.robertobiggi.cardcollection.core.price.AlertState
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import com.robertobiggi.cardcollection.core.price.PricePoint
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.CardmarketGuidePriceProvider
import com.robertobiggi.cardcollection.core.price.CircuitBreaker
import com.robertobiggi.cardcollection.core.price.PriceChain
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.ProviderSlot
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.price.StepOutcome
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.PriceAlertEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/** Lavoro preparatorio dell'aggiornamento prezzi che richiede tempo e che viene mostrato all'utente. */
enum class PriceStep { DOWNLOAD_GUIDE, READ_GUIDE }

/**
 * Avanzamento dell'aggiornamento prezzi: carta e variante in lavorazione, oppure (con [step]) un lavoro
 * preparatorio (scaricare o leggere il listino Cardmarket); in quel caso [variant] è null.
 */
data class PriceProgress(val cardName: String, val variant: VariantType?, val done: Int, val total: Int, val step: PriceStep? = null)

/** Motivo per cui un aggiornamento prezzi si è interrotto prima della fine. */
enum class AbortReason { SOURCE_PAUSED, OFFLINE }

/** Esito dell'aggiornamento prezzi; [abort] è null se il run è arrivato in fondo. */
data class PriceRunResult(val ok: Int, val fallback: Int, val failed: Int, val abort: AbortReason?)

/**
 * Avviso di prezzo da notificare.
 * @property cardId carta.
 * @property collectionId collezione da aprire toccando la notifica, null se la carta non è in nessuna.
 * @property cardName nome della carta.
 * @property variantType tipo della variante, null se non riconosciuto.
 * @property event cosa è successo al prezzo.
 */
data class PriceAlertNotice(
    val cardId: String,
    val collectionId: Long?,
    val cardName: String,
    val variantType: VariantType?,
    val event: AlertEvent,
)

/** Esito del controllo giornaliero degli avvisi; [abort] non è null se si è fermato prima della fine. */
data class AlertCheckResult(val notices: List<PriceAlertNotice>, val checked: Int, val abort: AbortReason?)

/**
 * Aggiorna i prezzi delle varianti desiderate o possedute e salva l'ultimo risultato in locale.
 * Il prodotto Cardmarket e il blueprint CardTrader di ogni carta arrivano dal database delle carte: il prezzo in euro viene dal listino
 * pubblico di Cardmarket. Se l'utente ha inserito il token CardTrader, ogni variante ha anche il prezzo CardTrader, in una catena
 * separata: non sostituisce Cardmarket e i suoi errori non contano come errori dell'aggiornamento.
 * Se una variante non ha dati nuovi resta valido il prezzo salvato in precedenza.
 */
class PriceRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val cacheDir: File,
    private val images: ImageStore,
) {
    private val priceDao = db.priceDao()

    /**
     * Esegue l'aggiornamento prezzi delle carte visibili di una collezione.
     * @param collectionId Long - collezione da aggiornare.
     * @param progress suspend (PriceProgress) -> Unit - callback di avanzamento.
     * @return PriceRunResult - conteggi ed eventuale motivo di interruzione.
     */
    suspend fun runPriceCheck(collectionId: Long, progress: suspend (PriceProgress) -> Unit): PriceRunResult {
        val startedAt = System.currentTimeMillis()
        val runId = priceDao.insertRun(PriceRunEntity(0, startedAt, null, STATUS_RUNNING, 0, 0, 0, null, collectionId))

        val cards = db.catalogDao().visibleCardsOf(collectionId).associateBy { it.id }
        val entries = db.collectionDao().entriesOf(collectionId).associateBy { it.variantId }
        val variants = if (cards.isEmpty()) emptyList() else cards.keys.chunked(SQL_CHUNK).flatMap { db.catalogDao().variantsOf(it) }
        val typesByCard = variants.groupBy({ it.cardId }, { it.type })
        val targets = variants.filter { v ->
            val entry = entries[v.id]
            (entry?.wanted ?: true) || (entry?.quantity ?: 0) > 0
        }

        var position = 0
        val sources = priceSources { step -> progress(PriceProgress(STEP_LABEL, null, position, targets.size, step)) }

        var ok = 0
        var failed = 0
        var consecutiveNetworkErrors = 0
        var abortReason: AbortReason? = null
        var completed = false
        try {
            for ((index, variant) in targets.withIndex()) {
                val card = cards[variant.cardId] ?: continue
                val type = VariantType.fromName(variant.type) ?: continue
                if (sources.cardmarketGuide.allCircuitsOpen()) {
                    abortReason = AbortReason.SOURCE_PAUSED
                    break
                }
                position = index
                progress(PriceProgress(card.name, type, index, targets.size))
                when (checkVariant(sources, card, variant, typesByCard[card.id].orEmpty(), conditionOf(entries[variant.id]?.condition))) {
                    VariantCheck.OK -> { ok++; consecutiveNetworkErrors = 0 }
                    VariantCheck.FAILED -> { failed++; consecutiveNetworkErrors = 0 }
                    VariantCheck.NETWORK_ERROR -> {
                        failed++
                        consecutiveNetworkErrors++
                        if (consecutiveNetworkErrors >= MAX_NETWORK_ERRORS) {
                            abortReason = AbortReason.OFFLINE
                            break
                        }
                    }
                    VariantCheck.SKIPPED -> Unit
                }
            }
            completed = true
        } finally {
            // Anche se il lavoro viene interrotto, il run non resta "in corso" per sempre.
            withContext(NonCancellable) {
                priceDao.updateRun(
                    PriceRunEntity(
                        id = runId,
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        status = if (completed && abortReason == null) STATUS_DONE else STATUS_ABORTED,
                        okCount = ok,
                        fallbackCount = 0,
                        failCount = failed,
                        summary = abortReason?.name,
                        collectionId = collectionId,
                    ),
                )
                priceDao.pruneRuns(collectionId, KEEP_RUNS)
            }
        }
        return PriceRunResult(ok, 0, failed, abortReason)
    }

    /**
     * Aggiorna subito i prezzi di tutte le varianti di una sola carta, senza registrare un run.
     * @param cardId String - id locale della carta.
     * @return PriceRunResult - conteggi; [PriceRunResult.abort] vale SOURCE_PAUSED se il listino è in
     *         pausa e OFFLINE se tutte le richieste sono fallite per la rete.
     */
    suspend fun checkCard(cardId: String, collectionId: Long?): PriceRunResult {
        val entries = collectionId?.let { db.collectionDao().entriesOf(it) }.orEmpty().associateBy { it.variantId }
        val card = db.catalogDao().getCard(cardId) ?: return PriceRunResult(0, 0, 0, null)
        val variants = db.catalogDao().variantsOf(listOf(cardId))
        val types = variants.map { it.type }
        val sources = priceSources {}
        var ok = 0
        var failed = 0
        var networkErrors = 0
        var abortReason: AbortReason? = null
        for (variant in variants) {
            if (sources.cardmarketGuide.allCircuitsOpen()) {
                abortReason = AbortReason.SOURCE_PAUSED
                break
            }
            when (checkVariant(sources, card, variant, types, conditionOf(entries[variant.id]?.condition))) {
                VariantCheck.OK -> ok++
                VariantCheck.FAILED -> failed++
                VariantCheck.NETWORK_ERROR -> { failed++; networkErrors++ }
                VariantCheck.SKIPPED -> Unit
            }
        }
        if (abortReason == null && networkErrors > 0 && ok == 0) abortReason = AbortReason.OFFLINE
        return PriceRunResult(ok, 0, failed, abortReason)
    }

    private fun conditionOf(name: String?): Condition = Condition.entries.firstOrNull { it.name == name } ?: Condition.NM

    /** Esito del controllo di una singola variante. */
    private enum class VariantCheck { OK, FAILED, NETWORK_ERROR, SKIPPED }

    /**
     * Chiede il prezzo di una variante al listino Cardmarket (e a CardTrader, se c'è il token) e, se lo trova, lo salva.
     * @param sources PriceSources - fonti dell'aggiornamento.
     * @param card CardEntity - carta della variante.
     * @param variant CardVariantEntity - variante da controllare.
     * @param types List<String> - tipi di tutte le varianti della carta (per l'affidabilità del prezzo Cardmarket).
     * @param condition Condition - condizione della copia nella collezione: CardTrader mostra il prezzo più basso di quella condizione o migliore.
     * @return VariantCheck - esito; SKIPPED se lingua o tipo non sono riconosciuti.
     */
    private suspend fun checkVariant(sources: PriceSources, card: CardEntity, variant: CardVariantEntity, types: List<String>, condition: Condition): VariantCheck {
        val type = VariantType.fromName(variant.type) ?: return VariantCheck.SKIPPED
        val language = Language.fromCode(card.language) ?: return VariantCheck.SKIPPED
        val request = PriceRequest(
            language = language,
            tcgdexId = card.tcgdexId,
            variant = type,
            cardHasNormalAndHolo = VariantType.NORMAL.name in types && VariantType.HOLO.name in types,
            cardHasFirstEdition = VariantType.FIRST_EDITION.name in types,
            setId = card.setId,
            setName = card.setName,
            setCode = card.setCode,
            localId = card.localId,
            name = card.name,
            dexId = card.dexId,
            cardTraderCondition = CtCondition.fromCondition(condition),
            cardmarketId = card.cardmarketProduct ?: card.cardmarketId,
            printProductId = variant.cardmarketId,
            printFinish = if (VariantType.HOLO.name in types && VariantType.NORMAL.name !in types) VariantType.HOLO else VariantType.NORMAL,
            cardTraderBlueprintId = card.cardTraderBlueprintId,
        )
        val outcome = sources.cardmarketGuide.fetch(request)
        // CardTrader non distingue le stampe a parte (shadowless, jumbo…).
        val cardTraderOutcome = if (type.isPrint) null else sources.cardTrader?.fetch(request)
        val cardTraderSaved = cardTraderOutcome?.quote?.let { saveQuote(variant.id, it) } == true
        // Normal e holo condividono il blueprint: se CardTrader ha una sola stampa, l'altra non ha prezzo e quello salvato prima era dell'altra.
        if (cardTraderOutcome != null && cardTraderOutcome.quote == null && request.cardHasNormalAndHolo &&
            (type == VariantType.NORMAL || type == VariantType.HOLO) && cardTraderOutcome.steps.all { it.outcome == StepOutcome.NO_DATA }
        ) {
            priceDao.deleteSnapshotsOf(variant.id, CardTraderPriceProvider.DISPLAY_NAME)
        }
        val quote = outcome.quote
        if (quote != null) {
            saveQuote(variant.id, quote)
            return VariantCheck.OK
        }
        if (cardTraderSaved) return VariantCheck.OK
        return if (outcome.steps.any { it.outcome == StepOutcome.NETWORK_ERROR }) VariantCheck.NETWORK_ERROR else VariantCheck.FAILED
    }

    /**
     * Scarica da CardTrader l'immagine delle carte che il catalogo non ha (es. i set giapponesi senza immagini su TCGdex).
     * Serve il token CardTrader e il blueprint della carta, che arriva dal database delle carte; senza, o con la rete assente,
     * non fa nulla.
     * @param progress suspend (SyncProgress) -> Unit - avanzamento, una volta per carta.
     * @return Int - numero di carte a cui è stata aggiunta l'immagine.
     */
    suspend fun fillImagesFromCardTrader(progress: suspend (SyncProgress) -> Unit = {}): Int {
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return 0
        val missing = db.catalogDao().cardsWithoutImage().filter { it.cardTraderBlueprintId != null && it.cardTraderExpansionId != null }
        val provider = CardTraderPriceProvider(CardTraderClient(http, token))
        val limiter = RateLimiter(REQUEST_INTERVAL_MS)
        var saved = 0
        for ((index, stored) in missing.withIndex()) {
            try {
                progress(SyncProgress(SyncPhase.IMAGES_CARDTRADER, stored.name, index, missing.size))
                limiter.acquire()
                if (saveCardTraderImage(provider, stored)) saved++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Rete assente o risposta non valida: le altre carte possono andare a buon fine, e il prossimo aggiornamento riprova.
            }
        }
        return saved
    }

    /**
     * Scarica da CardTrader l'immagine di una singola carta, anche se ne ha già una (quella scelta a mano viene sostituita).
     * Serve il token CardTrader.
     * @param cardId String - id locale della carta.
     * @return ImageResult - DOWNLOADED, NOT_AVAILABLE se manca il token o la carta non ha un blueprint, NETWORK_ERROR.
     */
    suspend fun downloadImageFromCardTrader(cardId: String): ImageResult {
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return ImageResult.NOT_AVAILABLE
        val stored = db.catalogDao().getCard(cardId) ?: return ImageResult.NOT_AVAILABLE
        return try {
            if (saveCardTraderImage(CardTraderPriceProvider(CardTraderClient(http, token)), stored)) ImageResult.DOWNLOADED else ImageResult.NOT_AVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageResult.NETWORK_ERROR
        }
    }

    /** Cerca il blueprint della carta su CardTrader e ne salva l'immagine. @return Boolean - true se è stata salvata. */
    private suspend fun saveCardTraderImage(provider: CardTraderPriceProvider, card: CardEntity): Boolean {
        val expansion = card.cardTraderExpansionId ?: return false
        val blueprint = card.cardTraderBlueprintId ?: return false
        val url = provider.imageUrl(expansion, blueprint) ?: return false
        val (low, high) = images.saveFromCardTrader(card.id, url) ?: return false
        val current = db.catalogDao().getCard(card.id)
        if (images.isManual(current?.imageHighPath)) images.deleteManual(current?.imageHighPath)
        if (images.isManual(current?.imageLowPath) && current?.imageLowPath != current?.imageHighPath) images.deleteManual(current?.imageLowPath)
        db.catalogDao().setImagePaths(card.id, low, high)
        return true
    }

    /**
     * Salva un prezzo trovato come ultimo snapshot della variante.
     * @param variantId String - variante.
     * @param quote PriceQuote - prezzo da salvare.
     * @return Boolean - sempre true (comodo per concatenare).
     */
    private suspend fun saveQuote(variantId: String, quote: PriceQuote): Boolean {
        priceDao.insertSnapshot(
            PriceSnapshotEntity(
                variantId = variantId,
                source = quote.source,
                eur = quote.eur,
                eurLow = quote.eurLow,
                eurAvg30 = quote.eurAvg30,
                usd = quote.usd,
                usdLow = quote.usdLow,
                sourceUpdatedAt = quote.sourceUpdatedAt,
                fetchedAt = System.currentTimeMillis(),
                confidence = quote.confidence.name,
            ),
        )
        priceDao.pruneSnapshots(variantId, KEEP_SNAPSHOTS)
        return true
    }

    /** Fonti dell'aggiornamento: il listino Cardmarket (sempre) e CardTrader (solo con il token). */
    private class PriceSources(val cardTrader: PriceChain?, val cardmarketGuide: PriceChain)

    /**
     * Crea le fonti di un aggiornamento prezzi.
     * @param onStep suspend (PriceStep) -> Unit - chiamata quando sta per iniziare un lavoro preparatorio lungo.
     * @return PriceSources - fonti pronte.
     */
    private suspend fun priceSources(onStep: suspend (PriceStep) -> Unit): PriceSources {
        val token = settings.cardTraderToken.first()
        val cardTrader = token.takeIf { it.isNotEmpty() }?.let { CardTraderPriceProvider(CardTraderClient(http, it)) }
        val guide = CardmarketGuidePriceProvider(
            CardmarketPriceGuide(http, File(cacheDir, GUIDE_FILE), onDownload = { onStep(PriceStep.DOWNLOAD_GUIDE) }, onRead = { onStep(PriceStep.READ_GUIDE) }),
        )
        return PriceSources(
            cardTrader = cardTrader?.let { PriceChain(listOf(ProviderSlot(it, RateLimiter(REQUEST_INTERVAL_MS), CircuitBreaker()))) },
            cardmarketGuide = PriceChain(listOf(ProviderSlot(guide, RateLimiter(0), CircuitBreaker()))),
        )
    }

    /**
     * Osserva gli avvisi di prezzo, per variante.
     * @return Flow<Map<String, PriceAlertEntity>> - avviso per id della variante.
     */
    fun observeAlerts(): Flow<Map<String, PriceAlertEntity>> = priceDao.observeAlerts().map { list -> list.associateBy { it.variantId } }

    /**
     * Crea o modifica l'avviso di prezzo di una variante; senza nessuna delle due opzioni lo toglie. Un avviso nuovo parte
     * dai prezzi attuali, così il primo controllo misura le variazioni da quello che l'utente vede adesso; uno modificato
     * tiene il suo stato (vedi [PriceAlertRules.editedState]).
     * @param variantId String - variante.
     * @param notifyChanges Boolean - true per l'avviso "sale o scende".
     * @param changePercent Int - variazione minima per l'avviso "sale o scende", ricondotta a [PriceAlertRules.CHANGE_PERCENT_RANGE].
     * @param targetEur Double? - prezzo obiettivo in euro (già validato), null se non impostato.
     * @return Boolean - true se l'avviso è attivo, false se è stato tolto.
     */
    suspend fun saveAlert(variantId: String, notifyChanges: Boolean, changePercent: Int, targetEur: Double?): Boolean {
        val target = targetEur?.takeIf { it > 0.0 && it <= PriceAlertRules.MAX_TARGET_EUR && it.isFinite() }
        if (!notifyChanges && target == null) {
            priceDao.deleteAlert(variantId)
            return false
        }
        val current = pointsOf(priceDao.snapshotsOf(listOf(variantId))).associate { it.key to it.value }
        val existing = priceDao.getAlert(variantId)
        val state = PriceAlertRules.editedState(
            existing?.let { AlertState(PriceAlertRules.decodeBaselines(it.baselines), it.targetReached) },
            existing?.targetEur,
            target,
            current,
        )
        priceDao.upsertAlert(
            PriceAlertEntity(
                variantId = variantId,
                notifyChanges = notifyChanges,
                targetEur = target,
                targetReached = state.targetReached,
                baselines = PriceAlertRules.encodeBaselines(state.baselines),
                createdAt = System.currentTimeMillis(),
                lastCheckedAt = existing?.lastCheckedAt,
                changePercent = changePercent.coerceIn(PriceAlertRules.CHANGE_PERCENT_RANGE),
            ),
        )
        return true
    }

    /**
     * Toglie l'avviso di prezzo di una variante.
     * @param variantId String - variante.
     * @return Unit
     */
    suspend fun deleteAlert(variantId: String) = priceDao.deleteAlert(variantId)

    /**
     * Controllo giornaliero degli avvisi: aggiorna i prezzi delle sole varianti con un avviso (listino Cardmarket e,
     * con il token, CardTrader), li confronta con lo stato salvato ([PriceAlertRules.evaluate]) e restituisce gli avvisi
     * da notificare. Non registra un run: non è l'aggiornamento di una collezione.
     * @return AlertCheckResult - avvisi da notificare, varianti controllate ed eventuale interruzione.
     */
    suspend fun runAlertCheck(): AlertCheckResult {
        val alerts = priceDao.alerts()
        if (alerts.isEmpty()) return AlertCheckResult(emptyList(), 0, null)
        val defaultPercent = settings.priceAlertChangePercent.first()
        val variants = alerts.map { it.variantId }.chunked(SQL_CHUNK).flatMap { db.catalogDao().variantsByIds(it) }.associateBy { it.id }
        val sources = priceSources {}
        val notices = mutableListOf<PriceAlertNotice>()
        var checked = 0
        var consecutiveNetworkErrors = 0
        var abortReason: AbortReason? = null
        for (alert in alerts) {
            val variant = variants[alert.variantId] ?: continue
            val card = db.catalogDao().getCard(variant.cardId) ?: continue
            if (sources.cardmarketGuide.allCircuitsOpen()) {
                abortReason = AbortReason.SOURCE_PAUSED
                break
            }
            val startedAt = System.currentTimeMillis()
            val types = db.catalogDao().variantsOf(listOf(card.id)).map { it.type }
            // Stessa condizione dell'aggiornamento della collezione: il prezzo CardTrader salvato è condiviso e non deve cambiare a seconda di chi l'ha scaricato.
            val condition = conditionOf(db.collectionDao().entriesOfVariant(variant.id).firstOrNull()?.condition)
            val check = checkVariant(sources, card, variant, types, condition)
            if (check == VariantCheck.NETWORK_ERROR) {
                if (++consecutiveNetworkErrors >= MAX_NETWORK_ERRORS) {
                    abortReason = AbortReason.OFFLINE
                    break
                }
            } else {
                consecutiveNetworkErrors = 0
            }
            // Solo i prezzi scaricati adesso: quelli vecchi o inseriti a mano non fanno partire avvisi.
            val fresh = priceDao.snapshotsOf(listOf(variant.id)).filter { it.fetchedAt >= startedAt }
            val evaluation = PriceAlertRules.evaluate(
                AlertConfig(alert.notifyChanges, alert.targetEur),
                AlertState(PriceAlertRules.decodeBaselines(alert.baselines), alert.targetReached),
                pointsOf(fresh),
                alert.changePercent ?: defaultPercent,
            )
            priceDao.updateAlertState(alert.variantId, PriceAlertRules.encodeBaselines(evaluation.state.baselines), evaluation.state.targetReached, System.currentTimeMillis())
            checked++
            if (evaluation.events.isNotEmpty()) {
                val collectionId = db.catalogDao().collectionOfCard(card.id)
                evaluation.events.forEach { notices += PriceAlertNotice(card.id, collectionId, card.name, VariantType.fromName(variant.type), it) }
            }
        }
        return AlertCheckResult(notices, checked, abortReason)
    }

    /**
     * Prezzi utilizzabili dagli avvisi: l'ultimo di ogni fonte, esclusi quelli inseriti a mano. Gli euro di CardTrader
     * contano come CardTrader, gli altri come la loro fonte (listino Cardmarket); i dollari come TCGplayer.
     * @param snapshots List<PriceSnapshotEntity> - prezzi salvati della variante.
     * @return List<PricePoint> - un prezzo per fonte e valuta.
     */
    private fun pointsOf(snapshots: List<PriceSnapshotEntity>): List<PricePoint> =
        snapshots.filter { it.source != CollectionFiles.MANUAL_SOURCE }.sortedByDescending { it.fetchedAt }.flatMap { s ->
            listOfNotNull(
                s.eur?.let { PricePoint(s.source, PricePoint.EUR, it) },
                s.usd?.let { PricePoint(TCGPLAYER_SOURCE, PricePoint.USD, it) },
            )
        }.distinctBy { it.key }

    /**
     * Salva un prezzo inserito a mano dall'utente.
     * @param variantId String - variante a cui associare il prezzo.
     * @param eur Double - prezzo in euro già validato.
     * @return Unit
     */
    suspend fun addManualPrice(variantId: String, eur: Double) {
        priceDao.insertSnapshot(
            PriceSnapshotEntity(
                variantId = variantId,
                source = CollectionFiles.MANUAL_SOURCE,
                eur = eur,
                eurLow = null,
                eurAvg30 = null,
                usd = null,
                usdLow = null,
                sourceUpdatedAt = null,
                fetchedAt = System.currentTimeMillis(),
                confidence = Confidence.MANUAL.name,
            ),
        )
        priceDao.pruneSnapshots(variantId, KEEP_SNAPSHOTS)
    }

    companion object {
        /** Snapshot conservati per variante. */
        const val KEEP_SNAPSHOTS = 20

        /** Aggiornamenti prezzi conservati nel registro di ogni collezione. */
        const val KEEP_RUNS = 10
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_ABORTED = "ABORTED"
        private const val REQUEST_INTERVAL_MS = 300L
        private const val STEP_LABEL = "…"
        private const val GUIDE_FILE = "cardmarket-price-guide.json"
        private const val MAX_NETWORK_ERRORS = 5
        private const val SQL_CHUNK = 500

        /** Nome con cui gli avvisi indicano i prezzi in dollari (TCGplayer), come nel dettaglio della carta. */
        const val TCGPLAYER_SOURCE = "TCGplayer"
    }
}
