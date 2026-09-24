package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class ApiSettingsTest {
    @Test fun `legacy OpenRouter base addresses migrate to the Jev compatibility route`() {
        for (address in listOf("https://openrouter.ai", "https://openrouter.ai/api/v1",
            "https://openrouter.ai/api/v1/chat/completions")) {
            assertEquals("https://openrouter.ai/api/v1/systemone", ApiSettings.fromInput(address, "test").endpoint)
        }
    }

    @Test fun `Vercel gateway address uses the TypeSafe compatibility route`() {
        assertEquals("https://ai-gateway.vercel.sh/typesafe/v1/systemone",
            ApiSettings.fromInput("https://ai-gateway.vercel.sh/v1", "test").endpoint)
    }

    @Test fun `provider models are selected together with their endpoints`() {
        assertEquals("jev-1.13.0", ApiSettings.fromInput("", "test").model)
        assertEquals("typesafe/jev-1.13", ApiSettings.fromInput("https://openrouter.ai/api/v1", "test").model)
        assertEquals("typesafe-ai/jev", ApiSettings.fromInput("https://ai-gateway.vercel.sh", "test").model)
        assertEquals("my-jev", ApiSettings.fromInput("https://example.org/jev", "test", "custom", "my-jev").model)
    }

    @Test fun `preset selection cannot accidentally keep the previous platform URL or model`() {
        val settings = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "test", "openrouter", "jev-1.13.0")
        assertEquals(JevProvider.OPENROUTER, settings.provider)
        assertEquals(JevProvider.OPENROUTER.endpoint, settings.endpoint)
        assertEquals("typesafe/jev-1.13", settings.model)
    }

    @Test fun `unknown hosts and nonstandard paths remain custom during migration`() {
        for (url in listOf("https://openrouter.ai.evil.example/api/v1", "https://example.org/openrouter.ai",
            "https://openrouter.ai/custom", "http://openrouter.ai/api/v1", "https://openrouter.ai:8443/api/v1")) {
            val settings = ApiSettings.fromInput(url, "test")
            assertEquals(JevProvider.CUSTOM, settings.provider)
            assertEquals(url, settings.endpoint)
        }
    }

    @Test fun `explicit custom URL is kept even on a known host`() {
        val settings = ApiSettings.fromInput("https://openrouter.ai/api/alpha/decisions", "test", "custom", "my-model")
        assertEquals("https://openrouter.ai/api/alpha/decisions", settings.endpoint)
        assertEquals("my-model", settings.model)
    }

    @Test fun `settings string never includes credential`() {
        assertFalse(ApiSettings.fromInput("", "private-test-token").toString().contains("private-test-token"))
    }

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
