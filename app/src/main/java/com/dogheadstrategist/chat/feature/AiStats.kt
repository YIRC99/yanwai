package com.dogheadstrategist.chat.feature

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 调用的延迟与 token 消耗统计。
 *
 * 全部只在本地累计（SharedPreferences），不上报、不进日志，用来回答「哪个功能慢、花了多少」。
 * 记录点统一在 [com.dogheadstrategist.chat.reply.ReplyHttpClient.request]，所以任何新增的
 * AI 能力只要带上 [Kind] 就会自动进入统计。
 */
object AiStats {
    private const val PREFS = "ai_stats"
    private const val APP_PACKAGE = "com.dogheadstrategist.chat"
    private const val PROVIDER_URI = "content://com.dogheadstrategist.chat.settings"
    private const val KEY_BUCKETS = "buckets"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 2000

    /** 统计维度：各功能各自的 AI 调用。 */
    enum class Kind(val key: String, val label: String) {
        REPLY("reply", "帮我回复"),
        TOPICS("topics", "找话题"),
        RELAY("relay", "自动填充"),
        CARD("card", "人物卡"),
        SENTRY("sentry", "自动起草"),
        WAR_REVIEW("war_review", "我的风格"),
        COMMUNICATE("communicate", "通知摘要"),
        AIDE("aide", "通知代办"),
    }

    data class Bucket(
        val calls: Int = 0,
        val ok: Int = 0,
        val totalMs: Long = 0,
        val lastMs: Long = 0,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val lastAt: Long = 0,
        val maxMs: Long = 0,
        val minMs: Long = 0,
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
        val failed: Int get() = (calls - ok).coerceAtLeast(0)
    }

    /**
     * 单次调用的明细。
     *
     * [firstMs] 只对续话有意义：那是「从发起请求到第一条备选显示出来」的耗时；
     * 其他功能一次性返回整包，没有首条概念，记 0。
     */
    data class CallRecord(
        val at: Long,
        val kind: String,
        val firstMs: Long,
        val totalMs: Long,
        val prompt: Long,
        val completion: Long,
        val ok: Boolean,
    ) {
        val label: String get() = Kind.entries.firstOrNull { it.key == kind }?.label ?: kind
        val totalTokens: Long get() = prompt + completion
    }

    /**
     * 记一次调用。失败时 token 传 0：模型没回话，也就没有消耗数据可算。
     *
     * 调用多数发生在微信进程（续话、自动起草、帮我回复都在那边），而统计页读的是 app 进程的
     * SharedPreferences —— 两边不是同一个文件。所以微信进程里改为委托回 app 进程写。
     */
    fun record(kind: Kind, durationMs: Long, promptTokens: Int, completionTokens: Int, ok: Boolean,
        firstMs: Long = 0L) {
        val context = ModulePrefs.appContext() ?: return
        if (context.packageName != APP_PACKAGE) {
            runCatching {
                context.contentResolver.call(Uri.parse(PROVIDER_URI), "ai_stats_record", kind.key,
                    Bundle().apply {
                        putLong("duration", durationMs)
                        putInt("prompt", promptTokens)
                        putInt("completion", completionTokens)
                        putBoolean("ok", ok)
                        putLong("first", firstMs)
                    })
            }.onFailure { MoodLog.w("AI_STATS_REMOTE_FAILED kind=${kind.key} ${it.javaClass.simpleName}") }
            return
        }
        write(context, kind, durationMs, promptTokens, completionTokens, ok, firstMs)
    }

    /** 由 [com.dogheadstrategist.chat.core.SettingsProvider] 在处理微信进程的委托时调用。 */
    internal fun recordRemote(context: Context, kindKey: String, durationMs: Long, promptTokens: Int,
        completionTokens: Int, ok: Boolean, firstMs: Long) {
        val kind = Kind.entries.firstOrNull { it.key == kindKey } ?: return
        write(context, kind, durationMs, promptTokens, completionTokens, ok, firstMs)
    }

