package com.robertobiggi.cardcollection.core.cardtrader

import com.robertobiggi.cardcollection.core.net.BlockedException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Client minimale per l'API CardTrader v2. Richiede il token personale dell'utente
 * (CardTrader → Impostazioni → API), inviato come Bearer.
 *
 * @param http client OkHttp condiviso.
 * @param token token API dell'utente.
 * @param baseUrl radice dell'API; modificabile solo per i test con MockWebServer.
 * @param limiter limita la frequenza delle richieste; di default è condiviso da tutti i client, così più sync insieme restano sotto la soglia di CardTrader.
 */
class CardTraderClient(
    private val http: OkHttpClient,
    private val token: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val limiter: RateLimiter = SHARED_LIMITER,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /**
     * Elenca i giochi supportati.
     * @return List<CtGameDto> - giochi di CardTrader (la risposta li avvolge in `{"array": [...]}`).
     */
    suspend fun games(): List<CtGameDto> =
        get(url("games"), CtGamesResponse.serializer()).array

    /**
     * Elenca tutte le espansioni di tutti i giochi.
     * @return List<CtExpansionDto> - espansioni.
     */
    suspend fun expansions(): List<CtExpansionDto> =
        get(url("expansions"), ListSerializer(CtExpansionDto.serializer()), MAX_LIST_BYTES)

    /**
     * Elenca i blueprint (le carte) di un'espansione.
     * @param expansionId Int - id dell'espansione.
     * @return List<CtBlueprintDto> - carte dell'espansione.
     */
    suspend fun blueprints(expansionId: Int): List<CtBlueprintDto> =
        get(url("blueprints/export", "expansion_id" to expansionId.toString()), ListSerializer(CtBlueprintDto.serializer()), MAX_LIST_BYTES)

    /**
     * Elenca gli annunci di un blueprint (i più economici per primi, al massimo 25).
     * @param blueprintId Int - id del blueprint.
     * @param language String? - codice lingua a due lettere, oppure null per tutte.
     * @return List<CtProductDto> - annunci del blueprint.
     */
    suspend fun products(blueprintId: Int, language: String?): List<CtProductDto> {
        val params = listOfNotNull("blueprint_id" to blueprintId.toString(), language?.let { "language" to it })
        val byBlueprint = get(
            url("marketplace/products", *params.toTypedArray()),
            MapSerializer(String.serializer(), ListSerializer(CtProductDto.serializer())),
        )
        return byBlueprint.values.flatten()
    }

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl =
        base.newBuilder().addPathSegments(path).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend fun <T> get(url: HttpUrl, deserializer: DeserializationStrategy<T>, maxBytes: Long = Http.MAX_BODY_BYTES): T =
        withContext(Dispatchers.IO) {
            limiter.acquire()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                Http.throwIfError(response)
                val body = Http.readLimited(response, maxBytes)
                try {
                    Http.JSON.decodeFromString(deserializer, body)
                } catch (e: IllegalArgumentException) {
                    throw InvalidResponseException("Risposta CardTrader non valida: ${e.message?.take(200)}")
                }
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.cardtrader.com/api/v2/"

        /** Pausa minima tra due richieste a CardTrader, in ms: sotto la soglia oltre la quale l'IP viene bloccato. */
        const val MIN_INTERVAL_MS = 500L

        /** Limitatore unico per tutto il processo, perché i client si creano in più punti e possono lavorare in parallelo. */
        val SHARED_LIMITER = RateLimiter(MIN_INTERVAL_MS)

        /** Dimensione massima per le liste di espansioni e blueprint (8 MB). */
        const val MAX_LIST_BYTES = 8L * 1024 * 1024
    }
}
