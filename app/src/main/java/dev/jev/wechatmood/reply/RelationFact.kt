package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

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
) {
    fun encode(): JSONObject = JSONObject().put("kind", kind).put("text", text)
        .put("evidence_time", evidenceTime).put("updated_at", updatedAt)
        .put("source", source).put("confidence", confidence)

    fun hint(): String = text

    companion object {
        val KINDS = listOf("工作", "城市", "家人", "喜好", "忌口", "宠物", "重要日期", "约定", "称呼")
        const val MAX_TEXT = 60

        fun decode(json: JSONObject): RelationFact? {
            val kind = json.optString("kind").trim()
            val text = json.optString("text").trim()
            if (kind !in KINDS || text.isBlank() || text.length > MAX_TEXT) return null
            val confidence = json.optDouble("confidence", 0.6).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.6
            return RelationFact(kind, text, json.optLong("evidence_time", 0), json.optLong("updated_at", 0),
                json.optString("source", "auto").takeIf { it == "manual" } ?: "auto", confidence)
        }
    }
}

/**
 * 事实存储：每个联系人一行 JSON。
 *
 * 不存原始聊天，只存提炼后的短句；同一种类里描述高度重合的会覆盖，
 * 否则新增，超过上限时丢最早更新的。
 */
class RelationFactStore(private val db: AnalysisCacheDatabase) : Closeable {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS relation_facts (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS relation_meta (contact_key TEXT PRIMARY KEY NOT NULL, last_at INTEGER NOT NULL, last_message_id INTEGER NOT NULL)")
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

    fun list(key: ReplyContactKey): List<RelationFact> {
        val payload = db.query("SELECT payload FROM relation_facts WHERE contact_key = ?", listOf(key.value)) ?: return emptyList()
        return runCatching {
            val array = JSONArray(payload)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RelationFact::decode) }
        }.getOrDefault(emptyList())
    }

    fun save(key: ReplyContactKey, facts: List<RelationFact>) {
        if (facts.isEmpty()) {
            db.execute("DELETE FROM relation_facts WHERE contact_key = ?", listOf(key.value))
            return
        }
        val array = JSONArray()
        facts.forEach { array.put(it.encode()) }
        db.execute("INSERT OR REPLACE INTO relation_facts(contact_key, payload) VALUES(?, ?)",
            listOf(key.value, array.toString()))
    }

    fun clear(key: ReplyContactKey) {
        db.execute("DELETE FROM relation_facts WHERE contact_key = ?", listOf(key.value))
    }

    override fun close() = db.close()
}

object RelationFacts {
    const val LIMIT = 40

    /** 同类且描述重合度高的视为同一条：保留最初的时间，更新内容与更新时间。 */
    fun merge(existing: List<RelationFact>, incoming: List<RelationFact>, now: Long): List<RelationFact> {
        val result = existing.toMutableList()
        for (fact in incoming) {
            val index = result.indexOfFirst { it.kind == fact.kind && similarity(it.text, fact.text) >= 0.55 }
            if (index >= 0) {
                val previous = result[index]
                result[index] = fact.copy(
                    // 保留最早看到这条事实的时间，用来判断它有多旧。
                    evidenceTime = when {
                        previous.evidenceTime <= 0 -> fact.evidenceTime
                        fact.evidenceTime <= 0 -> previous.evidenceTime
                        else -> minOf(previous.evidenceTime, fact.evidenceTime)
                    },
                    updatedAt = now,
                    source = if (previous.source == "manual") "manual" else fact.source,
                )
            } else {
                result += fact.copy(updatedAt = if (fact.updatedAt > 0) fact.updatedAt else now)
            }
        }
        return result.sortedByDescending { it.updatedAt }.take(LIMIT)
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
