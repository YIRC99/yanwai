package dev.jev.wechatmood.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Deliberately not a data class: toString must never expose the credential in logs.
class ApiSettings private constructor(val endpoint: String, val apiKey: String) {
    val isConfigured get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

        fun fromInput(endpoint: String, apiKey: String): ApiSettings {
            val address = endpoint.trim().ifBlank { DEFAULT_ENDPOINT }
            val url = address.toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty()) {
                "请填写完整的 HTTP 或 HTTPS 接口地址，地址中不要包含账号密码"
            }
            val key = apiKey.trim()
            require(key.all { it.code in 33..126 }) { "API Key 不能包含空格、换行或中文字符" }
            return ApiSettings(address, key)
        }
    }
}
