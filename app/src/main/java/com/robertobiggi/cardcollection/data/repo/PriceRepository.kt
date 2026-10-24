package com.robertobiggi.cardcollection.data.repo

import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalog
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketCatalogIdSource
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketPriceGuide
import com.robertobiggi.cardcollection.core.cardmarket.orElse
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.CardmarketGuidePriceProvider
import com.robertobiggi.cardcollection.core.price.CircuitBreaker
import com.robertobiggi.cardcollection.core.price.PriceChain
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.ProviderSlot
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.price.StepOutcome
import com.robertobiggi.cardcollection.core.price.TcgdexPriceProvider
import com.robertobiggi.cardcollection.core.tcgdex.CardRepair
import com.robertobiggi.cardcollection.core.tcgdex.SetOrdinal
import com.robertobiggi.cardcollection.core.tcgdex.SetOrdinalFinder
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.local.CardVariantEntity
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File

/** Lavoro preparatorio dell'aggiornamento prezzi che richiede tempo e che viene mostrato all'utente. */
enum class PriceStep { DOWNLOAD_GUIDE, READ_GUIDE, DOWNLOAD_CATALOG, READ_CATALOG, LOAD_CARDTRADER }

/**
 * Avanzamento dell'aggiornamento prezzi: carta e variante in lavorazione, oppure (con [step]) un lavoro
 * preparatorio (scaricare o leggere un file Cardmarket, caricare i set di CardTrader); in quel caso [variant] è null.
 */
