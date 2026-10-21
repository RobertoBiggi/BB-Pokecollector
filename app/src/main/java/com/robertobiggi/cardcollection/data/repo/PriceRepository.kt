package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.CircuitBreaker
import com.robertobiggi.cardcollection.core.price.PriceChain
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.ProviderSlot
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.price.StepOutcome
import com.robertobiggi.cardcollection.core.price.TcgdexPriceProvider
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/** Avanzamento dell'aggiornamento prezzi: carta e variante in lavorazione. */
data class PriceProgress(val cardName: String, val variant: VariantType, val done: Int, val total: Int)

/** Motivo per cui un aggiornamento prezzi si è interrotto prima della fine. */
enum class AbortReason { SOURCE_PAUSED, OFFLINE }

/** Esito dell'aggiornamento prezzi; [abort] è null se il run è arrivato in fondo. */
data class PriceRunResult(val ok: Int, val fallback: Int, val failed: Int, val abort: AbortReason?)

/**
 * Aggiorna i prezzi delle varianti desiderate o possedute e salva l'ultimo risultato in locale.
 * Se una variante non ha dati nuovi resta valido il prezzo salvato in precedenza.
 * Se l'utente ha inserito il token CardTrader, ogni variante ha anche il prezzo CardTrader, in una catena
 * separata: non sostituisce Cardmarket e i suoi errori non contano come errori dell'aggiornamento.
 */
