package com.robertobiggi.cardcollection.core.net

/** Error returned by a remote API, with the HTTP code to decide retries and circuit breaker. */
open class ApiException(val httpCode: Int, message: String) : Exception(message)

/** The source limits requests (HTTP 429). [retryAfterMs] comes from the Retry-After header, if present. */
class RateLimitedException(val retryAfterMs: Long?) : ApiException(429, "Troppe richieste (429)")

/** The source refuses access (HTTP 401/403), for example due to anti-bot blocks or an invalid key. */
class BlockedException(httpCode: Int) : ApiException(httpCode, "Accesso rifiutato ($httpCode)")

/** The requested resource does not exist (HTTP 404). */
class NotFoundException : ApiException(404, "Risorsa non trovata (404)")

/** The response exceeds the maximum accepted size or is not in the expected format. */
class InvalidResponseException(message: String) : Exception(message)
