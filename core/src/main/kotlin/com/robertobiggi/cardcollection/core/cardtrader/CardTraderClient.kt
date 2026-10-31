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
 * Minimal client for the CardTrader v2 API. Requires the user's personal token
 * (CardTrader → Settings → API), sent as Bearer.
 *
 * @param http shared OkHttp client.
 * @param token user's API token.
 * @param baseUrl root of the API; changeable only for tests with MockWebServer.
 * @param limiter limits the request rate; by default it is shared by all clients, so several syncs together stay below CardTrader's threshold.
 */
class CardTraderClient(
    private val http: OkHttpClient,
    private val token: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val limiter: RateLimiter = SHARED_LIMITER,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /**
     * Lists the supported games.
     * @return List<CtGameDto> - CardTrader games (the response wraps them in `{"array": [...]}`).
     */
    suspend fun games(): List<CtGameDto> =
        get(url("games"), CtGamesResponse.serializer()).array

    /**
     * Lists all expansions of all games.
     * @return List<CtExpansionDto> - expansions.
     */
    suspend fun expansions(): List<CtExpansionDto> =
        get(url("expansions"), ListSerializer(CtExpansionDto.serializer()), MAX_LIST_BYTES)

    /**
     * Lists the blueprints (cards) of an expansion.
     * @param expansionId Int - id of the expansion.
     * @return List<CtBlueprintDto> - cards of the expansion.
     */
    suspend fun blueprints(expansionId: Int): List<CtBlueprintDto> =
        get(url("blueprints/export", "expansion_id" to expansionId.toString()), ListSerializer(CtBlueprintDto.serializer()), MAX_LIST_BYTES)

    /**
     * Lists the listings of a blueprint (cheapest first, at most 25).
     * @param blueprintId Int - id of the blueprint.
     * @param language String? - two-letter language code, or null for all.
     * @return List<CtProductDto> - listings of the blueprint.
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

        /** Minimum pause between two requests to CardTrader, in ms: below the threshold beyond which the IP is blocked. */
        const val MIN_INTERVAL_MS = 500L

        /** Single limiter for the whole process, because clients are created in several places and can work in parallel. */
        val SHARED_LIMITER = RateLimiter(MIN_INTERVAL_MS)

        /** Maximum size for the lists of expansions and blueprints (8 MB). */
        const val MAX_LIST_BYTES = 8L * 1024 * 1024
    }
}
