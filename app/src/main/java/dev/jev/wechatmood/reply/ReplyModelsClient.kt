package dev.jev.wechatmood.reply

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ReplyModelsClient(private val client: Call.Factory = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun list(settings: ReplySettings): List<String> {
        require(settings.endpoint.isNotBlank() && settings.apiKey.isNotBlank()) { "请先填写 API 地址和 Key" }
        val url = settings.endpoint.removeSuffix("/chat/completions") + "/models"
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${settings.apiKey}").get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("获取列表超时或网络不可用，请重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { when (it.code) {
                            401, 403 -> "API Key 无效或没有模型列表权限，请检查当前供应商的 Key"
                            404, 405, 501 -> "此接口不提供标准模型列表。可手动填写模型 ID，或选择参考模型后检测回复。"
                            429 -> "获取列表过于频繁，请稍后重试"
                            in 300..399 -> "列表接口发生重定向，请在自定义中填写最终地址"
                            else -> "获取模型列表失败（HTTP ${it.code}），可稍后重试或手动填写模型 ID"
                        } }
                        val source = requireNotNull(it.body).source()
                        source.request(1024 * 1024L + 1)
                        check(source.buffer.size <= 1024 * 1024L) { "模型列表过大，请手动填写模型 ID" }
                        parse(source.readUtf8())
                    } }.recoverCatching { error ->
                        if (error is IllegalStateException) throw error
                        throw IllegalStateException("读取模型列表失败，请重试或手动填写模型 ID")
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
    companion object {
        fun parse(body: String): List<String> {
            val root = runCatching { JSONObject(body) }.getOrNull()
            check(root != null && !root.has("error")) { "服务未返回有效模型列表，可手动填写模型 ID 后检测回复" }
            val data = root.optJSONArray("data")
            check(data != null) { "服务返回的列表格式不兼容，可手动填写模型 ID 后检测回复" }
            check(data.length() <= 10000) { "模型列表过大，请手动填写模型 ID" }
            val ids = (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val id = item.opt("id") as? String ?: return@mapNotNull null
                if (id.isBlank() || id.length > 256 || id.any { it.isWhitespace() || it.isISOControl() }) return@mapNotNull null
                val output = item.optJSONArray("output_modalities")
                if (output != null && output.length() > 0 && (0 until output.length()).none { output.optString(it) == "text" }) return@mapNotNull null
                val name = id.lowercase(Locale.ROOT)
                if (listOf("embedding", "rerank", "whisper", "dall-e", "moderation", "realtime", "transcribe", "sora", "tts", "asr")
                        .any { token -> name.split('-', '_', '/', '.').contains(token) || name.startsWith(token) } ||
                    name.startsWith("gpt-image") || name.startsWith("doubao-seedream") || name.startsWith("doubao-seedance")) return@mapNotNull null
                id
            }.distinct().sorted()
            check(ids.isNotEmpty()) { "列表中没有找到可选的文本模型，可手动填写模型 ID 后检测回复" }
            return ids
        }
    }
}
