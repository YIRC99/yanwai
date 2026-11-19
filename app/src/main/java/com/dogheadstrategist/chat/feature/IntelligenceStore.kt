package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 人物档案的持久化：每个聊天对象一份画像 + 一批待总结的消息。
 *
 * 和 [WarReviewStore] 同样自己写 JSON 文件、临时文件再 rename，
 * 原因是这台机器上 SharedPreferences 写入不落盘（见 WarReviewStore 的说明）。
 *
 * 结构：
 * ```
 * {
 *   "talkers": {
 *     "<talker>": {
 *       "nick":      "群名或昵称",
 *       "profile":   "画像正文，空串表示还没总结过",
 *       "pending":   [{"s":"说话人","t":"正文"}],
 *       "seen":      ["<指纹>"],  // 已消化过的消息指纹，防止重复总结同一句
 *       "samples":   12,          // 累计被总结过的消息条数
 *       "updatedAt": 1740000000000
 *     }
 *   }
 * }
 * ```
 *
 * pending 存的是「已经落库、但还没被总结进画像」的消息。总结成功后整批清掉，
 * 所以 pending 的长度就是「攒了多少还没消化」——触发判断直接看它。
 */
/**
 * 一条待分析的聊天消息。
 *
 * [mid] 是微信的消息主键，去重就靠它——比「说话人+正文」的摘要可靠：
 * 同一个人连说两句「好的」是两条不同的消息，用正文摘要会被误合并成一条。
 * 老消息可能没有 mid，这时退回正文摘要。
 *
 * [send] 区分「对方说的」和「我说的」：画像要描述的是对面这个人，
 * 拿自己说的话去推断对方风格会跑偏。
 */
data class IntelMessage(
    val speaker: String,
    val text: String,
    val send: Boolean = false,
    val mid: String = "",
)

object IntelligenceStore {
    private const val FILE = "intelligence.json"
    private const val KEY_TALKERS = "talkers"

    /** pending 上限。攒到阈值就会总结，留个余量防止模型一直失败时无限堆积。 */
    private const val MAX_PENDING = 200

    /**
     * 一次总结消化多少条。
     *
     * 必须和 ContactProfileProtocol 实际喂给模型的条数对齐：模型只吃一批，
     * 清理却清掉全部的话，多出来的那些既没进画像、指纹又记进了 seen，就永久丢了。
     * 所以 peek 只给最旧的一批、clear 只删这一批，剩下的下一轮接着消化。
     */
    const val BATCH = 30

    /**
     * seen 保留多少条指纹。
     *
     * records 只是「最近几个会话的滚动副本」，同一条消息会在里面停留很久。
     * 指纹留得太少，老消息会被当成新的重新攒一遍，画像就会来回重复同一句话。
     */
    private const val MAX_SEEN = 400

    private val lock = Any()
    private var cache: JSONObject? = null

