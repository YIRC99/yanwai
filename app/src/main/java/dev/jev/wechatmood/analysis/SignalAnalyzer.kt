package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import kotlinx.coroutines.*
import dev.jev.wechatmood.hook.NativeVoiceBridge
import dev.jev.wechatmood.voice.VoicePreparation
import java.util.concurrent.ConcurrentHashMap

object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = JevHttpClient()
    private val llmClient = dev.jev.wechatmood.reply.ReplyHttpClient()
    private class Stage(@Volatile var text: String)
    private val stages = ConcurrentHashMap<String, Stage>()
    private val queue = AnalysisQueue(scope, ModulePrefs::canAnalyze, { input ->
        ModulePrefs.requestReload()
        analyze(input) {
            ModulePrefs.requestReload()
            ModulePrefs.canAnalyze(input)
        }
    }, onComplete = { mood ->
        MoodLog.i("聊天解读完成：${mood.label}；intentFailed=${mood.intentFailed}")
        ModulePrefs.report(if (mood.intentFailed) "JEV 情绪已完成，意图解读未完成" else "聊天分析完成，已缓存 ${MoodStore.size()} 条")
    }, onFailure = { error ->
        MoodLog.e("分析失败：${error.message}")
        ModulePrefs.report("分析失败：${error.message}")
    })
    fun failure(key: String): String? = queue.failure(key)
    fun retryFailure(key: String) { MoodStore.retryIntent(key); queue.retryFailure(key); NativeVoiceBridge.retryFailures() }
    fun progress(key: String): String? = stages[key]?.text
    fun reconcile(visibleKeys: Set<String>) = queue.reconcile(visibleKeys)
    fun cancelConversation(talker: String) = queue.cancelConversation(talker)
    fun cancelAll() = queue.cancelAll()
    fun resetSettings() { queue.resetSettings(); stages.clear() }

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze(input)) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        queue.submit(input, stillVisible)
        return key
    }

    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(),
        speaker: String = "对方"): Mood {
        val settings = ModulePrefs.apiSettings()
        check(settings.isConfigured) { "请先填写 JEV API Key" }
        return ChatAnalysis.analyzeSuspending(AnalysisInput(text, "sample", context, speaker = speaker),
            settings.model, { client.exchangeSuspending(it, settings) })
    }

    private suspend fun analyze(input: AnalysisInput, shouldContinue: () -> Boolean = { true }): Mood = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        // Keep both rounds on the same endpoint and credential, even if settings change mid-request.
        val snapshot = requireNotNull(ModulePrefs.analysisSettings()) { "设置尚未连接" }
        val settings = snapshot.api
        fun active(): Boolean { job.ensureActive(); return shouldContinue() && ModulePrefs.analysisSettings()?.sameAnalysis(snapshot) == true }
        check(settings.isConfigured) { "请先在言外设置中填写并保存 API Key" }
        val stage = Stage("正在分析…")
        stages[input.key] = stage
        try {
            val total = input.context.count { it.voice != null } + if (input.voice != null) 1 else 0
            var completed = 0
            val prepared = VoicePreparation.analysis(input) { source ->
                job.ensureActive()
                if (!active()) throw CancellationException("分析已关闭或配置已改变")
                stage.text = "正在转写语音 ${++completed}/$total…"
                NativeVoiceBridge.transcribe(source, shouldContinue)
            }
            stage.text = "正在分析…"
            val mood = IntentAnalysis.analyze(prepared, settings, snapshot.intent,
                { client.exchangeSuspending(it, settings) },
                { llmClient.request(snapshot.intent.llm, it, IntentProtocol::parse) }, ::active,
                { emotion -> stage.text = emotion.detail.substringAfter('\n') + "\n\n通用大模型正在解读…" })
            val note = buildString {
                if (input.voice != null) append("\n\n语音转写：${prepared.text}\n（仅根据转写文字分析）")
                if (prepared.coverage.unavailableVoice > 0) append("\n前文有 ${prepared.coverage.unavailableVoice} 条语音未能转写，分析依据不完整。")
            }
            mood.copy(detail = mood.detail + note)
        } catch (e: org.json.JSONException) {
            throw IllegalStateException("模型返回不完整，本次不显示判断")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(if (input.voice != null) "语音转写过长或分析返回不完整，本次不显示判断" else "模型返回不完整，本次不显示判断")
        } finally {
            stages.remove(input.key, stage)
        }
    }

}
