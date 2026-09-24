package dev.jev.wechatmood.updates

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion) = compareValuesBy(this, other,
        StableVersion::major, StableVersion::minor, StableVersion::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        fun parse(value: String): StableVersion? {
            if (value.length > 64) return null
            val parts = pattern.matchEntire(value)?.groupValues?.drop(1) ?: return null
            return StableVersion(parts[0].toIntOrNull() ?: return null,
                parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
        }
    }
}

data class ReleaseInfo(val tag: String, val version: StableVersion, val hasApk: Boolean) {
    // Never navigate to a server-supplied URL, nor automatically download an asset.
    val pageUrl get() = RELEASES_URL.toHttpUrl().newBuilder().addPathSegment("tag").addPathSegment(tag).build().toString()
    fun isUpdateFor(installed: String) = hasApk && (StableVersion.parse(installed)?.let { version > it } == true)

    companion object {
        const val RELEASES_URL = "https://github.com/YIRC99/yanwai/releases"
        fun parse(json: String): ReleaseInfo? {
            val data = JSONObject(json)
            if (data.getBoolean("draft") || data.getBoolean("prerelease")) return null
            val tag = data.getString("tag_name")
            val version = requireNotNull(StableVersion.parse(tag)) { "不支持的发布版本号" }
            val assets = data.getJSONArray("assets")
            val hasApk = (0 until assets.length()).any {
                val asset = assets.optJSONObject(it)
                asset != null && asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                    asset.optString("state") == "uploaded" && asset.optLong("size", 0) > 0
            }
            return ReleaseInfo(tag, version, hasApk)
        }
    }
}

sealed class ReleaseReply {
    data class Found(val json: String, val etag: String?) : ReleaseReply()
    data object Unchanged : ReleaseReply()
    data object NotPublished : ReleaseReply()
}

class UpdateCheckException(message: String, val retryAfterMs: Long = 0) : IOException(message)

/** Separate anonymous client: no model settings, keys, cookies or chat data are used. */
class ReleaseClient(version: String, private val endpoint: String = API_URL) {
    private val userAgent = "Yanwai/$version"
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun fetch(etag: String?): ReleaseReply {
        val request = Request.Builder().url(endpoint).header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }.build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    304 -> return ReleaseReply.Unchanged
                    404 -> return ReleaseReply.NotPublished
                    403, 429 -> {
                        val retrySeconds = response.header("Retry-After")?.toLongOrNull()
                            ?: response.header("X-RateLimit-Reset")?.toLongOrNull()?.let {
                                it - System.currentTimeMillis() / 1000
                            } ?: 900L
                        throw UpdateCheckException("GitHub 暂时限制访问，请稍后再试", retrySeconds.coerceIn(60, 86_400) * 1000)
                    }
                    200 -> {
                        val bytes = response.peekBody(1_048_577).bytes()
                        if (bytes.size > 1_048_576) throw UpdateCheckException("发布信息过大，请直接查看 GitHub")
                        val json = bytes.toString(Charsets.UTF_8)
                        try { ReleaseInfo.parse(json) } catch (_: Exception) {
                            throw UpdateCheckException("暂时无法识别发布信息，请直接查看 GitHub")
                        }
                        return ReleaseReply.Found(json, response.header("ETag"))
                    }
                    else -> throw UpdateCheckException("暂时无法检查更新，请稍后重试")
                }
            }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (_: IOException) {
            throw UpdateCheckException("无法连接 GitHub，请检查网络后重试")
        }
    }

    companion object { const val API_URL = "https://api.github.com/repos/YIRC99/yanwai/releases/latest" }
}

object UpdateSchedule {
    fun shouldCheck(now: Long, lastAttempt: Long, manual: Boolean, failed: Boolean, blockedUntil: Long): Boolean {
        // A user clock correction must not suppress checks for days.
        if (lastAttempt > now) return true
        if (blockedUntil > now) return false
        val interval = when { manual -> 60_000L; failed -> 900_000L; else -> 86_400_000L }
        return lastAttempt == 0L || now - lastAttempt >= interval
    }
}
