package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import org.json.JSONObject
import java.util.concurrent.CancellationException

object IntentAnalysis {
    suspend fun analyze(input: AnalysisInput, jev: ApiSettings, intent: IntentSettings,
        exchangeJev: suspend (JSONObject) -> String, exchangeLlm: suspend (JSONObject) -> IntentReading,
        shouldContinue: () -> Boolean = { true }, onEmotion: (Mood) -> Unit = {}): Mood {
        fun checkActive() { if (!shouldContinue()) throw CancellationException("分析已停止") }
        checkActive()
        if (intent.route == IntentRoute.JEV) return ChatAnalysis.analyzeSuspending(input, jev.model, exchangeJev, shouldContinue)
        val emotion = JevProtocol.parseEmotion(exchangeJev(JevProtocol.emotionPayload(input, jev.model)))
        checkActive()
        onEmotion(emotion)
        if (!intent.llm.isConfigured) return emotion.copy(intentFailed = true,
            detail = emotion.detail + "\n\n通用大模型尚未配置，请到言外的「聊天分析」中保存意图模型配置。")
        return try {
            val reading = exchangeLlm(IntentProtocol.payload(input, intent.llm))
            checkActive()
            emotion.copy(label = "意图解析", detail = emotion.detail + "\n\n通用大模型 · 推测\n" + reading.display())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            checkActive()
            emotion.copy(intentFailed = true, detail = emotion.detail + "\n\n通用大模型解读失败，已保留 JEV 情绪。\n点击卡片重试，或到设置检测意图模型。")
        }
    }
}
