package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle

/** Host-side client only. No cache files are created in the host or on shared storage. */
object AnalysisResultCache {
    @Volatile private var context: Context? = null
    private var lastFailure = 0L
    fun init(context: Context) { this.context = context.applicationContext ?: context }
    @Synchronized private fun failed(error: Throwable) {
        val now = System.nanoTime()
        if (lastFailure == 0L || now - lastFailure > 30_000_000_000L) {
            MoodLog.w("ANALYSIS_CACHE_BRIDGE_FAILED ${error.javaClass.simpleName}；持久缓存暂不可用，本次使用内存")
            lastFailure = now
        }
    }
    fun find(input: AnalysisInput, settings: RuntimeSettings): Mood? {
        return runCatching {
            AnalysisCacheLookup.find(input, settings, read = { key ->
                val response = requireNotNull(SettingsTransport.call(requireNotNull(context), "cache_get", key.value, null))
                response.getString("payload")?.let(CachedAnalysisResult::decode)
            }, promote = { key, result ->
                runCatching { write(key, result) }.onFailure(::failed)
            })?.mood
        }.onFailure(::failed).getOrNull()?.also { MoodLog.i("ANALYSIS_CACHE_HIT 已读取言外本地分析结果") }
    }
    fun save(input: AnalysisInput, settings: RuntimeSettings, mood: Mood, evidence: String) {
        if (mood.intentFailed) return
        val key = AnalysisCacheKey.of(input, settings) ?: return
        runCatching {
            write(key, CachedAnalysisResult(mood, evidence))
            MoodLog.i("ANALYSIS_CACHE_SAVED 分析结果已保存到言外私有目录")
        }.onFailure(::failed)
    }
    private fun write(key: AnalysisCacheKey, result: CachedAnalysisResult) {
        val payload = result.encode()
        require(payload.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        val extras = Bundle().apply { putString("payload", payload) }
        val response = requireNotNull(SettingsTransport.call(requireNotNull(context), "cache_put", key.value, extras))
        check(response.getBoolean("saved"))
    }
}
