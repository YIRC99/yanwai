package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Mood
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Synchronous orchestration called on the IO dispatcher; transport is injected for offline verification. */
object ChatAnalysis {
    fun analyze(input: AnalysisInput, model: String, exchange: (JSONObject) -> String,
        shouldContinue: () -> Boolean = { true }): Mood = perform(input, model, exchange, shouldContinue)

    suspend fun analyzeSuspending(input: AnalysisInput, model: String, exchange: suspend (JSONObject) -> String,
        shouldContinue: () -> Boolean = { true }): Mood = perform(input, model, { exchange(it) }, shouldContinue)

    private inline fun perform(input: AnalysisInput, model: String, exchange: (JSONObject) -> String,
        shouldContinue: () -> Boolean): Mood {
        checkActive(shouldContinue())
        val snapshot = input.copy(context = input.context.toList())
        val profile = JevProtocol.parseProfile(exchange(JevProtocol.payload(snapshot, model)))
        checkActive(shouldContinue())
        if (ChatTemplates.candidates(profile).isEmpty() && ChatActions.candidates(profile).isEmpty()) {
            return JevProtocol.fallback(profile)
        }
        val detail = exchange(JevProtocol.detailPayload(snapshot, model, profile))
        checkActive(shouldContinue())
        return JevProtocol.parseDetail(detail, profile)
    }

    private fun checkActive(active: Boolean) {
        if (!active) throw CancellationException("分析已停止或消息不再可见")
    }
}
