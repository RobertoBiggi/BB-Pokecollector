package com.robertobiggi.cardcollection.core.tcgcsv

import com.robertobiggi.cardcollection.core.net.Http
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One row of the TCGplayer prices of a set (TCGCSV): a product and one of its print variants, in dollars.
 * @property subTypeName print variant of the row ("Normal", "Holofoil", "Reverse Holofoil", "1st Edition Holofoil"…).
 */
@Serializable
data class TcgcsvPriceDto(
    val productId: Long,
    val lowPrice: Double? = null,
    val midPrice: Double? = null,
    val marketPrice: Double? = null,
    val subTypeName: String? = null,
)

@Serializable
private data class TcgcsvPricesResponse(val results: List<TcgcsvPriceDto> = emptyList())

/**
 * Client for TCGCSV (tcgcsv.com), a free public copy of the TCGplayer data refreshed every day, with no key.
 * A set's prices come in a single file, so the file is kept in memory: the cards of the same set in one update cost one request.
 * The client is meant to live for one price update (the data changes once a day).
 *
 * @param http shared OkHttp client.
 * @param baseUrl root of the TCGplayer data; changeable only for tests with MockWebServer.
 * @param limiter limits the rate of the real requests.
 */
class TcgcsvClient(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val limiter: RateLimiter = RateLimiter(REQUEST_INTERVAL_MS),
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val cache = HashMap<String, List<TcgcsvPriceDto>>()
    private val lock = Mutex()

    /**
     * Lists the prices of the products of a set, one row per product and print variant.
     * @param categoryId Int - TCGplayer category ([CATEGORY_POKEMON] or [CATEGORY_POKEMON_JAPAN]).
     * @param groupId Long - set (group) of TCGplayer.
     * @return List<TcgcsvPriceDto> - prices in dollars.
     * @throws com.robertobiggi.cardcollection.core.net.ApiException on HTTP errors.
     * @throws InvalidResponseException if the body is too large or not valid.
     */
    suspend fun prices(categoryId: Int, groupId: Long): List<TcgcsvPriceDto> = lock.withLock {
        val key = "$categoryId/$groupId"
        cache[key]?.let { return it }
        val rows = withContext(Dispatchers.IO) {
            val url = base.newBuilder().addPathSegment(categoryId.toString()).addPathSegment(groupId.toString()).addPathSegment("prices").build()
            limiter.acquire()
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            http.newCall(request).execute().use { response ->
                Http.throwIfError(response)
                val body = Http.readLimited(response, MAX_BODY_BYTES)
                try {
                    Http.JSON.decodeFromString(TcgcsvPricesResponse.serializer(), body).results
                } catch (e: IllegalArgumentException) {
                    throw InvalidResponseException("Invalid TCGCSV response: ${e.message?.take(200)}")
                }
            }
        }
        cache[key] = rows
        rows
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://tcgcsv.com/tcgplayer/"

        /** TCGplayer category of the Western (English) Pokémon cards. */
        const val CATEGORY_POKEMON = 3

        /** TCGplayer category of the Japanese Pokémon cards. */
        const val CATEGORY_POKEMON_JAPAN = 85

        /** The prices of a big set are a few hundred KB. */
        const val MAX_BODY_BYTES = 4L * 1024 * 1024

        /** Minimum pause between two real requests. */
        const val REQUEST_INTERVAL_MS = 250L
    }
}
