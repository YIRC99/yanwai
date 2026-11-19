package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.HttpClients
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ReplyHttpClient(private val client: Call.Factory = HttpClients.shared.newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()) {
    suspend fun generate(settings: ReplySettings, context: ReplyContext, draft: String, direction: String,
        knowledge: String, previous: String = "", focusMessageId: Long? = null,
        relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, customRelationship: String = "",
        onPartial: ((String) -> Unit)? = null): ReplySuggestion {
        check(settings.isConfigured) { "请先在言外的「回复建议」中保存地址、API Key 和模型名" }
        val payload = ReplyProtocol.payload(settings, context, draft, direction, knowledge, previous, focusMessageId,
            relationship, customRelationship, stream = true)
        return request(settings, payload, ReplyProtocol::parse, onPartial)
    }

    suspend fun findTopics(settings: ReplySettings, context: ReplyContext, draft: String, notes: String,
        knowledge: String, relationship: ReplyRelationship, time: TopicTimeContext,
        previous: List<TopicSuggestion> = emptyList(), customRelationship: String = ""): List<TopicSuggestion> {
        check(settings.isConfigured) { "请先配置回复模型" }
        return request(settings, TopicProtocol.payload(settings, context, draft, notes, knowledge, relationship, time, previous, customRelationship), TopicProtocol::parse)
    }

    internal suspend fun <T> request(settings: ReplySettings, payload: JSONObject, parse: (String) -> T,
        onPartial: ((String) -> Unit)? = null): T =
        try {
            once(settings, payload, parse, onPartial)
        } catch (error: IllegalStateException) {
            // 输出被截断说明 max_tokens 不够，放宽上限重试一次，比让用户手动点重试好。
            if (error.message?.contains("截断") == true) {
                once(settings, JSONObject(payload.toString()).put("max_tokens", 2400), parse, onPartial)
            } else if (payload.has("response_format")) {
                // 一些服务不支持 response_format，会直接 400；去掉再试。
                dev.jev.wechatmood.core.MoodLog.w("RESPONSE_FORMAT_UNSUPPORTED 去掉 response_format 重试")
                once(settings, JSONObject(payload.toString()).apply { remove("response_format") }, parse, onPartial)
            } else if (payload.has("stream_options")) {
                // 一些网关不认 stream_options，直接 400；去掉参数再试，不影响回复本身。
                dev.jev.wechatmood.core.MoodLog.w("REPLY_STREAM_OPTIONS_UNSUPPORTED 去掉 stream_options 重试")
                once(settings, JSONObject(payload.toString()).apply { remove("stream_options") }, parse, onPartial)
            } else if (hasImage(payload)) {
                // 模型不接受图片（常见 400）时退回纯文本，功能不至于整块失败。
                dev.jev.wechatmood.core.MoodLog.w("REPLY_FALLBACK_TEXT 模型不接受图片，已回退纯文本：${error.message?.take(60)}")
                once(settings, withoutImage(payload), parse, onPartial)
            } else throw error
        }

    private fun hasImage(payload: JSONObject): Boolean {
        val messages = payload.optJSONArray("messages") ?: return false
        for (index in 0 until messages.length()) {
            val content = messages.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (part in 0 until content.length()) {
                if (content.optJSONObject(part)?.optString("type") == "image_url") return true
            }
        }
        return false
    }

    /** 把多模态 user 消息还原成纯文本，作为模型不支持图片时的兜底。 */
    private fun withoutImage(payload: JSONObject): JSONObject {
        val copy = JSONObject(payload.toString())
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

    private suspend fun <T> once(settings: ReplySettings, payload: JSONObject, parse: (String) -> T,
        onPartial: ((String) -> Unit)?): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(settings.endpoint).header("Authorization", "Bearer ${settings.apiKey}")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("连接超时或网络不可用，请重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { reply ->
                            check(reply.isSuccessful) {
                                when (reply.code) {
                                    401, 403 -> "API Key 或模型权限不可用，请检查模型配置"
                                    402 -> "模型账户额度不足"
                                    429 -> "请求过于频繁，请稍后重试"
                                    400, 422 -> "上下文超出模型长度上限：把「参考最近」调小，或换上下文更大的模型"
                                    404 -> "接口地址或模型名不对，请检查回复模型配置"
                                    in 300..399 -> "接口发生重定向，请填写最终地址"
                                    else -> "模型服务暂不可用（HTTP ${reply.code}）"
                                }
                            }
                            val body = requireNotNull(reply.body) { "模型没有返回内容，请重试" }
                            val streaming = payload.optBoolean("stream") && body.contentType()?.toString()?.contains("event-stream") == true
                            if (streaming) streamBody(body, onPartial) else {
                                dev.jev.wechatmood.core.MoodLog.i("REPLY_STREAM off type=${body.contentType()}")
                                readWhole(body)
                            }
                        }.let(parse)
                    }.recoverCatching { error ->
                        if (error is IllegalStateException) throw error
                        throw IllegalStateException("读取回复失败，请检查网络后重试")
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    /** usage 只在服务返回时才有；流式拼出来的响应没有 usage，至少要记一次请求。 */
    private fun reportUsage(body: String) {
        val usage = runCatching { JSONObject(body).optJSONObject("usage") }.getOrNull()
        dev.jev.wechatmood.core.UsageReporter.report(
            usage?.optLong("prompt_tokens", 0L) ?: 0L,
            usage?.optLong("completion_tokens", 0L) ?: 0L)
    }

    private fun readWhole(body: ResponseBody): String {
        val source = body.source()
        source.request(1024 * 1024L + 1)
        check(source.buffer.size <= 1024 * 1024L) { "模型响应过长，请换一个模型后重试" }
        val text = source.readUtf8()
        reportUsage(text)
        return text
    }

    /**
     * SSE 逐段读取。第一个候选一旦完整就先回调给界面，不必等整组 3–5 条都生成完。
     * 返回体拼成普通 Chat Completions 响应，复用 [ReplyProtocol.parse] 与截断判断。
     */
    private fun streamBody(body: ResponseBody, onPartial: ((String) -> Unit)?): String {
        val source = body.source()
        val content = StringBuilder()
        var truncated = false
        var emitted = false
        var size = 0L
        val startedAt = System.currentTimeMillis()
        var firstAt = 0L
        var usagePrompt = 0L
        var usageCompletion = 0L
        while (true) {
            val line = source.readUtf8Line() ?: break
            size += line.length
            check(size <= 1024 * 1024L) { "模型响应过长，请换一个模型后重试" }
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
            json.optJSONObject("usage")?.let { usage ->
                usagePrompt = usage.optLong("prompt_tokens", 0L)
                usageCompletion = usage.optLong("completion_tokens", 0L)
            }
            val choices = json.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.optJSONObject(0) ?: continue
            if (choice.optString("finish_reason") == "length") truncated = true
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            val piece = delta?.optString("content").orEmpty()
            if (piece.isEmpty()) continue
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
            content.append(piece)
            if (!emitted) {
                firstReply(content)?.let { partial ->
                    emitted = true
                    runCatching { onPartial?.invoke(partial) }
                }
            }
        }
        check(content.isNotEmpty()) { "模型没有返回内容，请重试" }
        // 只记耗时和长度，不记内容：用于确认流式是否真的生效。
        dev.jev.wechatmood.core.MoodLog.i(
            "REPLY_STREAM ok chars=${content.length} first=${if (firstAt == 0L) -1 else firstAt - startedAt}ms total=${System.currentTimeMillis() - startedAt}ms usage=${usagePrompt}+${usageCompletion}")
        dev.jev.wechatmood.core.UsageReporter.report(usagePrompt, usageCompletion)
        return JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", content.toString()).put("refusal", JSONObject.NULL))
            .put("finish_reason", if (truncated) "length" else "stop"))).toString()
    }

    /** 从还没写完的 JSON 里取出第一个已经闭合的 replies 元素。 */
    companion object {
        internal fun firstReply(buffer: CharSequence): String? {
        val key = buffer.indexOf("\"replies\"")
        if (key < 0) return null
        var index = buffer.indexOf('[', key)
        if (index < 0) return null
        index++
        while (index < buffer.length && buffer[index].isWhitespace()) index++
        if (index >= buffer.length || buffer[index] != '"') return null
        index++
        val out = StringBuilder()
        while (index < buffer.length) {
            when (val ch = buffer[index]) {
                '\\' -> {
                    if (index + 1 >= buffer.length) return null
                    when (val next = buffer[index + 1]) {
                        'n', 'r', 't', 'b', 'f' -> out.append(' ')
                        'u' -> {
                            if (index + 5 >= buffer.length) return null
                            val code = buffer.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> out.append(next)
                    }
                    index += 2
                }
                '"' -> return out.toString().trim().takeIf { it.isNotEmpty() }
                else -> { out.append(ch); index++ }
            }
        }
            return null
        }
    }
}
