package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject

/** 合并转发的摘要请求：只要一段纯文本要点，不强制 JSON，兼容性最好。 */
object ForwardDigestProtocol {
    fun payload(settings: ReplySettings, message: ForwardDigest.Message, stream: Boolean = true): JSONObject {
        val startedAt = System.currentTimeMillis()
        val transcript = message.transcript()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", instructions))
            .put(JSONObject().put("role", "user").put("content", transcript))
        val request = JSONObject().put("model", settings.model).put("stream", stream)
        if (stream) request.put("stream_options", JSONObject().put("include_usage", true))
        // 只记条数、长度和耗时：转录里有标题、姓名和聊天正文，日志可以被导出去。
        dev.jev.wechatmood.core.MoodLog.i(
            "FORWARD_PROMPT items=${message.items.size} chars=${transcript.length} " +
                "ms=${System.currentTimeMillis() - startedAt}")
        return request.put("max_tokens", 450).put("temperature", 0.3).put("messages", messages)
    }

    fun parse(body: String): String {
        val root = JSONObject(body)
        check(!root.has("error")) { root.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "模型返回错误" } }
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason")
        check(finish == "stop") { "摘要被截断（finish_reason=$finish），请重试或换输出上限更高的模型" }
        val content = choice.getJSONObject("message").getString("content").trim()
        check(content.isNotBlank()) { "模型没有返回正文，换一个非推理模型试试" }
        return content
    }

    private val instructions = """
        你在微信里刷到一段被转发的聊天记录，现在要跟旁边的人随口讲一遍。
        用一段大白话说清楚：这事是怎么起的、中间在争什么、谁是什么态度、
        话题后来跑偏成了什么样、最后有没有结果。

        说话方式：
        - 短句为主，可以有半截话，像边想边说。
        - 不要「首先、其次、最后」「虽然、但是」「不仅、而且」这类连接词，
          也不要「综上所述」「总而言之」「值得一提的是」「值得注意的是」。
        - 不分点、不编号、不加小标题、不用 Markdown。
        - 讲完就停，不要结尾拔高讲道理。
        - 「其实」「说白了」这类词可以有，但别堆。
        - 人名、时间、金额、地点、数量、约定照原样说。
        - 如果这段记录只是普通聊天，既没争执也没闹剧，就照实讲在聊什么，别硬套冲突。
        - 真有结论就直说，没有就说后来没下文，别编。
        - 写成 [图片] [语音] [聊天记录] 的只当占位，不要猜内容。
        - 整段不超过 150 字、最多 6 句。不复述原话，只留关键。

        只输出这段转述。
    """.trimIndent()
}
