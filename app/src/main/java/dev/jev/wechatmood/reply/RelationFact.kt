package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheDatabase
import dev.jev.wechatmood.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 一条关系事实。只保存用户本人或模型从聊天里明确看到的内容，
 * 不做推断；[confidence] 只是模型的自我评估，界面不和它较真。
 */
data class RelationMeta(val lastAt: Long = 0, val lastMessageId: Long = 0)

data class RelationFact(
    val kind: String,
    val text: String,
    val evidenceTime: Long = 0,
    val updatedAt: Long = 0,
    val source: String = "auto",
    val confidence: Double = 0.6,
    /** 稳定标识；从旧版 JSON 迁移出来的条目按内容派生，反复读取不会换 id。 */
    val id: String = "",
    val status: String = STATUS_ACTIVE,
    /** 约定、重要日期的绝对时间；0 表示这条没有时间语义，不能进待办。 */
    val occurredAt: Long = 0,
    /** 依据消息的 localId；0 表示只知道批次，不知道具体哪条。 */
    val sourceChatMessageId: Long = 0,
) {
    val isOpen: Boolean get() = status == STATUS_ACTIVE
    val isTimed: Boolean get() = kind in TIMED_KINDS

    /** 空 id 也能给出一个可用的稳定标识，避免同一条事实被当成两条。 */
    val factId: String get() = id.takeIf { it.matches(ID_PATTERN) } ?: derivedId(kind, text, occurredAt)

    fun encode(): JSONObject = JSONObject().put("kind", kind).put("text", text)
        .put("evidence_time", evidenceTime).put("updated_at", updatedAt)
        .put("source", source).put("confidence", confidence)
        .put("id", factId).put("status", status)
        .put("occurred_at", occurredAt).put("source_chat_message_id", sourceChatMessageId)

    fun hint(): String = text

    companion object {
        val KINDS = listOf("工作", "城市", "家人", "喜好", "忌口", "宠物", "重要日期", "约定", "称呼")

        /** 这两类必须带绝对时间才有用；时间变了，旧条直接作废而不是并存。 */
        val TIMED_KINDS = setOf("约定", "重要日期")

        const val STATUS_ACTIVE = "active"
        const val STATUS_DONE = "done"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_SUPERSEDED = "superseded"
        val STATUSES = setOf(STATUS_ACTIVE, STATUS_DONE, STATUS_CANCELLED, STATUS_SUPERSEDED)

        /** 手动能改的只有这三种；superseded 是自动合并的结论，手改会让待办和档案对不上。 */
        val MANUAL_STATUSES = setOf(STATUS_ACTIVE, STATUS_DONE, STATUS_CANCELLED)

        const val MAX_TEXT = 60
        internal val ID_PATTERN = Regex("[0-9a-f]{16}")

        /**
         * 内容派生 id：同一句话永远得到同一个 id，迁移和重复提交都不会产生新条目。
         * 时间型条目把日期也算进去：周六改到周日是另一件事，两条要能同时存在（旧的标 superseded）。
         */
        internal fun derivedId(kind: String, text: String, occurredAt: Long = 0): String {
            val salt = if (kind in TIMED_KINDS && occurredAt > 0) FactTimes.dayKey(occurredAt) else ""
            val source = kind + "\u0000" + text.filter { it.isLetterOrDigit() } + "\u0000" + salt
            return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)
        }

        fun decode(json: JSONObject): RelationFact? {
            val kind = json.optString("kind").trim()
            val text = json.optString("text").trim()
            if (kind !in KINDS || text.isBlank() || text.length > MAX_TEXT) return null
            val confidence = json.optDouble("confidence", 0.6).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.6
            val status = json.optString("status").takeIf { it in STATUSES } ?: STATUS_ACTIVE
            val occurredAt = json.optLong("occurred_at", 0).coerceAtLeast(0)
            val id = json.optString("id").trim().takeIf { it.matches(ID_PATTERN) } ?: derivedId(kind, text, occurredAt)
            return RelationFact(kind, text, json.optLong("evidence_time", 0), json.optLong("updated_at", 0),
                json.optString("source", "auto").takeIf { it == "manual" } ?: "auto", confidence,
                id = id,
                status = status,
                occurredAt = occurredAt,
                sourceChatMessageId = json.optLong("source_chat_message_id", 0).coerceAtLeast(0))
        }
    }
}

