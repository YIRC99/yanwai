package dev.jev.wechatmood.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Presets all use the native TypeSafe question/answer format, not Chat Completions. */
enum class JevProvider(
    val id: String, val label: String, val endpoint: String, val model: String,
    val keyUrl: String?, val docsUrl: String, val guide: String,
) {
    TYPESAFE("typesafe", "Jev 官方 · TypeSafe", "https://api.typesafe.ai/v1/systemone", "jev-1.13.0",
        "https://console.typesafe.ai/", "https://docs.typesafe.ai/introduction/quickstart",
        "1. 打开 TypeSafe 控制台，注册或登录。\n2. 在控制台创建 API Key；若提示等待开通，先选择其他渠道。\n3. 将 Key 粘贴到下方，保存并检测连接。"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/systemone", "typesafe/jev-1.13",
        "https://openrouter.ai/settings/keys", "https://openrouter.ai/docs/guides/community/typesafe-sdk",
        "1. 注册或登录 OpenRouter，进入 Keys，点击 Create Key。\n2. 确认账户有可用额度，无需另申请 TypeSafe Key。\n3. 复制 Key 到下方，保存并检测连接。"),
    VERCEL("vercel", "Vercel AI Gateway", "https://ai-gateway.vercel.sh/typesafe/v1/systemone", "typesafe-ai/jev",
        "https://vercel.com/d?title=AI+Gateway+API+Keys&to=%2F%5Bteam%5D%2F~%2Fai-gateway%2Fapi-keys", "https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe",
        "1. 注册或登录 Vercel，进入 AI Gateway → API Keys → Create key。\n2. 创建 AI Gateway Key，不是普通 Vercel 访问令牌。按平台提示绑定信用卡，确认可用额度。\n3. 复制 Key 到下方，保存并检测连接。"),
    CUSTOM("custom", "自定义 Jev 兼容接口", "", "jev-1.13.0",
        null, "https://docs.typesafe.ai/introduction/quickstart",
        "向服务商申请 Key，并获取完整接口地址和模型名。服务需兼容 Jev 的 state、questions 与概率响应；普通聊天接口不能直接使用。"),
    ;

    companion object {
        fun resolve(id: String?, endpoint: String): JevProvider {
            entries.firstOrNull { it.id == id }?.let { return it }
            if (endpoint.isBlank()) return TYPESAFE
            val url = endpoint.trim().toHttpUrlOrNull() ?: return CUSTOM
            if (url.scheme != "https" || url.port != 443 || url.query != null || url.fragment != null ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return CUSTOM
            val paths = when (url.host) {
                "api.typesafe.ai" -> setOf("", "/v1", "/v1/systemone")
                "openrouter.ai" -> setOf("", "/api", "/api/v1", "/api/v1/systemone", "/api/alpha/decisions", "/api/v1/chat/completions")
                "ai-gateway.vercel.sh" -> setOf("", "/v1", "/v1/evaluate", "/v1/chat/completions", "/typesafe", "/typesafe/v1/systemone")
                else -> return CUSTOM
            }
            if (url.encodedPath.trimEnd('/') !in paths) return CUSTOM
            return when (url.host) {
                "api.typesafe.ai" -> TYPESAFE
                "openrouter.ai" -> OPENROUTER
                else -> VERCEL
            }
        }
    }
}
