package com.dogheadstrategist.chat.reply

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.AiStats
import com.dogheadstrategist.chat.feature.ThinkingMode
import com.dogheadstrategist.chat.feature.ThinkingPrefs
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** 连接体检结果：配置可用性、流式支持、模型是否仍在思考。 */
data class ConnectionProbe(
    val ok: Boolean,
    val error: String?,
    val latencyMs: Long,
    val reasoningChars: Int,
    val streamOk: Boolean,
    val streamLatencyMs: Long,
    val streamError: String?)

class ReplyHttpClient(private val client: Call.Factory = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun generate(settings: ReplySettings, context: ReplyContext, draft: String, direction: String,
        knowledge: String, previous: String = "", focusMessageId: Long? = null,
        relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, customRelationship: String = "",
        kind: AiStats.Kind = AiStats.Kind.REPLY): ReplySuggestion {
        check(settings.isConfigured) { "请先在狗头军师的「回复建议」中保存地址、API Key 和模型名" }
        val payload = ReplyProtocol.payload(settings, context, draft, direction, knowledge, previous, focusMessageId, relationship, customRelationship)
        return request(settings, payload, kind, ReplyProtocol::parse, ThinkingPrefs.replyForRequest())
    }

    /**
     * 我的风格：把样本提炼成「我怎么说话」的画像。
     *
     * 走和回复同一条 request 通道，所以超时、failover、token 统计都是现成的。
     * 思考开关不跟回复走——画像提炼是一次性长文本任务，思考对结果没有帮助，只会更慢。
     */
    suspend fun review(
        settings: ReplySettings,
        samples: List<String>,
        oldProfile: String,
        oldVocab: String,
    ): WarReviewProtocol.Result {
        val payload = WarReviewProtocol.payload(settings, samples, oldProfile, oldVocab)
        return request(settings, payload, AiStats.Kind.WAR_REVIEW, WarReviewProtocol::parse,
            ThinkingPrefs.warReviewForRequest())
    }

    /**
     * 通知摘要：把一条刚收到的消息压成一句话，替换微信那条通知。
     *
     * 和我的风格一样走 request 通道，超时、failover、token 统计都是现成的。
     * 思考对「一句话总结」没有帮助，只会更慢，所以不传 thinking。
     */
    suspend fun summarize(
        settings: ReplySettings,
        sender: String,
        text: String,
        maxChars: Int,
        maxLeft: Int,
        groupHint: Boolean? = null,
    ): CommunicateProtocol.Line {
        val payload = CommunicateProtocol.payload(settings, sender, text, maxChars, maxLeft, groupHint)
        val line = request(settings, payload, AiStats.Kind.COMMUNICATE, CommunicateProtocol::parse,
            ThinkingPrefs.communicateForRequest())
        // AI 偶尔不守字数，这里兜一道。标题绝不允许被名字撑爆。
        return line.copy(left = line.left.take(maxLeft))
    }

    /**
     * 通知代办：一次把多条通知送出去提取要事。
     *
     * 和通知摘要完全分开——它有自己的开关、自己的节流、自己的请求。
     * 两条路径互不引用，通知摘要改动不会波及这里，反之亦然。
     *
     * 返回值长度恒等于 [notices]：提取到的位置是草稿，模型判空的位置是空草稿，
     * 彻底解析失败的位置是 null。调用方据此决定哪些可以移出队列。
     */
    suspend fun extractBatch(
        settings: ReplySettings,
        notices: List<com.dogheadstrategist.chat.feature.PendingNotice>,
        types: List<com.dogheadstrategist.chat.feature.MatterType>,
        keyUsers: List<String> = emptyList(),
    ): List<AideProtocol.MatterDraft?> {
        if (notices.isEmpty()) return emptyList()
        val payload = AideProtocol.batchPayload(settings, notices, types, keyUsers)
        return request(
            settings, payload, AiStats.Kind.AIDE,
            parse = { body -> AideProtocol.parseBatch(body, notices.size) },
            thinkingEnabled = ThinkingPrefs.aideForRequest(),
        )
    }

    /**
     * 图片识别：一张图 → 最多一条要事。
     *
     * 和 [extractBatch] 走的是同一个 [request] 通道，所以超时、failover、
     * token 统计全是现成的，不需要另起一套。区别只有 payload 换成多模态结构，
     * 以及 count 固定为 1。
     *
     * 图片请求本身就比纯文本慢（要上传几百 KB），失败时退回备用线路的策略
     * 也和文本一致，不做特殊处理。
     */
    suspend fun extractPicture(
        settings: ReplySettings,
        types: List<com.dogheadstrategist.chat.feature.MatterType>,
        imageBase64: String,
        senderHint: String = "",
        keyUsers: List<String> = emptyList(),
    ): List<AideProtocol.MatterDraft?> {
        val payload = AideProtocol.picturePayload(settings, types, imageBase64, senderHint, keyUsers = keyUsers)
        return request(
            settings, payload, AiStats.Kind.AIDE,
            parse = { body -> AideProtocol.parseBatch(body, 1) },
            thinkingEnabled = ThinkingPrefs.aideForRequest(),
        )
    }

    /**
     * 一次拉取多条补全候选，供客户端做本地前缀匹配。
     *
     * [onCandidate] 非空时优先走流式：每解析出一行就回调一次，第一行到达即可显示。
     * 服务商忽略 stream 参数（返回整包 JSON）或流式失败时，自动退回整包请求，
     * 所以调用方不需要关心服务商是否支持流式。
     */
    suspend fun suggestRelay(settings: ReplySettings, context: ReplyContext, prefix: String, count: Int,
        onCandidate: ((String) -> Unit)? = null): List<String> {
        check(settings.isConfigured) { "请先配置回复模型" }
        if (onCandidate != null && System.currentTimeMillis() >= streamRetryAt) {
            val attempt = runCatching { streamSuggest(settings, context, prefix, count, onCandidate) }
            val streamed = attempt.getOrNull()
            if (!streamed.isNullOrEmpty()) return streamed
            // 用户继续打字会让请求被取消，这不是「不支持流式」，不能据此禁用。
            attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val reason = attempt.exceptionOrNull()
            // 「没解析出候选」多半是模型把 token 全花在思考上（content 一直为空），
            // 这不是服务端不支持流式，不该据此冷却两分钟——否则关掉思考也回不到快路径。
            val capabilityIssue = reason?.message?.contains("没有解析出候选") != true
            if (capabilityIssue) {
                streamRetryAt = System.currentTimeMillis() + STREAM_COOLDOWN_MS
            }
            MoodLog.i("RELAY_STREAM_FALLBACK 流式失败（" +
                (reason?.message ?: reason?.javaClass?.simpleName ?: "空响应") + "），" +
                if (capabilityIssue) "暂用整包请求" else "不冷却，下次仍试流式")
        }
        val payload = RelaySuggestProtocol.payload(settings, context, prefix, count)
        return request(settings, payload, AiStats.Kind.RELAY, RelaySuggestProtocol::parse, ThinkingPrefs.relayForRequest())
    }

    /** 逐行读取 SSE：模型每吐完一行就回调一次，避免等全部生成完。 */
    private suspend fun streamSuggest(settings: ReplySettings, context: ReplyContext, prefix: String,
        count: Int, onCandidate: (String) -> Unit): List<String> =
        suspendCancellableCoroutine { continuation ->
            val startedAt = System.nanoTime()
            val payload = RelaySuggestProtocol.streamPayload(settings, context, prefix, count)
            applyThinking(payload, ThinkingPrefs.relayForRequest())
            val call = client.newCall(Request.Builder().url(settings.endpoint)
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AiStats.record(AiStats.Kind.RELAY, (System.nanoTime() - startedAt) / 1_000_000, 0, 0, false)
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("连接超时或网络不可用，请重试"))
                }

                override fun onResponse(call: Call, response: Response) {
                    // 首发延时：第一条候选产生的时刻。定义在 runCatching 之外，
                    // 记录统计时才能读到它。
                    var firstMs = 0L
                    // 流式响应用量在最后一条 chunk 里（choices 为空，只有 usage）。
                    var usagePrompt = 0
                    var usageCompletion = 0
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) { httpError(it.code) }
                            val source = requireNotNull(it.body).source()
                            val collected = mutableListOf<String>()
                            val pending = StringBuilder()
                            fun emit(line: String) {
                                val text = line.trim()
                                if (text.isEmpty()) return
                                if (firstMs == 0L) firstMs = (System.nanoTime() - startedAt) / 1_000_000
                                collected += text
                                runCatching { onCandidate(text) }
                            }
                            // 不预判格式，逐行宽容解析：
                            //   ":" 开头 → SSE 注释/心跳，跳过
                            //   "data:" 开头 → 按 SSE 提取增量
                            //   其他 → 当作服务端忽略了 stream，按整包 JSON 累积
                            // 最后哪条路有结果就用哪条，避免被某个上游的格式差异卡死。
                            val rawBody = StringBuilder()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                val trimmed = line.trim()
                                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                                if (trimmed.startsWith("data:")) {
                                    val data = trimmed.removePrefix("data:").trim()
                                    if (data.isEmpty() || data == "[DONE]") continue
                                    extractUsage(data)?.let { (p, c) ->
                                        usagePrompt = p
                                        usageCompletion = c
                                    }
                                    val delta = extractDelta(data)
                                    if (delta.isNotEmpty()) {
                                        pending.append(delta)
                                        while (true) {
                                            val index = pending.indexOf("\n")
                                            if (index < 0) break
                                            val candidate = pending.substring(0, index)
                                            pending.delete(0, index + 1)
                                            emit(candidate)
                                        }
                                    }
                                } else if (rawBody.length < MAX_BODY) {
                                    rawBody.append(line).append('\n')
                                }
                            }
                            if (pending.isNotBlank()) emit(pending.toString())
                            // SSE 没吐出候选（上游可能自己包了一层），再拿整包内容兜底
                            if (collected.isEmpty() && rawBody.isNotBlank()) {
                                RelaySuggestProtocol.parse(rawBody.toString())
                                    .forEach { line -> emit(line) }
                            }
                            check(collected.isNotEmpty()) { "流式响应没有解析出候选" }
                            collected
                        }
                    }
                    AiStats.record(AiStats.Kind.RELAY, (System.nanoTime() - startedAt) / 1_000_000,
                        usagePrompt, usageCompletion, result.isSuccess, firstMs)
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    /** 开关打开时按用户选的片段关思考。开关是全局的（哪些功能关），片段主备各一份（怎么关）。 */
    private fun applyThinking(payload: org.json.JSONObject, enabled: Boolean, backup: Boolean = false) {
        if (!enabled) return
        if (backup) {
            ThinkingMode.apply(payload, ThinkingPrefs.templateBackupForRequest(), ThinkingPrefs.customBackupForRequest())
        } else {
            ThinkingMode.apply(payload, ThinkingPrefs.templateForRequest(), ThinkingPrefs.customForRequest())
        }
    }

    companion object {
        /** 流式失败后的冷却截止时间。冷却期间直接走整包，避免每次请求都白等一轮失败。 */
        @Volatile private var streamRetryAt = 0L
        private const val STREAM_COOLDOWN_MS = 120_000L
        /** 整包回退时最多累积多少字符，防止异常上游把内存拖爆。 */
        private const val MAX_BODY = 512 * 1024
    }


    /**
     * 连接体检：一次点击回答三个问题——配置通不通、流式可不可用、模型是否仍在思考。
     *
     * 流式单独测是因为它直接决定续话快慢：能用流式时灰字是逐行吐的，不能用就得等整包
     * 生成完。很多中转站「基本能用」但会忽略 stream 参数，不看这项永远查不出来。
     */
    suspend fun probeConnection(settings: ReplySettings, templateId: String, customJson: String): ConnectionProbe =
        withContext(Dispatchers.IO) {
            val appliedTemplate = templateId.takeIf { it.isNotBlank() && it != "none" }
            val base = runCatching { probeOnce(settings, appliedTemplate, customJson) }
            val stream = runCatching { probeStreamOnce(settings, templateId, customJson) }
            ConnectionProbe(
                ok = base.isSuccess,
                error = base.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                latencyMs = base.getOrNull()?.first ?: 0L,
                reasoningChars = base.getOrNull()?.second ?: 0,
                streamOk = stream.isSuccess,
                streamLatencyMs = stream.getOrNull() ?: 0L,
                streamError = stream.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
        }

    /** 流式探测：只等第一个增量到达，用来衡量续话能否走快路径。 */
    private fun probeStreamOnce(settings: ReplySettings, templateId: String, customJson: String): Long {
        val payload = org.json.JSONObject().apply {
            put("model", settings.model)
            put("stream", true)
            put("max_tokens", 200)
            put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("role", "user")
                put("content", "只说三个字：你好呀")
            }))
        }
        if (templateId.isNotBlank() && templateId != "none") ThinkingMode.apply(payload, templateId, customJson)
        val started = System.nanoTime()
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { httpError(response.code) }
            val source = requireNotNull(response.body).source()
            var received = 0
            var guard = 0
            while (guard++ < 300) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                if (!trimmed.startsWith("data:")) {
                    // 忽略 stream 直接回整包，说明这条快路径用不了
                    throw IllegalStateException("服务端忽略了 stream 参数，返回的是整包响应")
                }
                val data = trimmed.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                received += extractDelta(data).length
                if (received > 0) return (System.nanoTime() - started) / 1_000_000
            }
            throw IllegalStateException("流式响应没有收到任何增量")
        }
    }

    /**
     * 从 SSE 的一帧里取增量文本。各上游字段名不统一，按常见三种依次尝试：
     * delta.content（OpenAI 兼容）、delta.text（部分实现）、message.content（把非流式塞进 SSE）。
     */
    /**
     * 从流式 chunk 里取用量。
     *
     * 请求带了 stream_options.include_usage 后，服务端会在最后补一条
     * `data: {"choices":[],"usage":{...}}`。有的服务商干脆不支持，那就一直
     * 拿不到，返回 null，调用方保持 0——统计上会显示「用量未记录」而不是假装是 0。
     */
    private fun extractUsage(data: String): Pair<Int, Int>? = runCatching {
        val usage = org.json.JSONObject(data).optJSONObject("usage") ?: return@runCatching null
        val prompt = usage.optInt("prompt_tokens")
        val completion = usage.optInt("completion_tokens")
        if (prompt <= 0 && completion <= 0) null else Pair(prompt, completion)
    }.getOrNull()

    private fun extractDelta(data: String): String = runCatching {
        val json = org.json.JSONObject(data)
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return@runCatching ""
        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            delta.optString("content").ifEmpty { delta.optString("text") }
        } else {
            choice.optJSONObject("message")?.optString("content").orEmpty()
        }
    }.getOrDefault("")

    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "API Key 或模型权限不可用，请检查模型配置"
        402 -> "模型账户额度不足"
        429 -> "请求过于频繁，请稍后重试"
        400, 404, 422 -> "接口地址、模型或上下文长度不受支持，请检查配置"
        in 300..399 -> "接口发生重定向，请填写最终地址"
        else -> "模型服务暂不可用（HTTP $code）"
    }

    /** 用「用户已填定的角色名称 + 聊天记录」生成角色背景。 */
    suspend fun describeContact(settings: ReplySettings, context: ReplyContext, roleName: String): String {
        check(settings.isConfigured) { "请先配置回复模型" }
        require(roleName.isNotBlank()) { "请先填写角色名称" }
        val payload = ContactCardProtocol.payload(settings, context, roleName)
        applyThinking(payload, ThinkingPrefs.communicateForRequest())
        return request(settings, payload, AiStats.Kind.CARD, ContactCardProtocol::parse)
    }

    /**
     * 增量更新某个聊天对象的画像。
     *
     * 和 [describeContact] 走同一个统计口径（CARD），这样「人物档案到底花了多少」
     * 能在统计页里和角色卡一起看到，不用单独开一个分类。
     */
    suspend fun updateContactProfile(
        settings: ReplySettings,
        oldProfile: String,
        samples: List<com.dogheadstrategist.chat.feature.IntelMessage>,
        displayName: String,
        maxChars: Int,
    ): String {
        check(settings.isConfigured) { "请先配置回复模型" }
        require(displayName.isNotBlank()) { "缺少聊天对象名称" }
        require(samples.isNotEmpty()) { "没有可分析的消息" }
        val payload = ContactProfileProtocol.payload(settings, oldProfile, samples, displayName, maxChars)
        applyThinking(payload, ThinkingPrefs.intelForRequest())
        return request(settings, payload, AiStats.Kind.CARD, ContactProfileProtocol::parse)
    }

    suspend fun findTopics(settings: ReplySettings, context: ReplyContext, draft: String, notes: String,
        knowledge: String, relationship: ReplyRelationship, time: TopicTimeContext,
        previous: List<TopicSuggestion> = emptyList(), customRelationship: String = ""): List<TopicSuggestion> {
        check(settings.isConfigured) { "请先配置回复模型" }
        val payload = TopicProtocol.payload(settings, context, draft, notes, knowledge, relationship, time, previous, customRelationship)
        applyThinking(payload, ThinkingPrefs.topicsForRequest())
        return request(settings, payload, AiStats.Kind.TOPICS, TopicProtocol::parse)
    }

    /**
     * 单次探测。题干故意选容易引发推理的对比题，思考模型更愿意暴露推理输出；
     * 推理量 = reasoning_content 字符数 + usage 里的 reasoning_tokens。
     */
    private fun probeOnce(settings: ReplySettings, templateId: String?, customJson: String): Pair<Long, Int> {
        val payload = org.json.JSONObject().apply {
            put("model", settings.model)
            put("stream", false)
            put("max_tokens", 400)
            put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("role", "user")
                put("content", "9.11 和 9.9 哪个大？只回答 A 或 B：A 表示 9.11 大，B 表示 9.9 大")
            }))
        }
        if (templateId != null) ThinkingMode.apply(payload, templateId, customJson)
        val started = System.nanoTime()
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val elapsed = (System.nanoTime() - started) / 1_000_000
            check(response.isSuccessful) { httpError(response.code) }
            val body = requireNotNull(response.body).string()
            val json = org.json.JSONObject(body)
            val reasoningText = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("reasoning_content").orEmpty()
            val reasoningTokens = json.optJSONObject("usage")
                ?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", 0) ?: 0
            Pair(elapsed, reasoningText.length + reasoningTokens)
        }
    }

    /**
     * 带兜底的请求。
     *
     * 主模型最多等 [ReplySettings.failoverMs]，超时或失败就改用备用模型，
     * 备用再等 [ReplySettings.finalMs]，还不行才把错误抛出去。
     * 备用配置不完整（缺一项）时不做切换——切过去也必然失败。
     */
    internal suspend fun <T> request(settings: ReplySettings, payload: org.json.JSONObject,
        kind: AiStats.Kind, parse: (String) -> T, thinkingEnabled: Boolean = false): T {
        var primaryFailure: Throwable? = null
        try {
            val primaryPayload = org.json.JSONObject(payload.toString())
            applyThinking(primaryPayload, thinkingEnabled, backup = false)
            return requestOnce(settings, primaryPayload, kind, parse, settings.failoverMs)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!settings.hasBackup) throw error
            MoodLog.i("AI_FAILOVER kind=$kind reason=${error.message}")
            primaryFailure = error
        }
        // 备用从干净副本重建：主片段的字段不能残留，否则两套关闭参数会叠在一起。
        val backupPayload = org.json.JSONObject(payload.toString()).put("model", settings.backupModel)
        applyThinking(backupPayload, thinkingEnabled, backup = true)
        return try {
            requestOnce(settings.backup(), backupPayload, kind, parse, settings.finalMs)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            MoodLog.i("AI_FAILOVER_FAILED kind=$kind backup=${error.message}")
            // 两套都失败时，报告备用模型的错误——那是用户最后真正撞上的那一面。
            primaryFailure?.addSuppressed(error)
            throw error
        }
    }

    private suspend fun <T> requestOnce(settings: ReplySettings, payload: org.json.JSONObject,
        kind: AiStats.Kind, parse: (String) -> T, timeoutMs: Long): T =
        suspendCancellableCoroutine { continuation ->
            val startedAt = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000L
            // usage 只在响应体里出现，随解析一起读出；解析失败时保持 0。
            var promptTokens = 0
            var completionTokens = 0
            // 每档超时用一个派生 client：OkHttp 的超时是 client 级配置，不能按请求单独设。
            val activeClient = if (client is okhttp3.OkHttpClient && timeoutMs > 0) {
                client.newBuilder()
                    .connectTimeout(minOf(timeoutMs, 15_000L), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            } else client
            val call = activeClient.newCall(Request.Builder().url(settings.endpoint).header("Authorization", "Bearer ${settings.apiKey}")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AiStats.record(kind, elapsedMs(), 0, 0, false)
                    val message = if (e is java.io.InterruptedIOException) {
                        "模型超过 ${timeoutMs / 1000} 秒没有响应"
                    } else {
                        "连接超时或网络不可用，请重试"
                    }
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { when (it.code) {
                            401, 403 -> "API Key 或模型权限不可用，请检查模型配置"
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
                        runCatching {
                            val usage = org.json.JSONObject(body).optJSONObject("usage")
                            promptTokens = usage?.optInt("prompt_tokens") ?: 0
                            completionTokens = usage?.optInt("completion_tokens") ?: 0
                        }
                        parse(body)
                    } }.recoverCatching { error ->
                        if (error is IllegalStateException) throw error
                        throw IllegalStateException("读取回复失败，请检查网络后重试")
                    }
                    AiStats.record(kind, elapsedMs(), promptTokens, completionTokens, result.isSuccess)
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
}

