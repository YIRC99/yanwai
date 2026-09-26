package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.ReplySettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntentAnalysisTest {
    private val input = AnalysisInput("睡吧睡吧", "friend", listOf(ContextMessage("我", "累了一天，躺下了")))
    private val llm = ReplySettings.fromInput("https://example.com/v1", "secret", "model")
    private fun response(content: String, finish: String = "stop") = JSONObject().put("choices", org.json.JSONArray()
        .put(JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString()
    private val content = """{"intent":"可能是在道晚安","concern":"可能关心你累了想休息","tone":"表达平和，没有明显责备线索"}"""

    @Test fun `LLM route uses only Jev emotion and adds grounded prose without reply advice`() = runBlocking {
        var jevCalls = 0
        var llmCalls = 0
        val mood = IntentAnalysis.analyze(input, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"),
            IntentSettings(IntentRoute.LLM, llm), { payload ->
                jevCalls++
                assertEquals(setOf("emotion"), payload.getJSONObject("questions").keySet())
                JevFixtures.reply(payload)
            }, { payload ->
                llmCalls++
                val evidence = JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))
                assertEquals(input.text, evidence.getString("message"))
                assertEquals(1, evidence.getJSONArray("context").length())
                IntentProtocol.parse(response(content))
            })
        assertEquals(1, jevCalls); assertEquals(1, llmCalls)
        assertTrue(mood.detail.contains("委屈 100%"))
        assertTrue(mood.detail.contains("可能在意"))
        assertFalse(mood.detail.contains("建议："))
    }

    @Test fun `Jev route never sends chat to LLM`() = runBlocking {
        IntentAnalysis.analyze(input, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"),
            IntentSettings(), { JevFixtures.reply(it) }, { error("must not call LLM") })
        Unit
    }

    @Test fun `unconfigured LLM preserves emotion and sends no LLM request`() = runBlocking {
        val mood = IntentAnalysis.analyze(input, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"),
            IntentSettings(IntentRoute.LLM), { JevFixtures.reply(it) }, { error("must not call") })
        assertTrue(mood.intentFailed)
        assertEquals(1.0, mood.emotions["委屈"]!!, 0.001)
        assertTrue(mood.detail.contains("尚未配置"))
    }

    @Test fun `network coroutine cancellation cannot become a cached partial result`() = runBlocking {
        try {
            IntentAnalysis.analyze(input, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"),
                IntentSettings(IntentRoute.LLM, llm), { JevFixtures.reply(it) }, { throw kotlinx.coroutines.CancellationException("left chat") })
            fail("must cancel")
        } catch (_: java.util.concurrent.CancellationException) { }
    }

    @Test fun `LLM failure keeps emotion and gives explicit retry instead of fabricated interpretation`() = runBlocking {
        val mood = IntentAnalysis.analyze(input, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"),
            IntentSettings(IntentRoute.LLM, llm), { JevFixtures.reply(it) }, { error("secret response body") })
        assertTrue(mood.intentFailed)
        assertTrue(mood.detail.contains("情绪："))
        assertFalse(mood.detail.contains("secret"))
    }

    @Test fun `cancellation after emotion prevents LLM request`() = runBlocking {
        var active = true
        try {
            IntentAnalysis.analyze(input, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"),
                IntentSettings(IntentRoute.LLM, llm), { active = false; JevFixtures.reply(it) },
                { error("must not call LLM") }, { active })
            fail("must cancel")
        } catch (_: java.util.concurrent.CancellationException) { }
    }

    @Test fun `invalid truncated and non text model output is rejected`() {
        for (body in listOf(response(content, "length"), response("{}"), response(content.replace("\"可能是在道晚安\"", "123")),
            response(content.replace("可能是在道晚安", "x".repeat(401))))) {
            assertThrows(IllegalStateException::class.java) { IntentProtocol.parse(body) }
        }
        assertEquals("可能是在道晚安", IntentProtocol.parse(response(content)).intent)
    }
}
