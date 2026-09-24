package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val client = JevHttpClient()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failureMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun failure(key: String): String? = failureMessages[key]
    fun retryFailure(key: String) { failures.remove(key); failureMessages.remove(key) }

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze(input.talker)) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < 30_000) return key
        if (!MoodStore.claim(key)) return key
        failureMessages.remove(key)
        scope.launch {
            try {
                slots.withPermit {
                    ModulePrefs.reload()
                    if (!ModulePrefs.canAnalyze(input.talker) || !stillVisible()) { MoodStore.release(key); return@withPermit }
                    val mood = analyze(input) {
                        ModulePrefs.reload()
                        ModulePrefs.canAnalyze(input.talker) && stillVisible()
                    }
                    MoodStore.complete(key, mood)
                    failures.remove(key)
                    failureMessages.remove(key)
                    MoodLog.i("Jev 闲聊解读完成：${mood.label}")
                    ModulePrefs.report("Jev 分析完成，已缓存 ${MoodStore.size()} 条")
                }
            } catch (e: CancellationException) {
                MoodStore.release(key)
                throw e
            } catch (e: Exception) {
                failures[key] = System.currentTimeMillis()
                failureMessages[key] = e.message ?: "分析失败，请稍后重试"
                MoodStore.release(key)
                MoodLog.e("分析失败：${e.message}")
                ModulePrefs.report("分析失败：${e.message}")
            }
        }
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
            ChatAnalysis.analyze(input, settings.model, { client.exchange(it, settings) }) {
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