data class PriceProgress(val cardName: String, val variant: VariantType?, val done: Int, val total: Int, val step: PriceStep? = null)

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
    private val cacheDir: File,
    private val images: ImageStore,
) {
    private val priceDao = db.priceDao()

    /** Carte per cui si è già cercato il Pokédex in questa sessione, per non rifare la richiesta a ogni aggiornamento. */
    private val dexLookedUp = HashSet<String>()

    /** Carte per cui si è già cercato il blueprint CardTrader in questa sessione. */
    private val blueprintLookedUp = HashSet<String>()

    /** Carte scheletro per cui si è già cercato l'illustratore in questa sessione. */
    private val illustratorLookedUp = HashSet<String>()

    /** Posizioni già calcolate per le carte senza Pokédex (il valore può essere null: non trovata). */
    private val ordinals = HashMap<String, Optional>()

    private class Optional(val value: SetOrdinal?)

    private val ordinalFinder = SetOrdinalFinder(client)

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
        var position = 0
        val sources = priceSources { step -> progress(PriceProgress(STEP_LABEL, null, position, targets.size, step)) }

        var ok = 0
        var fallback = 0
        var failed = 0
        var consecutiveNetworkErrors = 0
        var abortReason: AbortReason? = null
        var completed = false
        try {
            for ((index, variant) in targets.withIndex()) {
                val card = cards[variant.cardId] ?: continue
                val type = VariantType.fromName(variant.type) ?: continue
                if (chain.allCircuitsOpen()) {
                    abortReason = AbortReason.SOURCE_PAUSED
                    break
                }
                position = index
                progress(PriceProgress(card.name, type, index, targets.size))
                when (checkVariant(chain, sources, card, variant, typesByCard[card.id].orEmpty(), conditionOf(entries[variant.id]?.condition))) {
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
            completed = true
        } finally {
            // Anche se il lavoro viene interrotto, il run non resta "in corso" per sempre.
            withContext(NonCancellable) {
                settings.setTcgdexPausedUntil(breaker.openUntil)
                priceDao.updateRun(
                    PriceRunEntity(
                        id = runId,
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        status = if (completed && abortReason == null) STATUS_DONE else STATUS_ABORTED,
                        okCount = ok,
                        fallbackCount = fallback,
                        failCount = failed,
                        summary = abortReason?.name,
                        collectionId = collectionId,
                    ),
                )
                priceDao.pruneRuns(collectionId, KEEP_RUNS)
            }
        }
        return PriceRunResult(ok, fallback, failed, abortReason)
    }

    /**
     * Aggiorna subito i prezzi di tutte le varianti di una sola carta, senza registrare un run.
     * @param cardId String - id locale della carta.
     * @return PriceRunResult - conteggi; [PriceRunResult.abort] vale SOURCE_PAUSED se TCGdex è in
     *         pausa e OFFLINE se tutte le richieste sono fallite per la rete.
     */
    suspend fun checkCard(cardId: String, collectionId: Long?): PriceRunResult {
        val entries = collectionId?.let { db.collectionDao().entriesOf(it) }.orEmpty().associateBy { it.variantId }
        val card = db.catalogDao().getCard(cardId) ?: return PriceRunResult(0, 0, 0, null)
        val variants = db.catalogDao().variantsOf(listOf(cardId))
        val types = variants.map { it.type }
        val breaker = CircuitBreaker(initialOpenUntil = settings.tcgdexPausedUntilNow())
        val chain = PriceChain(listOf(ProviderSlot(TcgdexPriceProvider(client), RateLimiter(REQUEST_INTERVAL_MS), breaker)))
        val sources = priceSources {}
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
            when (checkVariant(chain, sources, card, variant, types, conditionOf(entries[variant.id]?.condition))) {
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

    private fun conditionOf(name: String?): Condition = Condition.entries.firstOrNull { it.name == name } ?: Condition.NM

    /** Esito del controllo di una singola variante. */
    private enum class VariantCheck { OK, FALLBACK, FAILED, NETWORK_ERROR, SKIPPED }

    /**
     * Chiede il prezzo di una variante alla catena di fonti e, se lo trova, lo salva.
     * @param chain PriceChain - catena di fonti con limitatore e circuit breaker.
     * @param sources PriceSources - CardTrader (se c'è il token) e listino Cardmarket.
     * @param card CardEntity - carta della variante.
     * @param variant CardVariantEntity - variante da controllare.
     * @param condition Condition - condizione della copia nella collezione: CardTrader mostra il prezzo più basso di quella condizione o migliore.
     * @param types List<String> - tipi di tutte le varianti della carta (per l'affidabilità del prezzo Cardmarket).
     * @return VariantCheck - esito; SKIPPED se lingua o tipo non sono riconosciuti.
     */
    private suspend fun checkVariant(chain: PriceChain, sources: PriceSources, storedCard: CardEntity, variant: CardVariantEntity, types: List<String>, condition: Condition): VariantCheck {
        val card = withIllustrator(withDexId(storedCard))
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
            ordinal = if (card.dexId == null) ({ ordinalOf(card, language) }) else null,
            cardTraderCondition = CtCondition.fromCondition(condition),
            cardmarketId = card.cardmarketId,
            cardmarketHint = card.cardmarketProductHint,
        )
        // Il listino Cardmarket (aggiornato ogni notte) è la fonte principale dei prezzi in euro; TCGdex serve
        // per i dollari di TCGplayer e come ripiego per le carte che il listino non abbina.
        saveCardTraderBlueprint(sources.cardTraderProvider, card, request)
        val guideQuote = sources.cardmarketGuide.fetch(request).quote
        val outcome = chain.fetch(request)
        val tcgdexQuote = outcome.quote
        val cardTraderOutcome = sources.cardTrader?.fetch(request)
        val cardTraderSaved = cardTraderOutcome?.quote?.let { saveQuote(variant.id, it) } == true
        // Normal e holo condividono il blueprint: se CardTrader ha una sola stampa, l'altra non ha prezzo e quello salvato prima era dell'altra.
        if (cardTraderOutcome != null && cardTraderOutcome.quote == null && request.cardHasNormalAndHolo &&
            (type == VariantType.NORMAL || type == VariantType.HOLO) && cardTraderOutcome.steps.all { it.outcome == StepOutcome.NO_DATA }
        ) {
            priceDao.deleteSnapshotsOf(variant.id, CardTraderPriceProvider.DISPLAY_NAME)
        }
        if (guideQuote != null) {
            saveQuote(variant.id, guideQuote.copy(usd = tcgdexQuote?.usd, usdLow = tcgdexQuote?.usdLow))
            return VariantCheck.OK
        }
        if (tcgdexQuote == null) {
            if (cardTraderSaved) return VariantCheck.OK
            return if (outcome.steps.any { it.outcome == StepOutcome.NETWORK_ERROR }) VariantCheck.NETWORK_ERROR else VariantCheck.FAILED
        }
        saveQuote(variant.id, tcgdexQuote)
        return if (outcome.usedFallback) VariantCheck.FALLBACK else VariantCheck.OK
    }

    /**
     * Salva l'id del blueprint CardTrader della carta (una verifica per carta a ogni avvio), se si abbina con certezza; se l'abbinamento era sbagliato lo corregge.
     * Le Poké Ball e le Master Ball hanno un blueprint a parte, quindi non servono per la carta.
     * @param cardTrader CardTraderPriceProvider? - fonte CardTrader, null senza token.
     * @param card CardEntity - carta.
     * @param request PriceRequest - richiesta della variante.
     * @return Unit
     */
    private suspend fun saveCardTraderBlueprint(cardTrader: CardTraderPriceProvider?, card: CardEntity, request: PriceRequest) {
        if (cardTrader == null || request.variant.isBall || !blueprintLookedUp.add(card.id)) return
        try {
            val id = cardTrader.blueprintId(request) ?: return
            if (id == card.cardTraderBlueprintId) return
            db.catalogDao().setCardTraderBlueprint(card.id, id)
            // Un abbinamento corretto in seguito: l'immagine presa da CardTrader per quello sbagliato va ripresa (le immagini di TCGdex o scelte a mano restano).
            if (card.cardTraderBlueprintId != null && card.imageUrl == null && !images.isManual(card.imageHighPath)) {
                db.catalogDao().setImagePaths(card.id, null, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rete assente o risposta non valida: il link resta la ricerca per nome e il prossimo aggiornamento riprova.
            blueprintLookedUp.remove(card.id)
        }
    }

    /**
     * Trova il blueprint CardTrader delle carte che non ce l'hanno ancora (collezioni già esistenti), per il link diretto.
     * Serve il token CardTrader; senza, o con la rete assente, non fa nulla. Una carta che non si abbina con certezza resta com'è.
     * @return Int - numero di carte a cui è stato aggiunto il blueprint.
     */
    suspend fun fillCardTraderBlueprints(): Int {
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return 0
        val missing = db.catalogDao().cardsWithoutCardTraderBlueprint()
        if (missing.isEmpty()) return 0
        val provider = CardTraderPriceProvider(CardTraderClient(http, token))
        var saved = 0
        var consecutiveErrors = 0
        for (stored in missing) {
            if (consecutiveErrors >= MAX_NETWORK_ERRORS) break
            try {
                val card = withDexId(stored)
                val language = Language.fromCode(card.language) ?: continue
                val id = provider.blueprintId(requestOf(card, language))
                consecutiveErrors = 0
                if (id != null) {
                    db.catalogDao().setCardTraderBlueprint(card.id, id)
                    saved++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Rete assente o risposta non valida: le altre carte possono andare a buon fine, e la prossima apertura riprova.
                consecutiveErrors++
            }
        }
        return saved
    }

    /**
     * Scarica da CardTrader l'immagine delle carte inglesi e giapponesi che TCGdex non ha (es. i set giapponesi senza
     * carte) e completa rarità e numero stampato delle carte del catalogo Cardmarket. Serve il token CardTrader; senza,
     * o con la rete assente, non fa nulla. Una carta che non si abbina con certezza resta com'è.
     * @param progress suspend (SyncProgress) -> Unit - avanzamento, una volta per carta.
     * @return Int - numero di carte a cui è stata aggiunta l'immagine.
     */
    suspend fun fillImagesFromCardTrader(progress: suspend (SyncProgress) -> Unit = {}): Int {
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return 0
        val missing = db.catalogDao().cardsWithoutImage()
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
        fillDetailsFromCardTrader(provider, limiter)
        return saved
    }

    /**
     * Scarica da CardTrader l'immagine di una singola carta, anche se ne ha già una (quella scelta a mano viene sostituita).
     * Serve il token CardTrader.
     * @param cardId String - id locale della carta.
     * @return ImageResult - DOWNLOADED, NOT_AVAILABLE se manca il token o la carta non si abbina con certezza, NETWORK_ERROR.
     */
    suspend fun downloadImageFromCardTrader(cardId: String): ImageResult {
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return ImageResult.NOT_AVAILABLE
        val stored = db.catalogDao().getCard(cardId) ?: return ImageResult.NOT_AVAILABLE
        if (Language.fromCode(stored.language) == null) return ImageResult.NOT_AVAILABLE
        return try {
            if (saveCardTraderImage(CardTraderPriceProvider(CardTraderClient(http, token)), stored)) ImageResult.DOWNLOADED else ImageResult.NOT_AVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageResult.NETWORK_ERROR
        }
    }

    private fun requestOf(card: CardEntity, language: Language) = PriceRequest(
        language = language,
        tcgdexId = card.tcgdexId,
        variant = VariantType.NORMAL,
        cardHasNormalAndHolo = false,
        setId = card.setId,
        setName = card.setName,
        setCode = card.setCode,
        localId = card.localId,
        name = card.name,
        dexId = card.dexId,
        ordinal = if (card.dexId == null) ({ ordinalOf(card, language) }) else null,
        cardmarketId = card.cardmarketId,
        cardmarketHint = card.cardmarketProductHint,
    )

    /**
     * Completa rarità e numero stampato delle carte del catalogo Cardmarket con quelli di CardTrader. Non tocca i dati già presenti.
     * @return Int - numero di carte aggiornate.
     */
    private suspend fun fillDetailsFromCardTrader(provider: CardTraderPriceProvider, limiter: RateLimiter): Int {
        var saved = 0
        for (stored in db.catalogDao().cardsMissingDetails()) {
            try {
                val card = withDexId(stored)
                val language = Language.fromCode(card.language) ?: continue
                limiter.acquire()
                val found = provider.details(requestOf(card, language)) ?: continue
                val rarity = card.rarity ?: found.rarity
                val number = card.printedNumber.ifEmpty { found.printedNumber.orEmpty() }
                if (rarity == card.rarity && number == card.printedNumber) continue
                db.catalogDao().upsertCard(card.copy(rarity = rarity, printedNumber = number))
                saved++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Come per le immagini: le altre carte possono andare a buon fine, e il prossimo aggiornamento riprova.
            }
        }
        return saved
    }

    /** Cerca la carta su CardTrader e ne salva l'immagine. @return Boolean - true se è stata salvata. */
    private suspend fun saveCardTraderImage(provider: CardTraderPriceProvider, stored: CardEntity): Boolean {
        val card = withDexId(stored)
        val language = Language.fromCode(card.language) ?: return false
        val url = provider.imageUrl(requestOf(card, language)) ?: return false
        val (low, high) = images.saveFromCardTrader(card.id, url) ?: return false
        val current = db.catalogDao().getCard(card.id)
        if (images.isManual(current?.imageHighPath)) images.deleteManual(current?.imageHighPath)
        if (images.isManual(current?.imageLowPath) && current?.imageLowPath != current?.imageHighPath) images.deleteManual(current?.imageLowPath)
        db.catalogDao().setImagePaths(card.id, low, high)
        return true
    }

    /**
     * Completa il numero del Pokédex di una carta giapponese salvata prima che esistesse la colonna:
     * senza, CardTrader non riesce ad abbinare le carte delle espansioni del 1996-1999.
     * @param card CardEntity - carta salvata.
     * @return CardEntity - la carta con il Pokédex, oppure quella di partenza se non si trova (Allenatori, Energie, rete assente).
     */
    private suspend fun withDexId(card: CardEntity): CardEntity {
        if (card.dexId != null || card.language != Language.JA.code || !dexLookedUp.add(card.id)) return card
        val dexId = try {
            client.getCard(Language.JA, card.tcgdexId).dexId?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return card
        val updated = card.copy(dexId = dexId)
        db.catalogDao().upsertCard(updated)
        return updated
    }

    /**
     * Posizione di una carta senza Pokédex tra le carte Pokémon del suo set, per abbinarla a CardTrader
     * quando l'espansione non ha numeri di collezione. Calcolata una sola volta per carta e sessione.
     * @param card CardEntity - carta senza Pokédex (Allenatore o Energia).
     * @param language Language - lingua del catalogo.
     * @return SetOrdinal? - posizione, oppure null se non si trova o TCGdex non risponde.
     */
    private suspend fun ordinalOf(card: CardEntity, language: Language): SetOrdinal? {
        ordinals[card.id]?.let { return it.value }
        val ordinal = try {
            ordinalFinder.of(language, card.setId, card.localId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        ordinals[card.id] = Optional(ordinal)
        return ordinal
    }

    /**
     * Completa l'illustratore di una carta "scheletro" (senza dettaglio TCGdex) dalla stessa carta nel catalogo inglese.
     * @param card CardEntity - carta salvata.
     * @return CardEntity - la carta con l'illustratore, oppure quella di partenza se non è uno scheletro o la ricerca non è univoca.
     */
    private suspend fun withIllustrator(card: CardEntity): CardEntity {
        if (!CardRepair.isStub(card.rarity, card.illustrator, card.releaseDate, card.imageUrl != null) || !illustratorLookedUp.add(card.id)) return card
        val illustrator = try {
            CardRepair.illustratorFromEnglish(client, card.name, card.dexId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return card
        val updated = card.copy(illustrator = illustrator)
        db.catalogDao().upsertCard(updated)
        return updated
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

    /** Fonti oltre a TCGdex: CardTrader (solo con il token) e il listino Cardmarket. */
    private class PriceSources(val cardTrader: PriceChain?, val cardmarketGuide: PriceChain, val cardTraderProvider: CardTraderPriceProvider? = null)

    /**
     * Crea le fonti di un aggiornamento prezzi. Il listino Cardmarket c'è sempre: l'id prodotto della carta
     * viene da CardTrader se c'è il token e la carta si abbina, altrimenti dal catalogo Cardmarket.
     * @param onDownload suspend (PriceStep) -> Unit - chiamata quando sta per iniziare un lavoro preparatorio lungo.
     * @return PriceSources - fonti pronte.
     */
    private suspend fun priceSources(onStep: suspend (PriceStep) -> Unit): PriceSources {
        val token = settings.cardTraderToken.first()
        val cardTrader = token.takeIf { it.isNotEmpty() }?.let { CardTraderPriceProvider(CardTraderClient(http, it), onLoadingExpansions = { onStep(PriceStep.LOAD_CARDTRADER) }) }
        val catalogIds = CardmarketCatalogIdSource(CardmarketCatalog(http, File(cacheDir, CATALOG_FILE), onDownload = { onStep(PriceStep.DOWNLOAD_CATALOG) }, onRead = { onStep(PriceStep.READ_CATALOG) }))
        val guide = CardmarketGuidePriceProvider(
            cardTrader?.orElse(catalogIds) ?: catalogIds,
            CardmarketPriceGuide(http, File(cacheDir, GUIDE_FILE), onDownload = { onStep(PriceStep.DOWNLOAD_GUIDE) }, onRead = { onStep(PriceStep.READ_GUIDE) }),
        )
        return PriceSources(
            cardTrader = cardTrader?.let { PriceChain(listOf(ProviderSlot(it, RateLimiter(REQUEST_INTERVAL_MS), CircuitBreaker()))) },
            cardmarketGuide = PriceChain(listOf(ProviderSlot(guide, RateLimiter(0), CircuitBreaker()))),
            cardTraderProvider = cardTrader,
        )
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

        /** Aggiornamenti prezzi conservati nel registro di ogni collezione. */
        const val KEEP_RUNS = 10
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_ABORTED = "ABORTED"
        private const val REQUEST_INTERVAL_MS = 300L
        private const val STEP_LABEL = "…"
        private const val GUIDE_FILE = "cardmarket-price-guide.json"
        private const val CATALOG_FILE = "cardmarket-products.json"
        private const val MAX_NETWORK_ERRORS = 5
        private const val SQL_CHUNK = 500
    }
}

