package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JevProtocolTest {
    private val input = AnalysisInput("所以呢？", "friend", listOf(ContextMessage("我", "我想起来了")))

    @Test fun `compact card shows version and emotion probabilities without removed fields`() {
        val profile = JevFixtures.profile().copy(emotion = ChatDecision("calm", JevProtocol.emotions.keys.associateWith {
            when (it) { "happy" -> 0.2; "calm" -> 0.5; "annoyed" -> 0.3; else -> 0.0 }
        }, 0.2))
        val detail = JevProtocol.detailPayload(input, "test", profile)
        val card = JevProtocol.parseDetail(JevFixtures.reply(detail, mapOf("focus" to "promise_action")), profile).detail
        assertEquals("Jev ${BuildConfig.VERSION_NAME}", card.lineSequence().first())
        assertTrue(card.contains("开心 20% · 平静 50% · 生气 30%"))
        listOf("参考原话", "好感线索", "互动线索", "闲聊解读", "模型推测", "先别急着猜").forEach {
            assertFalse(card.contains(it))
        }
        assertFalse(JevProtocol.payload(input.text, "test").getJSONObject("questions").has("warmth"))
        assertFalse(detail.getJSONObject("questions").has("evidence"))
    }

    @Test fun `uncertain card keeps emotion probabilities without filler or generic advice`() {
        val card = JevProtocol.fallback(JevFixtures.profile()).detail
        assertEquals(2, card.lines().size)
        assertTrue(card.contains("委屈 100%"))
        assertFalse(card.contains("建议"))
        assertFalse(card.contains("先别急着猜"))
    }

    @Test fun `first pass identifies scene emotion and progress and preserves source`() {
        val body = JevProtocol.payload(input.text, "test", input.context)
        assertEquals(setOf("scene", "emotion", "progress", "target", "speech_act", "advice_need",
            "commitment", "own_fault", "new_topic"), body.getJSONObject("questions").keys().asSequence().toSet())
        assertEquals(input.text, body.getJSONObject("state").getString("message"))
        assertEquals("我想起来了", body.getJSONObject("state").getJSONArray("context").getJSONObject(0).getString("message"))
        assertEquals("对方", body.getJSONObject("state").getString("speaker"))
        val profile = JevProtocol.parseProfile(JevFixtures.reply(body))
        assertEquals("hurt", profile.emotion.choice)
    }

    @Test fun `both passes reject oversized input and bound history`() {
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.payload("长".repeat(1001), "test") }
        assertThrows(IllegalArgumentException::class.java) {
            JevProtocol.detailPayload(input.copy(text = "长".repeat(1001)), "test", JevFixtures.profile())
        }
        val history = (0..19).map { ContextMessage("我", "前文$it") } + ContextMessage("我", "长".repeat(1001))
        val context = JevProtocol.payload("当前", "test", history).getJSONObject("state").getJSONArray("context")
        assertEquals(9, context.length())
        assertEquals("前文11", context.getJSONObject(0).getString("message"))
    }

    @Test fun `detail round carries first pass estimates and only eligible cards`() {
        val detail = JevProtocol.detailPayload(input, "test", JevFixtures.profile())
        assertEquals("promise", detail.getJSONObject("state").getJSONObject("first_pass").getJSONObject("scene").getString("choice"))
        val options = detail.getJSONObject("questions").getJSONObject("focus").getJSONObject("criteria")
        assertTrue(options.has("promise_action"))
        assertFalse(options.has("promise_care"))
        assertTrue(options.has("none"))
        assertFalse(detail.getJSONObject("state").has("evidence_candidates"))
    }

    @Test fun `card uses independently selected action instead of template branch`() {
        val profile = JevFixtures.profile()
        val detail = JevProtocol.detailPayload(input, "test", profile)
        val answer = JevFixtures.reply(detail, mapOf("focus" to "promise_action", "action" to "fulfill"))
        val card = JevProtocol.parseDetail(answer, profile)
        assertTrue(card.detail.contains("现在更需要实际安排吗"))
        assertTrue(card.detail.contains("什么时候做"))
        assertFalse(card.detail.contains("所以呢？"))
        assertTrue(card.detail.contains("委屈 100%"))
        assertFalse(card.detail.contains("真实意图"))
        val ordinary = JevProtocol.parseDetail(JevFixtures.reply(detail, mapOf(
            "focus" to "promise_action", "reading_promise_action" to "ordinary")), profile)
        assertFalse(ordinary.detail.contains("该接行动了"))
        assertFalse(ordinary.detail.contains("建议："))
    }

    @Test fun `accepted repair suggests closure instead of more apologies`() {
        val profile = JevFixtures.profile("repair", "accepted")
        val detail = JevProtocol.detailPayload(input.copy(text = "这还差不多"), "test", profile)
        val mood = JevProtocol.parseDetail(JevFixtures.reply(detail, mapOf("focus" to "repair_accept", "action" to "fulfill")), profile)
        assertTrue(mood.detail.contains("约定的事准备怎么做"))
        assertFalse(mood.detail.contains("针对实际疏漏道歉"))
    }

    @Test fun `ambiguous reading shows only probabilities without an action`() {
        val profile = JevFixtures.profile()
        val detail = JevProtocol.detailPayload(input, "test", profile)
        val mood = JevProtocol.parseDetail(JevFixtures.reply(detail, mapOf(
            "focus" to "promise_action", "reading_promise_action" to "unclear")), profile)
        assertEquals(2, mood.detail.lines().size)
        assertTrue(mood.detail.contains("情绪："))
        assertFalse(mood.detail.contains("建议："))
        assertFalse(mood.detail.contains("该接行动了"))
    }

    @Test fun `invalid incomplete and inconsistent probabilities fail closed`() {
        val valid = JevFixtures.reply(JevProtocol.payload(input.text, "test"))
        val missing = JSONObject(valid).apply { getJSONObject("answers").remove("scene") }
        assertThrows(Exception::class.java) { JevProtocol.parseProfile(missing.toString()) }
        listOf(-0.1, 1.1).forEach { invalid ->
            val body = JSONObject(valid)
            body.getJSONObject("answers").getJSONObject("scene").put("confidence", invalid)
            assertThrows(Exception::class.java) { JevProtocol.parseProfile(body.toString()) }
        }
        val mismatched = JSONObject(valid)
        mismatched.getJSONObject("answers").getJSONObject("scene").put("choice", "daily")
        assertThrows(Exception::class.java) { JevProtocol.parseProfile(mismatched.toString()) }
        val unnormalized = JSONObject(valid)
        unnormalized.getJSONObject("answers").getJSONObject("emotion").getJSONObject("probabilities").put("calm", 0.5)
        assertThrows(Exception::class.java) { JevProtocol.parseProfile(unnormalized.toString()) }
    }

    @Test fun `unknown card interpretation or absent second round answer is rejected`() {
        val profile = JevFixtures.profile()
        val detail = JevProtocol.detailPayload(input, "test", profile)
        for (field in listOf("focus", "action", "reading_promise_action")) {
            val body = JSONObject(JevFixtures.reply(detail))
            body.getJSONObject("answers").getJSONObject(field).put("choice", "invented")
            assertThrows(Exception::class.java) { JevProtocol.parseDetail(body.toString(), profile) }
        }
        assertThrows(Exception::class.java) { JevProtocol.parseDetail("{}", profile) }
    }
}
