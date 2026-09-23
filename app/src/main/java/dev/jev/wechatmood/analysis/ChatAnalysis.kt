package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Mood
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Synchronous orchestration called on the IO dispatcher; transport is injected for offline verification. */
object ChatAnalysis {
    fun analyze(input: AnalysisInput, model: String, exchange: (JSONObject) -> String,
        shouldContinue: () -> Boolean = { true }): Mood {
        fun checkActive() { if (!shouldContinue()) throw CancellationException("分析已停止或消息不再可见") }
        checkActive()
        val profile = JevProtocol.parseProfile(exchange(JevProtocol.payload(input.text, model, input.context, input.speaker)))
        checkActive()
        if (ChatTemplates.candidates(profile).isEmpty() && ChatActions.candidates(profile).isEmpty()) {
            return JevProtocol.fallback(profile)
        }
        val detail = exchange(JevProtocol.detailPayload(input, model, profile))
        checkActive()
        return JevProtocol.parseDetail(detail, profile)
    }
}
