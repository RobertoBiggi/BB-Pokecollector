package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.NotFoundException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import kotlinx.coroutines.delay
import java.io.IOException

/** Esito di un singolo tentativo su una fonte. */
enum class StepOutcome { OK, NO_DATA, RATE_LIMITED, BLOCKED, CIRCUIT_OPEN, NETWORK_ERROR, ERROR }

/**
 * Riga del registro di un aggiornamento prezzi.
 *
 * @property providerId fonte interrogata.
 * @property outcome esito.
 */
data class ChainStep(val providerId: String, val outcome: StepOutcome)

/**
 * Risultato della catena di fonti per una variante.
 *
 * @property quote prezzo trovato, oppure null.
 * @property steps tentativi effettuati, in ordine.
 * @property usedFallback true se il prezzo è arrivato dopo un retry o da una fonte non primaria
 *           (il solo completamento con una seconda fonte non conta).
 */
data class ChainOutcome(val quote: PriceQuote?, val steps: List<ChainStep>, val usedFallback: Boolean)

/**
 * Fonte inserita nella catena con il suo rate limiter e circuit breaker.
 */
data class ProviderSlot(
    val provider: PriceProvider,
    val limiter: RateLimiter,
    val breaker: CircuitBreaker,
)

/**
 * Prova le fonti in ordine per ogni variante. Si ferma quando ha sia il prezzo in euro sia quello in
 * dollari: se la prima fonte ne ha solo uno, le successive completano quello mancante.
 * Su 429 e 5xx ritenta con attesa esponenziale; i blocchi (429/403) alimentano il circuit breaker.
 *
 * @param slots fonti in ordine di priorità.
 * @param maxRetries numero di nuovi tentativi per fonte dopo il primo.
 * @param backoffBaseMs attesa base prima del primo retry (raddoppia a ogni tentativo).
 * @param sleeper funzione di attesa (sostituibile nei test).
 */
class PriceChain(
    private val slots: List<ProviderSlot>,
    private val maxRetries: Int = 1,
    private val backoffBaseMs: Long = 2_000,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Indica se tutte le fonti sono in pausa: in quel caso conviene interrompere l'aggiornamento.
     * @return Boolean - true se nessuna fonte è utilizzabile.
     */
    fun allCircuitsOpen(): Boolean = slots.isNotEmpty() && slots.all { it.breaker.isOpen() }

    /**
     * Cerca il prezzo di una variante percorrendo la catena.
     * @param request PriceRequest - carta e variante.
     * @return ChainOutcome - prezzo (se trovato) e registro dei tentativi.
     */
    suspend fun fetch(request: PriceRequest): ChainOutcome {
        val steps = mutableListOf<ChainStep>()
        var retried = false
        var found: PriceQuote? = null
        var firstIndex = -1
        for ((index, slot) in slots.withIndex()) {
            val id = slot.provider.id
            if (slot.breaker.isOpen()) {
                steps += ChainStep(id, StepOutcome.CIRCUIT_OPEN)
                continue
            }
            var attempt = 0
            while (true) {
                slot.limiter.acquire()
                try {
                    val quote = slot.provider.fetch(request)
                    slot.breaker.recordSuccess()
                    if (quote != null && quote.hasAnyPrice()) {
                        steps += ChainStep(id, StepOutcome.OK)
                        val current = found
                        if (current == null) {
                            found = quote
                            firstIndex = index
                        } else {
                            found = current.complementedBy(quote)
                        }
                        if (found.isComplete()) return ChainOutcome(found, steps, usedFallback = firstIndex > 0 || retried)
                        break
                    }
                    steps += ChainStep(id, StepOutcome.NO_DATA)
                    break
                } catch (e: RateLimitedException) {
                    if (slot.breaker.recordBlock()) {
                        steps += ChainStep(id, StepOutcome.CIRCUIT_OPEN)
                        break
                    }
                    if (attempt < maxRetries) {
                        retried = true
                        sleeper(e.retryAfterMs ?: (backoffBaseMs shl attempt))
                        attempt++
                        continue
                    }
                    steps += ChainStep(id, StepOutcome.RATE_LIMITED)
                    break
                } catch (e: BlockedException) {
                    val opened = slot.breaker.recordBlock()
                    steps += ChainStep(id, if (opened) StepOutcome.CIRCUIT_OPEN else StepOutcome.BLOCKED)
                    break
                } catch (e: NotFoundException) {
                    steps += ChainStep(id, StepOutcome.NO_DATA)
                    break
                } catch (e: ApiException) {
                    if (e.httpCode >= 500 && attempt < maxRetries) {
                        retried = true
                        sleeper(backoffBaseMs shl attempt)
                        attempt++
                        continue
                    }
                    steps += ChainStep(id, StepOutcome.ERROR)
                    break
                } catch (e: InvalidResponseException) {
                    steps += ChainStep(id, StepOutcome.ERROR)
                    break
                } catch (e: IOException) {
                    steps += ChainStep(id, StepOutcome.NETWORK_ERROR)
                    break
                }
            }
        }
        return ChainOutcome(found, steps, usedFallback = found != null && (firstIndex > 0 || retried))
    }
}
