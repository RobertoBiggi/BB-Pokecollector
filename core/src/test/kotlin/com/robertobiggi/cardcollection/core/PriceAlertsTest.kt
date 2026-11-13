package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.price.AlertConfig
import com.robertobiggi.cardcollection.core.price.AlertEvent
import com.robertobiggi.cardcollection.core.price.AlertState
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import com.robertobiggi.cardcollection.core.price.PricePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PriceAlertsTest {

    private fun cm(value: Double) = PricePoint("Cardmarket", PricePoint.EUR, value)
    private fun ct(value: Double) = PricePoint("CardTrader", PricePoint.EUR, value)
    private fun tp(value: Double) = PricePoint("TCGplayer", PricePoint.USD, value)

    private val changes = AlertConfig(notifyChanges = true, targetEur = null)

    @Test
    fun `il primo prezzo visto diventa il riferimento senza avvisi`() {
        val result = PriceAlertRules.evaluate(changes, AlertState(), listOf(cm(10.0), ct(9.0)), 5)
        assertEquals(emptyList<AlertEvent>(), result.events)
        assertEquals(mapOf("Cardmarket|EUR" to 10.0, "CardTrader|EUR" to 9.0), result.state.baselines)
    }

    @Test
    fun `sale o scende oltre la soglia per ogni fonte`() {
        val state = AlertState(mapOf("Cardmarket|EUR" to 10.0, "CardTrader|EUR" to 9.0, "TCGplayer|USD" to 12.0))
        val result = PriceAlertRules.evaluate(changes, state, listOf(cm(10.6), ct(8.5), tp(12.2)), 5)
        val moved = result.events.filterIsInstance<AlertEvent.Change>()
        assertEquals(listOf("Cardmarket", "CardTrader"), moved.map { it.point.source })
        assertEquals(6.0, moved[0].percent, 1e-9)
        assertTrue(moved[1].percent < 0)
        // The reference moves only for the sources that sent an alert.
        assertEquals(mapOf("Cardmarket|EUR" to 10.6, "CardTrader|EUR" to 8.5, "TCGplayer|USD" to 12.0), result.state.baselines)
    }

    @Test
    fun `le piccole variazioni di ogni giorno si sommano`() {
        var state = AlertState(mapOf("Cardmarket|EUR" to 10.0))
        val events = mutableListOf<AlertEvent>()
        for (price in listOf(10.2, 10.4, 10.5)) {
            val result = PriceAlertRules.evaluate(changes, state, listOf(cm(price)), 5)
            events += result.events
            state = result.state
        }
        // +2%, +4%, then +5% from the reference: a single alert, on the third day.
        assertEquals(1, events.size)
        assertEquals(10.5, (events.single() as AlertEvent.Change).point.value)
    }

    @Test
    fun `con le variazioni spente il riferimento segue il prezzo`() {
        val off = AlertConfig(notifyChanges = false, targetEur = null)
        val result = PriceAlertRules.evaluate(off, AlertState(mapOf("Cardmarket|EUR" to 10.0)), listOf(cm(20.0)), 5)
        assertEquals(emptyList<AlertEvent>(), result.events)
        assertEquals(20.0, result.state.baselines["Cardmarket|EUR"])
    }

    @Test
    fun `prezzo obiettivo con margine del 5 percento, una volta sola e poi si riarma`() {
        val target = AlertConfig(notifyChanges = false, targetEur = 50.0)
        // 53 € is above 52.50 €: nothing.
        var result = PriceAlertRules.evaluate(target, AlertState(), listOf(cm(53.0)), 5)
        assertEquals(emptyList<AlertEvent>(), result.events)
        assertFalse(result.state.targetReached)
        // 52.50 €: inside the zone, alerts.
        result = PriceAlertRules.evaluate(target, result.state, listOf(cm(52.5)), 5)
        assertEquals(listOf(52.5), result.events.filterIsInstance<AlertEvent.TargetReached>().map { it.point.value })
        assertTrue(result.state.targetReached)
        // Still below: does not repeat the alert.
        result = PriceAlertRules.evaluate(target, result.state, listOf(cm(48.0)), 5)
        assertEquals(emptyList<AlertEvent>(), result.events)
        // Rises above the zone and then falls again: alerts again.
        result = PriceAlertRules.evaluate(target, result.state, listOf(cm(60.0)), 5)
        assertFalse(result.state.targetReached)
        result = PriceAlertRules.evaluate(target, result.state, listOf(cm(49.0)), 5)
        assertEquals(1, result.events.size)
    }

    @Test
    fun `il prezzo obiettivo guarda la fonte in euro piu economica e ignora i dollari`() {
        val target = AlertConfig(notifyChanges = false, targetEur = 50.0)
        val result = PriceAlertRules.evaluate(target, AlertState(), listOf(cm(70.0), ct(51.0), tp(10.0)), 5)
        val reached = result.events.filterIsInstance<AlertEvent.TargetReached>().single()
        assertEquals("CardTrader", reached.point.source)
    }

    @Test
    fun `senza prezzi in questo controllo lo stato non cambia`() {
        val target = AlertConfig(notifyChanges = true, targetEur = 50.0)
        val state = AlertState(mapOf("Cardmarket|EUR" to 40.0), targetReached = true)
        val result = PriceAlertRules.evaluate(target, state, listOf(cm(0.0), cm(Double.NaN)), 5)
        assertEquals(emptyList<AlertEvent>(), result.events)
        assertEquals(state, result.state)
    }

    @Test
    fun `soglia fuori intervallo ricondotta ai limiti`() {
        val state = AlertState(mapOf("Cardmarket|EUR" to 10.0))
        // 0% becomes 1%: 10.05 € (+0.5%) does not alert, 10.10 € (+1%) does.
        assertEquals(0, PriceAlertRules.evaluate(changes, state, listOf(cm(10.05)), 0).events.size)
        assertEquals(1, PriceAlertRules.evaluate(changes, state, listOf(cm(10.10)), 0).events.size)
    }

    @Test
    fun `controllo giornaliero alle 4 e 30, anche al cambio dell'ora legale`() {
        val rome = ZoneId.of("Europe/Rome")
        val night = ZonedDateTime.of(2026, 10, 29, 1, 0, 0, 0, rome)
        assertEquals(ZonedDateTime.of(2026, 10, 29, 4, 30, 0, 0, rome), PriceAlertRules.nextRun(night))
        val afternoon = ZonedDateTime.of(2026, 10, 29, 15, 0, 0, 0, rome)
        assertEquals(ZonedDateTime.of(2026, 10, 30, 4, 30, 0, 0, rome), PriceAlertRules.nextRun(afternoon))
        val exactly = ZonedDateTime.of(2026, 10, 29, 4, 30, 0, 0, rome)
        assertEquals(ZonedDateTime.of(2026, 10, 30, 4, 30, 0, 0, rome), PriceAlertRules.nextRun(exactly))
        // Night of 25 October 2026: back to standard time, the check stays at 4:30 local.
        val beforeDst = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, rome)
        assertEquals(4, PriceAlertRules.nextRun(beforeDst).hour)
        assertEquals(30, PriceAlertRules.nextRun(beforeDst).minute)
        assertEquals(25, PriceAlertRules.nextRun(beforeDst).dayOfMonth)
    }

    @Test
    fun `i riferimenti si salvano e si rileggono, scartando i testi malformati`() {
        val baselines = mapOf("Cardmarket|EUR" to 12.5, "TCGplayer|USD" to 14.0)
        assertEquals(baselines, PriceAlertRules.decodeBaselines(PriceAlertRules.encodeBaselines(baselines)))
        assertEquals(
            mapOf("Cardmarket|EUR" to 1.0, "CardTrader|EUR" to 2.0),
            PriceAlertRules.decodeBaselines("Cardmarket|EUR=1.0;rotto;X|EUR=-3;Y|EUR=abc;CardTrader|EUR=2;Inj=ection|EUR=4;Z|eur=5"),
        )
        assertEquals(emptyMap<String, Double>(), PriceAlertRules.decodeBaselines(null))
        assertEquals(emptyMap<String, Double>(), PriceAlertRules.decodeBaselines("x".repeat(5000)))
    }

    @Test
    fun `un avviso nuovo parte dai prezzi attuali`() {
        val state = PriceAlertRules.editedState(null, null, 50.0, mapOf("Cardmarket|EUR" to 60.0))
        assertEquals(AlertState(mapOf("Cardmarket|EUR" to 60.0), targetReached = false), state)
    }

    @Test
    fun `cambiare solo la soglia non rimanda l'avviso del prezzo obiettivo e tiene i riferimenti`() {
        val saved = AlertState(mapOf("Cardmarket|EUR" to 10.0), targetReached = true)
        val state = PriceAlertRules.editedState(saved, 50.0, 50.0, mapOf("Cardmarket|EUR" to 10.4, "CardTrader|EUR" to 9.0))
        // The Cardmarket reference stays 10 (changes add up); CardTrader, new, starts from the current price.
        assertEquals(mapOf("Cardmarket|EUR" to 10.0, "CardTrader|EUR" to 9.0), state.baselines)
        assertTrue(state.targetReached)
    }

    @Test
    fun `un prezzo obiettivo nuovo riarma l'avviso`() {
        val saved = AlertState(mapOf("Cardmarket|EUR" to 10.0), targetReached = true)
        assertFalse(PriceAlertRules.editedState(saved, 50.0, 40.0, emptyMap()).targetReached)
        assertFalse(PriceAlertRules.editedState(saved, 50.0, null, emptyMap()).targetReached)
    }
}
