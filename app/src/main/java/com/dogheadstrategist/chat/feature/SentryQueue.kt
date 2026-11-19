package com.dogheadstrategist.chat.feature

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知队列：app 进程收到微信通知后先落这里，微信进程的自动起草 agent 轮询取走。
 *
 * 为什么用队列而不是直接调用：两个进程是独立的，通知监听只能跑在 app 进程
 * （系统 API），而读聊天记录、生成回复只能跑在微信进程（能访问解密后的数据）。
 * 队列是两个进程之间唯一的交接面。
 *
 * 用 revision 做乐观并发：agent 取走时记下版本号，处理完只在版本未变时清空，
 * 避免把其间新到的通知一起抹掉。
 */
object SentryQueue {
    const val KEY_PENDING = "sentry_pending"
    const val KEY_REVISION = "sentry_pending_rev"
    private const val MAX_PENDING = 6

    fun push(context: Context, nick: String, text: String) {
        val prefs = context.getSharedPreferences(com.dogheadstrategist.chat.core.ModulePrefs.FILE_NAME, 0)
        runCatching {
            val list = JSONArray(prefs.getString(KEY_PENDING, "[]") ?: "[]")
            val item = JSONObject().apply {
                put("nick", nick.take(60))
                put("text", text.take(300))
                put("at", System.currentTimeMillis())
            }
            list.put(item)
            val trimmed = JSONArray()
            val from = (list.length() - MAX_PENDING).coerceAtLeast(0)
            for (i in from until list.length()) trimmed.put(list.get(i))
            prefs.edit()
                .putString(KEY_PENDING, trimmed.toString())
                .putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L) + 1L)
                .apply()
        }
    }

    fun pending(context: Context): String =
        runCatching {
            context.getSharedPreferences(com.dogheadstrategist.chat.core.ModulePrefs.FILE_NAME, 0)
                .getString(KEY_PENDING, "[]") ?: "[]"
        }.getOrDefault("[]")

    fun revision(context: Context): Long =
        runCatching {
            context.getSharedPreferences(com.dogheadstrategist.chat.core.ModulePrefs.FILE_NAME, 0)
                .getLong(KEY_REVISION, 0L)
        }.getOrDefault(0L)

    /** 只在版本号未变时清空，防止抹掉处理期间新到的通知。 */
    fun ack(context: Context, seenRevision: Long) {
        val prefs = context.getSharedPreferences(com.dogheadstrategist.chat.core.ModulePrefs.FILE_NAME, 0)
        runCatching {
            if (prefs.getLong(KEY_REVISION, 0L) == seenRevision) {
                prefs.edit().putString(KEY_PENDING, "[]").apply()
            }
        }
    }
}
