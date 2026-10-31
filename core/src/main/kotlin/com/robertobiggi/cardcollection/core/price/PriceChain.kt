package com.robertobiggi.cardcollection.core.price

import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.NotFoundException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import kotlinx.coroutines.delay
import java.io.IOException

/** Outcome of a single attempt on a source. */
enum class StepOutcome { OK, NO_DATA, RATE_LIMITED, BLOCKED, CIRCUIT_OPEN, NETWORK_ERROR, ERROR }

/**
 * Log line of a price update.
 *
 * @property providerId source queried.
 * @property outcome outcome.
 */
data class ChainStep(val providerId: String, val outcome: StepOutcome)

/**
 * Result of the chain of sources for a variant.
 *
 * @property quote price found, or null.
 * @property steps attempts made, in order.
 * @property usedFallback true if the price arrived after a retry or from a non-primary source
 *           (merely completing with a second source does not count).
 */
data class ChainOutcome(val quote: PriceQuote?, val steps: List<ChainStep>, val usedFallback: Boolean)

/**
 * Source inserted in the chain with its rate limiter and circuit breaker.
 */
data class ProviderSlot(
    val provider: PriceProvider,
    val limiter: RateLimiter,
    val breaker: CircuitBreaker,
)

/**
 * Tries the sources in order for each variant. Stops when it has both the euro and the
 * dollar price: if the first source has only one, the following ones fill in the missing one.
 * On 429 and 5xx it retries with exponential wait; blocks (429/403) feed the circuit breaker.
 *
 * @param slots sources in priority order.
 * @param maxRetries number of new attempts per source after the first.
 * @param backoffBaseMs base wait before the first retry (doubles at each attempt).
 * @param sleeper wait function (replaceable in tests).
 */
class PriceChain(
    private val slots: List<ProviderSlot>,
    private val maxRetries: Int = 1,
    private val backoffBaseMs: Long = 2_000,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Tells whether all sources are paused: in that case it is better to stop the update.
     * @return Boolean - true if no source is usable.
     */
    fun allCircuitsOpen(): Boolean = slots.isNotEmpty() && slots.all { it.breaker.isOpen() }

    /**
     * Looks up the price of a variant by going through the chain.
     * @param request PriceRequest - card and variant.
     * @return ChainOutcome - price (if found) and log of the attempts.
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
