package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.hook.MessageContext
import dev.jev.wechatmood.hook.MessageMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TemporalAnalysisTest {
    private fun row(text: String, time: Long, sent: Int = 0, id: Long = time, type: Int = 1) =
        MessageMetadata(type, sent, text, "friend", id, time)

    @Test fun `both rounds retain exact send time and computed overnight gap`() {
        val yesterday = 1_790_338_800_000L
        val today = yesterday + 12 * 60 * 60 * 1000L
        val input = MessageContext.collect(row("早，今天吃什么？", today), 1) {
            row("我现在很生气", yesterday)
        }!!
        val requests = mutableListOf<JSONObject>()
        ChatAnalysis.analyze(input, "test", { payload ->
            requests += payload
            JevFixtures.reply(payload)
        })
        assertEquals(2, requests.size)
        requests.forEach {
            val state = it.getJSONObject("state")
            assertEquals(today, state.getLong("sent_at_ms"))
            val previous = state.getJSONArray("context").getJSONObject(0)
            assertEquals(yesterday, previous.getLong("sent_at_ms"))
            assertEquals(720L, previous.getLong("minutes_before_target"))
            assertEquals(720L, state.getLong("gap_from_previous_minutes"))
        }
    }

    @Test fun `timestamp changes invalidate cache even for identical message ids and wording`() {
        val first = MessageContext.collect(row("嗯", 2000, id = 2), 1) { row("还生气吗", 1000, 1, 1) }!!
        val changedTarget = MessageContext.collect(row("嗯", 5000, id = 2), 1) { row("还生气吗", 1000, 1, 1) }!!
        val changedHistory = MessageContext.collect(row("嗯", 2000, id = 2), 1) { row("还生气吗", 500, 1, 1) }!!
        assertNotEquals(first.key, changedTarget.key)
        assertNotEquals(first.key, changedHistory.key)
    }

    @Test fun `media does not consume the text window and its evidence gap is reported`() {
        val rows = listOf(row("明天见面吗", 1000, 1)) +
            (2L..14L).map { row("图片", it * 1000, type = 3) }
        val input = MessageContext.collect(row("可以", 15000), rows.size) { rows[it] }!!
        assertEquals(listOf("明天见面吗"), input.context.map { it.text })
        val calls = mutableListOf<JSONObject>()
        ChatAnalysis.analyze(input, "test", { calls += it; JevFixtures.reply(it) })
        val coverage = calls.first().getJSONObject("state").getJSONObject("context_coverage")
        assertEquals(13, coverage.getInt("omitted_media"))
        assertEquals("loaded_page", coverage.getString("source"))
    }

    @Test fun `unknown timestamp is explicit and never replaced by analysis time`() {
        val input = MessageContext.collect(row("昨天很烦，现在好多了", 0), 0) { error("no predecessor") }!!
        val calls = mutableListOf<JSONObject>()
        ChatAnalysis.analyze(input, "test", { calls += it; JevFixtures.reply(it) })
        val state = calls.first().getJSONObject("state")
        assertTrue(state.has("sent_at_ms"))
        assertTrue(state.isNull("sent_at_ms"))
        assertTrue(state.isNull("gap_from_previous_minutes"))
    }

    @Test fun `future dated predecessors are excluded without reading beyond target`() {
        val input = MessageContext.collect(row("当前", 2000), 2) {
            assertTrue(it < 2)
            if (it == 0) row("已发生", 1000) else row("之后才发生", 3000)
        }!!
        assertEquals(listOf("已发生"), input.context.map { it.text })
    }

    @Test fun `direct refusal has a generic interpretation even when scene is other`() {
        val payload = JevProtocol.payload("谢谢，不过我不想去", "test")
        assertTrue(payload.getJSONObject("questions").getJSONObject("speech_act").getJSONObject("criteria").has("refuse"))
        val profile = JevProtocol.parseProfile(JevFixtures.reply(payload, mapOf(
            "scene" to "other", "speech_act" to "refuse", "commitment" to "none")))
        assertTrue(ChatTemplates.candidates(profile).any { it.id == "intent_refuse" })
        assertTrue(ChatActions.candidates(profile).any { it.id == "respect_refusal" })
    }

    @Test fun `second pass can correct emotion and suppress an action when current message asks for space`() {
        val input = MessageContext.collect(row("先别发消息，让我静静", 2000), 0) { error("no history") }!!
        val profile = JevFixtures.profile()
        val payload = JevProtocol.detailPayload(input, "test", profile)
        assertTrue(payload.getJSONObject("questions").has("emotion_review"))
        assertTrue(payload.getJSONObject("questions").has("speech_act_review"))
        val result = JevProtocol.parseDetail(JevFixtures.reply(payload, mapOf(
            "emotion_review" to "calm", "speech_act_review" to "pause", "action" to "fulfill")), profile)
        assertTrue(result.detail.contains("平静 100%"))
        assertFalse(result.detail.contains("什么时候做"))
    }
}