    @Synchronized
    private fun write(context: Context, kind: Kind, durationMs: Long, promptTokens: Int,
        completionTokens: Int, ok: Boolean, firstMs: Long) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val root = readRoot(prefs)
            val node = root.optJSONObject(kind.key) ?: JSONObject()
            root.put(kind.key, JSONObject()
                .put("calls", node.optInt("calls") + 1)
                .put("ok", node.optInt("ok") + if (ok) 1 else 0)
                .put("total_ms", node.optLong("total_ms") + durationMs.coerceAtLeast(0))
                .put("last_ms", durationMs.coerceAtLeast(0))
                .put("prompt", node.optLong("prompt") + promptTokens.coerceAtLeast(0))
                .put("completion", node.optLong("completion") + completionTokens.coerceAtLeast(0))
                .put("max_ms", maxOf(node.optLong("max_ms"), durationMs.coerceAtLeast(0)))
                .put("min_ms", if (node.optLong("min_ms") <= 0) durationMs.coerceAtLeast(0)
                    else minOf(node.optLong("min_ms"), durationMs.coerceAtLeast(0)))
                .put("last_at", System.currentTimeMillis()))
            appendRecord(prefs, CallRecord(
                at = System.currentTimeMillis(),
                kind = kind.key,
                firstMs = firstMs.coerceAtLeast(0),
                totalMs = durationMs.coerceAtLeast(0),
                prompt = promptTokens.coerceAtLeast(0).toLong(),
                completion = completionTokens.coerceAtLeast(0).toLong(),
                ok = ok,
            ))
            // commit 不用 apply：实测这台机器上 apply 的异步落盘没兑现，
            // ai_stats.xml 一直没被创建，首页「最近用过」重启后全空。
            prefs.edit().putString(KEY_BUCKETS, root.toString()).commit()
        }
    }

    fun snapshot(): Map<Kind, Bucket> {
        val context = ModulePrefs.appContext() ?: return emptyMap()
        return snapshot(context)
    }

    fun snapshot(context: Context): Map<Kind, Bucket> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = readRoot(prefs)
        return Kind.entries.associateWith { kind ->
            val node = root.optJSONObject(kind.key) ?: return@associateWith Bucket()
            Bucket(
                calls = node.optInt("calls"),
                ok = node.optInt("ok"),
                totalMs = node.optLong("total_ms"),
                lastMs = node.optLong("last_ms"),
                promptTokens = node.optLong("prompt"),
                completionTokens = node.optLong("completion"),
                lastAt = node.optLong("last_at"),
                maxMs = node.optLong("max_ms"),
                minMs = node.optLong("min_ms"),
            )
        }
    }

    /** 统计区间。今日与本周从明细里算，总计读累计桶（明细会被裁剪，不能代表总量）。 */
    enum class Range(val label: String) {
        TODAY("今日"),
        WEEK("本周"),
        ALL("总计"),
    }

    /**
     * 一个区间内的汇总。延时同时给平均/最大/最小——
     * 平均看不出偶发的长尾，最大最小能看出「有没有卡过一次」。
     */
    data class Summary(
        val calls: Int = 0,
        val ok: Int = 0,
        val prompt: Long = 0,
        val completion: Long = 0,
        val avgMs: Long = 0,
        val maxMs: Long = 0,
        val minMs: Long = 0,
        val avgFirstMs: Long = 0,
        val maxFirstMs: Long = 0,
        /** 最近一次调用的时间戳，0 表示区间内没有调用。首页「最近用过」靠它。 */
        val lastAt: Long = 0,
    ) {
        val totalTokens: Long get() = prompt + completion
        val failed: Int get() = (calls - ok).coerceAtLeast(0)
        val successRate: Int get() = if (calls == 0) 0 else ok * 100 / calls
    }

    fun summary(kind: Kind, range: Range): Summary {
        val context = ModulePrefs.appContext() ?: return Summary()
        return summary(context, kind, range)
    }

    fun summary(context: Context, kind: Kind, range: Range): Summary {
        if (range == Range.ALL) {
            val bucket = snapshot(context)[kind] ?: Bucket()
            return Summary(
                calls = bucket.calls,
                ok = bucket.ok,
                prompt = bucket.promptTokens,
                completion = bucket.completionTokens,
                avgMs = if (bucket.calls == 0) 0 else bucket.totalMs / bucket.calls,
                maxMs = bucket.maxMs,
                minMs = bucket.minMs,
                lastAt = bucket.lastAt,
            )
        }
        val from = rangeStart(range)
        return summarize(records(context).filter { it.kind == kind.key && it.at >= from })
    }

    /** 某个功能最近的若干次调用，新的在前。 */
    fun recent(context: Context, kind: Kind, limit: Int = 10): List<CallRecord> =
        records(context).filter { it.kind == kind.key }.take(limit)

    private fun summarize(list: List<CallRecord>): Summary {
        if (list.isEmpty()) return Summary()
        val times = list.map { it.totalMs }
        val withFirst = list.filter { it.firstMs > 0 }
        return Summary(
            calls = list.size,
            ok = list.count { it.ok },
            prompt = list.sumOf { it.prompt },
            completion = list.sumOf { it.completion },
            avgMs = times.sum() / list.size,
            maxMs = times.max(),
            minMs = times.min(),
            avgFirstMs = if (withFirst.isEmpty()) 0 else withFirst.sumOf { it.firstMs } / withFirst.size,
            maxFirstMs = withFirst.maxOfOrNull { it.firstMs } ?: 0,
            lastAt = list.maxOf { it.at },
        )
    }

    /** 区间起点：今日取零点，本周取周一零点。 */
    private fun rangeStart(range: Range): Long = runCatching {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val date = if (range == Range.WEEK) today.minusDays((today.dayOfWeek.value - 1).toLong()) else today
        date.atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_BUCKETS).remove(KEY_RECORDS).commit()
        }
    }

    /** 最近若干条明细，新的在前。 */
    fun records(context: Context, limit: Int = MAX_RECORDS): List<CallRecord> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            CallRecord(
                at = item.optLong("at"),
                kind = item.optString("kind"),
                firstMs = item.optLong("first"),
                totalMs = item.optLong("total"),
                prompt = item.optLong("prompt"),
                completion = item.optLong("completion"),
                ok = item.optBoolean("ok"),
            )
        }.sortedByDescending { it.at }.take(limit)
    }.getOrDefault(emptyList())


    private fun appendRecord(prefs: android.content.SharedPreferences, record: CallRecord) {
        runCatching {
            val array = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
            val fresh = JSONArray()
            fresh.put(JSONObject()
                .put("at", record.at)
                .put("kind", record.kind)
                .put("first", record.firstMs)
                .put("total", record.totalMs)
                .put("prompt", record.prompt)
                .put("completion", record.completion)
                .put("ok", record.ok))
            // 只保留最近 MAX_RECORDS 条，避免 SharedPreferences 无限膨胀
            for (index in 0 until minOf(array.length(), MAX_RECORDS - 1)) {
                array.optJSONObject(index)?.let { fresh.put(it) }
            }
            prefs.edit().putString(KEY_RECORDS, fresh.toString()).commit()
        }
    }

    private fun readRoot(prefs: android.content.SharedPreferences): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_BUCKETS, "{}") ?: "{}") }.getOrDefault(JSONObject())
}
