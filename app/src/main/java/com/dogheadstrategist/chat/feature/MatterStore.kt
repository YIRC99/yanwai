package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 通知代办：一条被提取出来的「要事」。
 *
 * 标题不单独存——它由 [typeKey] 反查类型得到。用户改了类型的名字，
 * 历史记录里的标题应该跟着变，而不是留着一个当初的快照。
 */
data class MatterItem(
    /** 毫秒时间戳 + 序号，保证单调递增，列表排序和删除都靠它。 */
    val id: Long,
    val typeKey: String,
    /** 模型提炼出来的核心内容，一条一句话。 */
    val content: String,
    /** 提取当时的 emoji 快照。类型被删了也还能显示。 */
    val emoji: String,
    /** 发信人昵称。 */
    val source: String,
    /** 会话 talker，点击回会话时用。 */
    val talker: String,
    /** 原始通知正文，排查时能看到模型是基于什么做的判断。 */
    val rawText: String,
    val createdAt: Long,
    /** 用户在小部件上勾掉了。列表不再显示，但记录留着——误勾还能翻出来。 */
    val done: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("typeKey", typeKey)
        put("content", content)
        put("emoji", emoji)
        put("source", source)
        put("talker", talker)
        put("rawText", rawText)
        put("createdAt", createdAt)
        put("done", done)
    }

    companion object {
        fun fromJson(obj: JSONObject): MatterItem? {
            val id = obj.optLong("id", 0L)
            val content = obj.optString("content").trim()
            if (id <= 0L || content.isEmpty()) return null
            return MatterItem(
                id = id,
                typeKey = obj.optString("typeKey").trim(),
                content = content,
                emoji = obj.optString("emoji").trim().ifBlank { "\uD83D\uDCCC" },
                source = obj.optString("source").trim(),
                talker = obj.optString("talker").trim(),
                rawText = obj.optString("rawText").trim(),
                createdAt = obj.optLong("createdAt", 0L),
                done = obj.optBoolean("done", false),
            )
        }
    }
}

/**
 * 通知代办的持久化。两份文件分开存：
 *
 * - `aide_types.json`：类型配置，很小，改一次写一次。
 * - `aide_items.json`：提取结果，会稳定增长，每次推送都要追加。
 *
 * 拆开是因为写频率差两个数量级。合在一起的话，每来一条要事都要把整份配置
 * 重写一遍，而配置里可能躺着用户手写的十几条自定义类型。
 *
 * 和我的风格一样自己写 JSON 文件而不是走 SharedPreferences：这台机器上
 * commit() 一直返回 true 但磁盘文件几个月不动，落到 prefs 的东西重启就丢。
 *
 * 跨进程写：微信进程不直接碰文件，通过 [com.dogheadstrategist.chat.core.SettingsProvider]
 * 的 `aide_items_put` 把 JSON 推过来，由 app 进程落盘。
 */
object MatterStore {
    private const val FILE_TYPES = "aide_types.json"
    private const val FILE_ITEMS = "aide_items.json"

    /** 最多留多少条。超了按时间从旧到新丢。 */
    const val MAX_ITEMS = 300

    private val typeLock = Any()
    private val itemLock = Any()

    // ===================== 类型 =====================

    /**
     * 当前生效的类型列表。
     *
     * 文件不存在时返回预置六种；文件存在但被清空（长度为 0）时也返回预置——
     * 用户把自定义类型全删光了不该导致模型没有类型可认。
     */
    fun types(context: Context): List<MatterType> = typesOrNull(context) ?: MatterType.PRESETS

