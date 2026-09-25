package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import kotlinx.coroutines.*

object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = JevHttpClient()
    private val queue = AnalysisQueue(scope, ModulePrefs::canAnalyze, { input ->
        ModulePrefs.requestReload()
        analyze(input) {
            ModulePrefs.requestReload()
            ModulePrefs.canAnalyze(input)
        }
    }, onComplete = { mood ->
        MoodLog.i("Jev 闲聊解读完成：${mood.label}")
        ModulePrefs.report("Jev 分析完成，已缓存 ${MoodStore.size()} 条")
    }, onFailure = { error ->
        MoodLog.e("分析失败：${error.message}")
        ModulePrefs.report("分析失败：${error.message}")
    })
    fun failure(key: String): String? = queue.failure(key)
    fun retryFailure(key: String) = queue.retryFailure(key)
    fun reconcile(visibleKeys: Set<String>) = queue.reconcile(visibleKeys)
    fun cancelConversation(talker: String) = queue.cancelConversation(talker)
    fun cancelAll() = queue.cancelAll()

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze(input)) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        queue.submit(input, stillVisible)
        return key
    }

    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(),
        speaker: String = "对方"): Mood = analyze(AnalysisInput(text, "sample", context, speaker = speaker))

    private suspend fun analyze(input: AnalysisInput, shouldContinue: () -> Boolean = { true }): Mood = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        // Keep both rounds on the same endpoint and credential, even if settings change mid-request.
        val settings = ModulePrefs.apiSettings()
        check(settings.isConfigured) { "请先在言外设置中填写并保存 API Key" }
        try {
            ChatAnalysis.analyzeSuspending(input, settings.model, { client.exchangeSuspending(it, settings) }) {
                job.ensureActive()
                shouldContinue()
            }
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        }
    }

}
