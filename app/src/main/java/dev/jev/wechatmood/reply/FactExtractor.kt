package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 关系事实提取：一次请求把最近聊天压成几条稳定事实。
 *
 * 只用用户自己配置的回复模型；输入是最近 100 条、最多 6000 字，
 * 输出必须是 JSON 数组，越界或非法项直接丢弃，不做二次解释。
 */
class FactExtractor(private val client: ReplyHttpClient = ReplyHttpClient()) {
    suspend fun extract(settings: ReplySettings, messages: List<ReplyMessage>): List<RelationFact> {
        check(settings.isConfigured) { "请先配置回复模型" }
        val transcript = messages.filter { it.text.isNotBlank() && !it.text.startsWith("【引用") }
            .takeLast(MAX_MESSAGES)
            .joinToString("\n") { "${it.speaker}: ${it.text.take(80)}" }
            .take(MAX_CHARACTERS)
        check(transcript.isNotBlank()) { "没有可用的聊天内容" }
        val payload = JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 600).put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", INSTRUCTIONS))
                .put(JSONObject().put("role", "user").put("content", transcript)))
        return client.request(settings, payload, ::parse)
    }

    internal fun parse(body: String): List<RelationFact> = runCatching {
        val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return@runCatching emptyList()
        val array = JSONArray(content.substring(start, end + 1))
        (0 until minOf(array.length(), MAX_FACTS))
            .mapNotNull { array.optJSONObject(it)?.let(RelationFact::decode) }
            .distinctBy { it.kind to it.text }
    }.onFailure { MoodLog.w("FACT_PARSE_FAILED ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    companion object {
        const val MAX_MESSAGES = 100
        const val MAX_CHARACTERS = 6000
        const val MAX_FACTS = 12
        private const val INSTRUCTIONS = """你是资料整理助手。从聊天记录里提取关于「对方」的稳定事实，只输出 JSON 数组。
kind 只能是：工作、城市、家人、喜好、忌口、宠物、重要日期、约定、称呼。
规则：
1. 只写对方明确说过或明确确认过的内容，不推测、不总结情绪、不编造。
2. 一条不超过 30 个字，写成陈述句，例如「在游戏公司做运营」。
3. 同一件事只写一次；没有可提取的内容就输出 []。
4. 不要输出任何解释或 Markdown。
示例：[{"kind":"工作","text":"在游戏公司做运营","confidence":0.8}]"""
    }
}
