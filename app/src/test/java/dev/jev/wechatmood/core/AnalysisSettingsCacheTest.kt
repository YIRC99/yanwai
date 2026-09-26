package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class AnalysisSettingsCacheTest {
    @Test fun `switching only intent route invalidates cache while unrelated reply edits do not`() {
        MoodStore.clear()
        val session = SettingsSession()
        val jev = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key")
        session.accept(RuntimeSettings(1, false, jev, "install"))
        MoodStore.complete(requireNotNull(MoodStore.acquire("cached")), Mood("old", 0.0, 0, ""))
        session.accept(RuntimeSettings(2, false, jev, "install", replyConsent = true))
        assertNotNull(MoodStore.get("cached"))
        session.accept(RuntimeSettings(3, false, jev, "install", intent = IntentSettings(IntentRoute.LLM)))
        assertNull(MoodStore.get("cached"))
        MoodStore.complete(requireNotNull(MoodStore.acquire("new")), Mood("new", 0.0, 0, ""))
        session.accept(RuntimeSettings(2, false, jev, "install"))
        assertNotNull(MoodStore.get("new"))
        MoodStore.clear()
    }

    @Test fun `intent profiles never overwrite reply settings or reuse its key`() {
        val values = mutableMapOf("reply_api_key" to "private-reply", "reply_model" to "reply-model")
        assertEquals("", IntentProfiles.load(dev.jev.wechatmood.reply.ReplyProvider.DEEPSEEK, values::get).apiKey)
        val settings = dev.jev.wechatmood.reply.ReplySettings.fromInput("https://api.deepseek.com/v1", "intent-key", "intent-model")
        values.putAll(IntentProfiles.valuesToSave(dev.jev.wechatmood.reply.ReplyProvider.DEEPSEEK, settings, values::get))
        assertEquals("private-reply", values["reply_api_key"])
        assertEquals("intent-key", IntentSettings.load(values::get).llm.apiKey)
        assertEquals(IntentRoute.JEV, IntentSettings.load(values::get).route)
        assertEquals("", IntentProfiles.load(dev.jev.wechatmood.reply.ReplyProvider.OPENAI, values::get).apiKey)
    }

    @Test fun `changing analysis credentials revokes old results and in flight ownership`() {
        MoodStore.clear()
        val session = SettingsSession()
        session.accept(RuntimeSettings(1, false, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "old"), "install"))
        val cached = requireNotNull(MoodStore.acquire("cached"))
        MoodStore.complete(cached, Mood("old", 0.0, 0, ""))
        val pending = requireNotNull(MoodStore.acquire("pending"))
        session.accept(RuntimeSettings(2, false, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "new"), "install"))
        assertNull(MoodStore.get("cached"))
        assertFalse(MoodStore.complete(pending, Mood("stale", 0.0, 0, "")))
        MoodStore.clear()
    }
}
