package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.hook.AnalysisCardContent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol checks, not measured live-model interpretation accuracy. */
class ExpressionSignalsTest {
    private val input = AnalysisInput("行行行你最厉害", "friend")
    private fun response(payload: JSONObject, key: String, probability: Double, confidence: Double = 1.0): String {
        val body = JSONObject(JevFixtures.reply(payload))
        body.getJSONObject("answers").put(key, JSONObject().put("type", "choice")
            .put("choice", if (probability >= 0.5) "signal" else "ordinary").put("confidence", confidence)
            .put("probabilities", JSONObject().put("signal", probability).put("ordinary", 1.0 - probability).put("unknown", 0.0)))
        return body.toString()
    }

    @Test fun `independent expressions coexist and review uses fresh evidence`() {
        var calls = 0
        val mood = ChatAnalysis.analyze(input, "test", { payload ->
            calls++
            val suffix = if (calls == 2) "_review" else ""
            val questions = payload.getJSONObject("questions")
            ExpressionSignals.definitions.keys.forEach {
                assertEquals("choice", questions.getJSONObject("expression_$it$suffix").getString("type"))
            }
            JevFixtures.reply(payload, mapOf("expression_sarcastic$suffix" to "signal", "expression_repair$suffix" to "signal"))
        })
        assertEquals(2, calls)
        assertEquals(1.0, mood.expressions.getValue("反话"), 0.0)
        assertEquals(1.0, mood.expressions.getValue("递台阶"), 0.0)
        assertFalse(mood.emotions.containsKey("反话"))
        assertTrue(mood.detail.contains("表达（可能）：反话 100% · 递台阶 100%"))
        val profile = JevFixtures.profile()
        val review = JevProtocol.detailPayload(input, "test", profile)
        assertFalse(review.getJSONObject("state").getJSONObject("first_pass").toString().contains("expression"))
        val changed = JevProtocol.parseDetail(response(review, "expression_sarcastic_review", .8), profile)
        assertEquals(.8, changed.expressions.getValue("反话"), 0.0)
    }

    @Test fun `expressions never change template or action routing`() {
        val profile = JevFixtures.profile()
        val changed = profile.copy(expressionSignals = ExpressionSignals(mapOf("sarcastic" to 1.0)))
        assertEquals(ChatTemplates.candidates(profile), ChatTemplates.candidates(changed))
        assertEquals(ChatActions.candidates(profile), ChatActions.candidates(changed))
        assertEquals(JevProtocol.fallback(profile).emotions, JevProtocol.fallback(changed).emotions)
    }

    @Test fun `all parsers reject missing invalid or incomplete expression answers`() {
        val first = JevProtocol.payload(input, "test")
        val profile = JevProtocol.parseProfile(JevFixtures.reply(first))
        val review = JevProtocol.detailPayload(input, "test", profile)
        val emotion = JevProtocol.emotionPayload(input, "test")
        for ((payload, suffix, parse) in listOf(
            Triple(first, "", { s: String -> JevProtocol.parseProfile(s); Unit }),
            Triple(review, "_review", { s: String -> JevProtocol.parseDetail(s, profile); Unit }),
            Triple(emotion, "", { s: String -> JevProtocol.parseEmotion(s); Unit }))) {
            for (key in ExpressionSignals.definitions.keys) {
                val body = JSONObject(JevFixtures.reply(payload))
                body.getJSONObject("answers").remove("expression_$key$suffix")
                assertThrows(Exception::class.java) { parse(body.toString()) }
            }
            assertThrows(Exception::class.java) { parse(response(payload, "expression_sarcastic$suffix", 1.1)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ExpressionSignals(mapOf("sarcastic" to Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { ExpressionSignals(mapOf("made_up" to .8)) }
    }

    @Test fun `weak low confidence and unknown signals stay hidden and visible list is bounded`() {
        val payload = JevProtocol.emotionPayload(input, "test")
        for ((probability, confidence) in listOf(.4999 to 1.0, 1.0 to .34)) {
            assertFalse(JevProtocol.parseEmotion(response(payload, "expression_sarcastic", probability, confidence)).detail.contains("表达（可能）"))
        }
        assertTrue(JevProtocol.parseEmotion(response(payload, "expression_sarcastic", .5, .35)).detail.contains("反话 50%"))
        val signals = ExpressionSignals(ExpressionSignals.definitions.keys.associateWith { 1.0 })
        val mood = JevProtocol.fallback(JevFixtures.profile().copy(expressionSignals = signals))
        assertEquals(2, mood.detail.lines().single { it.startsWith("表达（可能）") }.split(" · ").size)
        assertEquals("表达（可能）：", AnalysisCardContent.lines(mood, CardDisplaySettings()).single { it.text.startsWith("表达（可能）") }.label)
    }

    @Test fun `LLM route preserves independent signals when intent is unavailable`() = runBlocking {
        val mood = IntentAnalysis.analyze(input, ApiSettings.fromInput("https://example.test", "key", model = "m"),
            IntentSettings(route = IntentRoute.LLM),
            { response(it, "expression_probing", .75) }, { error("must not call unconfigured LLM") })
        assertTrue(mood.intentFailed)
        assertEquals(.75, mood.expressions.getValue("试探"), 0.0)
    }

    @Test fun `persistent result roundtrip retains expressions and reads old payloads`() {
        val mood = JevProtocol.fallback(JevFixtures.profile().copy(expressionSignals = ExpressionSignals(mapOf("sarcastic" to .8))))
        val result = CachedAnalysisResult(mood, "synthetic evidence")
        assertEquals(result, CachedAnalysisResult.decode(result.encode()))
        val old = JSONObject(result.encode()).apply { remove("expressions") }
        assertTrue(CachedAnalysisResult.decode(old.toString()).mood.expressions.isEmpty())
    }
}
