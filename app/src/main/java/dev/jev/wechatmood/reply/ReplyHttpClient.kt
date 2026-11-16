package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.HttpClients
import dev.jev.wechatmood.core.MoodLog
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ReplyHttpClient(private val client: Call.Factory = HttpClients.shared.newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()) {
    suspend fun generate(settings: ReplySettings, context: ReplyContext, draft: String, direction: String,
        knowledge: String, previous: String = "", focusMessageId: Long? = null,
        relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, customRelationship: String = ""): ReplySuggestion {
        check(settings.isConfigured) { "请先在言外的「回复建议」中保存地址、API Key 和模型名" }
        val payload = ReplyProtocol.payload(settings, context, draft, direction, knowledge, previous, focusMessageId, relationship, customRelationship)
        return request(settings, payload, ReplyProtocol::parse)
    }

    suspend fun findTopics(settings: ReplySettings, context: ReplyContext, draft: String, notes: String,
        knowledge: String, relationship: ReplyRelationship, time: TopicTimeContext,
        previous: List<TopicSuggestion> = emptyList(), customRelationship: String = ""): List<TopicSuggestion> {
        check(settings.isConfigured) { "请先配置回复模型" }
        return request(settings, TopicProtocol.payload(settings, context, draft, notes, knowledge, relationship, time, previous, customRelationship), TopicProtocol::parse)
    }

    internal suspend fun <T> request(settings: ReplySettings, payload: org.json.JSONObject, parse: (String) -> T): T =
        try {
            once(settings, payload, parse)
        } catch (error: IllegalStateException) {
            // 输出被截断说明 max_tokens 不够，放宽上限重试一次，比让用户手动点重试好。
            if (error.message?.contains("截断") == true) {
                once(settings, org.json.JSONObject(payload.toString()).put("max_tokens", 2400), parse)
            } else if (hasImage(payload)) {
                // 模型不接受图片（常见 400）时退回纯文本，功能不至于整块失败。
                MoodLog.w("REPLY_FALLBACK_TEXT 模型不接受图片，已回退纯文本：${error.message?.take(60)}")
                once(settings, withoutImage(payload), parse)
            } else throw error
        }

    private fun hasImage(payload: org.json.JSONObject): Boolean {
        val messages = payload.optJSONArray("messages") ?: return false
        for (index in 0 until messages.length()) {
            val content = messages.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (part in 0 until content.length()) {
                if (content.optJSONObject(part)?.optString("type") == "image_url") return true
            }
        }
        return false
    }

    /** 把多模态 user 消息还原成纯文本，作为不支持图片时的兜底。 */
    private fun withoutImage(payload: org.json.JSONObject): org.json.JSONObject {
        val copy = org.json.JSONObject(payload.toString())
        val messages = copy.optJSONArray("messages") ?: return copy
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val content = message.optJSONArray("content") ?: continue
            val text = (0 until content.length()).mapNotNull { part ->
                content.optJSONObject(part)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.joinToString("\n")
            message.put("content", text)
        }
        return copy
    }

    private suspend fun <T> once(settings: ReplySettings, payload: org.json.JSONObject, parse: (String) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(settings.endpoint).header("Authorization", "Bearer ${settings.apiKey}")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("连接超时或网络不可用，请重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { when (it.code) {
                            401, 403 -> "API Key 或模型权限不可用，请检查模型配置"
                            402 -> "模型账户额度不足"
                            429 -> "请求过于频繁，请稍后重试"
                            400, 422 -> "上下文超出模型长度上限：把「参考最近」调小，或换上下文更大的模型"
                            404 -> "接口地址或模型名不对，请检查回复模型配置"
                            in 300..399 -> "接口发生重定向，请填写最终地址"
                            else -> "模型服务暂不可用（HTTP ${it.code}）"
                        } }
                        val source = requireNotNull(it.body).source()
                        source.request(1024 * 1024L + 1)
                        check(source.buffer.size <= 1024 * 1024L) { "模型响应过长，请换一个模型后重试" }
                        val body = source.readUtf8()
                        parse(body)
                    } }.recoverCatching { error ->
                        if (error is IllegalStateException) throw error
                        throw IllegalStateException("读取回复失败，请检查网络后重试")
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
}
