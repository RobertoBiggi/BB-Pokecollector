package com.robertobiggi.cardcollection.core.price

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Source of the current time, replaceable in tests. */
fun interface Clock {
    /**
     * Returns the current instant.
     * @return Long - milliseconds since the epoch.
     */
    fun nowMs(): Long

    companion object {
        /** System clock. */
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Limits the call rate to a source: at least [minIntervalMs] passes between two calls.
 *
 * @param minIntervalMs minimum interval between two calls, in milliseconds.
 * @param clock clock used to measure time.
 * @param sleeper wait function (replaceable in tests).
 */
class RateLimiter(
    private val minIntervalMs: Long,
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastCallAt: Long? = null

    /**
     * Waits, if needed, until the next call is allowed, and records it.
     * @return Unit
     */
    suspend fun acquire() {
        mutex.withLock {
            val last = lastCallAt
            if (last != null) {
                val wait = last + minIntervalMs - clock.nowMs()
                if (wait > 0) sleeper(wait)
            }
            lastCallAt = clock.nowMs()
        }
    }
}

/**
 * Circuit breaker: after [threshold] consecutive blocks (429/403) the source is paused
 * for [cooldownMs], so the app stops insisting and does not worsen the block.
 *
 * @param threshold number of consecutive blocks that opens the circuit.
 * @param cooldownMs duration of the pause in milliseconds.
 * @param clock clock used to measure time.
 * @param initialOpenUntil end of a pause already in progress (read from the saved settings), 0 if none.
 */
class CircuitBreaker(
    private val threshold: Int = 3,
    private val cooldownMs: Long = 6 * 60 * 60 * 1000L,
    private val clock: Clock = Clock.SYSTEM,
    initialOpenUntil: Long = 0L,
) {
    /** Instant (ms) until which the source stays paused; 0 if the circuit is closed. */
    var openUntil: Long = initialOpenUntil
        private set

    private var consecutiveFailures = 0

    /**
     * Tells whether the source is paused.
     * @return Boolean - true if the circuit is open at this instant.
     */
    fun isOpen(): Boolean = clock.nowMs() < openUntil

    /**
     * Records a successful call and resets the block count.
     * @return Unit
     */
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /**
     * Records a block of the source; when the threshold is reached it opens the circuit.
     * @return Boolean - true if this call opened the circuit.
     */
    fun recordBlock(): Boolean {
        consecutiveFailures++
        if (consecutiveFailures >= threshold) {
            openUntil = clock.nowMs() + cooldownMs
            consecutiveFailures = 0
            return true
        }
        return false
    }
}