    /** 读类型清单。返回 null 表示文件损坏且无备份可救——写路径必须据此放弃。 */
    private fun typesOrNull(context: Context): List<MatterType>? {
        synchronized(typeLock) {
            val array = loadArray(context, FILE_TYPES) ?: return null
            if (array.length() == 0) return MatterType.PRESETS
            val list = ArrayList<MatterType>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                MatterType.fromJson(obj)?.let { list.add(it) }
            }
            return if (list.isEmpty()) MatterType.PRESETS else list
        }
    }

    /** 给 SettingsProvider.snapshot() 打包用。微信进程拼 prompt 时需要这份清单。 */
    fun typesJson(context: Context): String {
        val array = JSONArray()
        for (type in types(context)) array.put(type.toJson())
        return array.toString()
    }

    fun saveTypes(context: Context, list: List<MatterType>) {
        synchronized(typeLock) {
            val array = JSONArray()
            for (type in list) array.put(type.toJson())
            write(context, FILE_TYPES, array.toString())
        }
    }

    fun addType(context: Context, type: MatterType): Boolean {
        val current = typesOrNull(context) ?: return false
        if (current.any { it.key == type.key }) return false
        saveTypes(context, current + type)
        return true
    }

    /**
     * 改一个已有类型。
     *
     * 预置类型允许改描述和 emoji，但不允许改 key——key 是模型回填 title 的匹配依据，
     * 改了历史记录就认不回来了。
     */
    fun updateType(context: Context, type: MatterType): Boolean {
        val current = typesOrNull(context) ?: return false
        val index = current.indexOfFirst { it.key == type.key }
        if (index < 0) return false
        val merged = current.toMutableList()
        merged[index] = type.copy(preset = current[index].preset)
        saveTypes(context, merged)
        return true
    }

    /** 删自定义类型。预置类型拒绝删除，返回 false。 */
    /**
     * 删除一个类型。
     *
     * 预置类型也允许删：六个预置是按「对话里可能出现什么要记的事」分的，
     * 但每个人的情况不一样，用户用不上的就该能清掉，留在列表里只是噪音。
     *
     * 唯一守住的是「至少留一个」：类型清单是模型归类的全部依据，全删光之后
     * 通知代办不会报错，只会安静地什么都提取不出来，用户还以为坏了。与其让人
     * 从这种静默失败里猜原因，不如在这里直接挡住。
     *
     * 删掉的预置随时可以用 [restoreMissingPresets] 加回来。
     */
    fun deleteType(context: Context, key: String): Boolean {
        val current = typesOrNull(context) ?: return false
        if (current.none { it.key == key }) return false
        if (current.size <= 1) return false
        saveTypes(context, current.filterNot { it.key == key })
        return true
    }

    /**
     * 把被删掉的预置类型补回来，已有的不覆盖。
     *
     * 预置能删之后必须有这条退路：用户删完想反悔，靠「新建类型」自己填
     * 描述和示例是补不回来的，只有这里能拿回出厂的那份定义。
     *
     * @return 补回了几个
     */
    fun restoreMissingPresets(context: Context): Int {
        val current = typesOrNull(context) ?: return 0
        val keys = current.map { it.key }.toHashSet()
        val missing = MatterType.PRESETS.filterNot { it.key in keys }
        if (missing.isEmpty()) return 0
        saveTypes(context, current + missing)
        return missing.size
    }

    /** 把某个预置类型恢复成出厂描述。 */
    fun resetType(context: Context, key: String): Boolean {
        val preset = MatterType.presetOf(key) ?: return false
        return updateType(context, preset)
    }

    // ===================== 事项 =====================

    fun items(context: Context): List<MatterItem> = itemsOrNull(context) ?: emptyList()

    /** 读事项清单。返回 null 表示文件损坏且无备份可救——写路径必须据此放弃。 */
    private fun itemsOrNull(context: Context): List<MatterItem>? {
        synchronized(itemLock) {
            val array = loadArray(context, FILE_ITEMS) ?: return null
            val list = ArrayList<MatterItem>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                MatterItem.fromJson(obj)?.let { list.add(it) }
            }
            // 新的在前
            return list.sortedByDescending { it.createdAt }
        }
    }

    /** 给小组件用：最近几条未完成的事。勾掉的留在文件里，只是不再占位置。 */
    fun recentItems(context: Context, limit: Int): List<MatterItem> =
        items(context).filterNot { it.done }.take(limit)

    /** 勾掉或恢复一条。 */
    fun setDone(context: Context, id: Long, done: Boolean) {
        synchronized(itemLock) {
            val list = itemsOrNull(context) ?: return
            if (list.none { it.id == id }) return
            saveItems(context, list.map { if (it.id == id) it.copy(done = done) else it })
        }
    }

    /**
     * 微信进程推过来一批要事。
     *
     * 入参是 JSON 数组字符串。整批校验后再落盘：单条格式不对就跳过，
     * 不因为一条脏数据把这一批全丢掉——通知里出现奇怪字符是常事。
     */
    fun putRemote(context: Context, json: String): Boolean {
        if (json.isBlank()) return false
        val incoming = runCatching { JSONArray(json) }.getOrNull() ?: return false
        if (incoming.length() == 0) return false
        synchronized(itemLock) {
            val merged = itemsOrNull(context)?.toMutableList() ?: return false
            val existingIds = merged.mapTo(HashSet()) { it.id }
            var added = 0
            for (index in 0 until incoming.length()) {
                val obj = incoming.optJSONObject(index) ?: continue
                val item = MatterItem.fromJson(obj) ?: continue
                if (existingIds.contains(item.id)) continue
                existingIds.add(item.id)
                merged.add(item)
                added++
            }
            if (added == 0) return false
            saveItems(context, merged)
            MoodLog.i("AIDE_ITEMS_PUT added=$added total=${merged.size}")
            return true
        }
    }

    fun deleteItem(context: Context, id: Long) {
        synchronized(itemLock) {
            val current = itemsOrNull(context) ?: return
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return
            saveItems(context, next)
        }
    }

    fun clear(context: Context) {
        synchronized(itemLock) { saveItems(context, emptyList()) }
    }

    /**
     * 清理过期和超量的记录。
     *
     * 过期判断用「类型当前配置的 expireHours」而不是「创建时的值」：
     * 用户把待办改成 7 天、把金额改成 1 天，历史记录应该立刻按新规则走。
     * 类型被删掉了就按 24 小时算，不让它们永久堆着。
     */
    fun prune(context: Context) {
        val types = types(context).associateBy { it.key }
        val now = System.currentTimeMillis()
        synchronized(itemLock) {
            val current = itemsOrNull(context) ?: return
            val kept = current.filter { item ->
                val hours = types[item.typeKey]?.expireHours ?: 24
                hours <= 0 || now - item.createdAt < hours * 3_600_000L
            }.take(MAX_ITEMS)
            if (kept.size != current.size) {
                saveItems(context, kept)
                MoodLog.i("AIDE_PRUNE before=${current.size} after=${kept.size}")
            }
        }
    }

    // ===================== 落盘 =====================

    private fun saveItems(context: Context, list: List<MatterItem>) {
        val array = JSONArray()
        for (item in list.sortedByDescending { it.createdAt }) array.put(item.toJson())
        write(context, FILE_ITEMS, array.toString())
    }

    /**
     * 读一个 JSON 数组文件。
     *
     * 返回 null 表示「读不出来且没有备份可救」：IO 失败、或内容损坏且 .bak 也救不回。
     * 以前这里用 getOrNull() 吞掉一切，读失败和「文件本来空」无法区分，
     * 一次读失败就会让写入路径拿空列表当基线，把攒着的记录覆盖成空。
     */
    private fun loadArray(context: Context, name: String): JSONArray? {
        val file = File(context.filesDir, name)
        val backup = File(context.filesDir, "$name.bak")
        if (!file.exists()) return JSONArray()
        val text = runCatching { file.readText() }.getOrElse {
            MoodLog.w("AIDE_STORE_READ_FAILED $name " + it.javaClass.simpleName)
            return null
        }
        return runCatching { JSONArray(text) }.getOrElse { error ->
            MoodLog.w("AIDE_STORE_CORRUPT $name " + error.javaClass.simpleName)
            val recovered = runCatching {
                if (backup.exists()) JSONArray(backup.readText()) else null
            }.getOrNull()
            if (recovered != null) {
                MoodLog.w("AIDE_STORE_RECOVERED $name n=" + recovered.length())
                runCatching { atomicWrite(file, recovered.toString()) }
                recovered
            } else {
                runCatching { file.copyTo(File(file.parentFile, "$name.corrupt"), overwrite = true) }
                null
            }
        }
    }

    private fun write(context: Context, name: String, text: String) {
        // 主文件和备份都原子替换：以前 renameTo 失败会退回 file.writeText，
        // 进程那一刻被杀就留下半截 JSON，冷启动读到空 → 下次写入覆盖历史记录。
        if (!atomicWrite(File(context.filesDir, name), text)) return
        runCatching { atomicWrite(File(context.filesDir, "$name.bak"), text) }
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
        MoodLog.w("AIDE_ATOMIC_WRITE_FAILED " + target.name + " " + it.javaClass.simpleName)
        false
    }
}
