package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.ReplySettings
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/** Every host entry (manual/automatic/retry) and the connection probe uses this route. No fallback. */
object AnalysisRouter {
    suspend fun analyze(input: AnalysisInput, settings: RuntimeSettings,
        exchangeJev: suspend (JSONObject) -> String, exchangeLlm: suspend (ReplySettings, JSONObject) -> String,
        shouldContinue: () -> Boolean = { true }, onEmotion: (Mood) -> Unit = {}): Mood {
        fun active() { if (!shouldContinue()) throw CancellationException("分析已停止或输入已改变") }
        active()
        check(settings.canAnalyze) { if (settings.emotion.source == EmotionSource.LLM) "请配置所选 LLM 模型" else "请配置 JEV API Key" }
        if (settings.emotion.source == EmotionSource.LLM) {
            val config = settings.emotionLlm
            val body = exchangeLlm(config, LlmEmotionProtocol.payload(input, config))
            active()
            return LlmEmotionProtocol.parse(body)
        }
        return IntentAnalysis.analyze(input, settings.api, settings.intent, exchangeJev,
            { IntentProtocol.parse(exchangeLlm(settings.intent.llm, it)) }, shouldContinue, onEmotion)
    }
}
