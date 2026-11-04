package com.robertobiggi.cardcollection.core.price

import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Price of a variant read from a source during an update.
 *
 * @property source source (e.g. "Cardmarket", "CardTrader").
 * @property currency currency: [EUR] (the only one with a price source today).
 * @property value price, greater than zero.
 */
data class PricePoint(val source: String, val currency: String, val value: Double) {
    /** Key under which the reference price of this source and currency is remembered. */
    val key: String get() = "$source|$currency"

    companion object {
        const val EUR = "EUR"
    }
}

/**
 * What the user wants to know about a variant.
 *
 * @property notifyChanges true for an alert when the price rises or falls beyond the threshold.
 * @property targetEur price in euros below (or near) which to alert; null if not set.
 */
data class AlertConfig(val notifyChanges: Boolean, val targetEur: Double?)

/**
 * Saved state of an alert between one check and the next.
 *
 * @property baselines reference price per source and currency ([PricePoint.key]): the last one an alert was sent for,
 *   or the first seen. Changes are measured from here, so small daily ones add up.
 * @property targetReached true if the target price alert has already been sent and the price has not risen again yet.
 */
data class AlertState(val baselines: Map<String, Double> = emptyMap(), val targetReached: Boolean = false)

/** Alert to notify. */
sealed interface AlertEvent {
    /**
     * The price of a source has risen or fallen beyond the threshold.
     * @property point new price.
     * @property previous previous reference price.
     */
    data class Change(val point: PricePoint, val previous: Double) : AlertEvent {
        /** Percentage change (positive if it rises). */
        val percent: Double get() = (point.value - previous) / previous * 100.0
    }

    /**
     * The lowest price in euros has come near or below the target price.
     * @property point lowest price in euros.
     * @property target target price.
     */
    data class TargetReached(val point: PricePoint, val target: Double) : AlertEvent
}

/**
 * Outcome of checking an alert.
 * @property events alerts to notify.
 * @property state state to save for the next check.
 */
data class AlertEvaluation(val events: List<AlertEvent>, val state: AlertState)

/** Price alert rules: what to notify and when to run the daily check. */
object PriceAlertRules {

    /** Default minimum change for the "rises or falls" alert, in percent. */
    const val DEFAULT_CHANGE_PERCENT = 5

    /** Changes allowed in the settings, in percent. */
    val CHANGE_PERCENT_RANGE = 1..50

    /** Margin above the target price within which the price is "similar" (5%: with 50 € it alerts from 52.50 € down). */
    const val TARGET_MARGIN = 0.05

    /** Maximum accepted target price, in euros. */
    const val MAX_TARGET_EUR = 1_000_000.0

    /**
     * Time of the daily check: the Cardmarket price list is regenerated around 3 a.m.
     * (real `createdAt` "…T02:48:17+0200"), the check starts an hour and a half later.
     */
    val DAILY_RUN_TIME: LocalTime = LocalTime.of(4, 30)