    /** 只读快照。 */
    /** 只读快照。读失败时返回空快照——调用方只读，用空数据不会破坏磁盘内容。 */
    fun read(context: Context): Snapshot = synchronized(lock) {
        Snapshot(
            runCatching { load(context) }.getOrElse {
                MoodLog.w("INTEL_LOAD_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
                null
            } ?: JSONObject()
        )
    }
    /** 读改写，一次原子落盘。 */
    /** 读改写，一次原子落盘。 */
    fun edit(context: Context, block: Editor.() -> Unit) = synchronized(lock) {
        // load 返回 null 表示「文件损坏且没有备份可救」。必须放弃这一轮写入：
        // 继续用空对象写下去，会把磁盘上的历史覆盖成「只有这次新增的内容」。
        val obj = runCatching { load(context) }.getOrElse {
            MoodLog.w("INTEL_LOAD_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
            null
        } ?: return
        Editor(obj).block()
        write(context, obj)
    }

    private fun load(context: Context): JSONObject? {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val backup = File(context.filesDir, "$FILE.bak")
        // 文件不存在是正常的首次运行
        if (!file.exists()) return JSONObject()
        // IO 失败让异常抛出去：上层据此放弃这次写入，避免覆盖
        val text = file.readText()
        val obj = runCatching { JSONObject(text) }.getOrElse { error ->
            MoodLog.w("INTEL_CORRUPT " + error.javaClass.simpleName)
            // 主文件坏了先看备份：以前这里直接 getOrDefault(空对象)，等于把历史判死刑
            val recovered = runCatching {
                if (backup.exists()) JSONObject(backup.readText()) else null
            }.getOrNull()
            if (recovered != null) {
                MoodLog.w("INTEL_RECOVERED_FROM_BAK keys=" + recovered.length())
                runCatching { atomicWrite(file, recovered.toString()) }
                recovered
            } else {
                // 没有备份可救：留一份证据但不删原文件，返回 null 让调用方放弃写入
                runCatching { file.copyTo(File(file.parentFile, "$FILE.corrupt"), overwrite = true) }
                return null
            }
        }
        cache = obj
        // 首次读到就补一份底，避免「备份还没生成就损坏」的空窗期
        runCatching { if (!backup.exists()) atomicWrite(backup, obj.toString()) }
        return obj
    }

    private fun write(context: Context, obj: JSONObject) {
        val text = runCatching { obj.toString() }.getOrElse {
            MoodLog.w("INTEL_WRITE_FAILED serialize " + it.javaClass.simpleName)
            return
        }
        // 主文件和备份都走原子替换：以前 tmp.renameTo 失败会退回 file.writeText，
        // 进程在那一刻被杀就留下写了一半的 JSON，冷启动后读到空 → 下一次写入覆盖历史。
        if (!atomicWrite(File(context.filesDir, FILE), text)) return
        runCatching { atomicWrite(File(context.filesDir, "$FILE.bak"), text) }
        cache = obj
    }

    /**
     * 写临时文件再整份替换。
     *
     * 失败返回 false，调用方放弃这一轮——宁可这次不写，也不能直接往正式文件里写。
     */
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
            // 个别文件系统不支持原子替换：退回普通整份替换，仍不会写坏正式文件
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrElse {
        MoodLog.w("INTEL_ATOMIC_WRITE_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
        false
    }

    // ===================== 便捷读写 =====================

    /** 该对象的画像；没有返回空串。 */
    fun profile(context: Context, talker: String): String =
        read(context).talker(talker)?.optString("profile").orEmpty()

    /** 该对象还攒着多少条没消化的消息。 */
    fun pendingCount(context: Context, talker: String): Int =
        read(context).talker(talker)?.optJSONArray("pending")?.length() ?: 0

    /** 该对象累计被总结过多少条。 */
    fun sampleCount(context: Context, talker: String): Int =
        read(context).talker(talker)?.optInt("samples") ?: 0

    fun updatedAt(context: Context, talker: String): Long =
        read(context).talker(talker)?.optLong("updatedAt") ?: 0L

    /**
     * 把一批新消息追加进 pending。
     *
     * 同一条消息可能被多次扫描到（records 是滚动副本），所以调用方传进来的
     * 应该已经是「没见过的」。这里再做一次尾部去重兜底，避免模型反复总结同一句。
     */
    fun appendPending(
        context: Context,
        talker: String,
        nick: String,
        samples: List<IntelMessage>,
    ) {
        if (samples.isEmpty()) return
        edit(context) {
            val entry = objFor(talker, nick)
            val pending = entry.optJSONArray("pending") ?: JSONArray()
            val seen = entry.optJSONArray("seen") ?: JSONArray()
            // pending 内的、以及已经消化过的，都不再收
            val known = HashSet<String>()
            for (index in 0 until pending.length()) {
                val item = pending.optJSONObject(index) ?: continue
                known.add(keyOf(item.optString("m"), item.optString("s"), item.optString("t")))
            }
            for (index in 0 until seen.length()) {
                seen.optString(index)?.takeIf { it.isNotBlank() }?.let { known.add(it) }
            }
            var added = 0
            for (message in samples) {
                if (message.text.isBlank()) continue
                if (!known.add(keyOf(message.mid, message.speaker, message.text))) continue
                pending.put(JSONObject().apply {
                    put("s", message.speaker)
                    put("t", message.text)
                    put("m", message.mid)
                    put("o", message.send)
                })
                added++
            }
            // 超过上限时丢最旧的：画像已经消化过前面那些，留着只会撑大文件。
            // 注意要算出最终数组再一次性 put——先 put(kept) 再 put(pending) 会让截断失效。
            val finalPending = if (pending.length() > MAX_PENDING) {
                JSONArray().apply {
                    for (index in pending.length() - MAX_PENDING until pending.length()) {
                        pending.opt(index)?.let { put(it) }
                    }
                }
            } else {
                pending
            }
            if (added > 0 || finalPending !== pending) entry.put("pending", finalPending)
            entry.put("nick", nick)
        }
    }

    /**
     * 只看 pending 里最旧的一批，不动任何状态。
     *
     * 和 [clearPending] 配对使用：先 peek 拿去跑模型，成功了才把这一批清掉。
     * 这样模型失败时 pending 原封不动留在原地，下一轮还能接着用——
     * 如果先取后清、失败再放回，会因为 seen 里已经记了指纹而被判重丢掉。
     *
     * 只取最旧的 [BATCH] 条而不是全部：模型一次吃不了那么多，
     * 全给出去再全清掉，多出来的会被静默吞掉。
     */
    fun peekPending(context: Context, talker: String): List<IntelMessage> {
        val entry = read(context).talker(talker) ?: return emptyList()
        val pending = entry.optJSONArray("pending") ?: return emptyList()
        val out = ArrayList<IntelMessage>(BATCH.coerceAtMost(pending.length()))
        for (index in 0 until pending.length()) {
            if (out.size >= BATCH) break
            val item = pending.optJSONObject(index) ?: continue
            val text = item.optString("t")
            if (text.isBlank()) continue
            out.add(
                IntelMessage(
                    speaker = item.optString("s"),
                    text = text,
                    send = item.optBoolean("o"),
                    mid = item.optString("m"),
                )
            )
        }
        return out
    }

    /**
     * 总结成功后，把「真正交给模型的那一批」清掉，并把这批的指纹写进 seen。
     *
     * 关键点是按 [consumed] 精确删除，而不是清空整个 pending：
     * pending 里可能还有这一批之后攒下的新消息，一起清掉等于丢数据。
     *
     * 只有模型确实返回了非空结果才该调这里——否则这批样本会永远不再被总结。
     */
    fun clearPending(context: Context, talker: String, consumed: List<IntelMessage>) {
        if (consumed.isEmpty()) return
        val done = HashSet<String>(consumed.size)
        for (message in consumed) done.add(keyOf(message.mid, message.speaker, message.text))
        edit(context) {
            val entry = objFor(talker, "")
            val pending = entry.optJSONArray("pending") ?: return@edit
            val kept = JSONArray()
            for (index in 0 until pending.length()) {
                val item = pending.optJSONObject(index) ?: continue
                val key = keyOf(item.optString("m"), item.optString("s"), item.optString("t"))
                if (key in done) continue
                kept.put(item)
            }
            entry.put("pending", kept)

            val seen = entry.optJSONArray("seen") ?: JSONArray()
            val merged = ArrayList<String>(seen.length() + done.size)
            for (index in 0 until seen.length()) {
                seen.optString(index)?.takeIf { it.isNotBlank() }?.let { merged.add(it) }
            }
            merged.addAll(done)
            val trimmed = JSONArray()
            // 保留最新的：老指纹过期后就算被 records 重新扫到，也会因为不在 seen 里
            // 而重新进 pending——但那时它早已进了画像，重复一次无害，丢数据才是问题。
            for (fp in merged.takeLast(MAX_SEEN)) trimmed.put(fp)
            entry.put("seen", trimmed)
        }
    }

    /** 成功总结一批之后才记数，避免失败重试导致重复累计。 */
    fun recordSamples(context: Context, talker: String, count: Int) {
        if (count <= 0) return
        edit(context) {
            val entry = objFor(talker, "")
            entry.put("samples", entry.optInt("samples") + count)
        }
    }

    /**
     * 写入新画像。
     *
     * 「旧画像为空时直接覆盖」这条要特别小心：调用方只在模型返回了非空结果时才会调到这里，
     * 所以这里的覆盖是安全的——不会把一份好画像冲成空白。
     */
    fun setProfile(context: Context, talker: String, profile: String, now: Long) {
        if (profile.isBlank()) return
        edit(context) {
            val entry = objFor(talker, "")
            entry.put("profile", profile)
            entry.put("updatedAt", now)
        }
    }

    fun clear(context: Context, talker: String) {
        edit(context) {
            talkers().remove(talker)
        }
    }

    fun clearAll(context: Context) {
        edit(context) {
            reset()
        }
    }

    /** 有 pending 或有画像的对象列表，按更新时间倒序。 */
    fun entries(context: Context): List<Entry> {
        val snapshot = read(context)
        val talkers = snapshot.talkers()
        val out = ArrayList<Entry>()
        for (talker in talkers.keys()) {
            val obj = talkers.optJSONObject(talker) ?: continue
            val profile = obj.optString("profile")
            val pending = obj.optJSONArray("pending")?.length() ?: 0
            if (profile.isBlank() && pending == 0) continue
            out.add(
                Entry(
                    talker = talker,
                    nick = obj.optString("nick").ifBlank { talker },
                    profile = profile,
                    pending = pending,
                    samples = obj.optInt("samples"),
                    updatedAt = obj.optLong("updatedAt"),
                ),
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    // ===================== 内部结构 =====================

    /**
     * 去重键：有 mid 就用 mid，没有才退回正文摘要。
     *
     * 不用 hashCode 做摘要：48 位在几百条的量级上碰撞概率不低，一旦撞上就会把
     * 一条真消息当成「已消化」丢掉。这里用长度 + 滚动哈希，稳定且几乎不会撞。
     */
    private fun keyOf(mid: String, speaker: String, text: String): String {
        if (mid.isNotBlank()) return "m:" + mid
        return "h:" + fingerprint(speaker, text)
    }

    private fun fingerprint(speaker: String, text: String): String {
        val key = speaker + "\u0000" + text
        var hash = 1125899906842597L
        for (ch in key) {
            hash = 31 * hash + ch.code
        }
        return hash.toString(36) + ":" + key.length
    }

    /**
     * @param label 用户在微信里给这个人设的身份名（如「老婆」）。由 [Intelligence.entries]
     *   查一次身份库填进来——存储层不依赖 reply 包，所以这里只是个可空的展示字段。
     */
    data class Entry(
        val talker: String,
        val nick: String,
        val profile: String,
        val pending: Int,
        val samples: Int,
        val updatedAt: Long,
        val label: String = "",
    )

    class Snapshot(private val obj: JSONObject) {
        fun talkers(): JSONObject = obj.optJSONObject(KEY_TALKERS) ?: JSONObject()
        fun talker(key: String): JSONObject? = talkers().optJSONObject(key)
    }

    class Editor(private val obj: JSONObject) {
        /** 清空全部对象。obj 是 private，所以由 Editor 自己来做这件事。 */
        fun reset() {
            obj.put(KEY_TALKERS, JSONObject())
        }

        fun talkers(): JSONObject {
            val existing = obj.optJSONObject(KEY_TALKERS)
            if (existing != null) return existing
            val fresh = JSONObject()
            obj.put(KEY_TALKERS, fresh)
            return fresh
        }

        /** 取该 talker 的子对象，没有就建一个。建的时候顺手把 nick 写上。 */
        fun objFor(talker: String, nick: String): JSONObject {
            val all = talkers()
            val existing = all.optJSONObject(talker)
            if (existing != null) {
                if (nick.isNotBlank() && existing.optString("nick") != nick) {
                    existing.put("nick", nick)
                }
                return existing
            }
            val fresh = JSONObject().apply {
                put("nick", nick)
                put("profile", "")
                put("pending", JSONArray())
                put("seen", JSONArray())
                put("samples", 0)
                put("updatedAt", 0L)
            }
            all.put(talker, fresh)
            return fresh
        }
    }
}
