package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class ApiProfilesTest {
    @Test fun `first switch saves legacy official credential before replacing active values`() {
        val saved = mutableMapOf("api_base" to ApiSettings.DEFAULT_ENDPOINT, "api_key" to "legacy-key")
        saved.putAll(ApiProfiles.valuesToSave(ApiSettings.fromInput("", "new-key", "openrouter"), saved::get))
        assertEquals("legacy-key", saved["channel_typesafe_key"])
        assertEquals(ApiSettings.DEFAULT_ENDPOINT, saved["channel_typesafe_endpoint"])
        assertEquals("new-key", saved["channel_openrouter_key"])
        assertEquals("new-key", saved["api_key"])
        assertEquals("openrouter", saved["api_provider"])
    }

    @Test fun `legacy custom endpoint and model survive switching and recreation`() {
        val saved = mutableMapOf("api_base" to "https://custom.example/jev?version=2", "api_key" to "custom-key")
        saved.putAll(ApiProfiles.valuesToSave(ApiSettings.fromInput("", "new-key", "vercel"), saved::get))
        assertEquals("https://custom.example/jev?version=2", saved["channel_custom_endpoint"])
        assertEquals("custom-key", saved["channel_custom_key"])
        assertEquals("jev-1.13.0", saved["channel_custom_model"])
    }

    @Test fun `existing profile is not replaced by legacy migration`() {
        val saved = mutableMapOf("api_base" to ApiSettings.DEFAULT_ENDPOINT, "api_key" to "legacy-key",
            "channel_typesafe_key" to "saved-key")
        saved.putAll(ApiProfiles.valuesToSave(ApiSettings.fromInput("", "router-key", "openrouter"), saved::get))
        assertEquals("saved-key", saved["channel_typesafe_key"])
    }

    @Test fun `clearing active key clears its profile while retaining other channels`() {
        val saved = mutableMapOf("api_provider" to "openrouter", "api_key" to "router-key",
            "channel_openrouter_key" to "router-key", "channel_typesafe_key" to "official-key")
        saved.putAll(ApiProfiles.valuesToSave(ApiSettings.fromInput("", "", "openrouter"), saved::get))
        assertEquals("", saved["api_key"])
        assertEquals("", saved["channel_openrouter_key"])
        assertEquals("official-key", saved["channel_typesafe_key"])
    }
}
