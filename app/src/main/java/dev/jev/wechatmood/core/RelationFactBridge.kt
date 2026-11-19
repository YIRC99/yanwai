package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.RelationMeta
import dev.jev.wechatmood.reply.ReplyContactKey
import org.json.JSONArray
import org.json.JSONObject

/** 关系档案的读写；所有落盘都经过 UID 受限的设置服务，微信进程不直接碰数据库。 */
object RelationFactBridge {
    fun enabled(context: Context): Boolean = runCatching {
        SettingsTransport.call(context, "relation_facts_enabled", null, null)?.getBoolean("enabled") == true
    }.onFailure { MoodLog.w("RELATION_FACT_BRIDGE_FAIL enabled ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    fun load(context: Context, key: ReplyContactKey): List<RelationFact> = runCatching {
        val bundle = SettingsTransport.call(context, "relation_facts_get", key.value,
            Bundle().apply { generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) } }) ?: return emptyList()
        decode(bundle.getStringArrayList("facts").orEmpty())
    }.onFailure { MoodLog.w("RELATION_FACT_BRIDGE_FAIL load ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(emptyList())

    /** 含已完成、已否掉与已作废的条目，待办窗口用它在底部列「已处理」。 */
    fun loadAll(context: Context, key: ReplyContactKey): List<RelationFact> = runCatching {
        val bundle = SettingsTransport.call(context, "relation_facts_get", key.value,
            Bundle().apply {
                putBoolean("all", true)
                generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) }
            }) ?: return emptyList()
        decode(bundle.getStringArrayList("facts").orEmpty())
    }.onFailure { MoodLog.w("RELATION_FACT_BRIDGE_FAIL loadAll ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(emptyList())

    /** 手动标记完成或否掉一条事实；失败时返回 false，界面据此提示重试。 */
    fun setStatus(context: Context, key: ReplyContactKey, factId: String, status: String): Boolean = runCatching {
        val result = SettingsTransport.call(context, "relation_facts_status", key.value, Bundle().apply {
            putString("fact_id", factId)
            putString("status", status)
            generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) }
        })
        result?.getBoolean("saved") == true
    }.onFailure { MoodLog.w("RELATION_FACT_BRIDGE_FAIL status ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    /** 手动补一条事实；失败返回 null，界面据此提示而不是假装保存成功。 */
    fun addManual(context: Context, key: ReplyContactKey, kind: String, text: String, occurredAt: Long = 0): List<RelationFact>? = try {
        val result = SettingsTransport.call(context, "relation_facts_add", key.value, Bundle().apply {
            putString("kind", kind)
            putString("text", text)
            putLong("occurred_at", occurredAt)
            generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) }
        })
        result?.getStringArrayList("facts")?.let(::decode)
    } catch (error: Exception) {
        MoodLog.w("RELATION_FACT_BRIDGE_FAIL add ${error.javaClass.simpleName}: ${error.message}")
        null
    }

    /** 彻底删除一条事实，并让后续自动整理不再把它抽回来。 */
    fun delete(context: Context, key: ReplyContactKey, factId: String): Boolean = runCatching {
        val result = SettingsTransport.call(context, "relation_facts_delete", key.value, Bundle().apply {
            putString("fact_id", factId)
            generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) }
        })
        result?.getBoolean("saved") == true
    }.onFailure { MoodLog.w("RELATION_FACT_BRIDGE_FAIL delete ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    fun meta(context: Context, key: ReplyContactKey): RelationMeta = runCatching {
        val bundle = SettingsTransport.call(context, "relation_facts_get", key.value,
            Bundle().apply { generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) } }) ?: return RelationMeta()
        RelationMeta(bundle.getLong("last_at", 0L), bundle.getLong("last_message_id", 0L))
    }.getOrDefault(RelationMeta())

    /** 返回合并后的完整列表；失败时返回本次想写入的内容，不影响调用方继续。 */
    fun save(context: Context, key: ReplyContactKey, facts: List<RelationFact>,
        lastAt: Long, lastMessageId: Long): List<RelationFact> = runCatching {
        val array = JSONArray()
        facts.forEach { array.put(it.encode()) }
        val result = SettingsTransport.call(context, "relation_facts_put", key.value, Bundle().apply {
            putString("facts", array.toString())
            putLong("last_at", lastAt)
            putLong("last_message_id", lastMessageId)
            generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) }
        })
        decode(result?.getStringArrayList("facts").orEmpty()).ifEmpty { facts }
    }.getOrDefault(facts)

    fun clear(context: Context, key: ReplyContactKey) {
        runCatching {
            SettingsTransport.call(context, "relation_facts_clear", key.value,
                Bundle().apply { generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) } })
        }
    }

    private fun generation(): String? = ModulePrefs.analysisSettings()?.generation

    private fun decode(items: List<String>): List<RelationFact> = items.mapNotNull { payload ->
        runCatching { RelationFact.decode(JSONObject(payload)) }.getOrNull()
    }
}
