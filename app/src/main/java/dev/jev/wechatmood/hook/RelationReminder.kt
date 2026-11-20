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
import dev.jev.wechatmood.reply.DueContact
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
 * 等于没有指向性。现在点通知直接进那个聊天，通知上还能一次标记完成或稍后再提。
 *
 * 局限写在明处：档案存在微信自己的库里，微信进程不在时不发通知。
 */
internal object RelationReminder {
    private const val CHANNEL_ID = "yanwai_relation_todo"
    private const val CHANNEL_NAME = "关系档案提醒"
    private const val PREFS = "yanwai_relation_notice"
    private const val KEY_LAST_SCAN = "last_global_scan"
    private const val NOTICE_BASE = 1100
    private const val SUMMARY_ID = 1099
    private const val LEGACY_NOTICE_ID = 1101
    private const val MAX_NOTICES = 5
    private const val SLOT_COUNT = 512
    private const val SNOOZE_MINUTES = 60
    private const val GLOBAL_THROTTLE_MS = 15 * 60_000L
    private const val GLOBAL_DELAY_MS = 3_000L
    private const val GROUP = "yanwai_relation_todo_group"

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Runnable? = null

    /** 单聊路径：刚打开聊天或刚整理完，看一眼今天有没有到期的。 */
    suspend fun check(context: Context, key: ReplyContactKey, talker: String?, facts: List<RelationFact>) {
        val now = System.currentTimeMillis()
        if (snoozed(context, key.value, now)) {
            MoodLog.i("RELATION_REMIND_SKIP_SNOOZED")
            return
        }
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

    /**
     * 「稍后提醒」：撤掉当前通知，到点之后下一次检查再弹。
     *
     * 不挂 AlarmManager：微信进程一死，动态注册的接收器和闹钟就都不在了。这里只记一个时间点，
     * 顺手把当天的提醒指纹清掉，用户下次进微信（或进这个聊天）过了时间自然会重新提醒。
     */
    fun snooze(context: Context, contactKey: String, minutes: Int, noticeId: Int) {
        val span = minutes.coerceIn(1, 24 * 60)
        val until = System.currentTimeMillis() + span * 60_000L
        prefs(context).edit().putLong(snoozeKey(contactKey), until).remove(stampKey(contactKey)).apply()
        if (noticeId > 0) {
            runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(noticeId)
            }
        }
        MoodLog.i("RELATION_REMIND_SNOOZE minutes=$span")
    }

    private fun clearNotified(context: Context, contactKey: String) {
        runCatching { prefs(context).edit().remove(stampKey(contactKey)).apply() }
    }

    /**
     * 全局路径：进微信首页时扫一遍。
     * 之前只在进入那个私聊时才检查，等于「你正好去找他」才提醒，和提醒的本意相反。
     */
    suspend fun checkAll(context: Context) {
        if (!takeGlobalSlot(context)) return
        val now = System.currentTimeMillis()
        val groups = withContext(Dispatchers.IO) {
            runCatching { RelationFactBridge.dueContacts(context) }.getOrDefault(emptyList())
        }.filter { !snoozed(context, it.key, now) }
        if (groups.isEmpty()) {
            MoodLog.i("RELATION_REMIND_ALL nothing_due")
            return
        }
        val resolved = fillMissing(context, groups)
        val names = names(resolved.mapNotNull { it.talker })
        fun label(group: DueContact) = displayName(
            group.name.ifBlank { group.talker?.let { names[it] }.orEmpty() }, group.talker)
        // 先把该提醒的挑出来再截断：旧写法到 5 个就直接 break，后面的联系人既没通知也没痕迹。
        val fresh = resolved.filter { markNotified(context, it.key, it.facts, now) }
        if (fresh.isEmpty()) {
            MoodLog.i("RELATION_REMIND_ALL nothing_new contacts=" + resolved.size)
            return
        }
        val shown = fresh.take(MAX_NOTICES)
        for (group in shown) {
            notify(context, group.key, group.talker, label(group), group.facts, now)
        }
        if (fresh.size > 1) notifySummary(context, fresh.map { label(it) })
        MoodLog.i("RELATION_REMIND_ALL contacts=" + groups.size + " posted=" + shown.size +
            " summary=" + (fresh.size > 1) + " overflow=" + (fresh.size - shown.size))
    }

