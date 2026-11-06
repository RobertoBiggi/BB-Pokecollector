package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.analytics.AnalyticsContext
import com.robertobiggi.cardcollection.core.analytics.AptabaseClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AptabaseTest {
    private val context = AnalyticsContext("0.6.24", "24", "14", "it-IT")

    @Test
    fun `the region comes from the key`() {
        assertEquals("https://eu.aptabase.com", AptabaseClient.baseUrlOf("A-EU-1234567890"))
        assertEquals("https://us.aptabase.com", AptabaseClient.baseUrlOf("A-US-1234567890"))
    }

    @Test
    fun `empty, malformed or self-hosted keys disable it`() {
        listOf("", "  ", "A-EU-", "EU-123", "A-SH-123", "A-DEV-123", "A-EU-1-2").forEach { assertNull(AptabaseClient.baseUrlOf(it), it) }
        assertFalse(AptabaseClient(OkHttpClient(), "").configured)
    }

    @Test
    fun `without a key nothing is sent`() = runTest {
        assertFalse(AptabaseClient(OkHttpClient(), "").track("app_open", context))
    }

    @Test
    fun `sends the event with the key and no identifier`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val client = AptabaseClient(OkHttpClient(), "A-EU-1234567890", server.url("/").toString().trimEnd('/'))
            assertTrue(client.track("app_open", context))
            val request = server.takeRequest()
            assertEquals("/api/v0/event", request.path)
            assertEquals("A-EU-1234567890", request.getHeader("App-Key"))
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("app_open", json["eventName"]!!.jsonPrimitive.content)
            val system = json["systemProps"]!!.jsonObject
            assertEquals("0.6.24", system["appVersion"]!!.jsonPrimitive.content)
            assertEquals("Android", system["osName"]!!.jsonPrimitive.content)
            assertEquals(setOf("timestamp", "sessionId", "eventName", "systemProps", "props"), json.keys)
        }
    }

    @Test
    fun `a server error is not an exception`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val client = AptabaseClient(OkHttpClient(), "A-EU-1", server.url("/").toString().trimEnd('/'))
            assertFalse(client.track("app_open", context))
        }
    }
}
