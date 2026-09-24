package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.JevProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

/** Opt-in paid network checks: -PjevLiveTest=true. Credentials come only from the process environment. */
class LiveJevVerificationTest {
    @Test fun openRouter() = verify(JevProvider.OPENROUTER, "OPENROUTER_API_KEY")
    @Test fun vercel() = verify(JevProvider.VERCEL, "AI_GATEWAY_API_KEY")

    private fun verify(provider: JevProvider, env: String) {
        val key = System.getenv(env).orEmpty()
        check(key.isNotBlank()) { "Missing $env for explicit live verification" }
        val settings = ApiSettings.fromInput("", key, provider.id)
        val client = JevHttpClient()
        val samples = listOf(
            AnalysisInput("今天终于拿到奖学金了，好开心！", "synthetic-sharing"),
            AnalysisInput("这还差不多。", "synthetic-plans", listOf(
                ContextMessage("对方", "你是不是忘了周末吃饭的事？"),
                ContextMessage("我", "记得，这次我来安排，明天把餐厅和时间告诉你。"))),
            AnalysisInput("今天又被客户无缘无故骂了一顿，真的好委屈。", "synthetic-venting"),
        )
        var totalCalls = 0
        var twoRoundCases = 0
        for ((index, input) in samples.withIndex()) {
            var calls = 0
            val start = System.nanoTime()
            try {
                val mood = ChatAnalysis.analyze(input, settings.model, { payload ->
                    calls++
                    totalCalls++
                    assertEquals(settings.model, payload.getString("model"))
                    val body = client.exchange(payload, settings)
                    val response = JSONObject(body)
                    val answers = response.getJSONObject("answers")
                    val questions = payload.getJSONObject("questions")
                    assertEquals(questions.length(), answers.length())
                    assertTrue("Response leaked credential", !body.contains(key))
                    println("LIVE ${provider.id} sample=${index + 1} round=$calls answers=${answers.length()} model=${response.optString("model")}")
                    body
                })
                assertTrue(mood.detail.contains("情绪："))
                if (calls == 2) twoRoundCases++
                println("LIVE ${provider.id} sample=${index + 1} PASS calls=$calls elapsedMs=${(System.nanoTime() - start) / 1_000_000} label=${mood.label}")
            } catch (e: IllegalStateException) {
                if (provider == JevProvider.VERCEL && e.message.orEmpty().contains("账户尚未验证")) {
                    println("LIVE vercel BLOCKED customer_verification_required; inference NOT verified")
                    assumeNoException("Vercel account verification required; not an inference pass", e)
                }
                throw e
            }
        }
        assertTrue("No two-round analysis was exercised", twoRoundCases > 0)
        println("LIVE ${provider.id} COMPLETE samples=${samples.size} calls=$totalCalls twoRoundCases=$twoRoundCases")
    }
}
