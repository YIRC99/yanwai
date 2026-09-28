package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.reply.ReplySettings
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Verified official endpoint + model capabilities only; custom gateways keep their own defaults.
 * References and exceptions are recorded in docs/ANALYSIS_SOURCES.md.
 */
object AnalysisThinking {
    private fun canDisable(settings: ReplySettings): Boolean {
        if (settings.endpoint.isBlank()) return false
        val host = settings.endpoint.toHttpUrl().host
        val model = settings.model.lowercase()
        return when (host) {
            "ark.cn-beijing.volces.com" -> model in setOf("doubao-seed-evolving", "doubao-seed-2-1-pro-260915",
                "doubao-seed-2-1-lite-260915", "doubao-seed-2-1-pro-260628", "doubao-seed-2-1-turbo-260628",
                "doubao-seed-2-0-lite-260428", "doubao-seed-2-0-mini-260428", "doubao-seed-2-0-pro-260215",
                "doubao-seed-2-0-lite-260215", "doubao-seed-2-0-mini-260215", "doubao-seed-2-0-code-preview-260215")
            "api.deepseek.com" -> model in setOf("deepseek-chat", "deepseek-reasoner", "deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash")
            "api.xiaomimimo.com" -> model in setOf("mimo-v2.5", "mimo-v2.5-pro")
            "api.moonshot.cn", "api.moonshot.ai" -> model == "kimi-k2.5"
            "open.bigmodel.cn" -> model in setOf("glm-4.5", "glm-4.5-air", "glm-4.5-airx", "glm-4.5-flash", "glm-4.5v", "glm-4.6", "glm-4.6v", "glm-4.7", "glm-4.7-flash", "glm-5", "glm-5-turbo", "glm-5.1", "glm-5.2")
            else -> false
        }
    }
    private fun openAiNone(settings: ReplySettings): Boolean = settings.endpoint.isNotBlank() &&
        settings.endpoint.toHttpUrl().host == "api.openai.com" && settings.model.lowercase() in setOf(
            "gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-5.4-2026-03-05", "gpt-5.5", "gpt-6-sol", "gpt-6-luna")
    fun apply(settings: ReplySettings, payload: JSONObject): JSONObject = payload.apply {
        if (canDisable(settings)) put("thinking", JSONObject().put("type", "disabled"))
        else if (openAiNone(settings)) put("reasoning_effort", "none")
    }
    fun description(settings: ReplySettings): String = if (canDisable(settings) || openAiNone(settings)) "已按接口能力请求关闭深度思考"
        else "此模型未启用已确认的关闭思考参数，按服务默认运行"
}
