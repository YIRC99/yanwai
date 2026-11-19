package dev.jev.wechatmood.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationDue
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.ReplyContactKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 约定到期的本地提醒。
 *
 * 两条触发线：进入这个私聊时检查一次；进入微信首页时把全部联系人扫一遍（15 分钟节流）。
 * 通知要写清「和谁、什么事、几点」——旧版只说「1 条约会今天到期」，点了还只落在微信首页，
 * 等于没有指向性。现在点通知直接进那个聊天，通知上还能一次标记完成。
 *
 * 局限写在明处：档案存在微信自己的库里，微信进程不在时不发通知。
 */
internal object RelationReminder {
    private const val CHANNEL_ID = "yanwai_relation_todo"
    private const val CHANNEL_NAME = "关系档案提醒"
    private const val PREFS = "yanwai_relation_notice"
    private const val KEY_LAST_SCAN = "last_global_scan"
    private const val NOTICE_BASE = 1100
    private const val LEGACY_NOTICE_ID = 1101
    private const val MAX_NOTICES = 5
    private const val GLOBAL_THROTTLE_MS = 15 * 60_000L
    private const val GLOBAL_DELAY_MS = 3_000L
    private const val GROUP = "yanwai_relation_todo_group"

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Runnable? = null

    /** 单聊路径：刚打开聊天或刚整理完，看一眼今天有没有到期的。 */
    suspend fun check(context: Context, key: ReplyContactKey, talker: String?, facts: List<RelationFact>) {
        val now = System.currentTimeMillis()
        val due = RelationDue.select(facts, now)
        if (due.isEmpty()) {
            cancel(context, key)
            return
        }
        if (!markNotified(context, key.value, due, now)) return
        notify(context, key.value, talker, nameFor(talker).orEmpty(), due, now)
    }

    /**
     * 状态被改过之后重算一次：全部完成了就把通知撤掉，还有别的就按新内容重发。
     *
     * 之前只在「检查」时撤通知，所以在面板里把条目标成已完成，通知栏里那条会一直挂着。
     */
    suspend fun refresh(context: Context, key: ReplyContactKey, talker: String?) {
        val facts = withContext(Dispatchers.IO) {
            runCatching { RelationFactBridge.load(context, key) }.getOrDefault(emptyList())
        }
        val now = System.currentTimeMillis()
        val due = RelationDue.select(facts, now)
        if (due.isEmpty()) {
            cancel(context, key)
            clearNotified(context, key.value)
            return
        }
        notify(context, key.value, talker, nameFor(talker).orEmpty(), due, now)
    }

