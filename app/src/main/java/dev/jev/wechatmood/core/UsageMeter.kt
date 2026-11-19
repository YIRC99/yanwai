package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import java.time.LocalDate
import java.util.concurrent.Executors

data class UsageToday(val requests: Int = 0, val promptTokens: Long = 0, val completionTokens: Long = 0) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/**
 * 本地用量统计：只记次数与 token，不记内容。
 *
 * 请求发生在微信进程，由 [UsageReporter] 通过设置服务上报到这里；
 * 跨天自动清零，不做长期归档。
 */
object UsageMeter {
    private const val FILE = "usage_stats"
    private const val KEY_DAY = "day"
    private const val KEY_REQUESTS = "requests"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_COMPLETION = "completion"

    @Synchronized fun add(context: Context, prompt: Long, completion: Long) {
        val prefs = context.getSharedPreferences(FILE, 0)
        val today = LocalDate.now().toString()
        val fresh = prefs.getString(KEY_DAY, null) != today
        val requests = if (fresh) 0 else prefs.getInt(KEY_REQUESTS, 0)
        val prompted = if (fresh) 0L else prefs.getLong(KEY_PROMPT, 0)
        val completed = if (fresh) 0L else prefs.getLong(KEY_COMPLETION, 0)
        prefs.edit().apply {
            if (fresh) clear().putString(KEY_DAY, today)
            putInt(KEY_REQUESTS, requests + 1)
            putLong(KEY_PROMPT, prompted + prompt.coerceAtLeast(0))
            putLong(KEY_COMPLETION, completed + completion.coerceAtLeast(0))
        }.apply()
    }

    fun today(context: Context): UsageToday {
        val prefs = context.getSharedPreferences(FILE, 0)
        if (prefs.getString(KEY_DAY, null) != LocalDate.now().toString()) return UsageToday()
        return UsageToday(prefs.getInt(KEY_REQUESTS, 0), prefs.getLong(KEY_PROMPT, 0), prefs.getLong(KEY_COMPLETION, 0))
    }

    fun summary(context: Context): String {
        val usage = today(context)
        if (usage.requests == 0) return "今天还没有调用模型"
        return "今天 ${usage.requests} 次请求 · ${usage.totalTokens} tokens"
    }
}

/** 微信进程里的上报入口；拿不到设置服务时直接放弃，不影响模型请求。 */
object UsageReporter {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-usage").apply { isDaemon = true } }
    @Volatile private var context: Context? = null

    fun init(context: Context) { this.context = context.applicationContext }

    fun report(prompt: Long = 0, completion: Long = 0) {
        val ctx = context ?: return
        // 只在微信进程上报：主进程自己发起的检测直接记本地即可，避免 provider 自调用。
        if (ctx.packageName != "com.tencent.mm") return
        worker.execute {
            runCatching {
                SettingsTransport.call(ctx, "usage_add", null, Bundle().apply {
                    putLong("prompt", prompt)
                    putLong("completion", completion)
                })
            }
        }
    }
}
