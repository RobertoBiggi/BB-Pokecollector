package com.robertobiggi.cardcollection.core.price

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sorgente del tempo corrente, sostituibile nei test. */
fun interface Clock {
    /**
     * Restituisce l'istante corrente.
     * @return Long - millisecondi dall'epoch.
     */
    fun nowMs(): Long

    companion object {
        /** Orologio di sistema. */
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Limita la frequenza delle chiamate a una fonte: tra due chiamate passano almeno [minIntervalMs].
 *
 * @param minIntervalMs intervallo minimo tra due chiamate, in millisecondi.
 * @param clock orologio usato per misurare il tempo.
 * @param sleeper funzione di attesa (sostituibile nei test).
 */
class RateLimiter(
    private val minIntervalMs: Long,
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastCallAt: Long? = null

    /**
     * Attende, se serve, finché la prossima chiamata è consentita, e la registra.
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
 * Circuit breaker: dopo [threshold] blocchi consecutivi (429/403) la fonte viene messa in pausa
 * per [cooldownMs], così l'app smette di insistere e non peggiora il blocco.
 *
 * @param threshold numero di blocchi consecutivi che apre il circuito.
 * @param cooldownMs durata della pausa in millisecondi.
 * @param clock orologio usato per misurare il tempo.
 * @param initialOpenUntil fine di una pausa già in corso (letta dalle impostazioni salvate), 0 se nessuna.
 */
class CircuitBreaker(
    private val threshold: Int = 3,
    private val cooldownMs: Long = 6 * 60 * 60 * 1000L,
    private val clock: Clock = Clock.SYSTEM,
    initialOpenUntil: Long = 0L,
) {
    /** Istante (ms) fino a cui la fonte resta in pausa; 0 se il circuito è chiuso. */
    var openUntil: Long = initialOpenUntil
        private set

    private var consecutiveFailures = 0

    /**
     * Indica se la fonte è in pausa.
     * @return Boolean - true se il circuito è aperto in questo istante.
     */
    fun isOpen(): Boolean = clock.nowMs() < openUntil

    /**
     * Registra una chiamata riuscita e azzera il conteggio dei blocchi.
     * @return Unit
     */
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /**
     * Registra un blocco della fonte; al raggiungimento della soglia apre il circuito.
     * @return Boolean - true se questa chiamata ha aperto il circuito.
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