    /** 2.9.142 之前的通知 id 固定 1101，升级后旧通知留在栏里点不动，启动时清掉。 */
    fun cancelLegacy(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(LEGACY_NOTICE_ID) }
    }

    private fun clearNotified(context: Context, contactKey: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(contactKey).apply()
        }
    }

    /**
     * 全局路径：进微信首页时扫一遍。
     * 之前只在进入那个私聊时才检查，等于「你正好去找他」才提醒，和提醒的本意相反。
     */
    suspend fun checkAll(context: Context) {
        if (!takeGlobalSlot(context)) return
        val groups = withContext(Dispatchers.IO) {
            runCatching { RelationFactBridge.dueContacts(context) }.getOrDefault(emptyList())
        }
        if (groups.isEmpty()) {
            MoodLog.i("RELATION_REMIND_ALL nothing_due")
            return
        }
        val names = names(groups.mapNotNull { it.talker })
        val now = System.currentTimeMillis()
        var posted = 0
        for (group in groups) {
            if (posted >= MAX_NOTICES) break
            if (!markNotified(context, group.key, group.facts, now)) continue
            notify(context, group.key, group.talker, group.name.ifBlank { group.talker?.let { names[it] } ?: "" },
                group.facts, now)
            posted++
        }
        MoodLog.i("RELATION_REMIND_ALL contacts=" + groups.size + " posted=" + posted)
    }

    /** 进微信首页时排一次：延迟几秒避开首帧，重复调用作废上一次。 */
    fun scheduleGlobalCheck(context: Context) {
        cancelPending()
        val app = context.applicationContext
        val job = Runnable {
            pending = null
            scope.launch {
                runCatching { checkAll(app) }
                    .onFailure { MoodLog.w("RELATION_REMIND_ALL_FAILED " + it.javaClass.simpleName) }
            }
        }
        pending = job
        handler.postDelayed(job, GLOBAL_DELAY_MS)
    }

    fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    fun cancel(context: Context, key: ReplyContactKey) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(noticeId(key.value)) }
    }

    private fun notify(context: Context, contactKey: String, talker: String?, name: String,
        facts: List<RelationFact>, now: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(manager)
        val shown = facts.take(RelationDue.MAX_ITEMS)
        val rest = facts.size - shown.size
        val lines = shown.map { RelationDue.line(it, now) }
        val big = if (rest > 0)
            lines.joinToString("\n") + "\n还有 " + rest + " 条，进聊天后从「附加 · 待办」查看"
        else lines.joinToString("\n")
        val noticeId = noticeId(contactKey)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (name.isNotBlank()) "待办 · " + name else "待办 · 未获取备注名")
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, contactKey, talker, noticeId))
        // 通知按钮在个别 ROM 上会抛错；抛了就退化成纯点击，不影响提醒本身。
        runCatching {
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource("android", android.R.drawable.ic_menu_agenda),
                if (facts.size > 1) "全部标记完成" else "标记完成",
                doneIntent(context, contactKey, facts, noticeId)).build())
        }.onFailure { MoodLog.w("RELATION_REMIND_ACTION_FAILED " + it.javaClass.simpleName) }
        runCatching { manager.notify(noticeId, builder.build()) }
            .onFailure { MoodLog.w("RELATION_REMIND_NOTIFY_FAILED " + it.javaClass.simpleName) }
        MoodLog.i("RELATION_REMIND posted items=" + facts.size + " named=" + name.isNotBlank() +
            " talker=" + (talker != null))
    }

    /**
     * 点通知进那个聊天。
     *
     * 先回到模块自己的接收器，再在微信进程里 startActivity：LauncherUI 那套
     * 「带 Chat_User 的 extra」在这版微信上会被忽略，落到首页；ChattingUI 才是现在的入口。
     */
    private fun openIntent(context: Context, contactKey: String, talker: String?, noticeId: Int): PendingIntent {
        val intent = Intent(RelationTodoActions.ACTION_OPEN)
            .setPackage("com.tencent.mm")
            .putExtra(RelationTodoActions.EXTRA_KEY, contactKey)
            .putExtra(RelationTodoActions.EXTRA_TALKER, talker)
            .putExtra(RelationTodoActions.EXTRA_NOTICE, noticeId)
        return PendingIntent.getBroadcast(context, noticeId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun doneIntent(context: Context, contactKey: String, facts: List<RelationFact>,
        noticeId: Int): PendingIntent {
        val intent = Intent(RelationTodoActions.ACTION_DONE)
            .setPackage("com.tencent.mm")
            .putExtra(RelationTodoActions.EXTRA_KEY, contactKey)
            .putExtra(RelationTodoActions.EXTRA_IDS, facts.joinToString(",") { it.factId })
            .putExtra(RelationTodoActions.EXTRA_NOTICE, noticeId)
        return PendingIntent.getBroadcast(context, noticeId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "约定与重要日期的到期提醒，点开直接进对应聊天"
        })
    }

    /** 每个联系人一个通知 id：旧版写死 1101，多个联系人互相覆盖，只剩最后一条。 */
    private fun noticeId(contactKey: String): Int = NOTICE_BASE + (contactKey.hashCode() and 0x3FF)

    /** 指纹带日期和条目 id：同一批一天只提醒一次；当天又新增一条会重新提醒。 */
    private fun markNotified(context: Context, contactKey: String, due: List<RelationFact>, now: Long): Boolean =
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = FactTimes.dayKey(now) + "|" + due.map { it.factId }.sorted().joinToString(",")
            if (prefs.getString(contactKey, null) == stamp) return@runCatching false
            prefs.edit().putString(contactKey, stamp).apply()
            true
        }.getOrDefault(false)

    private fun takeGlobalSlot(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_SCAN, 0) < GLOBAL_THROTTLE_MS) return@runCatching false
        prefs.edit().putLong(KEY_LAST_SCAN, now).apply()
        true
    }.getOrDefault(false)

    private suspend fun nameFor(talker: String?): String? {
        if (talker.isNullOrBlank()) return null
        return names(listOf(talker))[talker]
    }

    private suspend fun names(talkers: List<String>): Map<String, String> {
        if (talkers.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            runCatching { ReplyDatabaseHistory.displayNames(talkers) }.getOrDefault(emptyMap())
        }
    }
}
