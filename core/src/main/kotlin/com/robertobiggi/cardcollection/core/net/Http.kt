package com.robertobiggi.cardcollection.core.net

import kotlinx.serialization.json.Json
import okhttp3.Response

/** Functions shared by the HTTP clients (CardTrader, Cardmarket price list). */
object Http {
    /** Maximum accepted size for a JSON response (2 MB). */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    private const val MAX_RETRY_AFTER_SECONDS = 60L

    /** JSON parser tolerant of unknown fields, shared by the clients. */
    val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = true
    }

    /**
     * Converts an unsuccessful HTTP response into the corresponding typed exception.
     * @param response Response - OkHttp response to check.
     * @return Unit - returns nothing if the response is 2xx.
     * @throws ApiException for any code other than 2xx.
     */
    fun throwIfError(response: Response) {
        if (response.isSuccessful) return
        when (response.code) {
            429 -> throw RateLimitedException(parseRetryAfter(response.header("Retry-After")))
            401, 403 -> throw BlockedException(response.code)
            404 -> throw NotFoundException()
            else -> throw ApiException(response.code, "Errore HTTP ${response.code}")
        }
    }

    /**
     * Reads the response body, rejecting those over [maxBytes].
     * @param response Response - OkHttp response with a body to read.
     * @return String - body decoded as UTF-8.
     * @throws InvalidResponseException if the body is missing or too large.
     */
    fun readLimited(response: Response, maxBytes: Long = MAX_BODY_BYTES): String {
        val body = response.body ?: throw InvalidResponseException("Risposta senza corpo")
        val declared = body.contentLength()
        if (declared > maxBytes) throw InvalidResponseException("Risposta troppo grande")
        val source = body.source()
        if (source.request(maxBytes + 1)) throw InvalidResponseException("Risposta troppo grande")
        return source.buffer.readUtf8()
    }

    /**
     * Interprets the Retry-After header expressed in seconds (maximum 60: longer waits exceed the time of background jobs).
     * @param value String? - value of the header, if present.
     * @return Long? - wait in milliseconds, or null if absent or not numeric.
     */
    fun parseRetryAfter(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1000
    }
}
