package com.dogheadstrategist.chat.sentry

import android.content.Context
import android.net.Uri
import com.dogheadstrategist.chat.feature.AiStats
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.SentryPrefs
import com.dogheadstrategist.chat.feature.SentryQueue
import com.dogheadstrategist.chat.hook.ReplyDatabaseHistory
import com.dogheadstrategist.chat.reply.ReplyContext
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动起草 agent：跑在微信进程里，负责「真正干活」的那一半。
 *
 * 分工原因：通知只能由 app 进程收（系统 API），而昵称换会话、读聊天记录、生成回复
 * 都依赖微信内部数据，只能在微信进程做。两个进程通过 SettingsProvider 的
 * sentry_pull / sentry_ack 交接，队列本身是 app 进程写的 prefs。
 *
 * 生成的建议按会话缓存在内存里，聊天界面打开时取走；有冷却时间，避免同一会话被
 * 连续几条通知反复请求模型（那样又慢又费钱）。
 */
object SentryAgent {
    private const val POLL_MS = 2500L
    /** 建议保留时长。设置里选"永不过期"时返回 Long.MAX_VALUE，判断条件自然恒真。 */
    private fun cacheTtlMs(): Long {
        val minutes = SentryPrefs.cacheTtlMinutesForRequest()
        return if (minutes <= 0) Long.MAX_VALUE else minutes * 60_000L
    }
    /** 记录同步间隔：解冻期间定期把最近会话抄给 app 进程，不必每轮都写。 */
    private const val SYNC_INTERVAL_MS = 30 * 1000L
    private const val SETTINGS_URI = "content://com.dogheadstrategist.chat.settings"

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val lastRunAt = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap<String, Boolean>()
    private var appContext: Context? = null
    private var lastSyncAt = 0L

    /**
     * @param anchorId 这条建议是围绕哪条消息生成的（消息主键）。
     *   渲染时用它校验界面是否还在那条消息上，比会话标识可靠——会话标识是微信内部字段，
     *   切会话时会滞后，而消息列表本身就是界面内容。
     */
    private data class Cached(val suggestions: List<String>, val at: Long)

    fun install(context: Context) {
        if (context.packageName != "com.tencent.mm") return
        if (started) return
        started = true
        val app = context.applicationContext ?: context
        appContext = app
        scope.launch {
            MoodLog.i("SENTRY_AGENT_STARTED poll=${POLL_MS}ms")
            while (isActive) {
                runCatching { tick(app) }
                    .onFailure { MoodLog.w("SENTRY_TICK_FAILED " + it.javaClass.simpleName) }
                // 我的风格搭这趟常驻循环，但它自己判断开关和间隔，不依赖自动起草是否启用
                runCatching { WarReviewAgent.maybeRun(app) }
                    .onFailure { MoodLog.w("WAR_REVIEW_TICK_FAILED " + it.javaClass.simpleName) }
                delay(POLL_MS)
            }
        }
    }

    /** 聊天界面取建议。过期或没有就返回空，调用方不显示卡片。 */
    fun suggestionsForTalker(talker: String): List<String>? {
        if (talker.isBlank()) return null
        val now = System.currentTimeMillis()
        val ttl = cacheTtlMs()
        cache[talker]?.let {
            if (now - it.at > ttl) {
                cache.remove(talker)
                return null
            }
            return it.suggestions
        }
        return fromPoolByTalker(talker)
    }

    /** 这个会话是不是正在跑模型。卡片用它决定要不要先显示一个占位气泡。 */
    fun isGenerating(talker: String): Boolean =
        talker.isNotBlank() && running.containsKey(talker)

