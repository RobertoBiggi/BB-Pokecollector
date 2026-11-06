package com.robertobiggi.cardcollection.core.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** What the app tells about itself: no identifiers, nothing about the user or the collection. */
data class AnalyticsContext(
    val appVersion: String,
    val buildNumber: String,
    val osVersion: String,
    val locale: String,
)

/**
 * Anonymous usage counter on [Aptabase](https://aptabase.com): one `app_open` event per day, with app version, Android version and
 * language. No device identifier is sent: the session id is random and lives only as long as the process.
 *
 * @param http client that sends the event.
 * @param appKey Aptabase app key (`A-EU-xxxxxxxxxx`, not a secret). Empty or not valid: nothing is ever sent.
 * @param baseUrl server to send to, derived from the region in the key (changeable in tests).
 */
class AptabaseClient(
    private val http: OkHttpClient,
    private val appKey: String,
    private val baseUrl: String? = baseUrlOf(appKey),
) {
    /** True if the key is valid, so events can be sent. */
    val configured: Boolean get() = baseUrl != null

    private val sessionId = java.util.UUID.randomUUID().toString()

    /**
     * Sends one event. Errors are swallowed: analytics must never disturb the app.
     * @param name String - event name.
     * @param context AnalyticsContext - app and system version.
     * @return Boolean - true if the server accepted it.
     */
    suspend fun track(name: String, context: AnalyticsContext): Boolean {
        val url = baseUrl?.let { "$it/api/v0/event".toHttpUrlOrNull() } ?: return false
        val request = Request.Builder()
            .url(url)
            .header("App-Key", appKey)
            .post(body(name, context, Instant.now()).toString().toRequestBody(JSON_TYPE))
            .build()
        return try {
            withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.isSuccessful } }
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        /** Hosts that [baseUrlOf] can return (the app only allows these for the connection). */
        val HOSTS = setOf("eu.aptabase.com", "us.aptabase.com")

        /**
         * Server of the region written in the key.
         * @param appKey String - key such as `A-EU-1234567890`.
         * @return String? - base address, or null if the key is empty, malformed or from another region (self-hosted).
         */
        fun baseUrlOf(appKey: String): String? {
            val parts = appKey.trim().split('-')
            if (parts.size != 3 || parts[0] != "A" || parts[2].isEmpty()) return null
            return when (parts[1]) {
                "EU" -> "https://eu.aptabase.com"
                "US" -> "https://us.aptabase.com"
                else -> null
            }
        }
    }

    private fun body(name: String, context: AnalyticsContext, now: Instant): JsonObject = buildJsonObject {
        put("timestamp", now.toString())
        put("sessionId", sessionId)
        put("eventName", name)
        put("systemProps", buildJsonObject {
            put("isDebug", false)
            put("locale", context.locale)
            put("osName", "Android")
            put("osVersion", context.osVersion)
            put("appVersion", context.appVersion)
            put("appBuildNumber", context.buildNumber)
            put("engineName", "")
            put("engineVersion", "")
            put("sdkVersion", "cardcollection@1")
        })
        put("props", buildJsonObject { })
    }
}
