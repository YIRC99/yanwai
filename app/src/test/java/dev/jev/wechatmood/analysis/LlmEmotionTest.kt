package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LlmEmotionTest {
    private val llm = ReplySettings.fromInput("https://api.deepseek.com/v1", "fake-key", "deepseek-chat")
    private val input = AnalysisInput("明天再说吧", "wxid_fiction", background = ContactBackground("虚构同事", "r1"))
    private val answer = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"emotion\":\"不明确\",\"intent\":\"可能想暂停\",\"concern\":\"无法判断\",\"tone\":\"信息不足\"}"}}]}"""
    private fun config(display: CardDisplaySettings = CardDisplaySettings()) = RuntimeSettings(1, false,
        ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, ""), "install", intent = IntentSettings(IntentRoute.LLM, llm),
        cardDisplay = display, emotion = EmotionSettings(EmotionSource.LLM))
    @Test fun `LLM route works without JEV key and performs exactly one request`() = runBlocking {
        var calls = 0
        val settings = config()
        assertTrue(settings.canAnalyze)
        val mood = AnalysisRouter.analyze(input, settings, { error("JEV must not be called") }, { _, payload ->
            calls++
            assertTrue(payload.toString().contains("虚构同事"))
            answer
        })
        assertEquals(1, calls); assertTrue(mood.emotions.isEmpty())
        assertTrue(mood.detail.contains("LLM 定性")); assertTrue(mood.detail.contains("可能在意："))
        assertEquals(mood.detail, CachedAnalysisResult.decode(CachedAnalysisResult(mood, "").encode()).mood.detail)
    }
    @Test fun `malformed incomplete and truncated responses fail without fabricated probabilities`() {
        listOf("{}", answer.replace("stop", "length"), answer.replace("不明确", "98%"),
            answer.replace("可能想暂停", "")).forEach { body ->
            assertThrows(IllegalStateException::class.java) { LlmEmotionProtocol.parse(body) }
        }
    }
    @Test fun `cancellation never falls back and retry is explicit`() = runBlocking {
        var calls = 0
        try { AnalysisRouter.analyze(input, config(), { error("no fallback") }, { _, _ -> calls++; throw CancellationException() }); fail() }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
        val mood = AnalysisRouter.analyze(input, config(), { error("no fallback") }, { _, _ -> calls++; answer })
        assertFalse(mood.intentFailed); assertEquals(2, calls)
    }
    @Test fun `defaults preserve JEV and display changes do not invalidate analysis`() {
        assertEquals(EmotionSource.JEV, EmotionSettings.load { null }.source)
        assertTrue(config().sameAnalysis(config(CardDisplaySettings(false, false, false))))
        val stable = input.copy(accountScope = AnalysisCacheKey.digest("a"), messageId = 1, createdAt = 10)
        assertNotEquals(AnalysisCacheKey.of(stable, config()), AnalysisCacheKey.of(stable.copy(background = ContactBackground("new", "r2")), config()))
    }
    @Test fun `known provider disables thinking but compatible unknown provider gets no guessed parameter`() {
        assertEquals("disabled", LlmEmotionProtocol.payload(input, llm).getJSONObject("thinking").getString("type"))
        val unknown = ReplySettings.fromInput("https://custom.example/v1", "k", "deepseek-chat")
        assertFalse(LlmEmotionProtocol.payload(input, unknown).has("thinking"))
    }
    @Test fun `reloading page history preserves completed LLM analysis for the same message`() {
        val stable = input.copy(accountScope = AnalysisCacheKey.digest("a"), messageId = 1, createdAt = 10)
        assertEquals(AnalysisCacheKey.of(stable, config()),
            AnalysisCacheKey.of(stable.copy(context = listOf(ContextMessage("我", "明天需要答复"))), config()))
    }
    @Test fun `no instructions or background are added to unsupported JEV state`() {
        assertFalse(JevProtocol.payload(input, "jev").toString().contains("虚构同事"))
        assertFalse(JevProtocol.emotionPayload(input, "jev").toString().contains("contact_background"))
    }
    @Test fun `LLM card toggles only project detail without losing complete result`() {
        val mood = LlmEmotionProtocol.parse(answer)
        val lines = dev.jev.wechatmood.hook.AnalysisCardContent.lines(mood, CardDisplaySettings(false, false, false))
        assertTrue(lines.any { it.text.contains("LLM 定性") })
        assertFalse(lines.any { it.text.startsWith("意图解析：") || it.text.startsWith("可能在意：") || it.text.startsWith("情绪倾向：") })
        assertTrue(CachedAnalysisResult.decode(CachedAnalysisResult(mood, "").encode()).mood.detail.contains("可能在意："))
    }
    @Test fun `explicit reuse follows reply config without modifying old intent selection`() {
        val api = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "")
        val configured = RuntimeSettings(1, false, api, "install", reply = llm,
            intent = IntentSettings(IntentRoute.JEV), emotion = EmotionSettings(EmotionSource.LLM, true))
        assertTrue(configured.canAnalyze); assertSame(llm, configured.emotionLlm)
        assertEquals(IntentRoute.JEV, configured.intent.route)
        val changed = RuntimeSettings(2, false, api, "install",
            reply = ReplySettings.fromInput(llm.endpoint, llm.apiKey, "other-model"),
            emotion = EmotionSettings(EmotionSource.LLM, true))
        assertFalse(configured.sameAnalysis(changed))
        val jevEdit = RuntimeSettings(3, false, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "unused-key"),
            "install", reply = llm, emotion = EmotionSettings(EmotionSource.LLM, true))
        assertTrue(configured.sameAnalysis(jevEdit))
    }
    @Test fun `official capability exceptions never get unsupported off switches`() {
        fun payload(endpoint: String, model: String) = LlmEmotionProtocol.payload(input, ReplySettings.fromInput(endpoint, "fake", model))
        assertEquals("none", payload("https://api.openai.com/v1", "gpt-5.4").getString("reasoning_effort"))
        assertFalse(payload("https://api.openai.com/v1", "gpt-6-astra").has("reasoning_effort"))
        assertFalse(payload("https://open.bigmodel.cn/api/paas/v4", "glm-5.3").has("thinking"))
        assertEquals("disabled", payload("https://open.bigmodel.cn/api/paas/v4", "glm-5.2").getJSONObject("thinking").getString("type"))
        assertEquals("disabled", payload("https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-1-pro-260628").getJSONObject("thinking").getString("type"))
        assertFalse(payload("https://ark.cn-beijing.volces.com/api/v3", "ep-unknown").has("thinking"))
    }
}