    /**
     * 旧档案没记过聊天对象：提醒叫不出名字，也点不进会话。
     * 这里按 rcontact 反解一次并补写目录；解不出来就退回「待办」，不编名字。
     */
    private suspend fun fillMissing(context: Context, groups: List<DueContact>): List<DueContact> {
        val missingGroups = groups.filter { it.talker.isNullOrBlank() }
        if (missingGroups.isEmpty()) return groups
        // 档案里存过的依据消息是最好的线索：它能反查到当初是哪段聊天。
        val evidence = missingGroups.associate { group ->
            group.key to group.facts.mapNotNull { it.sourceChatMessageId }.filter { it > 0 }.distinct()
        }
        val found = withContext(Dispatchers.IO) {
            runCatching { ReplyDatabaseHistory.resolveContacts(evidence.keys, evidence) }.getOrDefault(emptyMap())
        }
        if (found.isEmpty()) return groups
        for ((key, pair) in found) {
            val contactKey = runCatching { ReplyContactKey(key) }.getOrNull() ?: continue
            RelationFactBridge.rememberContact(context, contactKey, pair.first, pair.second)
        }
        return groups.map { group ->
            val hit = found[group.key] ?: return@map group
            group.copy(talker = group.talker ?: hit.first, name = group.name.ifBlank { hit.second })
        }
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
        val id = existingNoticeId(context, key.value) ?: return
        runCatching { manager.cancel(id) }
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
        val noticeId = noticeId(context, contactKey)
        val title = title(name, talker)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
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
        runCatching {
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource("android", android.R.drawable.ic_menu_recent_history),
                "一小时后",
                snoozeIntent(context, contactKey, noticeId)).build())
        }.onFailure { MoodLog.w("RELATION_REMIND_ACTION_FAILED " + it.javaClass.simpleName) }
        prefs(context).edit().remove(snoozeKey(contactKey)).apply()
        runCatching { manager.notify(noticeId, builder.build()) }
            .onFailure { MoodLog.w("RELATION_REMIND_NOTIFY_FAILED " + it.javaClass.simpleName) }
        MoodLog.i("RELATION_REMIND posted items=" + facts.size + " title=" + title +
            " talker=" + (talker != null))
    }

    /**
     * 多联系人时的汇总条：详细通知只发得下几条，剩下的不能凭空消失。
     * 点汇总回到微信首页，逐个点开各自的详细通知。
     */
    private fun notifySummary(context: Context, names: List<String>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(manager)
        val unique = names.filter { it.isNotBlank() }.distinct()
        val unnamed = names.count { it.isBlank() }
        val head = unique.take(6).joinToString("、")
        val tail = if (unique.size > 6) "等 " + unique.size + " 位联系人" else ""
        val fallback = if (unnamed > 0) (if (unique.isEmpty()) "" else "、") + "还有 $unnamed 位没对上名字" else ""
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("待办 · " + names.size + " 位联系人")
            .setContentText((head + tail + fallback).ifBlank { "有到期的约定与重要日期" })
            .setStyle(Notification.BigTextStyle().bigText(
                (unique.joinToString("、") + fallback).ifBlank { "有到期的约定与重要日期" } +
                    "\n点开各自的提醒可以直接进聊天"))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, "", null, SUMMARY_ID))
        runCatching { manager.notify(SUMMARY_ID, builder.build()) }
            .onFailure { MoodLog.w("RELATION_REMIND_SUMMARY_FAILED " + it.javaClass.simpleName) }
    }

    /** 名字、微信号都没有时至少写「待办」，不再出现「未获取备注名」这种说了等于没说的标题。 */
    private fun title(name: String, talker: String?): String {
        val shown = displayName(name, talker)
        return if (shown.isBlank()) "待办" else "待办 · " + shown
    }

    /** 备注名 → 微信昵称（由 displayNames 解析）→ 微信号/wxid，逐级回退。 */
    private fun displayName(name: String, talker: String?): String {
        val clean = name.trim()
        if (clean.isNotEmpty()) return clean
        val id = talker?.trim().orEmpty()
        if (id.isEmpty()) return ""
        return if (id.length > 24) id.take(24) + "…" else id
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

    private fun snoozeIntent(context: Context, contactKey: String, noticeId: Int): PendingIntent {
        val intent = Intent(RelationTodoActions.ACTION_SNOOZE)
            .setPackage("com.tencent.mm")
            .putExtra(RelationTodoActions.EXTRA_KEY, contactKey)
            .putExtra(RelationTodoActions.EXTRA_NOTICE, noticeId)
            .putExtra(RelationTodoActions.EXTRA_MINUTES, SNOOZE_MINUTES)
        return PendingIntent.getBroadcast(context, noticeId + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "约定与重要日期的到期提醒，点开直接进对应聊天"
        })
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stampKey(contactKey: String) = "stamp_" + contactKey

    private fun snoozeKey(contactKey: String) = "snooze_" + contactKey

    private fun snoozed(context: Context, contactKey: String, now: Long): Boolean =
        prefs(context).getLong(snoozeKey(contactKey), 0) > now

    /**
     * 每个联系人一个固定通知 id，存在本机偏好里。
     *
     * 原来用 `hashCode and 0x3FF` 现算，1024 个桶里两个联系人撞上就会互相覆盖，
     * 而且撞了只在用户那儿表现为「一条提醒莫名其妙不见了」。现在按槽位分配，
     * 满了就回收最久没用的那个，仍写成 1100+槽位，旧版本的通知 id 不会被误认。
     */
    private fun noticeId(context: Context, contactKey: String): Int {
        val prefs = prefs(context)
        val known = prefs.getInt("nid_" + contactKey, 0)
        if (known in 1..SLOT_COUNT && prefs.getString("nkey_" + known, null) == contactKey) {
            prefs.edit().putLong("nused_" + known, System.currentTimeMillis()).apply()
            return NOTICE_BASE + known
        }
        val now = System.currentTimeMillis()
        var free = 0
        var oldestSlot = 1
        var oldestAt = Long.MAX_VALUE
        for (slot in 1..SLOT_COUNT) {
            val owner = prefs.getString("nkey_" + slot, null)
            if (owner == null) {
                free = slot
                break
            }
            val used = prefs.getLong("nused_" + slot, 0)
            if (used < oldestAt) {
                oldestAt = used
                oldestSlot = slot
            }
        }
        if (free == 0) {
            free = oldestSlot
            val owner = prefs.getString("nkey_" + free, null)
            if (!owner.isNullOrEmpty()) {
                runCatching {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(NOTICE_BASE + free)
                }
                prefs.edit().remove("nid_" + owner).apply()
            }
        }
        prefs.edit()
            .putInt("nid_" + contactKey, free)
            .putString("nkey_" + free, contactKey)
            .putLong("nused_" + free, now)
            .apply()
        return NOTICE_BASE + free
    }

    private fun existingNoticeId(context: Context, contactKey: String): Int? {
        val prefs = prefs(context)
        val slot = prefs.getInt("nid_" + contactKey, 0)
        if (slot in 1..SLOT_COUNT && prefs.getString("nkey_" + slot, null) == contactKey) {
            return NOTICE_BASE + slot
        }
        return null
    }

    /** 指纹带日期、逾期档位和条目 id：同一批同一档只提醒一次；当天又新增一条会重新提醒。 */
    private fun markNotified(context: Context, contactKey: String, due: List<RelationFact>, now: Long): Boolean =
        runCatching {
            val prefs = prefs(context)
            val stamp = RelationDue.stampKey(due, now)
            if (prefs.getString(stampKey(contactKey), null) == stamp) return@runCatching false
            prefs.edit().putString(stampKey(contactKey), stamp).apply()
            true
        }.getOrDefault(false)

    private fun takeGlobalSlot(context: Context): Boolean = runCatching {
        val prefs = prefs(context)
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
            runCatching { ReplyDatabaseHistory.displayNames(talkers, relaxed = true) }.getOrDefault(emptyMap())
        }
    }
}
