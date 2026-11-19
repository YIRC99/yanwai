package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条已经收到、还没送去提取的微信通知。 */
data class PendingNotice(
    val id: Long,
    val sender: String,
    val text: String,
    val talker: String,
    val createdAt: Long,
    /**
     * 连续失败次数。
     *
     * 有的文本会让模型稳定报错（超长、怪字符导致 JSON 截断）。这种条目如果一直留在
     * 队列里，每轮都重试同一批，后面的要事永远轮不到——必须能放弃它。
     */
    val failCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sender", sender)
        put("text", text)
        put("talker", talker)
        put("createdAt", createdAt)
        if (failCount > 0) put("failCount", failCount)
    }

    companion object {
        fun fromJson(obj: JSONObject): PendingNotice? {
            val id = obj.optLong("id", 0L)
            val text = obj.optString("text").trim()
            if (id <= 0L || text.isEmpty()) return null
            return PendingNotice(
                id = id,
                sender = obj.optString("sender").trim(),
                text = text,
                talker = obj.optString("talker").trim(),
                createdAt = obj.optLong("createdAt", 0L),
                failCount = obj.optInt("failCount", 0),
            )
        }
    }
}

/**
 * 通知代办的待处理队列。
 *
 * 通知到达时只做一件事：把正文塞进这个文件，不调模型。
 * [AideDigest] 定期把攒下的这一批一次性送出去，比每条发一次请求省得多，
 * 也不会和时间敏感的通知摘要抢同一次调用。
 *
 * 队列有上限。通知刷屏时宁可丢掉最旧的几条，也不能让它无限长下去—
 * 这个文件在 app 的 filesDir 里，涨太快会拖慢每次读写。
 */
object AideInbox {
    private const val FILE = "aide_inbox.json"

    /**
     * 最多攒这么多。超了丢最旧的。
     *
     * 去掉本地预筛后入队量会明显变多，上限必须跟着放大：挤掉是数据丢失，
     * 比多花点模型钱严重。400 条 × 300 字约 450KB，读写一次的开销可以接受。
     */
    private const val MAX_PENDING = 400

    /**
     * 单个会话最多占多少条。
     *
     * 一个群刷屏时不该把整个队列吃满，把别人的要事全挤出去。
     * 这不算预筛——它不看内容，只是按来源配额。
     */
    private const val MAX_PER_SENDER = 20

    /** 单条正文截断长度。提取只需要要点，长文尾巴没有价值。 */
    private const val MAX_TEXT = 300

    private val lock = Any()

    fun push(context: Context, sender: String, text: String, talker: String) {
        val body = text.trim().take(MAX_TEXT)
        if (body.isEmpty()) return
        synchronized(lock) {
            val current = load(context)?.toMutableList() ?: return
            // 同一会话同一句话在短时间内重复推送（微信会重发更新后的通知），跳过
            val duplicate = current.any { it.sender == sender && it.text == body }
            if (duplicate) return
            // 这个会话已经占满配额，就先丢掉它自己最旧的一条。
            // 丢自己的、不丢别人的：刷屏不该把其他会话的要事挤出队列
            val sameSender = current.filter { it.sender == sender }
            if (sameSender.size >= MAX_PER_SENDER) {
                sameSender.minByOrNull { it.createdAt }?.let { current.remove(it) }
            }
            current.add(
                PendingNotice(
                    id = System.currentTimeMillis() * 1000 + (seq++ % 1000),
                    sender = sender,
                    text = body,
                    talker = talker,
                    createdAt = System.currentTimeMillis(),
                )
            )
            val trimmed = if (current.size > MAX_PENDING) current.takeLast(MAX_PENDING) else current
            save(context, trimmed)
        }
    }

    fun pending(context: Context): List<PendingNotice> = synchronized(lock) { load(context) ?: emptyList() }

    fun count(context: Context): Int = synchronized(lock) { load(context)?.size ?: 0 }

    /**
     * 记一次失败。
     *
     * 连续失败到 [giveUpAfter] 次就放弃这条——它多半让模型稳定报错（超长、怪字符、
     * JSON 被截断）。不放弃的话每轮重试的都是同一批，队列永远不前进。
     */
    fun markFailed(context: Context, ids: Set<Long>, giveUpAfter: Int = 3) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val current = load(context) ?: return
            val next = ArrayList<PendingNotice>(current.size)
            for (item in current) {
                if (!ids.contains(item.id)) {
                    next.add(item)
                    continue
                }
                val fails = item.failCount + 1
                if (fails >= giveUpAfter) {
                    MoodLog.w(
                        "AIDE_INBOX_GIVE_UP id=" + item.id + " sender=" + item.sender.take(12) +
                            " fails=" + fails,
                    )
                } else {
                    next.add(item.copy(failCount = fails))
                }
            }
            save(context, next)
        }
    }

    /** 处理完的移出队列。失败的留在里面等下一轮。 */
    fun remove(context: Context, ids: Set<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val current = load(context) ?: return
            val next = current.filterNot { ids.contains(it.id) }
            if (next.size == current.size) return
            save(context, next)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { save(context, emptyList()) }
    }

    /** 同一毫秒内多条通知时区分 id。 */
    private var seq = 0

    /**
     * 读队列。
     *
     * 返回 null 表示「文件损坏且没有备份可救」——调用方必须据此放弃写入，
     * 不能再像以前那样 getOrNull() ?: emptyList()，那样读失败和「队列本来空」
     * 无法区分，一次读失败就会把攒着的待办覆盖成空。
     */
    private fun load(context: Context): List<PendingNotice>? {
        val file = File(context.filesDir, FILE)
        val backup = File(context.filesDir, "$FILE.bak")
        if (!file.exists()) return emptyList()
        val text = file.readText()      // IO 失败让异常抛出去
        val array = runCatching { JSONArray(text) }.getOrElse { error ->
            MoodLog.w("AIDE_INBOX_CORRUPT " + error.javaClass.simpleName)
            val recovered = runCatching {
                if (backup.exists()) JSONArray(backup.readText()) else null
            }.getOrNull()
            if (recovered != null) {
                MoodLog.w("AIDE_INBOX_RECOVERED_FROM_BAK n=" + recovered.length())
                runCatching { atomicWrite(file, recovered.toString()) }
                recovered
            } else {
                runCatching { file.copyTo(File(file.parentFile, "$FILE.corrupt"), overwrite = true) }
                return null
            }
        }
        val list = ArrayList<PendingNotice>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            PendingNotice.fromJson(obj)?.let { list.add(it) }
        }
        runCatching { if (!backup.exists()) atomicWrite(backup, array.toString()) }
        return list
    }

    private fun save(context: Context, list: List<PendingNotice>) {
        val array = JSONArray()
        for (item in list) array.put(item.toJson())
        val text = array.toString()
        if (!atomicWrite(File(context.filesDir, FILE), text)) return
        runCatching { atomicWrite(File(context.filesDir, "$FILE.bak"), text) }
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
        MoodLog.w("AIDE_INBOX_ATOMIC_WRITE_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
        false
    }
}
