package com.dogheadstrategist.chat.reply

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 模型配置。
 *
 * 一套主配置 + 一套可选备用配置。两级超时：
 * - [failoverMs] 主模型的等待上限，超过就切备用；
 * - [finalMs] 备用模型的等待上限，再超时就整体失败。
 */
/** Never include credentials in toString. */
class ReplySettings private constructor(
    val endpoint: String, val apiKey: String, val model: String,
    val backupEndpoint: String = "", val backupApiKey: String = "", val backupModel: String = "",
    val failoverMs: Long = DEFAULT_FAILOVER_MS, val finalMs: Long = DEFAULT_FINAL_MS,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    val isConfigured get() = endpoint.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /** 备用配置三项齐全才算可用；缺一项就视为没配，避免切过去必然失败。 */
    val hasBackup get() = backupEndpoint.isNotBlank() && backupModel.isNotBlank() && backupApiKey.isNotBlank()

    /** 切成备用配置的视图。备用自己不再带备用，避免无限套娃。 */
    fun backup(): ReplySettings = ReplySettings(
        backupEndpoint, backupApiKey, backupModel, "", "", "", failoverMs, finalMs, maxTokens)

    fun withBackup(endpoint: String, apiKey: String, model: String): ReplySettings = ReplySettings(
        this.endpoint, this.apiKey, this.model, endpoint, apiKey, model, failoverMs, finalMs, maxTokens)

    fun withTimeouts(failoverMs: Long, finalMs: Long): ReplySettings = ReplySettings(
        endpoint, apiKey, model, backupEndpoint, backupApiKey, backupModel,
        failoverMs.coerceIn(MIN_FAILOVER_MS, MAX_FAILOVER_MS),
        finalMs.coerceIn(MIN_FINAL_MS, MAX_FINAL_MS), maxTokens)

    /**
     * 回复建议的输出长度上限。
     *
     * 这是「模型未返回完整的回复建议」的主要来源：请求里不给 max_tokens 时，
     * 各服务商默认值差别很大（常见 256~1024），群聊一次要给几条建议外加说明，
     * 很容易在 JSON 中途被截断，解析失败后整条建议就没了。
     */
    fun withMaxTokens(tokens: Int): ReplySettings = ReplySettings(
        endpoint, apiKey, model, backupEndpoint, backupApiKey, backupModel,
        failoverMs, finalMs, tokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS))

    companion object {
        const val KEY_ENDPOINT = "reply_endpoint"
        const val KEY_API_KEY = "reply_api_key"
        const val KEY_MODEL = "reply_model"
        const val KEY_CONSENT = "reply_consent"
        const val KEY_BACKUP_ENDPOINT = "reply_backup_endpoint"
        const val KEY_BACKUP_API_KEY = "reply_backup_api_key"
        const val KEY_BACKUP_MODEL = "reply_backup_model"
        const val KEY_FAILOVER_MS = "reply_failover_ms"
        const val KEY_FINAL_MS = "reply_final_ms"
        const val KEY_MAX_TOKENS = "reply_max_tokens"

        /** 主模型等这么久没动静就切备用。 */
        const val DEFAULT_FAILOVER_MS = 8_000L
        const val MIN_FAILOVER_MS = 3_000L
        const val MAX_FAILOVER_MS = 30_000L

        /** 备用模型再等这么久还不行就整体失败。 */
        const val DEFAULT_FINAL_MS = 30_000L
        const val MIN_FINAL_MS = 10_000L
        const val MAX_FINAL_MS = 120_000L

        /** 输出长度上限：默认给足，单聊够用、群聊多条建议也不至于被截断。 */
        const val DEFAULT_MAX_TOKENS = 1000
        const val MIN_MAX_TOKENS = 200
        /**
         * 输出上限。DeepSeek 系模型单次输出上限普遍是 8K（8192），
         * 这里取 8200 是为了让滑块 stepSize=100 能整除（(8200-200)/100=80），
         * 否则 Material Slider 会在运行时抛异常。
         */
        const val MAX_MAX_TOKENS = 8200

        fun empty() = ReplySettings("", "", "")
        /**
         * 从模块配置里读一份可用的设置。没配好返回 null。
         *
         * 通知服务和设置页都要用这份读取逻辑，放在这里免得两边各写一遍。
         */
        fun load(context: android.content.Context): ReplySettings? = runCatching {
            val prefs = context.getSharedPreferences(
                com.dogheadstrategist.chat.core.ModulePrefs.FILE_NAME, 0,
            )
            fromInput(
                prefs.getString(KEY_ENDPOINT, "").orEmpty(),
                prefs.getString(KEY_API_KEY, "").orEmpty(),
                prefs.getString(KEY_MODEL, "").orEmpty(),
            ).withTimeouts(
                prefs.getLong(KEY_FAILOVER_MS, DEFAULT_FAILOVER_MS),
                prefs.getLong(KEY_FINAL_MS, DEFAULT_FINAL_MS),
            ).withMaxTokens(
                prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS),
            )
        }.getOrNull()

        fun fromInput(endpoint: String, apiKey: String, model: String): ReplySettings {
            if (endpoint.isBlank() && apiKey.isBlank() && model.isBlank()) return empty()
            val url = endpoint.trim().toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "请填写 HTTP(S) 地址，不要包含账号密码、查询参数或锚点"
            }
            val key = apiKey.trim()
            require(key.all { it.code in 33..126 }) { "API Key 不能包含空格、换行或中文字符" }
            val name = model.trim()
            require(name.none { it.isWhitespace() || it.isISOControl() }) { "模型名不能包含空格或换行" }
            val base = url.toString().trimEnd('/')
            val normalized = when {
                url.encodedPath.trimEnd('/') == "" -> "$base/v1/chat/completions"
                url.encodedPath.trimEnd('/').endsWith("/chat/completions") -> base
                else -> "$base/chat/completions"
            }
            return ReplySettings(normalized, key, name)
        }
    }
}
