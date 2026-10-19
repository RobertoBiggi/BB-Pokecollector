package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.net.NotFoundException
import com.robertobiggi.cardcollection.core.net.RateLimitedException
import com.robertobiggi.cardcollection.core.validation.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Client minimale per l'API pubblica TCGdex v2 (nessuna chiave richiesta).
 *
 * @param http client OkHttp condiviso (timeout e user agent configurati dal chiamante).
 * @param baseUrl radice dell'API; modificabile solo per i test con MockWebServer.
 */
class TcgdexClient(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /**
     * Cerca le carte il cui nome contiene il testo indicato.
     * @param language Language - lingua del catalogo in cui cercare.
     * @param name String - testo da cercare nel nome (validato con [Validators.isValidQueryPattern]).
     * @return List<CardBriefDto> - elenco sintetico delle carte trovate.
     * @throws IllegalArgumentException se il testo non è valido.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun searchCards(language: Language, name: String): List<CardBriefDto> {
        require(Validators.isValidQueryPattern(name)) { "Testo di ricerca non valido" }
        val url = base.newBuilder()
            .addPathSegment(language.code)
            .addPathSegment("cards")
            .addQueryParameter("name", name.trim())
            .build()
        return get(url, ListSerializer(CardBriefDto.serializer()))
    }

    /**
     * Scarica il dettaglio di una carta, prezzi per variante inclusi.
     * @param language Language - lingua del catalogo.
     * @param cardId String - id TCGdex della carta (es. "me01-094"), validato prima dell'uso.
     * @return CardDto - dettaglio della carta.
     * @throws IllegalArgumentException se l'id non è valido.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun getCard(language: Language, cardId: String): CardDto {
        require(Validators.isValidRemoteId(cardId)) { "Id carta non valido" }
        val url = base.newBuilder()
            .addPathSegment(language.code)
            .addPathSegment("cards")
            .addPathSegment(cardId)
            .build()
        return get(url, CardDto.serializer())
    }

    /**
     * Scarica il dettaglio di un set (sigla, serie, data di uscita).
     * @param language Language - lingua del catalogo.
     * @param setId String - id TCGdex del set (es. "me03", "SM10a"), validato prima dell'uso.
     * @return SetDto - dettaglio del set.
     * @throws IllegalArgumentException se l'id non è valido.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun getSet(language: Language, setId: String): SetDto {
        require(Validators.isValidRemoteId(setId)) { "Id set non valido" }
        val url = base.newBuilder()
            .addPathSegment(language.code)
            .addPathSegment("sets")
            .addPathSegment(setId)
            .build()
        return get(url, SetDto.serializer())
    }

    /**
     * Elenca le carte con un certo numero nel set. TCGdex confronta per "contiene", quindi il
     * chiamante deve tenere solo le corrispondenze esatte.
     * @param language Language - lingua del catalogo.
     * @param localId String - numero della carta, solo cifre (1-4).
     * @return List<CardBriefDto> - elenco sintetico delle carte trovate.
     * @throws IllegalArgumentException se il numero non è valido.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun cardsByLocalId(language: Language, localId: String): List<CardBriefDto> {
        require(LOCAL_NUMBER.matches(localId)) { "Numero carta non valido" }
        val url = base.newBuilder()
            .addPathSegment(language.code)
            .addPathSegment("cards")
            .addQueryParameter("localId", localId)
            .build()
        // Con numeri corti la lista può essere lunga: limite più alto del solito.
        return get(url, ListSerializer(CardBriefDto.serializer()), MAX_LIST_BYTES)
    }

    /**
     * Elenca tutti i set di una lingua, con il numero ufficiale di carte.
     * @param language Language - lingua del catalogo.
     * @return List<SetBriefDto> - elenco sintetico dei set.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun listSets(language: Language): List<SetBriefDto> {
        val url = base.newBuilder()
            .addPathSegment(language.code)
            .addPathSegment("sets")
            .build()
        return get(url, ListSerializer(SetBriefDto.serializer()))
    }

    /**
     * Esegue una GET e decodifica il corpo JSON, convertendo gli errori HTTP in eccezioni tipizzate.
     * @param url HttpUrl - indirizzo completo da chiamare.
     * @param deserializer DeserializationStrategy<T> - decoder kotlinx.serialization del tipo atteso.
     * @param maxBytes Long - dimensione massima accettata per il corpo.
     * @return T - oggetto decodificato.
     * @throws ApiException se la risposta non è 2xx.
     * @throws InvalidResponseException se il corpo è troppo grande o non decodificabile.
     */
    private suspend fun <T> get(url: HttpUrl, deserializer: DeserializationStrategy<T>, maxBytes: Long = MAX_BODY_BYTES): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            http.newCall(request).execute().use { response ->
                throwIfError(response)
                val body = readLimited(response, maxBytes)
                try {
                    JSON.decodeFromString(deserializer, body)
                } catch (e: IllegalArgumentException) {
                    throw InvalidResponseException("Risposta TCGdex non valida: ${e.message?.take(200)}")
                }
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tcgdex.net/v2/"

        /** Dimensione massima accettata per una risposta JSON (2 MB). */
        const val MAX_BODY_BYTES = 2L * 1024 * 1024

        /** Dimensione massima per le liste di ricerca per numero (6 MB). */
        const val MAX_LIST_BYTES = 6L * 1024 * 1024

        private val LOCAL_NUMBER = Regex("^\\d{1,4}$")

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
         * Legge il corpo della risposta rifiutando quelli oltre [MAX_BODY_BYTES].
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
         * Interpreta l'header Retry-After espresso in secondi.
         * @param value String? - valore dell'header, se presente.
         * @return Long? - attesa in millisecondi (massimo 10 minuti), oppure null se assente o non numerico.
         */
        fun parseRetryAfter(value: String?): Long? {
            val seconds = value?.trim()?.toLongOrNull() ?: return null
            return (seconds.coerceIn(0, 600)) * 1000
        }
    }
}