/** 模型给的相对时间（周五、下周三）在抽取当刻换算成绝对时间，之后不再依赖自然语言。 */
object FactTimes {
    private val PATTERNS = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")

    fun parseLocal(text: String): Long {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > 32) return 0
        for (pattern in PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.CHINA).apply {
                isLenient = false
                timeZone = TimeZone.getDefault()
            }
            val parsed = runCatching { format.parse(raw) }.getOrNull() ?: continue
            return parsed.time
        }
        return 0
    }

    fun formatLocal(value: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
        SimpleDateFormat(pattern, Locale.CHINA).format(Date(value))

    fun dayKey(value: Long): String = formatLocal(value, "yyyy-MM-dd")

    /** 抽取时只说了日期会写成 00:00；这种条目的“几点”是假的，界面不该显示成零点。 */
    fun hasClock(value: Long): Boolean {
        if (value <= 0) return false
        val calendar = Calendar.getInstance().apply { timeInMillis = value }
        return calendar.get(Calendar.HOUR_OF_DAY) != 0 || calendar.get(Calendar.MINUTE) != 0
    }

    fun startOfDay(value: Long): Long = Calendar.getInstance().apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 今日／明日／昨天，其余写日期；只到日的不补 00:00。 */
    fun label(value: Long, now: Long = System.currentTimeMillis()): String {
        if (value <= 0) return "时间未知"
        val days = ((startOfDay(value) - startOfDay(now)) / 86_400_000L).toInt()
        val day = when (days) {
            0 -> "今天"
            1 -> "明天"
            -1 -> "昨天"
            else -> formatLocal(value, "MM-dd E")
        }
        return if (hasClock(value)) day + " " + formatLocal(value, "HH:mm") else day
    }

    /** 只说到日的条目，到当天结束才算过期，不能拿 00:00 和现在比。 */
    fun overdue(value: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (value <= 0) return false
        return if (hasClock(value)) value < now else startOfDay(value) < startOfDay(now)
    }

    fun sameDay(left: Long, right: Long): Boolean {
        if (left <= 0 || right <= 0) return left == right
        val a = Calendar.getInstance().apply { timeInMillis = left }
        val b = Calendar.getInstance().apply { timeInMillis = right }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}

/**
 * 事实存储：一条事实一行，[payload] 存完整对象，其余列只用于查询与排序。
 *
 * 不存原始聊天，只存提炼后的短句；同一件事合并成一条，时间型条目时间变了就把旧的标成 superseded。
 * 旧版 `relation_facts`（每联系人一行 JSON）只读保留，首次打开时迁移一次。
 */
class RelationFactStore(private val db: AnalysisCacheDatabase,
    private val now: () -> Long = System::currentTimeMillis) : Closeable {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS relation_facts (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS relation_meta (contact_key TEXT PRIMARY KEY NOT NULL, last_at INTEGER NOT NULL, last_message_id INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS relation_fact_items (fact_id TEXT PRIMARY KEY NOT NULL, contact_key TEXT NOT NULL, " +
            "status TEXT NOT NULL, kind TEXT NOT NULL, occurred_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE INDEX IF NOT EXISTS relation_fact_items_contact ON relation_fact_items(contact_key, status, updated_at)")
        db.execute("CREATE TABLE IF NOT EXISTS relation_fact_migrations (contact_key TEXT PRIMARY KEY NOT NULL, migrated_at INTEGER NOT NULL)")
        // 彻底删除的条目记在这里：光删行不够，下次抽取还会抽出同一句话、同一个派生 id。
        db.execute("CREATE TABLE IF NOT EXISTS relation_fact_tombstones (contact_key TEXT NOT NULL, fact_id TEXT NOT NULL, " +
            "deleted_at INTEGER NOT NULL, PRIMARY KEY(contact_key, fact_id))")
        migrateLegacy()
    }

    /** 旧版一行 JSON 拆成多条；迁移过的联系人记下来，不重复处理。 */
    private fun migrateLegacy() {
        val keys = runCatching { db.queryRows("SELECT contact_key FROM relation_facts") }.getOrDefault(emptyList())
        var contacts = 0
        var migrated = 0
        for (contactKey in keys) {
            if (db.query("SELECT 1 FROM relation_fact_migrations WHERE contact_key = ?", listOf(contactKey)) != null) continue
            val payload = db.query("SELECT payload FROM relation_facts WHERE contact_key = ?", listOf(contactKey))
            if (payload != null) {
                runCatching { JSONArray(payload) }.getOrNull()?.let { array ->
                    val facts = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RelationFact::decode) }
                    facts.forEach { upsert(contactKey, it) }
                    migrated += facts.size
                }
            }
            db.execute("INSERT OR REPLACE INTO relation_fact_migrations(contact_key, migrated_at) VALUES(?, ?)",
                listOf(contactKey, now().toString()))
            contacts++
        }
        // 只记条数，不记内容：日志会被导出。
        if (contacts > 0) MoodLog.i("RELATION_FACT_MIGRATED contacts=$contacts facts=$migrated")
    }

    private fun upsert(contactKey: String, fact: RelationFact) {
        db.execute("INSERT OR REPLACE INTO relation_fact_items(fact_id, contact_key, status, kind, occurred_at, updated_at, payload) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?)",
            listOf(fact.factId, contactKey, fact.status, fact.kind, fact.occurredAt.toString(),
                fact.updatedAt.toString(), fact.encode().toString()))
    }

    /** 上次提取的时间与最后一条消息，用来控制自动提取的频率。 */
    fun meta(key: ReplyContactKey): RelationMeta {
        val payload = db.query("SELECT last_at || ':' || last_message_id FROM relation_meta WHERE contact_key = ?",
            listOf(key.value)) ?: return RelationMeta()
        val parts = payload.split(':')
        return RelationMeta(parts.getOrNull(0)?.toLongOrNull() ?: 0, parts.getOrNull(1)?.toLongOrNull() ?: 0)
    }

    fun saveMeta(key: ReplyContactKey, meta: RelationMeta) {
        db.execute("INSERT OR REPLACE INTO relation_meta(contact_key, last_at, last_message_id) VALUES(?, ?, ?)",
            listOf(key.value, meta.lastAt.toString(), meta.lastMessageId.toString()))
    }

    /** 当前有效的事实；提示词注入和档案列表都用这个。 */
    fun list(key: ReplyContactKey): List<RelationFact> = rows(key, "status = 'active'")

    /** 含已作废、已完成，用于合并和排查。 */
    fun listAll(key: ReplyContactKey): List<RelationFact> = rows(key, null)

    private fun rows(key: ReplyContactKey, filter: String?): List<RelationFact> {
        val where = if (filter == null) "contact_key = ?" else "contact_key = ? AND $filter"
        return db.queryRows("SELECT payload FROM relation_fact_items WHERE $where ORDER BY updated_at DESC", listOf(key.value))
            .mapNotNull { payload -> runCatching { RelationFact.decode(JSONObject(payload)) }.getOrNull() }
    }

    /** 整体替换该联系人；传入的列表已经由 [RelationFacts.merge] 合并与裁剪。 */
    fun replace(key: ReplyContactKey, facts: List<RelationFact>) {
        val ignored = ignored(key)
        db.execute("DELETE FROM relation_fact_items WHERE contact_key = ?", listOf(key.value))
        facts.filter { it.factId !in ignored }.forEach { upsert(key.value, it) }
    }

    /** 被彻底删除过的 fact_id；自动抽取写回时据此过滤，避免同一件事实反复复活。 */
    fun ignored(key: ReplyContactKey): Set<String> = runCatching {
        db.queryRows("SELECT fact_id FROM relation_fact_tombstones WHERE contact_key = ?", listOf(key.value)).toSet()
    }.getOrDefault(emptySet())

    /**
     * 彻底删除一条：删行并留下忽略标记。
     * 返回 false 表示这条已经不在（可能刚被重新整理过），界面应刷新而不是假装成功。
     */
    fun delete(key: ReplyContactKey, factId: String): Boolean {
        if (!factId.matches(RelationFact.ID_PATTERN)) return false
        val exists = db.query("SELECT 1 FROM relation_fact_items WHERE fact_id = ? AND contact_key = ?",
            listOf(factId, key.value)) != null
        if (!exists) return false
        db.execute("DELETE FROM relation_fact_items WHERE fact_id = ? AND contact_key = ?", listOf(factId, key.value))
        db.execute("INSERT OR REPLACE INTO relation_fact_tombstones(contact_key, fact_id, deleted_at) VALUES(?, ?, ?)",
            listOf(key.value, factId, now().toString()))
        return true
    }

    /** 手动重新加回一条时解除忽略：手动是明确意图，不该被上次的删除拦住。 */
    fun forget(key: ReplyContactKey, factId: String) {
        db.execute("DELETE FROM relation_fact_tombstones WHERE contact_key = ? AND fact_id = ?", listOf(key.value, factId))
    }

    /**
     * 手动改一条事实的状态（完成、否掉、恢复），返回是否真的写进去。
     *
     * 只按 fact_id + contact_key 精确匹配，不做模糊匹配；superseded 保持只读，
     * 否则会把已作废的旧约定重新翻出来，和新的那条同时出现在待办里。
     */
    fun setStatus(key: ReplyContactKey, factId: String, status: String): Boolean {
        if (status !in RelationFact.MANUAL_STATUSES || !factId.matches(RelationFact.ID_PATTERN)) return false
        val payload = db.query("SELECT payload FROM relation_fact_items WHERE fact_id = ? AND contact_key = ?",
            listOf(factId, key.value)) ?: return false
        val fact = runCatching { RelationFact.decode(JSONObject(payload)) }.getOrNull() ?: return false
        if (fact.status == RelationFact.STATUS_SUPERSEDED) return false
        // 已经处于目标状态就不重复写，避免每次点都刷新 updated_at 把排序搅乱。
        if (fact.status == status) return true
        upsert(key.value, fact.copy(status = status, updatedAt = now()))
        return true
    }

    /** 手动补一条档案；类型和长度照抽取的同一套约束，重复添加同一句只会留下一条。 */
    fun addManual(key: ReplyContactKey, kind: String, text: String, occurredAt: Long = 0): List<RelationFact>? {
        val clean = text.trim()
        if (kind !in RelationFact.KINDS || clean.isEmpty() || clean.length > RelationFact.MAX_TEXT) return null
        val stamp = now()
        val fact = RelationFact(kind = kind, text = clean, evidenceTime = 0, updatedAt = stamp,
            source = "manual", confidence = 1.0, occurredAt = occurredAt.coerceAtLeast(0))
        // 手动添加视为"我要它回来"，先解除上次彻底删除留下的忽略。
        forget(key, fact.factId)
        replace(key, RelationFacts.merge(listAll(key), listOf(fact), stamp))
        return list(key)
    }

    /** 待办：带绝对时间的约定与重要日期，按时间先后排；过期不等于无效，由界面决定怎么显示。 */
    fun todos(key: ReplyContactKey): List<RelationFact> {
        val placeholders = RelationFact.TIMED_KINDS.joinToString(",") { "?" }
        return db.queryRows("SELECT payload FROM relation_fact_items WHERE contact_key = ? AND status = 'active' " +
            "AND kind IN ($placeholders) AND occurred_at > 0 ORDER BY occurred_at ASC",
            listOf(key.value) + RelationFact.TIMED_KINDS.toList())
            .mapNotNull { payload -> runCatching { RelationFact.decode(JSONObject(payload)) }.getOrNull() }
    }

    fun clear(key: ReplyContactKey) {
        db.execute("DELETE FROM relation_fact_items WHERE contact_key = ?", listOf(key.value))
        db.execute("DELETE FROM relation_facts WHERE contact_key = ?", listOf(key.value))
        db.execute("DELETE FROM relation_fact_migrations WHERE contact_key = ?", listOf(key.value))
        db.execute("DELETE FROM relation_fact_tombstones WHERE contact_key = ?", listOf(key.value))
        // 水位一起清掉，下次才会重新抽，否则清空后再也不更新。
        db.execute("DELETE FROM relation_meta WHERE contact_key = ?", listOf(key.value))
    }

    fun clearAll() {
        db.execute("DELETE FROM relation_fact_items")
        db.execute("DELETE FROM relation_facts")
        db.execute("DELETE FROM relation_fact_migrations")
        db.execute("DELETE FROM relation_fact_tombstones")
        db.execute("DELETE FROM relation_meta")
    }

    override fun close() = db.close()
}

