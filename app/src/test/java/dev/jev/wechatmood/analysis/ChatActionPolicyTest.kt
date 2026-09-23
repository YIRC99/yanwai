package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import org.junit.Assert.*
import org.junit.Test

/** Synthetic first-pass decisions test engineering gates, not real Jev understanding. */
class ChatActionPolicyTest {
    private fun profile(text: String, choices: Map<String, String>) = JevProtocol.parseProfile(
        JevFixtures.reply(JevProtocol.payload(text, "test"), choices))

    @Test fun `first pass asks separately about target act advice commitment fault and new topic`() {
        val questions = JevProtocol.payload("承办方态度特别差", "test").getJSONObject("questions")
        for (key in listOf("target", "speech_act", "advice_need", "commitment", "own_fault", "new_topic")) {
            assertTrue("Missing $key", questions.has(key))
        }
    }

    @Test fun `third party complaint never offers personal apology or agreement fulfillment`() {
        val input = AnalysisInput("承办方态度特别差", "friend")
        val profile = profile(input.text, mapOf("scene" to "repair", "progress" to "explain",
            "target" to "third_party", "speech_act" to "vent", "advice_need" to "no",
            "commitment" to "none", "own_fault" to "no", "new_topic" to "no"))
        val questions = JevProtocol.detailPayload(input, "test", profile).getJSONObject("questions")
        assertFalse(questions.getJSONObject("focus").getJSONObject("criteria").has("repair_apology"))
        val actions = questions.getJSONObject("action").getJSONObject("criteria")
        assertTrue(actions.has("acknowledge_third_party"))
        assertFalse(actions.has("apologize"))
        assertFalse(actions.has("fulfill"))
        assertFalse(actions.has("offer_solution"))
    }

    @Test fun `conditional acceptance does not offer fulfillment or closure`() {
        val input = AnalysisInput("只要拿奖我都接受", "friend")
        val profile = profile(input.text, mapOf("scene" to "closing", "progress" to "accepted",
            "target" to "experience", "speech_act" to "share", "commitment" to "conditional",
            "own_fault" to "no", "new_topic" to "no"))
        val questions = JevProtocol.detailPayload(input, "test", profile).getJSONObject("questions")
        val actions = questions.getJSONObject("action").getJSONObject("criteria")
        assertFalse(actions.has("fulfill"))
        assertFalse(actions.has("goodbye"))
        assertFalse(questions.optJSONObject("focus")?.getJSONObject("criteria")?.has("closing_done") == true)
    }

    @Test fun `new topic still gets action selection when scene is other and progress says closing`() {
        val input = AnalysisInput("已经比完了，这两天就在外面玩了", "friend")
        var calls = 0
        val mood = ChatAnalysis.analyze(input, "test", { body ->
            calls++
            if (calls == 1) JevFixtures.reply(body, mapOf("scene" to "other", "progress" to "closing",
                "target" to "experience", "speech_act" to "share", "new_topic" to "yes",
                "commitment" to "none", "own_fault" to "no"))
            else {
                val actions = body.getJSONObject("questions").getJSONObject("action").getJSONObject("criteria")
                assertTrue(actions.has("follow_new_topic"))
                assertFalse(actions.has("goodbye"))
                assertFalse(actions.has("fulfill"))
                JevFixtures.reply(body, mapOf("focus" to "none", "action" to "follow_new_topic"))
            }
        })
        assertEquals(2, calls)
        assertTrue(mood.detail.contains("建议："))
        assertTrue(mood.detail.contains("新话题"))
        assertFalse(mood.detail.contains("照约定"))
    }

    @Test fun `action none does not inherit the selected template advice`() {
        val profile = JevFixtures.profile()
        val body = JevProtocol.detailPayload(AnalysisInput("所以呢？", "friend"), "test", profile)
        val result = JevProtocol.parseDetail(JevFixtures.reply(body,
            mapOf("focus" to "promise_action", "action" to "none")), profile)
        assertTrue(result.detail.contains("现在更需要实际安排吗"))
        assertFalse(result.detail.contains("建议："))
    }

    @Test fun `sharing good news can be acknowledged or celebrated without another question`() {
        val p = profile("拿到奖学金了", mapOf("scene" to "daily", "emotion" to "happy",
            "progress" to "sharing", "speech_act" to "share", "commitment" to "none"))
        val ids = ChatActions.candidates(p).map { it.id }
        assertTrue(ids.contains("acknowledge_share"))
        assertTrue(ids.contains("celebrate"))
    }

    @Test fun `new invitation keeps invite interpretations despite preceding event ending`() {
        val p = profile("比赛结束了，明晚一起吃饭吗", mapOf("scene" to "invite", "progress" to "closing",
            "speech_act" to "question", "new_topic" to "yes", "commitment" to "none"))
        assertTrue(ChatTemplates.candidates(p).any { it.id == "invite_probe" })
    }

    @Test fun `accepting an explanation permits relief without inventing an outstanding agreement`() {
        val p = profile("明白了，是我误会你了，这事说清了", mapOf("scene" to "repair", "progress" to "accepted",
            "speech_act" to "confirm", "target" to "none", "commitment" to "none", "new_topic" to "no"))
        assertTrue(ChatTemplates.candidates(p).any { it.id == "repair_accept" })
        assertFalse(ChatActions.candidates(p).any { it.id == "fulfill" || it.id == "apologize" })
        assertTrue(ChatActions.candidates(p).any { it.id == "acknowledge" })
    }

    @Test fun `no interpretation candidates sends only a real action question`() {
        val input = AnalysisInput("申请表在哪里下载", "friend")
        val p = profile(input.text, mapOf("scene" to "other", "progress" to "clarify",
            "speech_act" to "question", "commitment" to "none", "new_topic" to "no"))
        val body = JevProtocol.detailPayload(input, "test", p)
        assertEquals(setOf("action"), body.getJSONObject("questions").keys().asSequence().toSet())
        val result = JevProtocol.parseDetail(JevFixtures.reply(body, mapOf("action" to "answer_question")), p)
        assertTrue(result.detail.contains("建议：先回答"))
    }

    @Test fun `no action candidates never asks a one option question`() {
        val p = profile("今天挺普通的", mapOf("scene" to "daily", "progress" to "sharing",
            "speech_act" to "unknown", "commitment" to "none", "new_topic" to "unknown"))
        val body = JevProtocol.detailPayload(AnalysisInput("今天挺普通的", "friend"), "test", p)
        assertFalse(body.getJSONObject("questions").has("action"))
        assertFalse(JevProtocol.parseDetail(JevFixtures.reply(body), p).detail.contains("建议："))
    }

    @Test fun `uncertain prerequisites do not unlock apology fulfillment or goodbye`() {
        val p = profile("嗯", mapOf("scene" to "repair", "progress" to "unknown",
            "target" to "listener", "own_fault" to "yes", "speech_act" to "goodbye", "commitment" to "accepted"))
        val uncertain = p.copy(facts = p.facts.mapValues { (_, decision) -> decision.copy(confidence = 0.1) })
        assertTrue(ChatActions.candidates(uncertain).isEmpty())
    }
}
