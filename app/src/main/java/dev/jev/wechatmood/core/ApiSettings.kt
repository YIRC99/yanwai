package dev.jev.wechatmood.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Deliberately not a data class: toString must never expose the credential in logs.
class ApiSettings private constructor(val endpoint: String, val apiKey: String,
    val provider: JevProvider, val model: String) {
    val isConfigured get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

        fun fromInput(endpoint: String, apiKey: String, providerId: String? = null, model: String = ""): ApiSettings {
            val provider = JevProvider.resolve(providerId, endpoint)
            val address = if (provider == JevProvider.CUSTOM) endpoint.trim() else provider.endpoint
            val url = address.toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty()) {
                "请填写完整的 HTTP 或 HTTPS 接口地址，地址中不要包含账号密码"
            }
            require(url.isHttps || url.host.isPrivateHost()) {
                "公网地址请用 HTTPS，否则 API Key 和聊天内容会明文发送；本机或局域网地址可以继续用 HTTP"
            }
            val key = apiKey.trim()
            require(key.all { it.code in 33..126 }) { "API Key 不能包含空格、换行或中文字符" }
            val selectedModel = if (provider == JevProvider.CUSTOM) model.trim().ifBlank { provider.model } else provider.model
            require(selectedModel.all { it.code in 33..126 }) { "模型名不能包含空格、换行或中文字符" }
            return ApiSettings(address, key, provider, selectedModel)
        }

    }
}

/** 本机或局域网地址允许 HTTP，避免把自建中转当成必须上公网。 */
internal fun String.isPrivateHost(): Boolean =
    this == "localhost" || this == "127.0.0.1" || this == "::1" ||
        startsWith("192.168.") || startsWith("10.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(this)
