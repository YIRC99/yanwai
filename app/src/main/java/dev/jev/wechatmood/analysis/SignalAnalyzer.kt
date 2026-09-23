package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failureMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun failure(key: String): String? = failureMessages[key]

    fun submit(text: String, talker: String?, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze) return null
        val trimmed = text.trim().take(4000)
        if (trimmed.isEmpty()) return null
        val key = MoodStore.keyOf(trimmed, talker)
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < 30_000) return key
        if (!MoodStore.claim(key)) return key
        failureMessages.remove(key)
        scope.launch {
            try {
                slots.withPermit {
                    ModulePrefs.reload()
                    if (!ModulePrefs.canAnalyze || !stillVisible()) { MoodStore.release(key); return@withPermit }
                    val mood = requestMood(trimmed)
                    MoodStore.complete(key, mood)
                    failures.remove(key)
                    failureMessages.remove(key)
                    MoodLog.i("Jev 分析完成：${mood.label}，风险=${mood.risk}")
                    ModulePrefs.report("Jev 分析完成，已缓存 ${MoodStore.size()} 条")
                }
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

    suspend fun requestMood(text: String): Mood = withContext(Dispatchers.IO) {
        check(ModulePrefs.apiKey.isNotBlank()) { "安装包没有内置密钥" }
        val request = Request.Builder().url(ModulePrefs.apiBase)
            .header("Authorization", "Bearer ${ModulePrefs.apiKey}")
            .post(JevProtocol.payload(text, ModulePrefs.apiModel).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "密钥无效或模型未授权"
                        402 -> "模型账户额度不足"
                        429 -> "请求过于频繁，请稍后重试"
                        else -> "模型服务暂不可用"
                    }
                    throw IllegalStateException("$reason（HTTP ${response.code}）")
                }
                try { JevProtocol.parse(response.body?.string().orEmpty()) }
                catch (e: Exception) { throw IllegalStateException("模型返回不完整，本次不显示判断") }
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("连接超时或网络不可用，请稍后重试")
        }
    }
}
