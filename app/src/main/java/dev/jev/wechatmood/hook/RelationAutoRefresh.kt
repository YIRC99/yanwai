package dev.jev.wechatmood.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.ReplyContactKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进入私聊页后静默整理一次：不打开「帮我回」也让档案跟上。
 *
 * 门槛与抽屉里的自动整理一致：距上次整理超过 3 天，或这段时间新增了 30 条以上消息。
 * 只读页面消息判断数量，真正发请求前已经由 [RelationFactRefresher] 做去重与合并。
 */
internal object RelationAutoRefresh {
    private const val DELAY_MS = 6_000L
    private const val STALE_MS = 3 * 24 * 3600_000L
    private const val FRESH_MESSAGES = 30
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Runnable? = null

    /** 每次 Activity resume 都排一次；6 秒内又切页就作废上一次。 */
    fun schedule(activity: Activity) {
        cancel()
        val job = Runnable { run(activity) }
        pending = job
        handler.postDelayed(job, DELAY_MS)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun run(activity: Activity) {
        pending = null
        scope.launch {
            try {
                check(activity)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                MoodLog.w("RELATION_FACT_AUTOPAGE_FAILED " + error.javaClass.simpleName)
            }
        }
    }

    private suspend fun check(activity: Activity) {
        val talker = MessageSniffer.currentReplyTalker() ?: return
        if (talker.endsWith("@chatroom")) return
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull() ?: return
        if (live.talker != talker) return
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) return
        val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(live) } ?: return
        val key = ReplyContactKey.of(account, talker) ?: return
        // 6 秒延迟期间用户可能已经切走：整理只认这次读到的账号、联系人与消息。
        val snapshot = RelationFactRefresher.Snapshot(account, talker, live.messages)
        // 到期提醒只受「显示档案」本身影响，关掉自动整理不该把提醒一起停掉。
        val facts = withContext(Dispatchers.IO) { runCatching { RelationFactBridge.load(activity, key) }.getOrDefault(emptyList()) }
        // 哈希键换不回聊天对象：顺手把聊天对象和备注名记一份，提醒点开才能进对会话。
        withContext(Dispatchers.IO) {
            val name = runCatching { ReplyDatabaseHistory.displayNames(listOf(talker))[talker] }.getOrNull()
            RelationFactBridge.rememberContact(activity, key, talker, name.orEmpty())
        }
        RelationReminder.check(activity, key, talker, facts)
        if (!RelationFactBridge.enabled(activity)) return
        val meta = withContext(Dispatchers.IO) { runCatching { RelationFactBridge.meta(activity, key) }.getOrNull() } ?: return
        val gap = System.currentTimeMillis() - meta.lastAt
        val fresh = live.messages.count { it.id > meta.lastMessageId }
        val due = meta.lastAt <= 0 || gap > STALE_MS || fresh >= FRESH_MESSAGES
        MoodLog.i("RELATION_FACT_AUTOPAGE due=$due gap_hours=${gap / 3600_000L} fresh=$fresh")
        if (!due) return
        val started = RelationFactRefresher.launch(activity, key, settings, snapshot = snapshot) { result, error ->
            when {
                error != null -> MoodLog.w("RELATION_FACT_AUTOPAGE_ERROR " + error.javaClass.simpleName)
                result == null -> Unit
                result.nothingNew -> MoodLog.i("RELATION_FACT_AUTOPAGE nothing_new")
                else -> MoodLog.i("RELATION_FACT_AUTOPAGE saved=" + result.facts.size)
            }
        }
        if (!started) MoodLog.i("RELATION_FACT_AUTOPAGE skipped running")
    }
}
