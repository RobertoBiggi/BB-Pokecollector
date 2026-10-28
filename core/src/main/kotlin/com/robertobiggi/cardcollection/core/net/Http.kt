package com.robertobiggi.cardcollection.core.net

import kotlinx.serialization.json.Json
import okhttp3.Response

/** Funzioni comuni ai client HTTP (CardTrader, listino Cardmarket). */
object Http {
    /** Dimensione massima accettata per una risposta JSON (2 MB). */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    private const val MAX_RETRY_AFTER_SECONDS = 60L

    /** Parser JSON tollerante ai campi sconosciuti, condiviso dai client. */
    val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = true
    }

    /**
     * Converte una risposta HTTP non riuscita nell'eccezione tipizzata corrispondente.
     * @param response Response - risposta OkHttp da controllare.
     * @return Unit - non restituisce nulla se la risposta è 2xx.
     * @throws ApiException per qualsiasi codice diverso da 2xx.
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
     * Legge il corpo della risposta rifiutando quelli oltre [maxBytes].
     * @param response Response - risposta OkHttp con corpo da leggere.
     * @return String - corpo decodificato in UTF-8.
     * @throws InvalidResponseException se il corpo manca o è troppo grande.
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
     * Interpreta l'header Retry-After espresso in secondi (massimo 60: attese più lunghe superano il tempo dei lavori in background).
     * @param value String? - valore dell'header, se presente.
     * @return Long? - attesa in millisecondi, oppure null se assente o non numerico.
     */
    fun parseRetryAfter(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1000
    }
}
