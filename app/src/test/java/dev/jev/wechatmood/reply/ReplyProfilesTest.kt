package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyProfilesTest {
    @Test fun `legacy account survives first switch and new account remains isolated`() {
        val values = mutableMapOf(ReplySettings.KEY_ENDPOINT to "https://api.deepseek.com/v1/chat/completions",
            ReplySettings.KEY_API_KEY to "old-secret", ReplySettings.KEY_MODEL to "old-model")
        assertEquals(ReplyProvider.DEEPSEEK, ReplyProvider.resolve(null, values[ReplySettings.KEY_ENDPOINT].orEmpty()))
        assertEquals("", ReplyProfiles.load(ReplyProvider.XIAOMI, values::get).apiKey)
        val next = ReplySettings.fromInput(ReplyProvider.XIAOMI.endpoint, "new-secret", "mimo-model")
        values.putAll(ReplyProfiles.valuesToSave(ReplyProvider.XIAOMI, next, values::get))
        assertEquals("old-secret", ReplyProfiles.load(ReplyProvider.DEEPSEEK, values::get).apiKey)
        assertEquals("old-model", ReplyProfiles.load(ReplyProvider.DEEPSEEK, values::get).model)
        assertEquals("new-secret", ReplyProfiles.load(ReplyProvider.XIAOMI, values::get).apiKey)
        assertEquals("new-secret", values[ReplySettings.KEY_API_KEY])
    }

    @Test fun `similar host or custom path is never classified as official provider`() {
        listOf("https://api.deepseek.com.evil.test/v1", "http://api.deepseek.com/v1", "https://api.deepseek.com/proxy/v1")
            .forEach { assertEquals(ReplyProvider.CUSTOM, ReplyProvider.resolve(null, it)) }
    }

    @Test fun `saved profile selection survives reload and custom endpoint is preserved`() {
        val values = mutableMapOf<String, String>()
        val custom = ReplySettings.fromInput("https://example.org/proxy/v2", "custom-key", "custom-model")
        values.putAll(ReplyProfiles.valuesToSave(ReplyProvider.CUSTOM, custom, values::get))
        values.putAll(ReplyProfiles.valuesToSave(ReplyProvider.GLM,
            ReplySettings.fromInput(ReplyProvider.GLM.endpoint, "glm-key", "glm-model"), values::get))
        assertEquals(ReplyProvider.GLM, ReplyProvider.resolve(values[ReplyProfiles.KEY_PROVIDER], values[ReplySettings.KEY_ENDPOINT].orEmpty()))
        assertEquals(custom.endpoint, ReplyProfiles.load(ReplyProvider.CUSTOM, values::get).endpoint)
        assertEquals("custom-key", ReplyProfiles.load(ReplyProvider.CUSTOM, values::get).apiKey)
        assertFalse(ReplyProfiles.load(ReplyProvider.GLM, values::get).toString().contains("glm-key"))
    }
}
