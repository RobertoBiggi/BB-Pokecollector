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

/** Preparatory work of the price update that takes time and is shown to the user. */
enum class PriceStep { DOWNLOAD_GUIDE, READ_GUIDE }

/**
 * Progress of the price update: card and variant being processed, or (with [step]) a preparatory
 * job (downloading or reading the Cardmarket price list); in that case [variant] is null.
 */
data class PriceProgress(val cardName: String, val variant: VariantType?, val done: Int, val total: Int, val step: PriceStep? = null)

/** Reason why a price update stopped before the end. */
enum class AbortReason { SOURCE_PAUSED, OFFLINE }

/** Outcome of the price update; [abort] is null if the run reached the end. */
data class PriceRunResult(val ok: Int, val fallback: Int, val failed: Int, val abort: AbortReason?)

/**
 * Price alert to notify.
 * @property cardId card.
 * @property collectionId collection to open by tapping the notification, null if the card is in none.
 * @property cardName name of the card.
 * @property variantType type of the variant, null if not recognized.
 * @property event what happened to the price.
 */
data class PriceAlertNotice(
    val cardId: String,
    val collectionId: Long?,
    val cardName: String,
    val variantType: VariantType?,
    val event: AlertEvent,
)

/** Outcome of the daily alert check; [abort] is not null if it stopped before the end. */
data class AlertCheckResult(val notices: List<PriceAlertNotice>, val checked: Int, val abort: AbortReason?)

/**
 * Updates the prices of the wanted or owned variants and saves the latest result locally.
 * The Cardmarket product and CardTrader blueprint of each card come from the card database: the price in euros comes from the public
 * Cardmarket price list. If the user entered the CardTrader token, each variant also gets the CardTrader price, in a
 * separate chain: it does not replace Cardmarket and its errors do not count as errors of the update.
 * If a variant has no new data the previously saved price stays valid.
 */
class PriceRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val cacheDir: File,
) {
    private val priceDao = db.priceDao()

    /**
     * Runs the price update for the visible cards of a collection.
     * @param collectionId Long - collection to update.
     * @param progress suspend (PriceProgress) -> Unit - progress callback.
     * @return PriceRunResult - counts and possible reason for the interruption.
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
            // Even if the job is interrupted, the run does not stay "in progress" forever.
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
     * Immediately updates the prices of all variants of a single card, without recording a run.
     * @param cardId String - local id of the card.
     * @return PriceRunResult - counts; [PriceRunResult.abort] is SOURCE_PAUSED if the price list is
     *         paused and OFFLINE if all requests failed because of the network.
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

    /** Outcome of checking a single variant. */
    private enum class VariantCheck { OK, FAILED, NETWORK_ERROR, SKIPPED }

    /**
     * Asks the Cardmarket price list (and CardTrader, if there is a token) for the price of a variant and, if found, saves it.
     * @param sources PriceSources - sources of the update.
     * @param card CardEntity - card of the variant.
     * @param variant CardVariantEntity - variant to check.
     * @param types List<String> - types of all variants of the card (for the reliability of the Cardmarket price).
     * @param condition Condition - condition of the copy in the collection: CardTrader shows the lowest price of that condition or better.
     * @return VariantCheck - outcome; SKIPPED if language or type are not recognized.
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
            variantBlueprintId = variant.cardTraderBlueprintId,
        )
        val outcome = sources.cardmarketGuide.fetch(request)
        // CardTrader does not tell separate prints apart (shadowless, jumbo…), except those with their own blueprint (oversized without a Cardmarket product).
        val cardTraderOutcome = if (type.isPrint && variant.cardTraderBlueprintId == null) null else sources.cardTrader?.fetch(request)
        val cardTraderSaved = cardTraderOutcome?.quote?.let { saveQuote(variant.id, it) } == true
        // Normal and holo share the blueprint: if CardTrader has only one print, the other has no price and the previously saved one belonged to the other.
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
     * Saves a found price as the latest snapshot of the variant.
     * @param variantId String - variant.
     * @param quote PriceQuote - price to save.
     * @return Boolean - always true (handy for chaining).
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

    /** Sources of the update: the Cardmarket price list (always) and CardTrader (only with the token). */
    private class PriceSources(val cardTrader: PriceChain?, val cardmarketGuide: PriceChain)

    /**
     * Creates the sources of a price update.
     * @param onStep suspend (PriceStep) -> Unit - called when a long preparatory job is about to start.
     * @return PriceSources - sources ready.
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
     * Observes the price alerts, per variant.
     * @return Flow<Map<String, PriceAlertEntity>> - alert by variant id.
     */
    fun observeAlerts(): Flow<Map<String, PriceAlertEntity>> = priceDao.observeAlerts().map { list -> list.associateBy { it.variantId } }

    /**
     * Creates or modifies the price alert of a variant; with neither option it removes it. A new alert starts
     * from the current prices, so the first check measures changes from what the user sees now; a modified one
     * keeps its state (see [PriceAlertRules.editedState]).
     * @param variantId String - variant.
     * @param notifyChanges Boolean - true for the "rises or falls" alert.
     * @param changePercent Int - minimum change for the "rises or falls" alert, brought back to [PriceAlertRules.CHANGE_PERCENT_RANGE].
     * @param targetEur Double? - target price in euros (already validated), null if not set.
     * @return Boolean - true if the alert is active, false if it was removed.
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
     * Removes the price alert of a variant.
     * @param variantId String - variant.
     * @return Unit
     */
    suspend fun deleteAlert(variantId: String) = priceDao.deleteAlert(variantId)

    /**
     * Daily check of the alerts: updates the prices of only the variants with an alert (Cardmarket price list and,
     * with the token, CardTrader), compares them with the saved state ([PriceAlertRules.evaluate]) and returns the alerts
     * to notify. It does not record a run: it is not the update of a collection.
     * @return AlertCheckResult - alerts to notify, variants checked and possible interruption.
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
            // Same condition as the collection update: the saved CardTrader price is shared and must not change depending on who downloaded it.
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
            // Only prices downloaded now: old or manually entered ones do not trigger alerts.
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
     * Prices usable by alerts: the latest of each source, excluding manually entered ones. CardTrader euros
     * count as CardTrader, the others as their source (Cardmarket price list); dollars as TCGplayer.
     * @param snapshots List<PriceSnapshotEntity> - saved prices of the variant.
     * @return List<PricePoint> - one price per source and currency.
     */
    private fun pointsOf(snapshots: List<PriceSnapshotEntity>): List<PricePoint> =
        snapshots.filter { it.source != CollectionFiles.MANUAL_SOURCE }.sortedByDescending { it.fetchedAt }.flatMap { s ->
            listOfNotNull(
                s.eur?.let { PricePoint(s.source, PricePoint.EUR, it) },
                s.usd?.let { PricePoint(TCGPLAYER_SOURCE, PricePoint.USD, it) },
            )
        }.distinctBy { it.key }

    /**
     * Saves a price manually entered by the user.
     * @param variantId String - variant to associate the price with.
     * @param eur Double - price in euros already validated.
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
        /** Snapshots kept per variant. */
        const val KEEP_SNAPSHOTS = 20

        /** Price updates kept in the log of each collection. */
        const val KEEP_RUNS = 10
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_ABORTED = "ABORTED"
        private const val REQUEST_INTERVAL_MS = 300L
        private const val STEP_LABEL = "…"
        private const val GUIDE_FILE = "cardmarket-price-guide.json"
        private const val MAX_NETWORK_ERRORS = 5
        private const val SQL_CHUNK = 500

        /** Name under which alerts refer to dollar prices (TCGplayer), as in the card detail. */
        const val TCGPLAYER_SOURCE = "TCGplayer"
    }
}
