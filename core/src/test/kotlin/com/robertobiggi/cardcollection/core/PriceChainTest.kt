package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PriceQuote
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.price.CircuitBreaker
import com.robertobiggi.cardcollection.core.price.Clock
import com.robertobiggi.cardcollection.core.price.PriceChain
import com.robertobiggi.cardcollection.core.price.PriceProvider
import com.robertobiggi.cardcollection.core.price.PriceRequest
import com.robertobiggi.cardcollection.core.price.ProviderSlot
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.price.StepOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PriceChainTest {

    private var now = 1_000_000L
    private val clock = Clock { now }
    private val sleeps = mutableListOf<Long>()
    private val sleeper: suspend (Long) -> Unit = { sleeps += it; now += it }

    private val request = PriceRequest(Language.EN, "me03-032", VariantType.NORMAL, false)
    private fun quote(source: String) = PriceQuote(source, 1.0, null, null, null, Confidence.HIGH)

    /** Test source that runs the programmed responses in order. */
    private class Scripted(override val id: String, private val script: MutableList<() -> PriceQuote?>) : PriceProvider {
        override val displayName = id
        var calls = 0
        override suspend fun fetch(request: PriceRequest): PriceQuote? {
            calls++
            return script.removeAt(0).invoke()
        }
    }

    private fun slot(p: PriceProvider, breaker: CircuitBreaker = CircuitBreaker(3, 1000, clock)) =
        ProviderSlot(p, RateLimiter(100, clock, sleeper), breaker)

    @Test
    fun `prima fonte ok, nessun fallback`() = runTest {
        val a = Scripted("a", mutableListOf({ quote("a") }))
        val out = PriceChain(listOf(slot(a)), sleeper = sleeper).fetch(request)
        assertEquals("a", out.quote?.source)
        assertFalse(out.usedFallback)
    }

    @Test
    fun `429 poi retry riuscito`() = runTest {
        val a = Scripted("a", mutableListOf({ throw RateLimitedException(null) }, { quote("a") }))
        val out = PriceChain(listOf(slot(a)), backoffBaseMs = 2000, sleeper = sleeper).fetch(request)
        assertEquals("a", out.quote?.source)
        assertTrue(out.usedFallback)
        assertTrue(sleeps.contains(2000L))
    }

    @Test
    fun `fonte senza dati passa alla successiva`() = runTest {
        val a = Scripted("a", mutableListOf({ null }))
        val b = Scripted("b", mutableListOf({ quote("b") }))
        val out = PriceChain(listOf(slot(a), slot(b)), sleeper = sleeper).fetch(request)
        assertEquals("b", out.quote?.source)
        assertEquals(listOf(StepOutcome.NO_DATA, StepOutcome.OK), out.steps.map { it.outcome })
        assertTrue(out.usedFallback)
    }

    @Test
    fun `prezzo trovato non interroga la seconda fonte`() = runTest {
        val a = Scripted("a", mutableListOf({ quote("a") }))
        val b = Scripted("b", mutableListOf({ quote("b") }))
        PriceChain(listOf(slot(a), slot(b)), sleeper = sleeper).fetch(request)
        assertEquals(0, b.calls)
    }

    @Test
    fun `blocchi ripetuti aprono il circuito e la fonte viene saltata`() = runTest {
        val breaker = CircuitBreaker(threshold = 3, cooldownMs = 10_000, clock = clock)
        val a = Scripted("a", MutableList(10) { { throw BlockedException(403) } })
        val chain = PriceChain(listOf(slot(a, breaker)), sleeper = sleeper)
        repeat(3) { chain.fetch(request) }
        assertTrue(breaker.isOpen())
        assertTrue(chain.allCircuitsOpen())
        val out = chain.fetch(request)
        assertNull(out.quote)
        assertEquals(StepOutcome.CIRCUIT_OPEN, out.steps.single().outcome)
        assertEquals(3, a.calls)
        now += 10_001
        assertFalse(breaker.isOpen(), "dopo la pausa la fonte torna disponibile")
    }

    @Test
    fun `rate limiter rispetta l'intervallo minimo`() = runTest {
        val limiter = RateLimiter(500, clock, sleeper)
        limiter.acquire()
        limiter.acquire()
        assertEquals(listOf(500L), sleeps)
    }
}
