package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import java.io.File

/**
 * 跨进程缓存：聊天记录副本 + app 进程生成的建议。
 *
 * 为什么需要它：微信被 NoActive 这类冻结工具冻住时，跑在微信进程里的 agent 一行代码
 * 都不会执行，通知虽然进了队列也没人处理。于是让微信进程在解冻期间把最近几个会话的
 * 聊天记录抄一份到 app 进程，之后 app 进程（不在冻结名单里）收到通知就能自己生成建议，
 * 不再等微信醒过来。
 *
 * 数据放在 app 自己的 files 目录，微信进程通过 SettingsProvider 的 call 方法读写。
 * 用文件而不是 prefs：记录副本可能有几十 KB，prefs 不适合承载这种体量。
 */
object SentryStore {
    private const val RECORDS_FILE = "sentry_records.json"
    private const val SUGGESTIONS_FILE = "sentry_suggestions.json"

    /** 会话索引：只有 talker 和昵称，不含任何聊天内容，用来给白名单挑人。 */
    private const val SESSIONS_FILE = "sentry_sessions.json"

    fun records(context: Context): String = read(context, RECORDS_FILE)

    /**
     * 按昵称反查 talker。
     *
     * 通知摘要跑在通知服务里，拿到的只有 title（群名或昵称），不能像微信进程那样
     * 直接读 talker。这份记录是微信进程同步过来的近期会话，群聊的 talker 以
     * @chatroom 结尾，据此判断是不是群比按正文冒号猜靠谱得多。
     *
     * @return 找不到返回空串，调用方自己退回文本判断
     */
    fun findTalker(context: Context, nick: String): String {
        if (nick.isBlank()) return ""
        val raw = records(context)
        if (raw.isBlank()) return ""
        return runCatching {
            val array = org.json.JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("nick") == nick) return item.optString("talker")
            }
            ""
        }.getOrDefault("")
    }

    fun putRecords(context: Context, json: String) = write(context, RECORDS_FILE, json)

    fun sessions(context: Context): String = read(context, SESSIONS_FILE)

    fun putSessions(context: Context, json: String) = write(context, SESSIONS_FILE, json)

    fun suggestions(context: Context): String = read(context, SUGGESTIONS_FILE)

    fun putSuggestions(context: Context, json: String) = write(context, SUGGESTIONS_FILE, json)

    /**
     * 清掉不再需要的记录副本。开白名单时用：名单外的会话在下次同步前可能还留着旧副本，
     * 这里主动删掉，避免「已经排除的人」的聊天内容继续躺在本地。
     */
    fun purgeRecords(context: Context, keep: (talker: String, nick: String) -> Boolean) {
        val json = records(context)
        if (json.isBlank()) return
        runCatching {
            val source = org.json.JSONArray(json)
            val kept = org.json.JSONArray()
            for (index in 0 until source.length()) {
                val obj = source.optJSONObject(index) ?: continue
                val talker = obj.optString("talker").orEmpty()
                val nick = obj.optString("nick").orEmpty()
                if (keep(talker, nick)) kept.put(obj)
            }
            write(context, RECORDS_FILE, kept.toString())
        }
    }

    /**
     * 最近读到的内容，按文件名缓存。
     *
     * 这几个文件是微信进程写的、app 进程读的，改不了用内存对象直接共享，
     * 但每次读都真去读盘也没必要——通知服务里每条消息都要查一次 talker。
     * 用「修改时间 + 文件大小」判断是否变化：跨进程写入后 mtime 会变，
     * 同秒内的多次写入再由文件大小兜底。
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, String>>()

    private fun read(context: Context, name: String): String = runCatching {
        val file = File(context.filesDir, name)
        if (!file.exists()) return@runCatching ""
        val mtime = file.lastModified()
        val size = file.length()
        cache[name]?.let { (cachedMtime, cachedSize, text) ->
            if (cachedMtime == mtime && cachedSize == size) return@runCatching text
        }
        val text = file.readText()
        cache[name] = Triple(mtime, size, text)
        text
    }.getOrElse {
        // 主文件读不出来时退回备份：以前直接 orEmpty()，读失败和「本来空」
        // 无法区分，上层拿空串当基线继续合并写入，就把旧记录覆盖掉了。
        MoodLog.w("SENTRY_READ_FAILED " + name + " " + it.javaClass.simpleName)
        runCatching {
            val backup = File(context.filesDir, "$name.bak")
            if (backup.exists()) backup.readText() else ""
        }.getOrDefault("")
    }

    private fun write(context: Context, name: String, content: String) {
        // 主文件和备份都原子替换：以前是 file.writeText 直接往正式文件里灌，
        // 进程那一刻被杀就留下半截 JSON，冷启动后读到残缺内容。
        val file = File(context.filesDir, name)
        if (!atomicWrite(file, content)) return
        runCatching { atomicWrite(File(context.filesDir, "$name.bak"), content) }
        // 写完整份再更新缓存：读到的永远是已经落盘的版本
        cache[name] = Triple(file.lastModified(), file.length(), content)
    }

    /** 写临时文件再整份替换。失败返回 false，调用方放弃这一轮。 */
    private fun atomicWrite(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrElse {
        MoodLog.w("SENTRY_ATOMIC_WRITE_FAILED " + target.name + " " + it.javaClass.simpleName)
        false
    }
}
