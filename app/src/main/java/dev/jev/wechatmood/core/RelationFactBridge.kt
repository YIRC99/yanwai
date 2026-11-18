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
    }.getOrDefault(false)

    fun load(context: Context, key: ReplyContactKey): List<RelationFact> = runCatching {
        val bundle = SettingsTransport.call(context, "relation_facts_get", key.value,
            Bundle().apply { generation()?.let { putString(SettingsProvider.KEY_GENERATION, it) } }) ?: return emptyList()
        decode(bundle.getStringArrayList("facts").orEmpty())
    }.getOrDefault(emptyList())

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
