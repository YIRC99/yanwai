package dev.jev.wechatmood.reply

/** Ordinary pay-as-you-go APIs, not Coding Plan or consumer chat subscriptions. */
enum class ReplyProvider(val id: String, val label: String, val endpoint: String,
    val consoleUrl: String, val hint: String, val referenceModels: List<String> = emptyList()) {
    DOUBAO("doubao", "豆包 · 火山方舟", "https://ark.cn-beijing.volces.com/api/v3",
        "https://console.volcengine.com/ark", "使用火山方舟 API Key。也可手动填写已开通的模型 ID 或 ep- 开头的推理接入点。",
        listOf("doubao-seed-2-1-pro-260628")),
    XIAOMI("xiaomi", "小米 · MiMo", "https://api.xiaomimimo.com/v1",
        "https://platform.xiaomimimo.com/", "使用小米 MiMo 开放平台的 API Key。获取列表后选择聊天模型。"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
        "https://platform.deepseek.com/", "使用 DeepSeek 开放平台的 API Key。模型列表以账户接口返回为准。"),
    OPENAI("openai", "OpenAI · GPT", "https://api.openai.com/v1",
        "https://platform.openai.com/api-keys", "使用 OpenAI API Key，ChatGPT 订阅不等同于 API 额度。请选支持 Chat Completions 的模型。"),
    GLM("glm", "智谱 · GLM", "https://open.bigmodel.cn/api/paas/v4",
        "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", "使用智谱开放平台的通用 API Key。此地址用于按量调用；Coding Plan 或其他地区地址请选自定义。",
        listOf("glm-5.3", "glm-5.3-flash", "glm-5.2")),
    KIMI("kimi", "Kimi · 月之暗面", "https://api.moonshot.cn/v1",
        "https://platform.kimi.com/console/api-keys", "使用 Kimi 开放平台的 API Key。其他区域或代理地址请选自定义。"),
    CUSTOM("custom", "自定义 · OpenAI 兼容", "", "",
        "填写服务商的 Base URL 或完整 Chat Completions 地址。模型列表按同一路径下的 /models 获取。");
    companion object {
        fun resolve(id: String?, endpoint: String): ReplyProvider {
            if (id == CUSTOM.id) return CUSTOM
            val normalized = runCatching { ReplySettings.fromInput(endpoint, "", "").endpoint }.getOrNull()
            return entries.firstOrNull { provider -> provider != CUSTOM &&
                (normalized == ReplySettings.fromInput(provider.endpoint, "", "").endpoint ||
                    provider == DEEPSEEK && normalized == "https://api.deepseek.com/chat/completions")
            } ?: CUSTOM
        }
    }
}

object ReplyProfiles {
    const val KEY_PROVIDER = "reply_provider"
    private fun key(provider: ReplyProvider, field: String) = "reply_profile_${provider.id}_$field"

    fun load(provider: ReplyProvider, read: (String) -> String?): ReplySettings {
        val activeEndpoint = read(ReplySettings.KEY_ENDPOINT).orEmpty()
        val active = ReplyProvider.resolve(read(KEY_PROVIDER), activeEndpoint)
        val legacy = read(key(provider, "key")) == null && provider == active
        val endpoint = if (legacy) activeEndpoint else read(key(provider, "endpoint")).orEmpty()
        val apiKey = read(if (legacy) ReplySettings.KEY_API_KEY else key(provider, "key")).orEmpty()
        val model = read(if (legacy) ReplySettings.KEY_MODEL else key(provider, "model")).orEmpty()
        return ReplySettings.fromInput(if (provider == ReplyProvider.CUSTOM) endpoint else provider.endpoint, apiKey, model)
    }

    /** Only the selected profile is published to WeChat; all other keys stay in app preferences. */
    fun valuesToSave(provider: ReplyProvider, settings: ReplySettings, read: (String) -> String?): Map<String, String> {
        val updates = linkedMapOf<String, String>()
        val previousEndpoint = read(ReplySettings.KEY_ENDPOINT).orEmpty()
        val previous = ReplyProvider.resolve(read(KEY_PROVIDER), previousEndpoint)
        if (read(key(previous, "key")) == null) {
            updates[key(previous, "endpoint")] = previousEndpoint
            updates[key(previous, "key")] = read(ReplySettings.KEY_API_KEY).orEmpty()
            updates[key(previous, "model")] = read(ReplySettings.KEY_MODEL).orEmpty()
        }
        updates.putAll(mapOf(KEY_PROVIDER to provider.id,
            ReplySettings.KEY_ENDPOINT to settings.endpoint, ReplySettings.KEY_API_KEY to settings.apiKey,
            ReplySettings.KEY_MODEL to settings.model, key(provider, "endpoint") to settings.endpoint,
            key(provider, "key") to settings.apiKey, key(provider, "model") to settings.model))
        return updates
    }
}
