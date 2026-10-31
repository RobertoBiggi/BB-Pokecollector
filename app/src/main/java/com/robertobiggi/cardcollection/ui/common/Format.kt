package com.robertobiggi.cardcollection.ui.common

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Formatting of prices and dates in the app's current language (changes with the choice in Settings).
 * EUR and USD are never converted.
 */
object Format {

    /**
     * Formats an amount in a currency.
     * @param value Double? - amount.
     * @param currency String - ISO code of the currency.
     * @return String - formatted amount, or "—" if absent.
     */
    private fun money(value: Double?, currency: String): String {
        if (value == null) return "—"
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        format.currency = Currency.getInstance(currency)
        return format.format(value)
    }

    /**
     * Formats an amount in euros.
     * @param value Double? - amount.
     * @return String - e.g. "2,50 €" in Italian, "€2.50" in English; "—" if absent.
     */
    fun eur(value: Double?): String = money(value, "EUR")

    /**
     * Formats an amount in dollars.
     * @param value Double? - amount.
     * @return String - formatted amount, or "—" if absent.
     */
    fun usd(value: Double?): String = money(value, "USD")

    /**
     * Formats date and time.
     * @param millis Long - instant in milliseconds.
     * @return String - date and time in the current language's style.
     */
    fun dateTime(millis: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))

    /**
     * Formats a date.
     * @param millis Long - instant in milliseconds.
     * @return String - date in the current language's style.
     */
    fun date(millis: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
}
