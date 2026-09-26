package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.hook.MessageContext
import dev.jev.wechatmood.hook.MessageMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AnalysisStateTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun `crossing local midnight does not start a new time block after two minutes`() {
        val input = AnalysisInput("你还没有回答", "f", listOf(ContextMessage("对方", "为什么", at("2026-09-25T15:59:00Z"))),
            createdAt = at("2026-09-25T16:01:00Z"), zoneId = "Asia/Shanghai")
        val state = AnalysisState.build(input)
        assertTrue(state.getBoolean("different_day_from_previous"))
        assertEquals(2, state.getInt("gap_from_previous_minutes"))
        assertEquals(0, state.getInt("time_block"))
        assertEquals(1, state.getInt("speaker_turn"))
        assertTrue(state.getString("sent_at").contains("2026-09-26T00:01"))
    }

    @Test fun `speaker turn boundary uses exact elapsed time not rounded minutes`() {
        val input = AnalysisInput("后半句", "f", listOf(ContextMessage("对方", "前半句", 1000)), createdAt = 121001)
        assertEquals(2, AnalysisState.build(input).getInt("speaker_turn"))
    }

    @Test fun `unknown times cannot appear as a known continuous time range`() {
        val input = AnalysisInput("当前", "f", listOf(ContextMessage("对方", "已知", 1000), ContextMessage("我", "时间缺失")), createdAt = 2000)
        val state = AnalysisState.build(input)
        assertTrue(state.isNull("gap_from_previous_minutes"))
        assertTrue(AnalysisState.description(input).contains("部分时间未知"))
    }

    @Test fun `daylight saving gap is computed from instants not local clock text`() {
        val input = AnalysisInput("现在", "f", listOf(ContextMessage("我", "之前", at("2026-11-01T05:30:00Z"))),
            createdAt = at("2026-11-01T06:30:00Z"), zoneId = "America/New_York")
        val state = AnalysisState.build(input)
        assertEquals(60, state.getInt("gap_from_previous_minutes"))
        assertFalse(state.getBoolean("different_day_from_previous"))
    }

    @Test fun `character budget preserves whole messages and marks truncation`() {
        val input = AnalysisInput("目标", "f", (1..30).map { ContextMessage("我", "长".repeat(999)) })
        val state = AnalysisState.build(input)
        val history = state.getJSONArray("context")
        assertEquals(12, history.length())
        assertTrue(state.getJSONObject("context_coverage").getBoolean("truncated"))
        assertEquals(999, history.getJSONObject(0).getString("message").length)
    }

    @Test fun `adapter reads have a strict bound and never fetch future positions`() {
        val visited = mutableListOf<Int>()
        val input = MessageContext.collect(MessageMetadata(1, 0, "目标", "f", 501, 501000), 500) {
            visited += it
            MessageMetadata(3, 0, "图片", "f", it.toLong(), it * 1000L)
        }!!
        assertEquals(MessagePolicy.MAX_CONTEXT_SCAN, visited.size)
        assertTrue(visited.all { it < 500 })
        assertTrue(input.coverage.truncated)
        assertEquals(80, input.coverage.omittedMedia)
    }

    @Test fun `two rounds use an immutable copy even if caller mutates history`() {
        val history = mutableListOf(ContextMessage("我", "原前文", 1000))
        val requests = mutableListOf<JSONObject>()
        ChatAnalysis.analyze(AnalysisInput("目标", "f", history, createdAt = 2000), "test", {
            requests += it
            history.clear()
            JevFixtures.reply(it)
        })
        assertEquals(2, requests.size)
        requests.forEach { assertEquals("原前文", it.getJSONObject("state").getJSONArray("context").getJSONObject(0).getString("message")) }
    }

    @Test fun `explicit pause overrides contradictory new topic and pending agreement`() {
        val payload = JevProtocol.payload("明天再说，先别发了", "test")
        val profile = JevProtocol.parseProfile(JevFixtures.reply(payload, mapOf("speech_act" to "pause", "new_topic" to "yes")))
        assertEquals(listOf("give_space"), ChatActions.candidates(profile).map { it.id })
    }

    @Test fun `reopened unresolved issue overrides a conflicting new topic estimate`() {
        val payload = JevProtocol.payload("昨天答应的事呢", "test")
        val profile = JevProtocol.parseProfile(JevFixtures.reply(payload, mapOf("topic_relation" to "reopened", "new_topic" to "yes")))
        assertFalse(profile.newTopic)
        assertTrue(profile.hasAgreement)
    }

    @Test fun `action premise rejection removes advice and missing verification fails closed`() {
        val p = JevFixtures.profile()
        val payload = JevProtocol.detailPayload(AnalysisInput("所以呢", "f"), "test", p)
        val response = JevFixtures.reply(payload, mapOf("action" to "fulfill", "support_fulfill" to "no"))
        assertFalse(JevProtocol.parseDetail(response, p).detail.contains("建议："))
        val broken = JSONObject(response).apply { getJSONObject("answers").remove("support_fulfill") }
        assertThrows(Exception::class.java) { JevProtocol.parseDetail(broken.toString(), p) }
    }

    @Test fun `corrected pause cannot retain first round promise interpretation`() {
        val p = JevFixtures.profile()
        val payload = JevProtocol.detailPayload(AnalysisInput("先别说了", "f"), "test", p)
        val response = JevFixtures.reply(payload, mapOf("focus" to "promise_action", "speech_act_review" to "pause"))
        val result = JevProtocol.parseDetail(response, p)
        assertFalse(result.detail.contains("现在更需要实际安排吗"))
        assertTrue(result.detail.contains("希望暂停交流"))
    }

    @Test fun `corrected goodbye cannot retain invitation to keep talking`() {
        val p = JevProtocol.parseProfile(JevFixtures.reply(JevProtocol.payload("晚安", "test"), mapOf(
            "scene" to "daily", "progress" to "sharing", "speech_act" to "share", "new_topic" to "yes")))
        val payload = JevProtocol.detailPayload(AnalysisInput("晚安", "f"), "test", p)
        val result = JevProtocol.parseDetail(JevFixtures.reply(payload, mapOf(
            "focus" to "daily_bridge", "speech_act_review" to "goodbye")), p)
        assertTrue(result.detail.contains("结束聊天"))
        assertFalse(result.detail.contains("在追问或提供新话题"))
    }

    @Test fun `generic tired expression never invents relational closeness`() {
        val p = JevProtocol.parseProfile(JevFixtures.reply(JevProtocol.payload("今天工作太累了", "test"), mapOf(
            "scene" to "other", "emotion" to "tired", "speech_act" to "share", "progress" to "sharing")))
        val payload = JevProtocol.detailPayload(AnalysisInput("今天工作太累了", "f"), "test", p)
        val result = JevProtocol.parseDetail(JevFixtures.reply(payload, mapOf("focus" to "feeling_tired", "emotion_review" to "tired")), p)
        assertFalse(result.detail.contains("关心靠近"))
        assertEquals("当前状态", result.label)
    }

    @Test fun `emotion review does not see previous emotion estimate as evidence`() {
        val p = JevFixtures.profile()
        val state = JevProtocol.detailPayload(AnalysisInput("周四，不是周三", "f"), "test", p).getJSONObject("state")
        assertFalse(state.getJSONObject("first_pass").has("emotion"))
        assertTrue(state.getJSONObject("first_pass").has("progress"))
    }
}