    /**
     * Compares the prices just read with the saved state and decides what to notify.
     * - For each source and currency: the first time the price becomes the reference; then, if change alerts are
     *   on and the price has moved by at least [changePercent] from the reference, an alert is sent and the reference
     *   becomes the new price. With change alerts off the reference follows the price, so
     *   turning them on later does not trigger old changes.
     * - Target price: compared with the lowest price in euros; the alert is sent only once when the price
     *   enters the zone (target + [TARGET_MARGIN]) and is re-armed when it leaves it.
     * Sources that gave no price in this check keep the reference they had.
     * @param config AlertConfig - what the user wants to know.
     * @param state AlertState - saved state.
     * @param points List<PricePoint> - prices read in this check (non-positive ones are ignored).
     * @param changePercent Int - minimum change in percent for the "rises or falls" alert.
     * @return AlertEvaluation - alerts to notify and new state.
     */
    fun evaluate(config: AlertConfig, state: AlertState, points: List<PricePoint>, changePercent: Int): AlertEvaluation {
        val valid = points.filter { it.value > 0.0 && it.value.isFinite() }.distinctBy { it.key }
        val threshold = changePercent.coerceIn(CHANGE_PERCENT_RANGE).toDouble()
        val events = mutableListOf<AlertEvent>()
        val baselines = state.baselines.toMutableMap()
        for (point in valid) {
            val previous = baselines[point.key]
            if (previous == null || previous <= 0.0 || !config.notifyChanges) {
                baselines[point.key] = point.value
                continue
            }
            if (abs(point.value - previous) / previous * 100.0 >= threshold - EPSILON) {
                events += AlertEvent.Change(point, previous)
                baselines[point.key] = point.value
            }
        }
        var reached = state.targetReached
        val target = config.targetEur?.takeIf { it > 0.0 }
        val cheapest = valid.filter { it.currency == PricePoint.EUR }.minByOrNull { it.value }
        if (target == null) {
            reached = false
        } else if (cheapest != null) {
            val inZone = cheapest.value <= target * (1 + TARGET_MARGIN) + EPSILON
            if (inZone && !reached) events += AlertEvent.TargetReached(cheapest, target)
            reached = inZone
        }
        return AlertEvaluation(events, AlertState(baselines, reached))
    }

    /**
     * State of an alert just created or modified by the user. A new alert starts from the current prices. A modified one
     * keeps the reference prices it had (changes keep adding up) and, if the target price has not
     * changed, also the fact that the target alert has already been sent: changing only the threshold must not send it again.
     * @param existing AlertState? - saved state, null if the alert is new.
     * @param existingTarget Double? - saved target price.
     * @param newTarget Double? - target price chosen now.
     * @param current Map<String, Double> - current prices by [PricePoint.key].
     * @return AlertState - state to save.
     */
    fun editedState(existing: AlertState?, existingTarget: Double?, newTarget: Double?, current: Map<String, Double>): AlertState {
        if (existing == null) return AlertState(current, targetReached = false)
        return AlertState(current + existing.baselines, targetReached = existing.targetReached && existingTarget == newTarget)
    }

    /**
     * Next daily check: today at [DAILY_RUN_TIME] if it has not passed yet, otherwise tomorrow.
     * Computed at every run, so it stays at the right time even across daylight saving changes.
     * @param now ZonedDateTime - now, in the phone's time zone.
     * @param time LocalTime - time of the check.
     * @return ZonedDateTime - instant of the next check.
     */
    fun nextRun(now: ZonedDateTime, time: LocalTime = DAILY_RUN_TIME): ZonedDateTime {
        val today = now.with(time).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1).with(time)
    }

    /**
     * Converts the reference prices into the text saved in the database ("Cardmarket|EUR=12.5;CardTrader|EUR=11.0").
     * @param baselines Map<String, Double> - reference price by [PricePoint.key].
     * @return String - text to save.
     */
    fun encodeBaselines(baselines: Map<String, Double>): String =
        baselines.entries.filter { it.value > 0.0 && it.value.isFinite() && isSafeKey(it.key) }
            .sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Reads the reference prices saved with [encodeBaselines]; malformed parts are ignored.
     * @param text String? - saved text.
     * @return Map<String, Double> - reference price by [PricePoint.key].
     */
    fun decodeBaselines(text: String?): Map<String, Double> {
        if (text.isNullOrBlank() || text.length > MAX_ENCODED_LENGTH) return emptyMap()
        return text.split(';').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            val value = part.substringAfter('=', "").toDoubleOrNull()
            if (!isSafeKey(key) || value == null || value <= 0.0 || !value.isFinite()) null else key to value
        }.toMap()
    }

    /**
     * Checks that a key is "source|currency" with harmless characters (no separators of the saved format).
     * @param key String - key to check.
     * @return Boolean - true if valid.
     */
    private fun isSafeKey(key: String): Boolean = KEY.matches(key)

    private val KEY = Regex("^[A-Za-z0-9 ._-]{1,32}\\|[A-Z]{3}$")
    private const val MAX_ENCODED_LENGTH = 2_000
    private const val EPSILON = 1e-9
}
