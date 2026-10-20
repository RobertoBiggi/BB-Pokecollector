package com.robertobiggi.cardcollection.core.tcgcsv

import com.robertobiggi.cardcollection.core.net.ApiException
import com.robertobiggi.cardcollection.core.net.InvalidResponseException
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.core.tcgdex.TcgdexClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Client per TCGCSV (tcgcsv.com), copia pubblica e gratuita dei dati TCGplayer aggiornata ogni giorno,
 * senza chiave. Copre sia le carte inglesi sia quelle giapponesi. Le linee guida del sito chiedono
 * uno User-Agent riconoscibile (lo imposta il chiamante) e poche richieste ravvicinate: per questo
 * ogni richiesta passa da [limiter].
 *
 * @param http client OkHttp condiviso.
 * @param baseUrl radice dei dati TCGplayer; modificabile solo per i test con MockWebServer.
 * @param limiter limita la frequenza delle richieste reali.
 */
class TcgcsvClient(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val limiter: RateLimiter = RateLimiter(REQUEST_INTERVAL_MS),
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /**
     * Elenca i set di una categoria TCGplayer.
     * @param categoryId Int - categoria ([CATEGORY_POKEMON] o [CATEGORY_POKEMON_JAPAN]).
     * @return List<TcgcsvGroupDto> - set della categoria.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun groups(categoryId: Int): List<TcgcsvGroupDto> =
        get(listOf(categoryId.toString(), "groups"), TcgcsvResponse.serializer(TcgcsvGroupDto.serializer())).results

    /**
     * Elenca i prodotti (carte e prodotti sigillati) di un set.
     * @param categoryId Int - categoria TCGplayer.
     * @param groupId Long - set.
     * @return List<TcgcsvProductDto> - prodotti del set.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun products(categoryId: Int, groupId: Long): List<TcgcsvProductDto> =
        get(listOf(categoryId.toString(), groupId.toString(), "products"), TcgcsvResponse.serializer(TcgcsvProductDto.serializer())).results

    /**
     * Elenca i prezzi dei prodotti di un set, una riga per prodotto e variante.
     * @param categoryId Int - categoria TCGplayer.
     * @param groupId Long - set.
     * @return List<TcgcsvPriceDto> - prezzi in dollari.
     * @throws ApiException in caso di errore HTTP.
     */
    suspend fun prices(categoryId: Int, groupId: Long): List<TcgcsvPriceDto> =
        get(listOf(categoryId.toString(), groupId.toString(), "prices"), TcgcsvResponse.serializer(TcgcsvPriceDto.serializer())).results

    /**
     * Esegue una GET e decodifica la risposta, con gli stessi controlli di dimensione ed errori del client TCGdex.
     * @param segments List<String> - segmenti del percorso, solo numeri e nomi fissi.
     * @param serializer KSerializer<T> - decodificatore della risposta.
     * @return T - risposta decodificata.
     * @throws ApiException per i codici HTTP diversi da 2xx.
     * @throws InvalidResponseException se il corpo è troppo grande o non valido.
     */
    private suspend fun <T> get(segments: List<String>, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        limiter.acquire()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            TcgdexClient.throwIfError(response)
            val body = TcgdexClient.readLimited(response, MAX_BODY_BYTES)
            try {
                TcgdexClient.JSON.decodeFromString(serializer, body)
            } catch (e: IllegalArgumentException) {
                throw InvalidResponseException("Risposta TCGCSV non valida: ${e.message?.take(200)}")
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://tcgcsv.com/tcgplayer/"

        /** Categoria TCGplayer delle carte Pokémon occidentali (inglesi). */
        const val CATEGORY_POKEMON = 3

        /** Categoria TCGplayer delle carte Pokémon giapponesi. */
        const val CATEGORY_POKEMON_JAPAN = 85

        /** Dimensione massima di una risposta: i prodotti di un set grande includono i testi degli attacchi. */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** Pausa minima tra due richieste reali (come negli esempi del sito). */
        const val REQUEST_INTERVAL_MS = 250L
    }
}
