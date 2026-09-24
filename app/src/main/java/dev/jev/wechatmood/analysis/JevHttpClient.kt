package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.ApiSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Shared by the Android app and opt-in live verification. Never reports raw service bodies. */
class JevHttpClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun exchange(payload: JSONObject, settings: ApiSettings): String {
        check(settings.isConfigured) { "请先填写并保存 API Key" }
        val request = Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful || json?.has("error") == true) {
                    val error = json?.optJSONObject("error")
                    val code = if (response.isSuccessful) error?.optInt("code", response.code) ?: response.code else response.code
                    val reason = when {
                        error?.optString("type") == "customer_verification_required" ->
                            "Vercel 账户尚未验证，请到 AI Gateway 控制台绑定信用卡后重试"
                        code == 401 -> "API Key 无效，请检查所选渠道与密钥是否对应"
                        code == 403 -> "账户或模型尚未授权，请到所选渠道控制台检查"
                        code == 402 -> "模型账户额度不足，请到所选渠道充值或检查额度"
                        code == 429 -> "请求过于频繁，请稍后重试"
                        code == 400 || code == 404 || code == 422 -> "接口地址或模型不受支持，请检查渠道和配置"
                        code in 300..399 -> "接口发生重定向，请填写最终的 Jev 接口地址"
                        else -> "模型服务暂不可用，请稍后重试"
                    }
                    throw IllegalStateException("$reason（HTTP ${response.code}）")
                }
                return body
            }
        } catch (_: java.io.IOException) {
            throw IllegalStateException("连接超时或网络不可用，请稍后重试")
        }
    }
}
