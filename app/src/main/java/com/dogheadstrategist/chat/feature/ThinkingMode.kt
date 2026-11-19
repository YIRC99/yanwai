package com.dogheadstrategist.chat.feature

import org.json.JSONObject

/**
 * 关闭思考模式：按「模板」往请求体里合并一段 JSON。
 *
 * 为什么不用模型名自动判断：各家模型命名混乱（同一模型经中转站可能改名、版本号叠加后缀），
 * 猜错的代价是请求直接 400，而用户其实最清楚自己用的是哪家。所以这里只提供现成片段，
 * 由用户自己选；选不到就手填。
 */
object ThinkingMode {
    /** 预设片段。合并进请求体的顶层字段。 */
    enum class Template(val id: String, val label: String, val hint: String, val json: String) {
        NONE("none", "不注入", "请求体保持原样", ""),
        THINKING_TYPE("thinking", "thinking.type", "DeepSeek / 智谱 GLM / 火山豆包",
            """{"thinking":{"type":"disabled"}}"""),
        QWEN("qwen", "enable_thinking", "通义千问 Qwen3", """{"enable_thinking":false}"""),
        OPENAI_EFFORT("openai", "reasoning_effort", "OpenAI GPT-5 / o4", """{"reasoning_effort":"minimal"}"""),
        CUSTOM("custom", "自定义", "手填 JSON，合并进请求体", "");

        companion object {
            fun of(id: String): Template = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    fun template(id: String): Template = Template.of(id)

    /**
     * 把选中的片段合并进请求体。自定义片段解析失败时静默跳过——
     * 宁可不关思考，也不能让整个请求因为一个手填错误而失败。
     */
    fun apply(payload: JSONObject, templateId: String, customJson: String) {
        val template = Template.of(templateId)
        val json = if (template == Template.CUSTOM) customJson else template.json
        if (json.isBlank()) return
        runCatching {
            val extra = JSONObject(json)
            extra.keys().forEach { key -> payload.put(key, extra.get(key)) }
        }
    }
}