class PriceRepository(
    private val db: AppDatabase,
    private val client: TcgdexClient,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
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

        val breaker = CircuitBreaker(initialOpenUntil = settings.tcgdexPausedUntilNow())
        val chain = PriceChain(listOf(ProviderSlot(TcgdexPriceProvider(client), RateLimiter(REQUEST_INTERVAL_MS), breaker)))
        val cardTrader = cardTraderChain()

        var ok = 0
        var fallback = 0
        var failed = 0
        var consecutiveNetworkErrors = 0
        var abortReason: AbortReason? = null
        for ((index, variant) in targets.withIndex()) {
            val card = cards[variant.cardId] ?: continue
            val type = VariantType.entries.firstOrNull { it.name == variant.type } ?: continue
            if (chain.allCircuitsOpen()) {
                abortReason = AbortReason.SOURCE_PAUSED
                break
            }
            progress(PriceProgress(card.name, type, index, targets.size))
            when (checkVariant(chain, cardTrader, card, variant, typesByCard[card.id].orEmpty())) {
                VariantCheck.OK -> { ok++; consecutiveNetworkErrors = 0 }
                VariantCheck.FALLBACK -> { fallback++; consecutiveNetworkErrors = 0 }
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
        settings.setTcgdexPausedUntil(breaker.openUntil)

        priceDao.updateRun(
            PriceRunEntity(
                id = runId,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                status = if (abortReason == null) STATUS_DONE else STATUS_ABORTED,
                okCount = ok,
                fallbackCount = fallback,
                failCount = failed,
                summary = abortReason?.name,
                collectionId = collectionId,
            ),
        )
        return PriceRunResult(ok, fallback, failed, abortReason)
    }

    /**
     * Aggiorna subito i prezzi di tutte le varianti di una sola carta, senza registrare un run.
     * @param cardId String - id locale della carta.
     * @return PriceRunResult - conteggi; [PriceRunResult.abort] vale SOURCE_PAUSED se TCGdex è in
     *         pausa e OFFLINE se tutte le richieste sono fallite per la rete.
     */
    suspend fun checkCard(cardId: String): PriceRunResult {
        val card = db.catalogDao().getCard(cardId) ?: return PriceRunResult(0, 0, 0, null)
        val variants = db.catalogDao().variantsOf(listOf(cardId))
        val types = variants.map { it.type }
        val breaker = CircuitBreaker(initialOpenUntil = settings.tcgdexPausedUntilNow())
        val chain = PriceChain(listOf(ProviderSlot(TcgdexPriceProvider(client), RateLimiter(REQUEST_INTERVAL_MS), breaker)))
        val cardTrader = cardTraderChain()
        var ok = 0
        var fallback = 0
        var failed = 0
        var networkErrors = 0
        var abortReason: AbortReason? = null
        for (variant in variants) {
            if (chain.allCircuitsOpen()) {
                abortReason = AbortReason.SOURCE_PAUSED
                break
            }
            when (checkVariant(chain, cardTrader, card, variant, types)) {
                VariantCheck.OK -> ok++
                VariantCheck.FALLBACK -> fallback++
                VariantCheck.FAILED -> failed++
                VariantCheck.NETWORK_ERROR -> { failed++; networkErrors++ }
                VariantCheck.SKIPPED -> Unit
            }
        }
        settings.setTcgdexPausedUntil(breaker.openUntil)
        if (abortReason == null && networkErrors > 0 && ok + fallback == 0) abortReason = AbortReason.OFFLINE
        return PriceRunResult(ok, fallback, failed, abortReason)
    }

    /** Esito del controllo di una singola variante. */
    private enum class VariantCheck { OK, FALLBACK, FAILED, NETWORK_ERROR, SKIPPED }

    /**
     * Chiede il prezzo di una variante alla catena di fonti e, se lo trova, lo salva.
     * @param chain PriceChain - catena di fonti con limitatore e circuit breaker.
     * @param cardTrader PriceChain? - catena CardTrader, null se manca il token.
     * @param card CardEntity - carta della variante.
     * @param variant CardVariantEntity - variante da controllare.
     * @param types List<String> - tipi di tutte le varianti della carta (per l'affidabilità del prezzo Cardmarket).
     * @return VariantCheck - esito; SKIPPED se lingua o tipo non sono riconosciuti.
     */
    private suspend fun checkVariant(chain: PriceChain, cardTrader: PriceChain?, card: CardEntity, variant: CardVariantEntity, types: List<String>): VariantCheck {
        val type = VariantType.entries.firstOrNull { it.name == variant.type } ?: return VariantCheck.SKIPPED
        val language = Language.fromCode(card.language) ?: return VariantCheck.SKIPPED
        val request = PriceRequest(
            language = language,
            tcgdexId = card.tcgdexId,
            variant = type,
            cardHasNormalAndHolo = VariantType.NORMAL.name in types && VariantType.HOLO.name in types,
            setId = card.setId,
            setName = card.setName,
            setCode = card.setCode,
            localId = card.localId,
        )
        val outcome = chain.fetch(request)
        val quote = outcome.quote
        val cardTraderSaved = cardTrader?.fetch(request)?.quote?.let { saveQuote(variant.id, it) } == true
        if (quote == null) {
            if (cardTraderSaved) return VariantCheck.OK
            return if (outcome.steps.any { it.outcome == StepOutcome.NETWORK_ERROR }) VariantCheck.NETWORK_ERROR else VariantCheck.FAILED
        }
        saveQuote(variant.id, quote)
        return if (outcome.usedFallback) VariantCheck.FALLBACK else VariantCheck.OK
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

    /**
     * Crea la catena CardTrader se l'utente ha inserito il token.
     * @return PriceChain? - catena con una sola fonte, oppure null senza token.
     */
    private suspend fun cardTraderChain(): PriceChain? {
        val token = settings.cardTraderToken.first()
        if (token.isEmpty()) return null
        val slot = ProviderSlot(
            CardTraderPriceProvider(CardTraderClient(http, token)),
            RateLimiter(REQUEST_INTERVAL_MS),
            CircuitBreaker(),
        )
        return PriceChain(listOf(slot))
    }

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
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_ABORTED = "ABORTED"
        private const val REQUEST_INTERVAL_MS = 300L
        private const val MAX_NETWORK_ERRORS = 5
        private const val SQL_CHUNK = 500
    }
}

