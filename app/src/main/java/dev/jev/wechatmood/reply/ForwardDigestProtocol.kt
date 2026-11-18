package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject

/** 合并转发的摘要请求：只要一段纯文本要点，不强制 JSON，兼容性最好。 */
object ForwardDigestProtocol {
    fun payload(settings: ReplySettings, message: ForwardDigest.Message, stream: Boolean = true): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", instructions))
            .put(JSONObject().put("role", "user").put("content", message.transcript()))
        val request = JSONObject().put("model", settings.model).put("stream", stream)
        if (stream) request.put("stream_options", JSONObject().put("include_usage", true))
        return request.put("max_tokens", 900).put("temperature", 0.3).put("messages", messages)
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
        你帮我整理一条微信「合并转发」的聊天记录。

        要求：
        - 先用一句话说这段记录在聊什么，再用 3 到 6 条列出要点，每条不超过 40 字。
        - 保留关键的人名、时间、数字、约定、结论；闲聊寒暄只做概括。
        - 有明显分歧、需要我回应或还没结论的地方，单独一行用「待定：」标出来。
        - 不要复述原文，不要编造没出现的信息；记录里写成 [图片] [语音] [聊天记录] 的只当占位。
        - 只输出纯文本，不要 Markdown 标题和代码块。
    """.trimIndent()
}