object RelationFacts {
    const val LIMIT = 40
    private const val CLOSED_LIMIT = 20
    private const val SAME_TEXT = 0.55
    private const val SAME_EVENT_TEXT = 0.45

    /** 合并后返回全部条目（含已作废）；调用方用 [RelationFact.isOpen] 过滤后再展示或注入。 */
    fun merge(existing: List<RelationFact>, incoming: List<RelationFact>, now: Long): List<RelationFact> {
        val result = existing.toMutableList()
        for (raw in incoming) {
            val fact = raw.copy(id = raw.factId, status = RelationFact.STATUS_ACTIVE,
                updatedAt = if (raw.updatedAt > 0) raw.updatedAt else now)
            if (fact.isTimed) mergeTimed(result, fact, now) else mergeStatic(result, fact, now)
        }
        return trim(result)
    }

    private fun mergeStatic(result: MutableList<RelationFact>, fact: RelationFact, now: Long) {
        val index = result.indexOfFirst { it.isOpen && it.kind == fact.kind && similarity(it.text, fact.text) >= SAME_TEXT }
        if (index >= 0) result[index] = updated(result[index], fact, now) else result += fact
    }

    /**
     * 时间型条目：同一天且描述接近算同一件事，直接覆盖；
     * 描述接近但时间变了（改到周日了）就把旧的标成 superseded，避免两条待办互相矛盾。
     */
    private fun mergeTimed(result: MutableList<RelationFact>, fact: RelationFact, now: Long) {
        val sameEvent = result.indexOfFirst {
            it.isOpen && it.kind == fact.kind && similarity(it.text, fact.text) >= SAME_EVENT_TEXT &&
                (fact.occurredAt <= 0 || it.occurredAt <= 0 || FactTimes.sameDay(it.occurredAt, fact.occurredAt))
        }
        if (sameEvent >= 0) {
            result[sameEvent] = updated(result[sameEvent], fact, now)
            return
        }
        val changed = result.indexOfFirst { it.isOpen && it.kind == fact.kind && similarity(it.text, fact.text) >= SAME_TEXT }
        if (changed >= 0) result[changed] = result[changed].copy(status = RelationFact.STATUS_SUPERSEDED, updatedAt = now)
        result += fact
    }

