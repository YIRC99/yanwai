package dev.jev.wechatmood.core

import android.content.Context
import android.content.Intent
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

    /** 清空请求的落盘标记：Android 9–13 的普通广播拿不到发送方 UID，微信不在运行时也收不到。 */
    private const val KEY_CLEAR_PENDING = "relation_facts_clear_pending"
    private const val METHOD_CLEAR_PENDING = "relation_facts_clear_pending"
    private const val METHOD_CLEAR_DONE = "relation_facts_clear_done"

    private var local: RelationFactStore? = null
    private var lastClearCheck = 0L

    private fun store(context: Context): RelationFactStore = synchronized(this) {
        local ?: RelationFactLocalStore.open(context).also { local = it }
    }

    /**
     * App 侧记录一次「清空全部档案」请求。
     *
     * 只是发广播不够：Android 9–13 的接收方拿不到发送方 UID 会直接拒绝，微信没在运行时也收不到。
     * 请求先落盘，微信进程下次连上设置服务时执行并回执，界面据回执清除提示。
     */
    fun requestClearAll(context: Context) {
        context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).edit().putBoolean(KEY_CLEAR_PENDING, true).commit()
        MoodLog.i("RELATION_FACT_CLEAR_REQUESTED")
        runCatching {
            context.sendBroadcast(Intent(CLEAR_ALL_ACTION).setPackage("com.tencent.mm"))
        }.onFailure { MoodLog.w("RELATION_FACT_CLEAR_BROADCAST_FAIL " + it.javaClass.simpleName) }
    }

    fun hasPendingClear(context: Context): Boolean =
        context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getBoolean(KEY_CLEAR_PENDING, false)

    fun clearPending(context: Context): Boolean = runCatching {
        context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).edit().remove(KEY_CLEAR_PENDING).commit()
    }.getOrDefault(false)

    /**
     * 微信进程侧执行待处理的清空请求，执行完再回执。
     * 只认微信自己的进程，避免在 App 进程把 App 的库当成档案库清掉。
     */
    fun runPendingClear(context: Context): Boolean {
        if (context.packageName != "com.tencent.mm") return false
        val pending = runCatching {
            SettingsTransport.call(context, METHOD_CLEAR_PENDING, null, null)?.getBoolean("pending") == true
        }.getOrNull() ?: false
        if (!pending) return false
        clearAllLocal(context)
        runCatching { SettingsTransport.call(context, METHOD_CLEAR_DONE, null, null) }
        MoodLog.i("RELATION_FACT_CLEAR_DONE 已按请求清空并回执")
        return true
    }

    /** 读档案前顺带看一眼有没有待执行的清空请求，节流避免每轮都走跨进程调用。 */
    fun runPendingClearThrottled(context: Context) {
        // 跨进程调用会阻塞，主线程上不做。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastClearCheck < 60_000L) return
            lastClearCheck = now
        }
        runCatching { runPendingClear(context) }
    }

    /**
     * 自动整理开关仍在 App 设置里。
     * 读不到时按关闭处理：设置服务连不上时宁可这次不整理，也不能背着用户上传聊天。
     */
    fun enabled(context: Context): Boolean = runCatching {
        SettingsTransport.call(context, "relation_facts_enabled", null, null)?.getBoolean("enabled")
    }.getOrNull() ?: false

    fun load(context: Context, key: ReplyContactKey): List<RelationFact> {
        // 清空请求可能是在微信没运行时记下的，读档案前先补执行。
        runPendingClearThrottled(context)
        return store(context).list(key)
    }

    /** 含已完成、已否掉与已作废的条目，待办窗口用它在底部列「已处理」。 */
    fun loadAll(context: Context, key: ReplyContactKey): List<RelationFact> {
        runPendingClearThrottled(context)
        return store(context).listAll(key)
    }

    fun meta(context: Context, key: ReplyContactKey): RelationMeta {
        return store(context).meta(key)
    }

    /**
     * 合并并写回；返回的是库里实际留下的有效条目，界面据此刷新。
     * 合并结果里可能还有被彻底删除（tombstone）挡掉的条目，不能直接拿它当返回值，
     * 否则这一轮生成会重新附上用户已经删掉的事实。
     */
    fun save(context: Context, key: ReplyContactKey, facts: List<RelationFact>,
        lastAt: Long, lastMessageId: Long): List<RelationFact> {
        val store = store(context)
        val merged = RelationFacts.merge(store.listAll(key), facts, System.currentTimeMillis())
        store.replace(key, merged)
        store.saveMeta(key, RelationMeta(lastAt, lastMessageId))
        val persisted = store.list(key)
        MoodLog.i("RELATION_FACT_SAVE incoming=${facts.size} open=${merged.count { it.isOpen }} persisted=${persisted.size}")
        return persisted
    }

    /** 只推进水位：这次没有可提取的事实，但下一轮不该重复上传同一批聊天。 */
    fun saveMeta(context: Context, key: ReplyContactKey, lastAt: Long, lastMessageId: Long) {
        store(context).saveMeta(key, RelationMeta(lastAt, lastMessageId))
    }

    /** 从剪贴板导入：走与自动整理相同的合并逻辑，重复条目不会翻倍。 */
    fun importFacts(context: Context, key: ReplyContactKey, facts: List<RelationFact>): Int {
        val store = store(context)
        val merged = RelationFacts.merge(store.listAll(key), facts, System.currentTimeMillis())
        store.replace(key, merged)
        MoodLog.i("RELATION_FACT_IMPORT incoming=" + facts.size + " open=" + merged.count { it.isOpen })
        return merged.count { it.isOpen }
    }

    fun setStatus(context: Context, key: ReplyContactKey, factId: String, status: String): Boolean {
        return store(context).setStatus(key, factId, status)
    }

    /** 还原被作废的条目；返回 false 表示它已经不在作废状态。 */
    fun restore(context: Context, key: ReplyContactKey, factId: String): Boolean {
        return store(context).restore(key, factId)
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

    /**
     * 旧版档案留在言外 App 的库里，新版直接打开微信私有目录的新文件。
     * 微信进程启动时取一次、合并进微信库，成功后让 App 删除旧副本，
     * 否则清空新库时旧数据还留在 App 里。
     */
    fun migrateLegacy(context: Context) {
        if (context.packageName != "com.tencent.mm") return
        val payload = runCatching {
            SettingsTransport.call(context, "relation_facts_legacy_export", null, null)?.getString("payload")
        }.getOrNull()
        if (payload.isNullOrBlank() || payload == "{}") return
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val store = store(context)
        var contacts = 0
        var facts = 0
        root.keys().forEach { contactKey ->
            val key = runCatching { ReplyContactKey(contactKey) }.getOrNull() ?: return@forEach
            val array = root.optJSONArray(contactKey) ?: return@forEach
            val items = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(RelationFact::decode)
            }
            if (items.isEmpty()) return@forEach
            // 合并而不是覆盖：微信库里可能已经有新版添加的条目。
            store.replace(key, RelationFacts.merge(store.listAll(key), items, System.currentTimeMillis()))
            contacts++
            facts += items.size
        }
        if (contacts == 0) return
        MoodLog.i("RELATION_FACT_LEGACY_MIGRATED contacts=$contacts facts=$facts")
        runCatching { SettingsTransport.call(context, "relation_facts_legacy_cleared", null, null) }
    }

    fun clearAllLocal(context: Context) {
        store(context).clearAll()
        MoodLog.i("RELATION_FACT_CLEAR_ALL_LOCAL")
    }

}
