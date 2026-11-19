package dev.jev.wechatmood.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.ReplyContactKey

/**
 * 约定到期的本地提醒。
 *
 * 模块没有常驻定时任务，触发点放在「进入这个私聊」的时候：只要今天有到期或过期的约定，
 * 且当天还没为这个联系人提醒过，就弹一条通知。点通知回到微信。
 */
internal object RelationReminder {
    private const val CHANNEL_ID = "yanwai_relation_todo"
    private const val NOTICE_ID = 1101
    private const val PREFS = "yanwai_relation_notice"

    fun check(context: Context, key: ReplyContactKey, facts: List<RelationFact>) {
        val now = System.currentTimeMillis()
        val timed = facts.filter { it.isOpen && it.isTimed && it.occurredAt > 0 }
        if (timed.isEmpty()) return
        val overdue = timed.count { FactTimes.overdue(it.occurredAt, now) }
        val today = timed.count { !FactTimes.overdue(it.occurredAt, now) && FactTimes.sameDay(it.occurredAt, now) }
        if (overdue + today == 0) return
        if (!markNotified(context, key)) return
        notify(context, overdue, today)
    }

    /** 同一联系人一天只提醒一次；先记账再弹，避免失败后反复重试。 */
    private fun markNotified(context: Context, key: ReplyContactKey): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = FactTimes.formatLocal(System.currentTimeMillis(), "yyyy-MM-dd")
        if (prefs.getString(key.value, null) == today) return@runCatching false
        prefs.edit().putString(key.value, today).apply()
        true
    }.getOrDefault(false)

    private fun notify(context: Context, overdue: Int, today: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "关系档案提醒",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "约定到期与过期提醒"
            })
        }
        val parts = buildList {
            if (today > 0) add(today.toString() + " 条约会今天到期")
            if (overdue > 0) add(overdue.toString() + " 条已经过期")
        }
        val intent = Intent().apply {
            component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("关系档案 · 有待办")
            .setContentText(parts.joinToString("，"))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(NOTICE_ID, notification) }
            .onFailure { MoodLog.w("RELATION_REMIND_NOTIFY_FAILED " + it.javaClass.simpleName) }
    }
}
