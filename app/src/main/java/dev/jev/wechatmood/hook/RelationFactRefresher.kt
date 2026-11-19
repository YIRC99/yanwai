package dev.jev.wechatmood.hook

import android.content.Context
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactExtractor
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关系档案的一次整理：读最近历史、抽取、按联系人写回。
 *
 * 请求跑在模块自己的 scope 上，不挂在任何一个面板的生命周期里：
 * 点完「整理一次」就关掉窗口、切去别的聊天，请求仍然会跑完并落库；
 * 下次打开档案页就能看到结果。同一联系人同时只允许一个请求。
 */
internal object RelationFactRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<String, Job>()
    private val lock = Any()

    /** facts 是合并后的有效条目；nothingNew 表示增量模式下没有比水位更新的消息。 */
    data class Result(val facts: List<RelationFact>, val scanned: Int, val nothingNew: Boolean)

    fun isRunning(key: ReplyContactKey): Boolean = synchronized(lock) { running[key.value]?.isActive == true }

    /** 已在跑同一联系人时返回 false，UI 据此提示“正在整理”而不是再发一次请求。 */
    fun launch(context: Context, key: ReplyContactKey, settings: ReplySettings,
        limit: Int = ModulePrefs.relationFactLimit(),
        incremental: Boolean = ModulePrefs.relationFactIncremental(),
        onDone: (Result?, Throwable?) -> Unit): Boolean {
        synchronized(lock) { if (running[key.value]?.isActive == true) return false }
        val app = context.applicationContext ?: context
        val job = scope.launch {
            try {
                onDone(refresh(app, key, settings, limit, incremental), null)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                MoodLog.w("RELATION_FACT_REFRESH_FAILED " + error.javaClass.simpleName)
                onDone(null, error)
            } finally {
                synchronized(lock) { running.remove(key.value) }
            }
        }
        synchronized(lock) { running[key.value] = job }
        return true
    }

    suspend fun refresh(context: Context, key: ReplyContactKey, settings: ReplySettings,
        limit: Int = ModulePrefs.relationFactLimit(),
        incremental: Boolean = ModulePrefs.relationFactIncremental()): Result {
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
            ?: error("读取聊天失败")
        // 页面上往往只有十几条，抽不出稳定事实；优先用本机历史补足，读不到再退回页面消息。
        val history = runCatching { ReplyDatabaseHistory.load(live, limit) }.getOrNull()
        val all = history?.messages?.takeIf { it.isNotEmpty() } ?: live.messages
        val meta = withContext(Dispatchers.IO) { RelationFactBridge.meta(context, key) }
        // 增量：只看上次水位之后的新消息；还没抽过（水位 0）时等于全量。
        val messages = if (incremental && meta.lastMessageId > 0) {
            all.filter { it.id > meta.lastMessageId }.takeLast(limit)
        } else {
            all.takeLast(limit)
        }
        MoodLog.i("RELATION_FACT_INPUT page=${live.messages.size} used=${messages.size} limit=$limit incremental=$incremental")
        if (messages.isEmpty()) return Result(emptyList(), 0, incremental)
        // 把已有档案一起给模型，它才能指出哪条被替换（换工作、搬家、改称呼）。
        val existing = withContext(Dispatchers.IO) { runCatching { RelationFactBridge.load(context, key) }.getOrDefault(emptyList()) }
        val extracted = withContext(Dispatchers.IO) { FactExtractor().extract(settings, messages, existing, limit = limit) }
        if (extracted.isEmpty()) return Result(emptyList(), messages.size, false)
        val saved = withContext(Dispatchers.IO) {
            RelationFactBridge.save(context, key, extracted, System.currentTimeMillis(), messages.lastOrNull()?.id ?: 0)
        }
        MoodLog.i("RELATION_FACT_SAVED count=${saved.size} replaced=${extracted.count { it.replaces.isNotEmpty() }}")
        return Result(saved, messages.size, false)
    }
}
