package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class ApiSettingsTest {
    @Test fun `fresh install has official endpoint and no credential`() {
        val settings = ApiSettings.fromInput("", "")
        assertEquals("https://api.typesafe.ai/v1/systemone", settings.endpoint)
        assertEquals("", settings.apiKey)
        assertFalse(settings.isConfigured)
    }

    @Test fun `custom endpoint preserves path and query without appending a route`() {
        val settings = ApiSettings.fromInput(" https://example.org/custom/jev?version=1 ", " test-token ")
        assertEquals("https://example.org/custom/jev?version=1", settings.endpoint)
        assertEquals("test-token", settings.apiKey)
        assertTrue(settings.isConfigured)
    }

    @Test fun `local http endpoint is allowed`() {
        assertEquals("http://192.168.1.2:8080/jev", ApiSettings.fromInput("http://192.168.1.2:8080/jev", "test").endpoint)
    }

    @Test fun `invalid addresses and embedded credentials are rejected`() {
        listOf("example.org", "file:///tmp/model", "https://user:secret@example.org/jev").forEach {
            assertThrows(IllegalArgumentException::class.java) { ApiSettings.fromInput(it, "test") }
        }
    }

    @Test fun `clearing key disables analysis and header control characters are rejected`() {
        assertFalse(ApiSettings.fromInput("https://example.org/jev", " ").isConfigured)
        assertThrows(IllegalArgumentException::class.java) { ApiSettings.fromInput("", "test\nheader") }
    }
}
