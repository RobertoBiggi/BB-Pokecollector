package com.robertobiggi.cardcollection.ui.common

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/** Formattazione di prezzi e date in italiano. EUR e USD non vengono mai convertiti. */
object Format {
    private val italian = Locale.ITALY
    private val eur = NumberFormat.getCurrencyInstance(italian).apply { currency = Currency.getInstance("EUR") }
    private val usd = NumberFormat.getCurrencyInstance(italian).apply { currency = Currency.getInstance("USD") }
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", italian).withZone(ZoneId.systemDefault())
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", italian).withZone(ZoneId.systemDefault())

    /**
     * Formatta un importo in euro.
     * @param value Double? - importo.
     * @return String - es. "2,50 €", oppure "—" se assente.
     */
    fun eur(value: Double?): String = value?.let { eur.format(it) } ?: "—"

    /**
     * Formatta un importo in dollari.
     * @param value Double? - importo.
     * @return String - es. "2,50 USD", oppure "—" se assente.
     */
    fun usd(value: Double?): String = value?.let { usd.format(it) } ?: "—"

    /**
     * Formatta data e ora.
     * @param millis Long - istante in millisecondi.
     * @return String - es. "17 ott 2026, 18:30".
     */
    fun dateTime(millis: Long): String = dateTime.format(Instant.ofEpochMilli(millis))

    /**
     * Formatta una data.
     * @param millis Long - istante in millisecondi.
     * @return String - es. "17 ott 2026".
     */
    fun date(millis: Long): String = date.format(Instant.ofEpochMilli(millis))
}
