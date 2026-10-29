package com.robertobiggi.cardcollection.core.price

import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Prezzo di una variante letto da una fonte in un aggiornamento.
 *
 * @property source fonte (es. "Cardmarket", "CardTrader", "TCGplayer").
 * @property currency valuta: [EUR] o [USD].
 * @property value prezzo, maggiore di zero.
 */
data class PricePoint(val source: String, val currency: String, val value: Double) {
    /** Chiave con cui si ricorda il prezzo di riferimento di questa fonte e valuta. */
    val key: String get() = "$source|$currency"

    companion object {
        const val EUR = "EUR"
        const val USD = "USD"
    }
}

/**
 * Cosa vuole sapere l'utente di una variante.
 *
 * @property notifyChanges true per un avviso quando il prezzo sale o scende oltre la soglia.
 * @property targetEur prezzo in euro sotto cui (o vicino a cui) avvisare; null se non impostato.
 */
data class AlertConfig(val notifyChanges: Boolean, val targetEur: Double?)

/**
 * Stato salvato di un avviso tra un controllo e l'altro.
 *
 * @property baselines prezzo di riferimento per fonte e valuta ([PricePoint.key]): l'ultimo per cui si è avvisato,
 *   o il primo visto. Le variazioni si misurano da qui, così quelle piccole di ogni giorno si sommano.
 * @property targetReached true se l'avviso del prezzo obiettivo è già partito e il prezzo non è ancora risalito.
 */
data class AlertState(val baselines: Map<String, Double> = emptyMap(), val targetReached: Boolean = false)

/** Avviso da notificare. */
sealed interface AlertEvent {
    /**
     * Il prezzo di una fonte è salito o sceso oltre la soglia.
     * @property point prezzo nuovo.
     * @property previous prezzo di riferimento precedente.
     */
    data class Change(val point: PricePoint, val previous: Double) : AlertEvent {
        /** Variazione in percentuale (positiva se sale). */
        val percent: Double get() = (point.value - previous) / previous * 100.0
    }

    /**
     * Il prezzo più basso in euro è arrivato vicino o sotto il prezzo obiettivo.
     * @property point prezzo più basso in euro.
     * @property target prezzo obiettivo.
     */
    data class TargetReached(val point: PricePoint, val target: Double) : AlertEvent
}

/**
 * Esito del controllo di un avviso.
 * @property events avvisi da notificare.
 * @property state stato da salvare per il prossimo controllo.
 */
data class AlertEvaluation(val events: List<AlertEvent>, val state: AlertState)

/** Regole degli avvisi di prezzo: cosa notificare e quando fare il controllo giornaliero. */
object PriceAlertRules {

    /** Variazione minima predefinita per l'avviso "sale o scende", in percentuale. */
    const val DEFAULT_CHANGE_PERCENT = 5

    /** Variazioni ammesse nelle impostazioni, in percentuale. */
    val CHANGE_PERCENT_RANGE = 1..50

    /** Margine sopra il prezzo obiettivo entro cui il prezzo è "simile" (5%: con 50 € avvisa da 52,50 € in giù). */
    const val TARGET_MARGIN = 0.05

    /** Prezzo obiettivo massimo accettato, in euro. */
    const val MAX_TARGET_EUR = 1_000_000.0

    /**
     * Ora del controllo giornaliero: il listino Cardmarket viene rigenerato verso le 3 di notte
     * (`createdAt` reale "…T02:48:17+0200"), il controllo parte un'ora e mezza dopo.
     */
    val DAILY_RUN_TIME: LocalTime = LocalTime.of(4, 30)

    /**
     * Confronta i prezzi appena letti con lo stato salvato e decide cosa notificare.
     * - Per ogni fonte e valuta: la prima volta il prezzo diventa il riferimento; poi, se gli avvisi di variazione sono
     *   attivi e il prezzo si è spostato di almeno [changePercent] dal riferimento, parte un avviso e il riferimento
     *   diventa il prezzo nuovo. Con gli avvisi di variazione spenti il riferimento segue il prezzo, così
     *   attivarli dopo non fa partire variazioni vecchie.
     * - Prezzo obiettivo: confrontato con il prezzo più basso in euro; l'avviso parte una volta sola quando il prezzo
     *   entra nella zona (obiettivo + [TARGET_MARGIN]) e si riarma quando ne esce.
     * Le fonti che in questo controllo non hanno dato un prezzo tengono il riferimento che avevano.
     * @param config AlertConfig - cosa vuole sapere l'utente.
     * @param state AlertState - stato salvato.
     * @param points List<PricePoint> - prezzi letti in questo controllo (quelli non positivi sono ignorati).
     * @param changePercent Int - variazione minima in percentuale per l'avviso "sale o scende".
     * @return AlertEvaluation - avvisi da notificare e stato nuovo.
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
     * Prossimo controllo giornaliero: oggi a [DAILY_RUN_TIME] se non è ancora passato, altrimenti domani.
     * Calcolato a ogni esecuzione, così resta all'ora giusta anche con il cambio dell'ora legale.
     * @param now ZonedDateTime - adesso, nel fuso orario del telefono.
     * @param time LocalTime - ora del controllo.
     * @return ZonedDateTime - istante del prossimo controllo.
     */
    fun nextRun(now: ZonedDateTime, time: LocalTime = DAILY_RUN_TIME): ZonedDateTime {
        val today = now.with(time).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1).with(time)
    }

    /**
     * Converte i prezzi di riferimento nel testo salvato nel database ("Cardmarket|EUR=12.5;CardTrader|EUR=11.0").
     * @param baselines Map<String, Double> - prezzo di riferimento per [PricePoint.key].
     * @return String - testo da salvare.
     */
    fun encodeBaselines(baselines: Map<String, Double>): String =
        baselines.entries.filter { it.value > 0.0 && it.value.isFinite() && isSafeKey(it.key) }
            .sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Legge i prezzi di riferimento salvati con [encodeBaselines]; le parti malformate sono ignorate.
     * @param text String? - testo salvato.
     * @return Map<String, Double> - prezzo di riferimento per [PricePoint.key].
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
     * Controlla che una chiave sia "fonte|valuta" con caratteri innocui (niente separatori del formato salvato).
     * @param key String - chiave da controllare.
     * @return Boolean - true se valida.
     */
    private fun isSafeKey(key: String): Boolean = KEY.matches(key)

    private val KEY = Regex("^[A-Za-z0-9 ._-]{1,32}\\|[A-Z]{3}$")
    private const val MAX_ENCODED_LENGTH = 2_000
    private const val EPSILON = 1e-9
}