    /** 保留最初看到这条事实的时间，用来判断它有多旧；手动来源不被自动结果盖掉。 */
    private fun updated(previous: RelationFact, fact: RelationFact, now: Long): RelationFact = fact.copy(
        id = previous.factId,
        evidenceTime = when {
            previous.evidenceTime <= 0 -> fact.evidenceTime
            fact.evidenceTime <= 0 -> previous.evidenceTime
            else -> minOf(previous.evidenceTime, fact.evidenceTime)
        },
        updatedAt = now,
        source = if (previous.source == "manual") "manual" else fact.source,
        status = RelationFact.STATUS_ACTIVE,
        sourceChatMessageId = if (fact.sourceChatMessageId > 0) fact.sourceChatMessageId else previous.sourceChatMessageId,
    )

    /** 有效事实按上限裁剪，作废记录单独留一小段便于回溯，不让它们挤掉有效条目。 */
    fun trim(facts: List<RelationFact>): List<RelationFact> {
        val open = facts.filter { it.isOpen }.sortedByDescending { it.updatedAt }.take(LIMIT)
        val closed = facts.filter { !it.isOpen }.sortedByDescending { it.updatedAt }.take(CLOSED_LIMIT)
        return (open + closed).sortedByDescending { it.updatedAt }
    }

    /** 二字组 Jaccard，"在一家游戏公司做运营" 和 "在游戏公司做运营" 能判成同一条。 */
    fun similarity(left: String, right: String): Double {
        val a = grams(left)
        val b = grams(right)
        if (a.isEmpty() || b.isEmpty()) return if (left == right) 1.0 else 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a.size + b.size - intersection)
    }

    private fun grams(text: String): Set<String> {
        val clean = text.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return if (clean.isEmpty()) emptySet() else setOf(clean)
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }.toSet()
    }
}
