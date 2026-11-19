package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.RelationFacts
import dev.jev.wechatmood.reply.RelationFactStore
import dev.jev.wechatmood.reply.RelationMeta
import dev.jev.wechatmood.reply.ReplyContactKey
import org.json.JSONObject

/**
 * 关系档案的读写。
 *
 * 数据存在微信进程自己的库里（[RelationFactLocalStore]），随微信进程存活；
 * 言外 App 只负责模型配置和开关，不再承担档案存储，App 被冻结也不会读不到档案。
 * 旧版 App 数据库只读保留，不再导入。
 */
object RelationFactBridge {
    /** App 清空全部档案时发给微信进程的命令；接收方只认言外自己的 UID。 */
    const val CLEAR_ALL_ACTION = "dev.jev.wechatmood.action.CLEAR_RELATION_FACTS"

    private var local: RelationFactStore? = null

    private fun store(context: Context): RelationFactStore = synchronized(this) {
        local ?: RelationFactLocalStore.open(context).also { local = it }
    }

    /** 自动整理开关仍在 App 设置里；读不到时按默认开启处理，避免功能突然消失。 */
    fun enabled(context: Context): Boolean = runCatching {
        SettingsTransport.call(context, "relation_facts_enabled", null, null)?.getBoolean("enabled")
    }.getOrNull() ?: true

    fun load(context: Context, key: ReplyContactKey): List<RelationFact> {
        return store(context).list(key)
    }

    /** 含已完成、已否掉与已作废的条目，待办窗口用它在底部列「已处理」。 */
    fun loadAll(context: Context, key: ReplyContactKey): List<RelationFact> {
        return store(context).listAll(key)
    }

    fun meta(context: Context, key: ReplyContactKey): RelationMeta {
        return store(context).meta(key)
    }

    /** 合并并写回；返回合并后的有效条目，界面据此刷新。 */
    fun save(context: Context, key: ReplyContactKey, facts: List<RelationFact>,
        lastAt: Long, lastMessageId: Long): List<RelationFact> {
        val store = store(context)
        val merged = RelationFacts.merge(store.listAll(key), facts, System.currentTimeMillis())
        store.replace(key, merged)
        store.saveMeta(key, RelationMeta(lastAt, lastMessageId))
        MoodLog.i("RELATION_FACT_SAVE incoming=${facts.size} open=${merged.count { it.isOpen }}")
        return merged.filter { it.isOpen }
    }

    fun setStatus(context: Context, key: ReplyContactKey, factId: String, status: String): Boolean {
        return store(context).setStatus(key, factId, status)
    }

    fun addManual(context: Context, key: ReplyContactKey, kind: String, text: String, occurredAt: Long = 0): List<RelationFact>? {
        return store(context).addManual(key, kind, text, occurredAt)
    }

    fun delete(context: Context, key: ReplyContactKey, factId: String): Boolean {
        return store(context).delete(key, factId)
    }

    /** 清空单个联系人。 */
    fun clear(context: Context, key: ReplyContactKey) {
        store(context).clear(key)
    }

    fun clearAllLocal(context: Context) {
        store(context).clearAll()
        MoodLog.i("RELATION_FACT_CLEAR_ALL_LOCAL")
    }

}
