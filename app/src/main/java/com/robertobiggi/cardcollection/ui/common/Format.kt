package com.robertobiggi.cardcollection.ui.common

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Formattazione di prezzi e date nella lingua corrente dell'app (cambia con la scelta in Impostazioni).
 * EUR e USD non vengono mai convertiti.
 */
object Format {

    /**
     * Formatta un importo in una valuta.
     * @param value Double? - importo.
     * @param currency String - codice ISO della valuta.
     * @return String - importo formattato, oppure "—" se assente.
     */
    private fun money(value: Double?, currency: String): String {
        if (value == null) return "—"
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        format.currency = Currency.getInstance(currency)
        return format.format(value)
    }

    /**
     * Formatta un importo in euro.
     * @param value Double? - importo.
     * @return String - es. "2,50 €" in italiano, "€2.50" in inglese; "—" se assente.
     */
    fun eur(value: Double?): String = money(value, "EUR")

    /**
     * Formatta un importo in dollari.
     * @param value Double? - importo.
     * @return String - importo formattato, oppure "—" se assente.
     */
    fun usd(value: Double?): String = money(value, "USD")

    /**
     * Formatta data e ora.
     * @param millis Long - istante in millisecondi.
     * @return String - data e ora nello stile della lingua corrente.
     */
    fun dateTime(millis: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))

    /**
     * Formatta una data.
     * @param millis Long - istante in millisecondi.
     * @return String - data nello stile della lingua corrente.
     */
    fun date(millis: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
}