    /** 按会话标识读建议池。 */
    private fun fromPoolByTalker(talker: String): List<String>? {
        val context = appContext ?: return null
        val raw = runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_suggestions_get", null, null)
                ?.getString("suggestions")
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("talker") != talker) continue
                if (System.currentTimeMillis() - item.optLong("at", 0L) > cacheTtlMs()) return null
                val list = item.optJSONArray("suggestions") ?: return null
                return (0 until list.length())
                    .mapNotNull { list.optString(it).takeIf { text -> text.isNotBlank() } }
                    .ifEmpty { null }
            }
            null
        }.getOrNull()
    }

    /**
     * 把最近几个会话的聊天记录抄一份给 app 进程。
     *
     * 这是整个「冻结也能预生成」方案的数据来源：只有微信进程读得到聊天库，所以趁它
     * 解冻的窗口把记录送出去，之后 app 进程就能脱离微信独立生成。
     * 每个会话取多少条，跟随「AI 分析上限」设置。
     */
    private suspend fun syncRecords(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < SYNC_INTERVAL_MS) return
        lastSyncAt = now
        syncSessionIndex(context)   // 只送昵称，供白名单挑人；不受 syncUsers 影响
        val users = SentryPrefs.syncUsersForRequest()
        if (users <= 0) return
        val sessions = runCatching { ReplyDatabaseHistory.recentTalkers(users) }.getOrDefault(emptyList())
        if (sessions.isEmpty()) return
        val account = runCatching { ReplyDatabaseHistory.account() }.getOrNull()
        val limit = if (account != null) {
            runCatching { com.dogheadstrategist.chat.core.ReplyLimitBridge.load(context, account) }
                .getOrDefault(SentryPrefs.contextForRequest())
        } else SentryPrefs.contextForRequest()
        // 白名单在源头生效：名单外的会话不读消息、不进数组，app 进程根本收不到。
        // 名单为空时视为全部放行，避免误开开关后自动起草彻底不工作。
        val whitelist =
            if (SentryPrefs.whitelistEnabledForRequest()) SentryPrefs.whitelistForRequest() else emptyList()
        val restrict = whitelist.isNotEmpty()
        var skipped = 0
        val array = JSONArray()
        for ((talker, nick) in sessions) {
            if (nick.isBlank()) continue
            if (restrict && !SentryPrefs.allows(whitelist, talker, nick)) {
                skipped++   // 名单外：这里就断掉，不触碰聊天内容
                continue
            }
            val records = runCatching { ReplyDatabaseHistory.recentMessages(talker, limit) }
                .getOrDefault(emptyList())
            if (records.size < 2) continue
            val messages = JSONArray()
            records.forEach { message ->
                messages.put(org.json.JSONObject().apply {
                    put("speaker", runCatching { message.speaker() }.getOrDefault(""))
                    put("send", message.isSend)
                    put("text", message.content.take(300))
                    put("ts", message.createdAt)
                    // 消息主键：app 进程生成后要用它作为锚点写进池子
                    put("mid", message.messageId)
                })
            }
            array.put(org.json.JSONObject().apply {
                put("talker", talker)
                put("nick", nick)
                put("messages", messages)
            })
        }
        if (array.length() == 0) return
        runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_records_put", array.toString(), null)
            MoodLog.i("SENTRY_RECORDS_SYNCED users=" + array.length() + " limit=" + limit +
                if (skipped > 0) " whitelistSkipped=" + skipped else "")
        }
    }

    /**
     * 会话索引：只把 talker 和昵称送过去，不带任何聊天内容。
     * 白名单是「谁可以进来」，所以挑人这一步不能依赖已被白名单过滤的记录副本。
     */
    private suspend fun syncSessionIndex(context: Context) {
        val sessions = runCatching { ReplyDatabaseHistory.recentTalkers(SentryPrefs.MAX_SYNC_USERS) }
            .getOrDefault(emptyList())
        val array = JSONArray()
        sessions.forEach { (talker, nick) ->
            if (nick.isNotBlank()) {
                array.put(org.json.JSONObject().apply { put("t", talker); put("n", nick) })
            }
        }
        if (array.length() == 0) return
        runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_sessions_put", array.toString(), null)
            MoodLog.i("SENTRY_SESSIONS_SYNCED count=" + array.length())
        }
    }

    fun clear(talker: String) {
        cache.remove(talker)
        // 池里也要清：卡片刷新时会回落到池子，不清就会把刚用掉的建议又读回来，
        // 表现成「点了一条填进输入框，卡片上还挂着一条」。
        runCatching {
            val context = appContext ?: return@runCatching
            val raw = context.contentResolver.call(
                Uri.parse(SETTINGS_URI), "sentry_suggestions_get", null, null)
                ?.getString("suggestions").orEmpty()
            if (raw.isBlank()) return@runCatching
            val source = JSONArray(raw)
            val kept = JSONArray()
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                if (item.optString("talker") != talker) kept.put(item)
            }
            if (kept.length() == source.length()) return@runCatching
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_suggestions_put", null,
                android.os.Bundle().apply { putString("suggestions", kept.toString()) })
            MoodLog.i("SENTRY_POOL_CLEARED talker=" + talker.take(12))
        }.onFailure { MoodLog.w("SENTRY_POOL_CLEAR_FAILED " + it.javaClass.simpleName) }
    }

    /**
     * 建议生成之后用户已经发过消息，这条建议就用不上了。
     * 判断依据是会话里最新一条消息是我发的、且时间晚于生成时刻。
     */
    fun answeredSince(talker: String): Boolean {
        // 建议可能是 app 进程生成后写进池子的，本地 cache 里没有记录，
        // 只认 cache 会导致这类建议永远撤不掉，所以再回退查一次池子里的生成时间。
        val generatedAt = cache[talker]?.at ?: poolGeneratedAt(talker) ?: return false
        val last = runCatching {
            com.dogheadstrategist.chat.hook.ReplyDatabaseHistory.lastMessageMeta(talker)
        }.getOrNull() ?: return false
        // lastMessageMeta 不过滤消息类型，所以发图片回复也能识别出来
        return last.isSend == 1 && last.createdAt >= generatedAt
    }

    /** 读建议池里这条建议的生成时间；池子里的建议不是本进程生成的，cache 里查不到。 */
    private fun poolGeneratedAt(talker: String): Long? {
        val context = appContext ?: return null
        val raw = runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_suggestions_get", null, null)
                ?.getString("suggestions")
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("talker") != talker) continue
                return item.optLong("at", 0L).takeIf { it > 0 }
            }
            null
        }.getOrNull()
    }

    private suspend fun tick(context: Context) {
        if (!SentryPrefs.enabledForRequest()) return
        runCatching { syncRecords(context) }
            .onFailure { MoodLog.w("SENTRY_SYNC_FAILED " + it.javaClass.simpleName) }
        val bundle = runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_pull", null, null)
        }.getOrNull() ?: return
        val items = parse(bundle.getString("pending").orEmpty())
        if (items.isEmpty()) return
        val revision = bundle.getLong("revision", 0L)
        // 先 ack 再处理：避免处理耗时期间队列堆积；版本未变才清空，不会误删新通知。
        runCatching {
            context.contentResolver.call(Uri.parse(SETTINGS_URI), "sentry_ack", revision.toString(), null)
        }
        for ((nick, text) in items) {
            runCatching { process(context, nick, text) }
                .onFailure { MoodLog.w("SENTRY_PROCESS_FAILED " + it.javaClass.simpleName) }
        }
    }

    private fun parse(raw: String): List<Pair<String, String>> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val nick = item.optString("nick")
                val text = item.optString("text")
                if (nick.isBlank() || text.isBlank()) null else nick to text
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun process(context: Context, nick: String, text: String) {
        val talker = ReplyDatabaseHistory.resolveTalker(nick)
        if (talker.isNullOrBlank()) {
            MoodLog.i("SENTRY_NO_TALKER nick=" + nick.take(12))
            return
        }
        generate(context, talker, "notice=" + text.take(10))
    }

    private suspend fun generate(context: Context, talker: String, source: String) {
        // 服务号 / 公众号（gh_ 开头）不是真人对话，给它生成建议没有意义，
        // 还会在聊天页里挤占版面。
        if (talker.startsWith("gh_")) {
            MoodLog.i("SENTRY_SKIP_OFFICIAL talker=" + talker.take(12))
            return
        }
        // 白名单第三道把关：app 进程的通知拦截可能因昵称差异漏判，这里按 talker 再确认一次。
        if (SentryPrefs.whitelistEnabledForRequest()) {
            val whitelist = SentryPrefs.whitelistForRequest()
            if (whitelist.isNotEmpty() && !SentryPrefs.allows(whitelist, talker, source)) {
                MoodLog.i("SENTRY_SKIP_NOT_WHITELISTED talker=" + talker.take(12))
                return
            }
        }
        if (running.putIfAbsent(talker, true) != null) return
        try {
            val now = System.currentTimeMillis()
            val cooldown = SentryPrefs.cooldownForRequest().toLong() * 1000L
            if (now - (lastRunAt[talker] ?: 0L) < cooldown) {
                MoodLog.i("SENTRY_COOLDOWN talker=" + talker.take(12))
                return
            }

            val records = ReplyDatabaseHistory.recentMessages(talker, SentryPrefs.contextForRequest())
            if (records.size < 2) {
                MoodLog.i("SENTRY_NO_HISTORY talker=" + talker.take(12) + " size=" + records.size)
                return
            }
            // 锚点：这条建议围绕的是哪条消息。
            // 不能用 records.last()：recentMessages 只取文本和语音，用户用图片回复时，
            // 这里会以为对方的话还在最后一条，白生成一次。
            val anchorMeta = runCatching { ReplyDatabaseHistory.lastMessageMeta(talker) }.getOrNull()
            if (SentryPrefs.onlyUnrepliedForRequest() && anchorMeta?.isSend == 1) {
                MoodLog.i("SENTRY_ALREADY_REPLIED talker=" + talker.take(12))
                return
            }

            val settings = ModulePrefs.replySettings() ?: return
            if (!settings.isConfigured) return

            val messages = records.map {
                ReplyMessage(it.messageId, it.speaker(), it.createdAt, it.content.take(400))
            }
            val page = ReplyContext(talker, messages)
            val suggestion = ReplyHttpClient().generate(settings, page, "", "", "", kind = AiStats.Kind.SENTRY)
            val picked = suggestion.parts.take(SentryPrefs.countForRequest())
            if (picked.isEmpty()) return

            cache[talker] = Cached(picked, now)
            lastRunAt[talker] = now
            MoodLog.i("SENTRY_READY talker=" + talker.take(12) + " count=" + picked.size +
                " " + source)
        } finally {
            running.remove(talker)
        }
    }
}
