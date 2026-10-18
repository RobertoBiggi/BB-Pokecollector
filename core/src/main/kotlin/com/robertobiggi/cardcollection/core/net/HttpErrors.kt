package com.robertobiggi.cardcollection.core.net

/** Errore restituito da un'API remota, con il codice HTTP per decidere retry e circuit breaker. */
open class ApiException(val httpCode: Int, message: String) : Exception(message)

/** La fonte limita le richieste (HTTP 429). [retryAfterMs] viene dall'header Retry-After, se presente. */
class RateLimitedException(val retryAfterMs: Long?) : ApiException(429, "Troppe richieste (429)")

/** La fonte rifiuta l'accesso (HTTP 401/403), ad esempio per blocchi anti-bot o chiave non valida. */
class BlockedException(httpCode: Int) : ApiException(httpCode, "Accesso rifiutato ($httpCode)")

/** La risorsa richiesta non esiste (HTTP 404). */
class NotFoundException : ApiException(404, "Risorsa non trovata (404)")

/** La risposta supera la dimensione massima accettata o non è nel formato atteso. */
class InvalidResponseException(message: String) : Exception(message)
