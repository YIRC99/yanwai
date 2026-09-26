package dev.jev.wechatmood.reply

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ReplyHttpClient(private val client: Call.Factory = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
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

    private suspend fun <T> request(settings: ReplySettings, payload: org.json.JSONObject, parse: (String) -> T): T =
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
                            401, 403 -> "API Key 或模型权限不可用，请检查回复配置"
                            402 -> "模型账户额度不足"
                            429 -> "请求过于频繁，请稍后重试"
                            400, 404, 422 -> "接口地址、模型或上下文长度不受支持，请检查配置"
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
